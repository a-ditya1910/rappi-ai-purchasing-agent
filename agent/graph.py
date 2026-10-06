"""The agent loop.

gather -> analyse -> propose -> preflight -> act

analyse sits deliberately between gather and propose. It computes the quantity
from first principles and the model does not see the incoming recommendation
until after that has happened, so it cannot anchor on 800 and reason backwards
to justify it. That ordering is the assignment's "should not necessarily be
assumed to be correct" made structural instead of a line in a prompt.

act either executes (when the platform says the decision is inside the agent's
authority) or queues it for a buyer. Execution is never the end of it - what was
written gets read back and checked, and a mismatch goes to the repair loop in
execute.py.
"""
import json
import logging
import time

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import tool
from langgraph.graph import END, StateGraph
from typing_extensions import TypedDict

import execute as execute_mod
import prompts
from config import cfg

log = logging.getLogger(__name__)

# facts a decision is not defensible without, per scenario
REQUIRED = {
    "S1_REVIEW": ["inventory-position", "demand-forecast", "open-pos",
                  "suppliers", "budget", "storage", "search-knowledge"],
    "S2_PARTIAL": ["po", "inventory-position", "demand-forecast", "suppliers",
                   "search-knowledge"],
    "S3_DEMAND": ["sales-actuals", "demand-forecast", "inventory-position", "open-pos",
                  "search-knowledge"],
}

MAX_RETRIEVED = 8

# what each scenario is about, so the policy lookup has something to match on
# beyond the planner's numbers
SITUATION = {
    "S1_REVIEW": "Reviewing a purchase recommendation before ordering.",
    "S2_PARTIAL": "A supplier confirmed less than was ordered.",
    "S3_DEMAND": "Actual sales have moved away from the forecast.",
}


class State(TypedDict, total=False):
    run_id: str
    scenario: str
    situation: dict
    facts: dict
    retrieved: list
    searches: list
    options: list
    analysis: dict
    proposal: dict
    validation: dict
    execution: dict
    gather_turns: int
    errors: list
    started: float


