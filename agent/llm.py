"""Gemini access, rate limited.

The whole provider surface is in this one file. Swapping to another model means
changing here and nothing else - the graph, the tools and the prompts never
import a provider sdk.
"""
import logging
import random
import time

import redis

from config import cfg

log = logging.getLogger(__name__)


class RateLimiter:
    """Fixed window counter in redis.

    The gemini free tier allows about 15 requests a minute per api key, not per
    process. An in-process limiter breaks the moment the eval runner and the ui
    both make calls - each thinks it has the whole budget and together they blow
    it. Redis is the only place the count can actually be shared.
    """

    def __init__(self, client=None, rpm=None):
        self.rpm = rpm or cfg.rpm_limit
        self.r = client or redis.Redis(
            host=cfg.redis_host, port=cfg.redis_port, socket_timeout=2
        )

    def acquire(self):
        while True:
            window = int(time.time() // 60)
            key = f"gemini:rpm:{window}"
            try:
                used = self.r.incr(key)
                if used == 1:
                    self.r.expire(key, 120)
            except redis.RedisError as e:
                # fail closed-ish: if we cannot count, slow down rather than
                # flood the api and get the key throttled
                log.warning("rate limiter unavailable (%s), pausing instead", e)
                time.sleep(60.0 / self.rpm)
                return

            if used <= self.rpm:
                return

            wait = 60 - (time.time() % 60) + 0.5
            log.info("rpm budget spent (%d/%d), waiting %.1fs", used, self.rpm, wait)
            time.sleep(wait)


ATTEMPTS = 4


def _chat_model(name):
    from langchain_google_genai import ChatGoogleGenerativeAI

    return ChatGoogleGenerativeAI(
        model=name,
        google_api_key=cfg.gemini_key,
        temperature=0,
        # the library's own default is 6 hidden attempts and no time limit. one
        # attempt here, so Gemini.invoke is the only retry and it shows in the log.
        # timeout is sent to google as the deadline - busy calls take 30-60s
        timeout=60,
        max_retries=1,
    )


class Gemini:
    def __init__(self, model=None, limiter=None, on_call=None):
        cfg.require_key()
        self.model_name = model or cfg.model
        self.limiter = limiter or RateLimiter()
        self.on_call = on_call
        self.calls = 0
        self.tokens_in = 0
        self.tokens_out = 0

        self._chat = _chat_model(self.model_name)
        fb = cfg.fallback_model
        self.fallback_name = fb if fb and fb != self.model_name else None
        self._fallback = _chat_model(fb) if self.fallback_name else None

    def bind(self, tools):
        return self._chat.bind_tools(tools)

    def invoke(self, messages, tools=None, label="llm"):
        """One model call. Retries on transient failures, then moves to the fallback
        model if the main one is still busy. Counts what it spent. label says which
        graph node made the call, for the trace."""
        models = [(self.model_name, self._chat)]
        if self._fallback is not None:
            models.append((self.fallback_name, self._fallback))

        last = None
        for name, base in models:
            chat = base.bind_tools(tools) if tools else base
            for attempt in range(ATTEMPTS):
                self.limiter.acquire()
                try:
                    t0 = time.time()     # after the limiter, so waiting doesn't count as latency
                    res = chat.invoke(messages)
                    self.calls += 1
                    tin, tout = self._count(res)
                    if self.on_call:
                        # tracing must never be the reason a decision fails
                        try:
                            self.on_call(label=label, model=name, tokens_in=tin,
                                         tokens_out=tout, ms=int((time.time() - t0) * 1000))
                        except Exception as e:
                            log.warning("could not trace llm call: %s", e)
                    return res
                except Exception as e:
                    last = e
                    if _daily_quota(e):
                        raise DailyQuotaExhausted(
                            "gemini free tier daily request quota is spent. it resets on "
                            "google's clock - try a different GEMINI_MODEL (2.5-flash-lite "
                            "and 3.1-flash-lite have separate allowances) or wait."
                        ) from e
                    if not _retryable(e):
                        raise
                    if attempt == ATTEMPTS - 1:
                        break
                    # 2, 4, 8s - a busy model needs longer than a second to clear
                    sleep = 2 ** (attempt + 1) + random.random()
                    log.warning("%s call failed (%s), retrying in %.1fs", name, e, sleep)
                    time.sleep(sleep)
            log.warning("%s still failing after %d attempts", name, ATTEMPTS)
        raise last

    def _count(self, res):
        meta = getattr(res, "usage_metadata", None) or {}
        tin = meta.get("input_tokens", 0) or 0
        tout = meta.get("output_tokens", 0) or 0
        self.tokens_in += tin
        self.tokens_out += tout
        return tin, tout


class DailyQuotaExhausted(RuntimeError):
    """The per day free tier allowance is gone. Retrying will not help until
    the quota resets, so fail loudly rather than burning three more attempts."""


def _daily_quota(e):
    s = str(e)
    return "PerDay" in s or "free_tier_requests" in s


def _retryable(e):
    if _daily_quota(e):
        return False
    # the type name too: httpx's ReadTimeout says "timed out", not "timeout"
    s = f"{type(e).__name__} {e}".lower()
    return any(x in s for x in ("429", "rate", "503", "500", "504", "deadline", "timeout",
                                "unavailable"))


def check_model_available(model=None):
    """Fail in two seconds with a clear message rather than mysteriously at run 7.

    This makes a real one token call rather than looking the model up in the
    models list. Being listed is not the same as being usable: gemini-2.5-flash
    is returned by /models on a new key and then 404s on generateContent with
    "no longer available to new users". Checking the list would have passed and
    the first real run would have failed.
    """
    cfg.require_key()
    want = model or cfg.model
    import httpx

    # the key goes in a header - as a query param it ended up in the http log line
    r = httpx.post(
        f"https://generativelanguage.googleapis.com/v1beta/models/{want}:generateContent",
        headers={"x-goog-api-key": cfg.gemini_key},
        json={"contents": [{"parts": [{"text": "ok"}]}]},
        # only a startup hint - the agent serves nothing until it returns
        timeout=8,
    )
    if r.status_code == 200:
        return True, want, []
    # busy is not missing. reporting it as unavailable also sent startup off to
    # probe eight other models, which kept the agent from serving for close to a minute
    if r.status_code in (429, 503):
        return True, want, [f"busy right now ({r.status_code}), calls will be retried"]

    detail = r.json().get("error", {}).get("message", r.text[:200])
    return False, want, [detail] + _usable_alternatives()


def _usable_alternatives():
    """Only models we have actually called successfully get suggested."""
    import httpx

    try:
        r = httpx.get("https://generativelanguage.googleapis.com/v1beta/models",
                      headers={"x-goog-api-key": cfg.gemini_key}, timeout=15)
        listed = [m["name"].split("/")[-1] for m in r.json().get("models", [])
                  if "generateContent" in m.get("supportedGenerationMethods", [])
                  and "flash" in m["name"] and "image" not in m["name"]
                  and "tts" not in m["name"]]
    except Exception:
        return []

    works = []
    for m in listed[:8]:
        try:
            r = httpx.post(
                f"https://generativelanguage.googleapis.com/v1beta/models/{m}:generateContent",
                headers={"x-goog-api-key": cfg.gemini_key},
                json={"contents": [{"parts": [{"text": "ok"}]}]}, timeout=20)
            if r.status_code == 200:
                works.append(m)
        except Exception:
            pass
    return works
