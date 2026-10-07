"""Agent service. Three endpoints, no state of its own worth keeping."""
import logging
import time

from fastapi import FastAPI
from pydantic import BaseModel

import execute as execute_mod
import graph
import inbox
import llm as llm_mod
import rag
from config import cfg
from platform_client import Platform, start_run

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


def tracer(platform):
    """Every model call becomes a step in the run's trace, next to the tool calls."""
    def on_call(label, model, tokens_in, tokens_out, ms):
        platform.log_step("LLM", label,
                          {"model": model, "tokensIn": tokens_in, "tokensOut": tokens_out},
                          latencyMs=ms, tokens=tokens_in + tokens_out)
    return on_call


def remember_run(run_id, sku, node, supplier, rec, decision, qty, outcome, why, reviewed=False):
    # say plainly whether anyone checked it. an unreviewed decision read back
    # later is the agent's opinion, not something that is known to be right
    status = "reviewed by a buyer" if reviewed else "unreviewed agent decision"
    rag.remember(
        f"run:{run_id}",
        f"Decision for {sku} at {node} ({status}): recommended {rec}, decided {decision} "
        f"{qty} units from {supplier}. Outcome: {outcome}. Reasoning: {(why or '')[:600]}",
        sku=sku, node_id=node, supplier_id=supplier, reviewed=reviewed)


@app.on_event("startup")
def startup():
    """Check the configured model is actually reachable on this key. Two seconds
    now beats a mysterious failure on run 7."""
    if not cfg.gemini_key:
        log.warning("GEMINI_API_KEY is not set - /decide will fail until it is")
        return
    try:
        ok, want, available = llm_mod.check_model_available()
        if ok and available:
            log.warning("gemini model %s is reachable but %s", want, available[0])
        elif ok:
            log.info("gemini model %s is available", want)
        else:
            flash = [m for m in available if "flash" in m][:6]
            log.error("model %s is NOT available on this key. flash models you "
                      "can use: %s", want, flash)
    except Exception as e:
        log.warning("could not check model availability: %s", e)

    # sync the knowledge docs on every start, so edits to the markdown are picked
    # up without a separate step. unchanged chunks cost nothing
    try:
        done, total = rag.ingest()
        log.info("knowledge index: %d of %d chunks embedded, rest unchanged", done, total)
    except Exception as e:
        log.warning("knowledge index unavailable, the agent runs without it: %s", e)


@app.get("/health")
def health():
    return {"ok": True, "model": cfg.model, "keyConfigured": bool(cfg.gemini_key),
            "platform": cfg.platform_url}


class ResumeRequest(BaseModel):
    approval_id: str
    approved_action: dict


@app.post("/resume/{run_id}")
def resume(run_id: str, req: ResumeRequest):
    """A buyer approved something the agent was not allowed to do alone.

    This runs the same execute and verify path the agent would have taken by
    itself. Human approval changes who decided, not how carefully the result
    gets checked - one code path, so there is only one place for a bug to live.
    """
    started = time.time()
    platform = Platform(run_id)
    model = llm_mod.Gemini(on_call=tracer(platform))

    action = req.approved_action or {}
    if not action.get("sku"):
        return {"runId": run_id, "error": "the approved action has no sku to act on"}

    if action.get("type") == "transfer":
        platform.log_step("DECISION", "approval",
                          {"approvalId": req.approval_id, "approvedAction": action})
        result = execute_mod.execute_transfer(platform, run_id, action, req.approval_id)
        platform.record_decision(
            decision=action.get("decision") or "ESCALATE", finalQty=action.get("qty"),
            explanation="Transfer approved by %s. Outcome: %s %s" % (
                req.approval_id, result.get("outcome"), result.get("detail") or ""),
            durationMs=int((time.time() - started) * 1000))
        remember_run(run_id, action["sku"], action["toNode"], action["fromNode"], None,
                     "transfer", action.get("qty"),
                     "approved by a buyer, then " + str(result.get("outcome")),
                     action.get("reason"), reviewed=True)
        return {"runId": run_id, "approvalId": req.approval_id, "outcome": result.get("outcome"),
                "transferId": result.get("transferId"), "verification": result.get("verification"),
                "detail": result.get("detail"), "llmCalls": 0,
                "durationMs": int((time.time() - started) * 1000)}

    plan = (platform.calculate_reorder(
        action["sku"], action["nodeId"], action["supplierId"]) or {}).get("data") or {}

    platform.log_step("DECISION", "approval",
                      {"approvalId": req.approval_id, "approvedAction": action})

    result = execute_mod.execute_and_verify(
        platform, model, run_id, action, plan, action.get("recommendedQty"),
        approval_id=req.approval_id)

    platform.record_decision(
        decision=action.get("decision") or "MODIFY", finalQty=action.get("qty"),
        explanation="Executed after approval %s. Outcome: %s"
                    % (req.approval_id, result.get("outcome")),
        llmCalls=model.calls, tokensIn=model.tokens_in, tokensOut=model.tokens_out,
        durationMs=int((time.time() - started) * 1000))

    # same ref as the original run, so this overwrites "waiting for approval"
    remember_run(run_id, action["sku"], action["nodeId"], action["supplierId"],
                 action.get("recommendedQty"), action.get("decision"), action.get("qty"),
                 "approved by a buyer, then " + str(result.get("outcome")), action.get("reason"),
                 reviewed=True)

    return {
        "runId": run_id,
        "approvalId": req.approval_id,
        "outcome": result.get("outcome"),
        "poId": result.get("poId"),
        "verification": result.get("verification"),
        "repairs": result.get("repairs"),
        "llmCalls": model.calls,
        "durationMs": int((time.time() - started) * 1000),
    }