def build(platform, llm, kb=None):
    """Wires the graph. platform, llm and kb (knowledge search) are injected so
    tests can drive the whole thing with fakes and no network."""

    if kb is None:
        import rag
        kb = rag.search
    read_tools = _read_tools(platform, kb)

    def gather(state):
        state.setdefault("facts", {})
        state.setdefault("errors", [])
        turns = state.get("gather_turns", 0)

        extra = ""
        sit = state["situation"]
        if sit.get("recommended_qty") is not None:
            # deliberately not shown as "the system recommends X" - that framing
            # invites agreement. it is a number to check, not an instruction.
            extra = f"  a purchasing recommendation of {sit['recommended_qty']} units exists\n"
        if sit.get("po_id"):
            extra += f"  purchase order: {sit['po_id']}\n"

        msgs = [SystemMessage(prompts.SYSTEM),
                HumanMessage(prompts.GATHER.format(
                    scenario=state["scenario"], sku=sit.get("sku"),
                    node_id=sit.get("node_id"), extra=extra))]

        while turns < cfg.max_gather_turns:
            turns += 1
            res = llm.invoke(msgs, tools=read_tools, label="gather")
            msgs.append(res)

            calls = getattr(res, "tool_calls", None) or []
            if not calls:
                break

            for call in calls:
                out = _run_tool(read_tools, call, state)
                msgs.append(ToolMessage(content=json.dumps(out)[:4000],
                                        tool_call_id=call["id"]))

            if _missing(state) == []:
                break

        missing = _missing(state)
        if missing and turns < cfg.max_gather_turns:
            # one nudge naming the gap, then move on. the model gets real
            # latitude, but the system does not depend on it having used it well.
            turns += 1
            msgs.append(HumanMessage(prompts.GATHER_GAP.format(missing=", ".join(missing))))
            res = llm.invoke(msgs, tools=read_tools, label="gather")
            for call in (getattr(res, "tool_calls", None) or []):
                _run_tool(read_tools, call, state)

        state["gather_turns"] = turns
        return state

    def analyse(state):
        """No model here at all. Pure arithmetic from the platform."""
        sit = state["situation"]

        if state["scenario"] == "S2_PARTIAL":
            # the supplier just shipped short, so planning against it alone answers
            # the wrong question. rank everyone who stocks the sku, each with its
            # own plan, and start from the best usable one
            res = platform.supplier_options(sit["sku"], sit["node_id"])
            usable = [o for o in (res.get("data") or []) if o.get("usable")]
            if not usable:
                state["errors"].append(f"no usable supplier options: {res.get('error')}")
                state["analysis"] = {}
                return state
            state["options"] = res["data"]
            plan, supplier = usable[0]["plan"], usable[0]["supplierId"]
        else:
            supplier = sit.get("supplier_id") or _pick_supplier(state)
            if not supplier:
                state["errors"].append("no supplier could be determined for this sku")
                state["analysis"] = {}
                return state

            res = platform.calculate_reorder(sit["sku"], sit["node_id"], supplier)
            if not res.get("ok"):
                state["errors"].append(f"calculate-reorder failed: {res.get('error')}")
                state["analysis"] = {}
                return state
            plan = res["data"]
        state["analysis"] = plan
        state["situation"]["supplier_id"] = supplier

        rec = sit.get("recommended_qty")
        if rec:
            delta = (plan["recommendedQty"] - rec) / rec * 100
            state["analysis"]["deltaVsRecommendationPct"] = round(delta, 1)

        _lookup_policies(state, plan)
        return state

    def _lookup_policies(state, plan):
        # the rules that apply are looked up by code, not left to the model. a live
        # run searched without the policy filter and never saw the rule it then
        # misquoted. the model's own search_knowledge is still there for extra context
        query = " ".join([SITUATION.get(state["scenario"], ""),
                          *plan.get("explanationSteps", []), *plan.get("warnings", [])])
        out = kb(query, doc_type="policy", k=3)
        hits = out.get("data") or []
        kept = state.setdefault("retrieved", [])
        for h in hits:
            if h["text"] not in kept:
                kept.insert(0, h["text"])     # rules first, ahead of anything else
        try:
            platform.log_step("RETRIEVAL", "policy_lookup", {
                "query": query[:300], "filters": {"doc_type": "policy"},
                "hits": [{"ref": h["ref"], "similarity": h["similarity"]} for h in hits],
                "error": out.get("error")})
        except Exception as e:
            log.warning("could not trace the policy lookup: %s", e)

    def propose(state):
        plan = state.get("analysis") or {}
        if not plan:
            state["proposal"] = {
                "decision": "INVESTIGATE",
                "reasoning": "Could not compute a quantity: " + "; ".join(state["errors"]),
                "key_factors": state["errors"],
                "confidence": 0.9,
            }
            return state

        rec = state["situation"].get("recommended_qty")
        rec_line = (f"A recommendation of {rec} units was provided. The independent "
                    f"calculation says {plan['recommendedQty']}. "
                    f"Difference: {plan.get('deltaVsRecommendationPct')}%."
                    if rec else "No recommendation was provided.")

        msgs = [SystemMessage(prompts.SYSTEM),
                HumanMessage(prompts.PROPOSE.format(
                    facts=_summarise(state["facts"]),
                    retrieved="\n\n".join(state.get("retrieved") or [])
                              or "(nothing was retrieved)",
                    analysis="\n".join(plan.get("explanationSteps", [])),
                    options=_options_block(state.get("options")),
                    recommendation_line=rec_line))]

        res = llm.invoke(msgs, tools=[_decision_tool()], label="propose")
        state["proposal"] = _extract_decision(res, plan)
        return state

    def preflight(state):
        """Deterministic. The model's opinion of whether it may act is not consulted."""
        _apply_supplier_choice(state)
        p = state.get("proposal") or {}
        plan = state.get("analysis") or {}
        qty = p.get("qty") or 0

        if p.get("decision") in ("INVESTIGATE", "REJECT") or qty <= 0:
            state["validation"] = {"verdict": "NOT_APPLICABLE",
                                   "riskTier": "T1",
                                   "detail": "no purchase proposed"}
            return state

        sit = state["situation"]
        res = platform.validate_purchase(
            sku=sit["sku"], nodeId=sit["node_id"], supplierId=sit["supplier_id"],
            qty=qty, unitPrice=plan.get("unitPrice"),
            expectedDelivery=_delivery_date(plan),
            recommendedQty=sit.get("recommended_qty"))

        state["validation"] = res.get("data") if res.get("ok") else {
            "verdict": "BLOCKED", "riskTier": "T3",
            "detail": res.get("error", "validation failed")}
        return state

    def act(state):
        """Execute if allowed, queue for a human if not, record either way."""
        p = state.get("proposal") or {}
        plan = state.get("analysis") or {}
        v = state.get("validation") or {}
        sit = state["situation"]
        qty = p.get("qty") or 0

        # the model may decide to order nothing, but not silently when the maths says
        # the store will run short. a wrong "do nothing" empties a shelf just as
        # surely as a wrong order overfills one, so a buyer gets to confirm it
        need, legal = plan.get("rawNeed") or 0, plan.get("recommendedQty") or 0
        no_order = p.get("decision") in ("REJECT", "INVESTIGATE") or (p.get("qty") or 0) <= 0
        if no_order and need > 0 and legal > 0:
            action = {
                "sku": sit["sku"], "nodeId": sit["node_id"],
                "supplierId": sit["supplier_id"], "qty": legal,
                "unitPrice": plan.get("unitPrice"),
                "expectedDelivery": _delivery_date(plan),
                "recommendedQty": sit.get("recommended_qty"),
                "decision": "MODIFY",
                "reason": "planner order, sent to a buyer because the agent declined it",
            }
            platform.request_approval(
                reason=("agent chose %s, but the planner shows a need of %d and a legal "
                        "order of %d. buyer to confirm doing nothing, or approve the order. "
                        "agent's reasoning: %s") % (p.get("decision"), need, legal,
                                                     (p.get("reasoning") or "")[:400]),
                riskTier="T3", proposedAction=action)
            platform.record_decision(
                decision=p.get("decision"), finalQty=0,
                explanation=_explain(p), validationReport=v, **_stats(llm, state))
            state["execution"] = {"outcome": "NEEDS_APPROVAL", "action": action,
                                  "guard": "declined_despite_need"}
            return state

        if v.get("verdict") == "NOT_APPLICABLE" or qty <= 0:
            platform.record_decision(
                decision=p.get("decision", "INVESTIGATE"), finalQty=0,
                explanation=_explain(p), validationReport=v, **_stats(llm, state))
            state["execution"] = {"outcome": "NO_ACTION"}
            return state

        # the full intent, so an approval later executes exactly this and the
        # resumed run can record the decision the agent actually made
        action = {
            "sku": sit["sku"], "nodeId": sit["node_id"],
            "supplierId": sit["supplier_id"], "qty": qty,
            "unitPrice": plan.get("unitPrice"),
            "expectedDelivery": _delivery_date(plan),
            "recommendedQty": sit.get("recommended_qty"),
            "decision": p.get("decision"),
            "reason": p.get("reasoning"),
        }

        # tier is the platform's call, never the model's. T3 gets queued, and
        # nothing is ordered until a buyer says so.
        if v.get("requiresApproval") or v.get("riskTier") == "T3":
            reason = "; ".join(
                "%s: %s" % (c["id"], c["detail"]) for c in v.get("checks", [])
                if c.get("status") in ("APPROVAL", "BLOCK")) or "needs buyer sign off"
            platform.request_approval(reason=reason, riskTier=v.get("riskTier", "T3"),
                                      proposedAction=action)
            platform.record_decision(
                decision=p.get("decision", "ESCALATE"), finalQty=qty,
                explanation=_explain(p), validationReport=v, **_stats(llm, state))
            state["execution"] = {"outcome": "NEEDS_APPROVAL", "action": action}
            return state

        result = execute_mod.execute_and_verify(
            platform, llm, state["run_id"], action, plan, sit.get("recommended_qty"))
        state["execution"] = result
        platform.record_decision(
            decision=p.get("decision", "MODIFY"), finalQty=qty,
            explanation=_explain(p), validationReport=v, **_stats(llm, state))
        return state

    g = StateGraph(State)
    g.add_node("gather", gather)
    g.add_node("analyse", analyse)
    g.add_node("propose", propose)
    g.add_node("preflight", preflight)
    g.add_node("act", act)
    g.set_entry_point("gather")
    g.add_edge("gather", "analyse")
    g.add_edge("analyse", "propose")
    g.add_edge("propose", "preflight")
    g.add_edge("preflight", "act")
    g.add_edge("act", END)
    return g.compile()


