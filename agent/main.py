"""Agent service. Three endpoints, no state of its own worth keeping."""
import logging
import time

from fastapi import FastAPI
from pydantic import BaseModel

import graph
import llm as llm_mod
from config import cfg
from platform_client import Platform

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s %(levelname)-5s %(name)s %(message)s")
log = logging.getLogger("agent")

app = FastAPI(title="ai purchasing agent")


class DecideRequest(BaseModel):
    run_id: str
    scenario: str
    sku: str | None = None
    node_id: str | None = None
    supplier_id: str | None = None
    recommended_qty: int | None = None
    po_id: str | None = None


@app.on_event("startup")
def startup():
    """Check the configured model is actually reachable on this key. Two seconds
    now beats a mysterious failure on run 7."""
    if not cfg.gemini_key:
        log.warning("GEMINI_API_KEY is not set - /decide will fail until it is")
        return
    try:
        ok, want, available = llm_mod.check_model_available()
        if ok:
            log.info("gemini model %s is available", want)
        else:
            flash = [m for m in available if "flash" in m][:6]
            log.error("model %s is NOT available on this key. flash models you "
                      "can use: %s", want, flash)
    except Exception as e:
        log.warning("could not check model availability: %s", e)


@app.get("/health")
def health():
    return {"ok": True, "model": cfg.model, "keyConfigured": bool(cfg.gemini_key),
            "platform": cfg.platform_url}


@app.post("/decide")
def decide(req: DecideRequest):
    started = time.time()
    platform = Platform(req.run_id)
    model = llm_mod.Gemini()

    compiled = graph.build(platform, model)
    state = compiled.invoke({
        "run_id": req.run_id,
        "scenario": req.scenario,
        "situation": {
            "sku": req.sku, "node_id": req.node_id, "supplier_id": req.supplier_id,
            "recommended_qty": req.recommended_qty, "po_id": req.po_id,
        },
        "facts": {}, "errors": [], "started": started,
    })

    proposal = state.get("proposal") or {}
    validation = state.get("validation") or {}
    return {
        "runId": req.run_id,
        "decision": proposal.get("decision"),
        "qty": proposal.get("qty"),
        "reasoning": proposal.get("reasoning"),
        "keyFactors": proposal.get("key_factors"),
        "assumptions": proposal.get("assumptions"),
        "confidence": proposal.get("confidence"),
        "validation": validation,
        "analysis": state.get("analysis"),
        "toolsCalled": platform.calls,
        "gatherTurns": state.get("gather_turns"),
        "llmCalls": model.calls,
        "tokensIn": model.tokens_in,
        "tokensOut": model.tokens_out,
        "durationMs": int((time.time() - started) * 1000),
        "errors": state.get("errors"),
    }