@app.post("/decide")
def decide(req: DecideRequest):
    started = time.time()
    platform = Platform(req.run_id)
    try:
        model = llm_mod.Gemini(on_call=tracer(platform))
        return run_graph(platform, model, req.run_id, req.scenario, {
            "sku": req.sku, "node_id": req.node_id, "supplier_id": req.supplier_id,
            "recommended_qty": req.recommended_qty, "po_id": req.po_id,
        }, started)
    except Exception as e:
        # without this a crash left the run RUNNING forever and its sku locked
        log.exception("run %s failed", req.run_id)
        try:
            platform.save_result(error=f"{type(e).__name__}: {e}"[:2000])
        except Exception:
            log.exception("could not record that run %s failed", req.run_id)
        raise


def run_graph(platform, model, run_id, scenario, situation, started):
    compiled = graph.build(platform, model)
    state = compiled.invoke({
        "run_id": run_id, "scenario": scenario, "situation": situation,
        "facts": {}, "errors": [], "started": started,
    })

    proposal = state.get("proposal") or {}
    sit = state.get("situation") or {}
    if sit.get("sku"):
        remember_run(run_id, sit["sku"], sit.get("node_id"), sit.get("supplier_id"),
                     sit.get("recommended_qty"), proposal.get("decision"), proposal.get("qty"),
                     (state.get("execution") or {}).get("outcome"), proposal.get("reasoning"))

    return {
        "runId": run_id,
        "decision": proposal.get("decision"),
        "qty": proposal.get("qty"),
        "supplierId": sit.get("supplier_id"),
        "reasoning": proposal.get("reasoning"),
        "keyFactors": proposal.get("key_factors"),
        "assumptions": proposal.get("assumptions"),
        "citations": proposal.get("citations") or [],
        "searches": state.get("searches") or [],
        "confidence": proposal.get("confidence"),
        "validation": state.get("validation") or {},
        "execution": state.get("execution"),
        "analysis": state.get("analysis"),
        "options": state.get("options"),
        "toolsCalled": platform.calls,
        "gatherTurns": state.get("gather_turns"),
        "llmCalls": model.calls,
        "tokensIn": model.tokens_in,
        "tokensOut": model.tokens_out,
        "durationMs": int((time.time() - started) * 1000),
        "errors": state.get("errors"),
    }


class SupplierMessage(BaseModel):
    sender: str
    subject: str = ""
    body: str


@app.post("/supplier-messages")
def supplier_message(req: SupplierMessage):
    """An email from a supplier. Read it, have the platform check and apply it,
    and if it leaves the store short, work out what to do about that.

    The raw email never goes further than the extraction call. What gets
    remembered, and what the S2 run sees, is built from fields the platform has
    already validated - so text injected into an email cannot reach a later prompt.
    """
    started = time.time()
    run_id = start_run("S2_PARTIAL")
    platform = Platform(run_id)
    model = llm_mod.Gemini(on_call=tracer(platform))
    platform.log_step("THOUGHT", "supplier_message",
                      {"sender": req.sender, "subject": req.subject})

    def stop(decision, why, **extra):
        platform.record_decision(decision=decision, finalQty=0, explanation=why,
                                 llmCalls=model.calls, tokensIn=model.tokens_in,
                                 tokensOut=model.tokens_out,
                                 durationMs=int((time.time() - started) * 1000))
        return {"runId": run_id, "decision": decision, "reasoning": why,
                "llmCalls": model.calls, "toolsCalled": platform.calls, **extra}

    reading, err = inbox.extract(model, req.sender, req.subject, req.body)
    if err:
        return stop("INVESTIGATE", "could not read the supplier message: " + err)

    res = platform.supplier_event(
        poId=reading["po_id"], sender=req.sender, kind=reading["kind"],
        confirmedQty=reading.get("confirmed_qty"),
        confirmedDelivery=reading.get("confirmed_delivery"),
        unitPrice=reading.get("unit_price"), rawText=req.body)
    if not res.get("ok"):
        return stop("INVESTIGATE", "the platform refused the supplier message - "
                    + (res.get("detail") or res.get("error")), reading=reading, event=res)

    ev = res["data"]
    if ev.get("duplicate"):
        return stop("REJECT", "this message was already processed, nothing changed",
                    reading=reading, event=ev)

    after = ev["after"]
    rag.remember(
        f"msg:{ev['eventId']}",
        f"{ev['poId']} from {ev['supplierId']}: {reading['kind'].lower()}, confirmed "
        f"{after['qtyConfirmed']} of {after['qtyOrdered']}, delivery {after['expectedDelivery']}, "
        f"price {after['unitPrice']}, shortfall {ev['shortfall']}",
        kind="supplier_message", sku=ev["sku"], supplier_id=ev["supplierId"])

    if ev["shortfall"] <= 0 and reading["kind"] != "DELAY":
        return stop("ACCEPT", "supplier confirmed the order, nothing else to do",
                    reading=reading, event=ev)

    out = run_graph(platform, model, run_id, "S2_PARTIAL", {
        "sku": ev["sku"], "node_id": ev["nodeId"], "supplier_id": ev["supplierId"],
        "recommended_qty": None, "po_id": ev["poId"],
    }, started)
    return {**out, "reading": reading, "event": ev}