# ---- tools exposed to the model -------------------------------------------

def _read_tools(platform, kb):
    @tool
    def search_knowledge(query: str, doc_type: str = None, sku: str = None,
                         supplier_id: str = None) -> dict:
        """Search the buying policies, the replenishment playbook, supplier profiles,
        product notes and past decisions.

        Filter with doc_type, because the playbook covers the same topics as the
        policies and will crowd them out otherwise:
          policy    the rule that applies - search this before deciding
          playbook  how buyers usually handle a situation
          supplier  a supplier's capacity and behaviour (add supplier_id)
          product   storage, shelf life, demand notes
          decision  what was decided before for this product (add sku)
          supplier_message  what a supplier told us before (add supplier_id)
        Cite the ref of anything you rely on."""
        filters = {k: v for k, v in (("doc_type", doc_type), ("sku", sku),
                                     ("supplier_id", supplier_id)) if v}
        out = kb(query, **filters)

        # this search never passes through the platform, so the interceptor can't
        # trace it. log it ourselves - query, filters and what came back - or a bad
        # answer can't be traced to a bad retrieval
        try:
            platform.log_step("RETRIEVAL", "search_knowledge", {
                "query": query, "filters": filters,
                "hits": [{"ref": h["ref"], "similarity": h["similarity"]}
                         for h in out.get("data") or []],
                "error": out.get("error")})
        except Exception as e:
            log.warning("could not trace a knowledge search: %s", e)
        return out

    @tool
    def get_demand_anomaly(sku: str, node_id: str) -> dict:
        """Whether a demand change is real or explained (promotion, one-off order):
        tracking signal, how many days it has lasted, and a verdict."""
        return platform.demand_anomaly(sku, node_id)

    @tool
    def get_product(sku: str) -> dict:
        """Master data for a sku: case pack, shelf life, volume, abc class."""
        return platform.product(sku)

    @tool
    def get_inventory_position(sku: str, node_id: str) -> dict:
        """On hand, reserved, in transit and the resulting position at a node."""
        return platform.inventory_position(sku, node_id)

    @tool
    def get_demand_forecast(sku: str, node_id: str, horizon_days: int = 14) -> dict:
        """Forecast demand, with how old the forecast is."""
        return platform.demand_forecast(sku, node_id, horizon_days)

    @tool
    def get_sales_actuals(sku: str, node_id: str, lookback_days: int = 60) -> dict:
        """What actually sold, day by day."""
        return platform.sales_actuals(sku, node_id, lookback_days)

    @tool
    def list_open_pos(sku: str, node_id: str) -> dict:
        """Purchase orders already placed and not yet received."""
        return platform.open_pos(sku, node_id)

    @tool
    def get_suppliers(sku: str) -> dict:
        """Every supplier for a sku with price, moq, lead time and reliability."""
        return platform.suppliers(sku)

    @tool
    def get_budget(node_id: str, category: str) -> dict:
        """Allocated, committed, spent and available budget for a category."""
        return platform.budget(node_id, category)

    @tool
    def get_storage(node_id: str, sku: str = None) -> dict:
        """Free storage at a node, and roughly how many units of a sku fit."""
        return platform.storage(node_id, sku)

    @tool
    def get_purchase_order(po_id: str) -> dict:
        """One purchase order with its lines and confirmed quantities."""
        return platform.po(po_id)

    return [search_knowledge, get_product, get_inventory_position, get_demand_forecast,
            get_sales_actuals, get_demand_anomaly, list_open_pos, get_suppliers,
            get_budget, get_storage, get_purchase_order]


