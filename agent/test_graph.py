"""Graph wiring tests. No api key, no network, no database.

A fake model lets us assert the things that actually matter about the loop:
that the independent calculation happens before the model is shown the
recommendation, that a thin investigation gets nudged, and that the model's
opinion of its own authority is ignored.
"""
import json

from langchain_core.messages import AIMessage

import graph


class FakeLLM:
    """Replays scripted responses, dispatching on which tools were bound rather
    than on call order.

    Positional scripting looked fine until the graph batched all six reads into
    one turn and every later response shifted by one. Keying off the phase means
    the tests do not silently depend on how many turns gather happens to take.
    """

    def __init__(self, gather, decision=None):
        self.gather = list(gather)
        self.decision = decision
        self.prompts_seen = []
        self.calls = 0
        self.tokens_in = 0
        self.tokens_out = 0

    def invoke(self, messages, tools=None):
        self.calls += 1
        self.prompts_seen.append("\n".join(str(getattr(m, "content", "")) for m in messages))
        names = [getattr(t, "name", "") for t in (tools or [])]
        if "record_decision" in names:
            return self.decision or AIMessage(content="no decision was recorded")
        return self.gather.pop(0) if self.gather else AIMessage(content="done gathering")


class FakePlatform:
    def __init__(self, plan=None):
        self.calls = []
        self.validated = None
        self._plan = plan or {
            "recommendedQty": 204, "rawNeed": 156, "inventoryPosition": 680,
            "leadTimeDays": 5, "unitPrice": 0.95, "estimatedCost": 193.80,
            "explanationSteps": [
                "Protection period = 5 lead time + 7 review period = 12 days",
                "Position = 320 on hand - 40 reserved + 400 arriving = 680",
                "Need 156 is below the 200 minimum order quantity",
            ],
            "warnings": [],
        }

    def calculate_reorder(self, sku, nodeId, supplierId):
        self.calls.append("/tools/calculate-reorder")
        return {"ok": True, "data": dict(self._plan)}

    def validate_purchase(self, **kw):
        self.calls.append("/tools/validate-purchase")
        self.validated = kw
        return {"ok": True, "data": {"verdict": "NEEDS_APPROVAL", "riskTier": "T3",
                                     "checks": [], "blocking": []}}

    def _ok(self, name, data):
        self.calls.append(name)
        return {"ok": True, "data": data}

    def product(self, sku):                       return self._ok("/tools/product", {"sku": sku})
    def inventory_position(self, sku, nodeId):    return self._ok("/tools/inventory-position", {"position": 680})
    def demand_forecast(self, sku, nodeId, h=14): return self._ok("/tools/demand-forecast", {"meanPerDay": 57.5})
    def sales_actuals(self, sku, nodeId, l=60):   return self._ok("/tools/sales-actuals", [])
    def open_pos(self, sku, nodeId):              return self._ok("/tools/open-pos", [{"poId": "PO-0007"}])
    def suppliers(self, sku):                     return self._ok("/tools/suppliers", [{"supplierId": "SUP-LACTEO", "isActive": True}])
    def budget(self, nodeId, category):           return self._ok("/tools/budget", {"available": 480})
    def storage(self, nodeId, sku=None):          return self._ok("/tools/storage", {"freeCm3": 6000000})
    def po(self, poId):                           return self._ok("/tools/po", {"poId": poId})


def tool_call(name, args, cid="1"):
    return AIMessage(content="", tool_calls=[{"name": name, "args": args, "id": cid}])


def gathered_everything():
    """One turn that calls all six mandatory reads together - the batching the
    prompt asks for, and what keeps us under the free tier rpm ceiling."""
    return AIMessage(content="", tool_calls=[
        {"name": "get_inventory_position", "args": {"sku": "SKU-MILK-1L", "node_id": "NODE-BOG-01"}, "id": "a"},
        {"name": "get_demand_forecast", "args": {"sku": "SKU-MILK-1L", "node_id": "NODE-BOG-01"}, "id": "b"},
        {"name": "list_open_pos", "args": {"sku": "SKU-MILK-1L", "node_id": "NODE-BOG-01"}, "id": "c"},
        {"name": "get_suppliers", "args": {"sku": "SKU-MILK-1L"}, "id": "d"},
        {"name": "get_budget", "args": {"node_id": "NODE-BOG-01", "category": "dairy"}, "id": "e"},
        {"name": "get_storage", "args": {"node_id": "NODE-BOG-01"}, "id": "f"},
    ])


