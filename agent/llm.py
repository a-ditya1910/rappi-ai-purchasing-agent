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


class Gemini:
    def __init__(self, model=None, limiter=None):
        cfg.require_key()
        self.model_name = model or cfg.model
        self.limiter = limiter or RateLimiter()
        self.calls = 0
        self.tokens_in = 0
        self.tokens_out = 0

        from langchain_google_genai import ChatGoogleGenerativeAI

        self._chat = ChatGoogleGenerativeAI(
            model=self.model_name,
            google_api_key=cfg.gemini_key,
            temperature=0,
        )

    def bind(self, tools):
        return self._chat.bind_tools(tools)

    def invoke(self, messages, tools=None):
        """One model call. Retries on transient failures, counts what it spent."""
        chat = self.bind(tools) if tools else self._chat

        last = None
        for attempt in range(3):
            self.limiter.acquire()
            try:
                res = chat.invoke(messages)
                self.calls += 1
                self._count(res)
                return res
            except Exception as e:
                last = e
                if not _retryable(e) or attempt == 2:
                    raise
                sleep = (2**attempt) + random.random()
                log.warning("gemini call failed (%s), retrying in %.1fs", e, sleep)
                time.sleep(sleep)
        raise last

    def _count(self, res):
        meta = getattr(res, "usage_metadata", None) or {}
        self.tokens_in += meta.get("input_tokens", 0) or 0
        self.tokens_out += meta.get("output_tokens", 0) or 0


def _retryable(e):
    s = str(e).lower()
    return any(x in s for x in ("429", "rate", "quota", "503", "500", "timeout", "unavailable"))


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

    r = httpx.post(
        f"https://generativelanguage.googleapis.com/v1beta/models/{want}:generateContent",
        params={"key": cfg.gemini_key},
        json={"contents": [{"parts": [{"text": "ok"}]}]},
        timeout=30,
    )
    if r.status_code == 200:
        return True, want, []

    detail = r.json().get("error", {}).get("message", r.text[:200])
    return False, want, [detail] + _usable_alternatives()


def _usable_alternatives():
    """Only models we have actually called successfully get suggested."""
    import httpx

    try:
        r = httpx.get("https://generativelanguage.googleapis.com/v1beta/models",
                      params={"key": cfg.gemini_key}, timeout=15)
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
                params={"key": cfg.gemini_key},
                json={"contents": [{"parts": [{"text": "ok"}]}]}, timeout=20)
            if r.status_code == 200:
                works.append(m)
        except Exception:
            pass
    return works
