"""Execute, check, and do something about it when the check fails.

Deliberately its own module, and deliberately called from two places: the main
graph when a decision is inside the agent's authority, and resume when a buyer
has approved one that was not. Same code either way - a human approved purchase
gets verified exactly as carefully as one the agent made alone.

The repair set is closed on purpose. An agent that can invent its own recovery
step, with money already committed and a mismatch in front of it, is how you get
a creative disaster at 3am.
"""
import hashlib
import json
import logging

import prompts

log = logging.getLogger(__name__)

MAX_REPAIRS = 2
REPAIRS = ["amend", "split", "cancel_and_recreate", "accept_as_is", "escalate"]


def idempotency_key(run_id, action_type, sku, qty):
    """Same intent always produces the same key, so a retry after a timeout
    returns the original order instead of making a second one."""
    raw = f"{run_id}|{action_type}|{sku}|{qty}"
    return hashlib.sha1(raw.encode()).hexdigest()[:32]


def execute_and_verify(platform, llm, run_id, action, plan, recommended_qty=None):
    """Place the order, then work out whether what happened matches the intent.

    Returns a dict the caller records: what was done, what was found, what was
    done about it.
    """
    sku = action["sku"]
    qty = int(action["qty"])
    result = {"action": action, "attempts": [], "repairs": []}

    key = idempotency_key(run_id, "create_po", sku, qty)
    res = platform.create_po(
        sku=sku, nodeId=action["nodeId"], supplierId=action["supplierId"],
        qty=qty, unitPrice=action.get("unitPrice") or plan.get("unitPrice"),
        expectedDelivery=action["expectedDelivery"], idempotencyKey=key,
        recommendedQty=recommended_qty, reason=action.get("reason"))

    if not res.get("ok"):
        result["outcome"] = "WRITE_FAILED"
        result["error"] = res.get("error")
        result["detail"] = res.get("detail")
        return result

    data = res["data"]
    result["attempts"].append(data)

    # refused rather than failed - the tier gate sent it to a human
    if not data.get("executed"):
        result["outcome"] = "NEEDS_APPROVAL"
        result["validation"] = data.get("validation")
        return result

    result["poId"] = data.get("poId")
    verification = data.get("verification") or {}
    result["verification"] = verification

    if verification.get("outcome") == "VERIFIED":
        result["outcome"] = "VERIFIED"
        return result

    # something differs from what we intended. decide what to do about it.
    return _repair(platform, llm, result, verification, plan, action)


def _repair(platform, llm, result, verification, plan, action):
    mismatches = [d for d in verification.get("diffs", []) if not d.get("ok")]
    attempts = 0

    while attempts < MAX_REPAIRS:
        attempts += 1
        choice = _choose_repair(llm, verification, mismatches, plan, action, attempts)
        result["repairs"].append(choice)
        log.info("repair %d: %s", attempts, choice.get("repair"))

        kind = choice.get("repair")
        po_id = result.get("poId")

        if kind == "escalate":
            platform.request_approval(
                reason=choice.get("reasoning", "verification failed and the agent "
                                                "could not safely correct it"),
                riskTier="T3",
                proposedAction={"poId": po_id, "suggested": choice})
            result["outcome"] = "ESCALATED"
            return result

        if kind == "accept_as_is":
            # the option a model reaches for when it wants to be finished, so it
            # is the one with the strictest gate: only when the shelf is still
            # covered. paperwork differences are survivable, empty shelves are not.
            l3_failed = any(d["level"] == "L3" and not d["ok"]
                            for d in verification.get("diffs", []))
            if l3_failed:
                result["repairs"][-1]["overridden"] = (
                    "refused: L3 failed, so the goal was not met and this is not "
                    "something to accept")
                continue
            result["outcome"] = "ACCEPTED_WITH_VARIANCE"
            return result

        if kind in ("amend", "split", "cancel_and_recreate"):
            po = platform.po(po_id)
            version = (po.get("data") or {}).get("version", 0)
            new_qty = int(choice.get("qty") or 0)

            if kind == "cancel_and_recreate":
                platform.cancel_po(po_id, version, choice.get("reasoning", "replacing"))
                result["outcome"] = "CANCELLED"
                return result

            amended = platform.amend_po(po_id, new_qty, version,
                                        choice.get("reasoning", "correcting after verification"))
            result["repairs"][-1]["applied"] = amended.get("data")

            if not (amended.get("data") or {}).get("executed"):
                # usually a stale version - somebody changed it under us. re-read
                # and try once more rather than forcing it.
                continue

            recheck = platform.po(po_id)
            result["verification_after_repair"] = recheck.get("data")
            result["outcome"] = "REPAIRED"
            return result

        result["repairs"][-1]["error"] = f"unknown repair {kind}"

    # ran out of attempts without converging
    platform.request_approval(
        reason="verification kept failing after %d repair attempts" % MAX_REPAIRS,
        riskTier="T3",
        proposedAction={"poId": result.get("poId"), "verification": verification})
    result["outcome"] = "ESCALATED"
    return result


def _choose_repair(llm, verification, mismatches, plan, action, attempt):
    """One model call, choosing from a closed set."""
    from langchain_core.messages import HumanMessage, SystemMessage
    from langchain_core.tools import tool

    @tool
    def choose_repair(repair: str, reasoning: str, qty: int = 0) -> dict:
        """Pick what to do about the mismatch.

        repair must be one of: amend, split, cancel_and_recreate, accept_as_is,
        escalate. qty is the corrected quantity, only needed for amend or split.
        """
        return {"ok": True}

    summary = "\n".join(
        "  %s %s: intended %s, actual %s" % (d["level"], d["field"], d["intended"], d["actual"])
        for d in mismatches)

    msgs = [
        SystemMessage(prompts.SYSTEM),
        HumanMessage(prompts.REPAIR.format(
            attempt=attempt, max_attempts=MAX_REPAIRS,
            mismatches=summary,
            notes="\n".join("  " + n for n in verification.get("notes", [])),
            ordered=action.get("qty"),
            plan=json.dumps({k: plan.get(k) for k in
                             ("recommendedQty", "targetPosition", "inventoryPosition",
                              "meanDailyDemand", "unitPrice")}, default=str))),
    ]
    res = llm.invoke(msgs, tools=[choose_repair])
    calls = getattr(res, "tool_calls", None) or []
    if not calls:
        return {"repair": "escalate",
                "reasoning": "the model did not return a structured repair choice"}

    out = dict(calls[0]["args"])
    if out.get("repair") not in REPAIRS:
        return {"repair": "escalate",
                "reasoning": "model chose %r, which is not an allowed repair" % out.get("repair")}
    return out