def decision(decision="MODIFY", qty=204, confidence=0.86):
    return tool_call("record_decision", {
        "decision": decision, "qty": qty, "confidence": confidence,
        "reasoning": "PO-0007 already covers most of the need",
        "key_factors": ["400 units arriving in 3 days", "moq forces 204"],
    })


def situation(**over):
    base = {"sku": "SKU-MILK-1L", "node_id": "NODE-BOG-01",
            "supplier_id": "SUP-LACTEO", "recommended_qty": 800}
    base.update(over)
    return {"run_id": "r1", "scenario": "S1_REVIEW", "situation": base,
            "facts": {}, "errors": []}


def run(llm, platform, state=None):
    return graph.build(platform, llm).invoke(state or situation())


# ---- the ordering that stops anchoring ------------------------------------

def test_calculation_happens_before_the_model_sees_the_recommendation():
    llm = FakeLLM([gathered_everything()], decision())
    out = run(llm, FakePlatform())

    gather_prompt, propose_prompt = llm.prompts_seen[0], llm.prompts_seen[-1]

    # the gather prompt must not frame 800 as an instruction
    assert "recommends" not in gather_prompt.lower()
    # and the independent 204 must already be in hand by the time we propose
    assert "204" in propose_prompt
    assert out["analysis"]["recommendedQty"] == 204


def test_delta_against_the_recommendation_is_computed_not_guessed():
    out = run(FakeLLM([gathered_everything()], decision()), FakePlatform())
    assert out["analysis"]["deltaVsRecommendationPct"] == -74.5


# ---- investigation floor ---------------------------------------------------

def test_thin_investigation_gets_nudged_with_the_specific_gap():
    llm = FakeLLM([
        tool_call("get_inventory_position", {"sku": "SKU-MILK-1L", "node_id": "NODE-BOG-01"}),
        AIMessage(content="I have enough"),
        AIMessage(content="", tool_calls=[
            {"name": "get_budget", "args": {"node_id": "NODE-BOG-01", "category": "dairy"}, "id": "z"}]),
    ], decision())
    run(llm, FakePlatform())

    nudge = [p for p in llm.prompts_seen if "not checked" in p]
    assert nudge, "should have named the missing facts"
    assert "budget" in nudge[0] and "suppliers" in nudge[0]


def test_gather_is_capped_so_a_loop_cannot_run_away():
    llm = FakeLLM([tool_call("get_product", {"sku": "SKU-MILK-1L"}, str(i)) for i in range(20)],
                  decision())
    out = run(llm, FakePlatform())
    assert out["gather_turns"] <= 5


# ---- authority is not the model's to decide -------------------------------

def test_preflight_runs_even_when_the_model_is_confident():
    platform = FakePlatform()
    run(FakeLLM([gathered_everything()], decision(confidence=0.99)), platform)

    assert "/tools/validate-purchase" in platform.calls
    assert platform.validated["qty"] == 204
    assert platform.validated["recommendedQty"] == 800


def test_no_purchase_means_no_validation_call():
    platform = FakePlatform()
    out = run(FakeLLM([gathered_everything()], decision("INVESTIGATE", qty=0)), platform)

    assert out["validation"]["verdict"] == "NOT_APPLICABLE"
    assert "/tools/validate-purchase" not in platform.calls


# ---- failure modes ---------------------------------------------------------

def test_prose_instead_of_a_structured_decision_becomes_investigate():
    out = run(FakeLLM([gathered_everything()],
                      AIMessage(content="I think we should probably order some")),
              FakePlatform())
    assert out["proposal"]["decision"] == "INVESTIGATE"
    assert out["proposal"]["confidence"] == 0.0


def test_a_failed_calculation_does_not_produce_a_guessed_quantity():
    class Broken(FakePlatform):
        def calculate_reorder(self, *a, **k):
            return {"ok": False, "error": "NO_FORECAST"}

    out = run(FakeLLM([gathered_everything()], decision()), Broken())
    assert out["proposal"]["decision"] == "INVESTIGATE"
    assert "NO_FORECAST" in " ".join(out["errors"])


def test_unknown_tool_is_reported_back_rather_than_crashing():
    llm = FakeLLM([tool_call("get_the_answer", {}), gathered_everything()], decision())
    out = run(llm, FakePlatform())
    assert out["proposal"]["decision"] == "MODIFY"
