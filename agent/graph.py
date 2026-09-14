"""The agent loop.

gather -> analyse -> propose -> preflight

analyse sits deliberately between gather and propose. It computes the quantity
from first principles and the model does not see the incoming recommendation
until after that has happened, so it cannot anchor on 800 and reason backwards
to justify it. That ordering is the assignment's "should not necessarily be
assumed to be correct" made structural instead of a line in a prompt.

Writes are not here. This graph decides; block 6 adds execute and verify.
"""
import json
import logging
import time

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import tool
from langgraph.graph import END, StateGraph
from typing_extensions import TypedDict

import prompts
from config import cfg

log = logging.getLogger(__name__)

# facts a decision is not defensible without, per scenario
REQUIRED = {
    "S1_REVIEW": ["inventory-position", "demand-forecast", "open-pos",
                  "suppliers", "budget", "storage"],
    "S2_PARTIAL": ["po", "inventory-position", "demand-forecast", "suppliers"],
    "S3_DEMAND": ["sales-actuals", "demand-forecast", "inventory-position", "open-pos"],
}


class State(TypedDict, total=False):
    run_id: str
    scenario: str
    situation: dict
    facts: dict
    analysis: dict
    proposal: dict
    validation: dict
    gather_turns: int
    errors: list
    started: float


def build(platform, llm):
    """Wires the graph. platform and llm are injected so tests can drive the
    whole thing with fakes and no network."""

    read_tools = _read_tools(platform)

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
            res = llm.invoke(msgs, tools=read_tools)
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
            res = llm.invoke(msgs, tools=read_tools)
            for call in (getattr(res, "tool_calls", None) or []):
                _run_tool(read_tools, call, state)

        state["gather_turns"] = turns
        return state

    def analyse(state):
        """No model here at all. Pure arithmetic from the platform."""
        sit = state["situation"]
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
        return state

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
                    analysis="\n".join(plan.get("explanationSteps", [])),
                    recommendation_line=rec_line))]

        res = llm.invoke(msgs, tools=[_decision_tool()])
        state["proposal"] = _extract_decision(res, plan)
        return state

    def preflight(state):
        """Deterministic. The model's opinion of whether it may act is not consulted."""
        p = state.get("proposal") or {}
        plan = state.get("analysis") or {}
        qty = p.get("qty") or plan.get("recommendedQty") or 0

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

    g = StateGraph(State)
    g.add_node("gather", gather)
    g.add_node("analyse", analyse)
    g.add_node("propose", propose)
    g.add_node("preflight", preflight)
    g.set_entry_point("gather")
    g.add_edge("gather", "analyse")
    g.add_edge("analyse", "propose")
    g.add_edge("propose", "preflight")
    g.add_edge("preflight", END)
    return g.compile()


# ---- tools exposed to the model -------------------------------------------

def _read_tools(platform):
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

    return [get_product, get_inventory_position, get_demand_forecast,
            get_sales_actuals, list_open_pos, get_suppliers, get_budget,
            get_storage, get_purchase_order]


def _decision_tool():
    @tool
    def record_decision(decision: str, reasoning: str, key_factors: list,
                        confidence: float, qty: int = 0,
                        assumptions: list = None) -> dict:
        """Record the purchasing decision.

        decision must be one of ACCEPT, MODIFY, REJECT, INVESTIGATE, ESCALATE.
        qty is the quantity to order, 0 if nothing should be ordered.
        assumptions are anything you took on faith rather than verified.
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
        if not args.get("qty"):
            args["qty"] = plan.get("recommendedQty", 0)
        return args
    # model answered in prose instead of calling the tool
    return {"decision": "INVESTIGATE",
            "reasoning": getattr(res, "content", "") or "no structured decision returned",
            "key_factors": [], "confidence": 0.0, "qty": 0}


def _delivery_date(plan):
    import datetime
    days = plan.get("leadTimeDays", 7)
    return (datetime.date(2026, 3, 10) + datetime.timedelta(days=days)).isoformat()