def _decision_tool():
    @tool
    def record_decision(decision: str, reasoning: str, key_factors: list,
                        confidence: float, qty: int = None,
                        assumptions: list = None, citations: list = None,
                        supplier_id: str = None) -> dict:
        """Record the purchasing decision.

        decision must be one of ACCEPT, MODIFY, REJECT, INVESTIGATE, ESCALATE.
        qty is the quantity to order. Leave it out to use the planner's quantity;
        0 means order nothing.
        assumptions are anything you took on faith rather than verified.
        citations are the refs of the reference documents the decision relies on.
        supplier_id: only when supplier options were given - which one to order from.
        """
        return {"ok": True}

    return record_decision


# ---- helpers ---------------------------------------------------------------

def _run_tool(tools, call, state):
    by_name = {t.name: t for t in tools}
    fn = by_name.get(call["name"])
    if not fn:
        return {"ok": False, "error": "NO_SUCH_TOOL", "detail": call["name"]}
    try:
        out = fn.invoke(call["args"])
    except Exception as e:
        out = {"ok": False, "error": "TOOL_FAILED", "detail": str(e)}
    state.setdefault("facts", {})[call["name"]] = out

    # retrieved text is kept apart from the facts: facts are keyed by tool name, so
    # a second search would overwrite the first, and facts get cut to 800 chars for
    # the propose prompt, which would cut a policy in half
    if call["name"] == "search_knowledge":
        state.setdefault("searches", []).append(call["args"])
    if call["name"] == "search_knowledge" and out.get("ok"):
        kept = state.setdefault("retrieved", [])
        for hit in out.get("data") or []:
            if hit["text"] not in kept and len(kept) < MAX_RETRIEVED:
                kept.append(hit["text"])
    return out


def _missing(state):
    required = REQUIRED.get(state["scenario"], [])
    called = " ".join(state.get("facts", {}).keys()).replace("_", "-")
    return [r for r in required if r.replace("-", "") not in called.replace("-", "")]


def _pick_supplier(state):
    sup = state.get("facts", {}).get("get_suppliers", {})
    rows = sup.get("data") if isinstance(sup, dict) else None
    if not rows:
        return None
    active = [r for r in rows if r.get("isActive")]
    return (active or rows)[0].get("supplierId")


def _summarise(facts):
    """Summaries, not raw dumps. A 60 row forecast becomes a shape - cheaper,
    and models reason worse when buried in irrelevant rows."""
    out = []
    for name, val in facts.items():
        if name == "search_knowledge":
            continue    # goes into the prompt whole, in its own block
        body = val.get("data") if isinstance(val, dict) else val
        text = json.dumps(body, default=str)
        if len(text) > 800:
            text = text[:800] + " ...(truncated)"
        out.append(f"{name}: {text}")
    return "\n".join(out)


def _extract_decision(res, plan):
    calls = getattr(res, "tool_calls", None) or []
    if calls:
        args = dict(calls[0]["args"])
        if args.get("decision") in ("REJECT", "INVESTIGATE"):
            # nothing is ordered for these, so the record should not say "REJECT 204"
            args["qty"] = 0
        elif args.get("qty") is None:
            # left out means "the planner's number". 0 stays 0 - a live run said
            # MODIFY, wrote "no order is needed", and got the planner's 170 placed
            args["qty"] = plan.get("recommendedQty", 0)
        return args
    # model answered in prose instead of calling the tool
    return {"decision": "INVESTIGATE",
            "reasoning": getattr(res, "content", "") or "no structured decision returned",
            "key_factors": [], "confidence": 0.0, "qty": 0}


def _apply_supplier_choice(state):
    """The model may pick a different supplier from the ranked options. Its plan
    then replaces the default one - quantity, price and lead time all come from
    that supplier's own calculation. A supplier that was not on the list is
    ignored rather than trusted."""
    p = state.get("proposal") or {}
    chosen = p.get("supplier_id")
    sit = state["situation"]
    if not chosen or chosen == sit.get("supplier_id") or not state.get("options"):
        return
    opts = {o["supplierId"]: o for o in state["options"] if o.get("usable")}
    if chosen not in opts:
        state["errors"].append(f"model chose {chosen}, which is not a usable option - ignored")
        return
    old = state.get("analysis") or {}
    new = opts[chosen]["plan"]
    # a quantity the model left to the planner follows the new supplier's planner
    if p.get("qty") == old.get("recommendedQty"):
        p["qty"] = new.get("recommendedQty")
    state["analysis"] = new
    sit["supplier_id"] = chosen


def _options_block(options):
    if not options:
        return ""
    lines = ["SUPPLIER OPTIONS (ranked by weighted penalty, lower is better; "
             "set supplier_id to choose one)"]
    for o in options:
        if not o.get("usable"):
            lines.append(f"  {o['supplierId']}: not usable - {o.get('note')}")
            continue
        pl = o["plan"]
        lines.append(
            f"  {o['supplierId']}: score {o['score']} {o['parts']} | order {pl['recommendedQty']} "
            f"at {pl['unitPrice']} = {pl['estimatedCost']} | lead {pl['leadTimeDays']}d "
            f"| ships up to {o.get('maxDailyCapacity')}/day"
            + (f" | {o['note']}" if o.get("note") else ""))
    return "\n".join(lines)


def _explain(p):
    why = p.get("reasoning") or ""
    if p.get("citations"):
        why += "\n\nSources: " + ", ".join(p["citations"])
    return why


def _stats(llm, state):
    return {"llmCalls": llm.calls, "tokensIn": llm.tokens_in, "tokensOut": llm.tokens_out,
            "durationMs": int((time.time() - state.get("started", time.time())) * 1000)}


def _delivery_date(plan):
    import datetime
    days = plan.get("leadTimeDays", 7)
    today = datetime.date.fromisoformat(cfg.sim_date)
    return (today + datetime.timedelta(days=days)).isoformat()
