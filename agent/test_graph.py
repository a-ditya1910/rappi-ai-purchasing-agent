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

    def __init__(self, gather, decision=None, repair=None):
        self.gather = list(gather)
        self.decision = decision
        self.repair = repair
        self.prompts_seen = []
        self.labels = []
        self.calls = 0
        self.tokens_in = 0
        self.tokens_out = 0

    def invoke(self, messages, tools=None, label=None):
        self.calls += 1
        self.labels.append(label)
        self.prompts_seen.append("\n".join(str(getattr(m, "content", "")) for m in messages))
        names = [getattr(t, "name", "") for t in (tools or [])]
        if "choose_repair" in names:
            return self.repair or AIMessage(content="no repair chosen")
        if "record_decision" in names:
            return self.decision or AIMessage(content="no decision was recorded")
        return self.gather.pop(0) if self.gather else AIMessage(content="done gathering")


class FakeKB:
    """Stands in for rag.search. Records every query it was asked."""

    def __init__(self, hits=None):
        self.queries = []
        self.hits = hits if hits is not None else [
            {"ref": "POL-PERISH-02", "type": "policy", "similarity": 0.81,
             "text": '<retrieved_doc ref="POL-PERISH-02">up to 1.25 times shelf life</retrieved_doc>'}]

    def __call__(self, query, **filters):
        self.queries.append((query, filters))
        return {"ok": True, "data": list(self.hits)}


class FakePlatform:
    def __init__(self, plan=None, validation=None, verification=None):
        self.calls = []
        self.validated = None
        self.created = []
        self.amended = []
        self.approvals = []
        self.decisions = []
        self.steps = []
        self._validation = validation or {"verdict": "NEEDS_APPROVAL", "riskTier": "T3",
                                          "checks": [], "blocking": [],
                                          "requiresApproval": True}
        self._verification = verification or {"outcome": "VERIFIED", "diffs": [], "notes": []}
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
        return {"ok": True, "data": dict(self._validation)}

    def create_po(self, **kw):
        self.calls.append("/tools/create-po")
        self.created.append(kw)
        return {"ok": True, "data": {
            "executed": True, "idempotent": False, "poId": "PO-9001",
            "verification": dict(self._verification), "message": "ok"}}

    def amend_po(self, poId, newQty, expectedVersion, reason):
        self.calls.append("/tools/amend-po")
        self.amended.append({"poId": poId, "newQty": newQty})
        return {"ok": True, "data": {"executed": True, "poId": poId}}

    def verify_po(self, poId, action):
        self.calls.append("/tools/verify-po")
        self.reverified = action
        return {"ok": True, "data": dict(getattr(self, "_reverify", {"outcome": "VERIFIED",
                                                                   "diffs": [], "notes": []}))}

    def cancel_po(self, poId, expectedVersion, reason):
        self.calls.append("/tools/cancel-po")
        return {"ok": True, "data": {"executed": True, "poId": poId}}

    def request_approval(self, reason, riskTier, proposedAction):
        self.calls.append("/tools/request-approval")
        self.approvals.append({"reason": reason, "riskTier": riskTier,
                               "action": proposedAction})
        return {"ok": True, "data": {"approvalId": "AP-1", "status": "PENDING"}}

    def record_decision(self, decision, finalQty=None, explanation=None,
                        validationReport=None, **stats):
        self.calls.append("/tools/record-decision")
        self.decisions.append({"decision": decision, "finalQty": finalQty,
                               "explanation": explanation, **stats})
        return {"ok": True, "data": {"ok": True}}

    def log_step(self, type_, name, payload=None, latencyMs=None, tokens=None):
        self.steps.append({"type": type_, "name": name, "payload": payload})

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
    def po(self, poId):                           return self._ok("/tools/po", {"poId": poId, "version": 1})
    def demand_anomaly(self, sku, nodeId, l=60):  return self._ok("/tools/demand-anomaly", {"verdict": "REAL"})

    def supplier_options(self, sku, nodeId):
        def opt(sid, qty, price, lead, score):
            return {"supplierId": sid, "usable": True, "score": score, "note": None,
                    "parts": {"price": 0.0}, "maxDailyCapacity": 2000,
                    "plan": {"recommendedQty": qty, "rawNeed": qty, "unitPrice": price,
                             "estimatedCost": qty * price, "leadTimeDays": lead,
                             "explanationSteps": [f"plan for {sid}"], "warnings": []}}
        return self._ok("/tools/supplier-options", [
            opt("SUP-ANDINA", 150, 3.90, 4, 0.04), opt("SUP-CAFEBR", 400, 4.40, 9, 0.09),
            {"supplierId": "SUP-GONE", "usable": False, "note": "inactive, cannot order"}])


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
        {"name": "search_knowledge", "args": {"query": "perishable over-buy"}, "id": "g"},
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


def run(llm, platform, state=None, kb=None):
    return graph.build(platform, llm, kb or FakeKB()).invoke(state or situation())


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


def covered_plan():
    """Position already covers the target - nothing needed, nothing to order."""
    return {"recommendedQty": 0, "rawNeed": 0, "inventoryPosition": 900,
            "leadTimeDays": 5, "unitPrice": 0.95,
            "explanationSteps": ["Position already covers the target, nothing to order"],
            "warnings": []}


def test_no_purchase_means_no_validation_call():
    platform = FakePlatform(plan=covered_plan())
    out = run(FakeLLM([gathered_everything()], decision("INVESTIGATE", qty=0)), platform)

    assert out["validation"]["verdict"] == "NOT_APPLICABLE"
    assert "/tools/validate-purchase" not in platform.calls
    assert "/tools/create-po" not in platform.calls
    assert out["execution"]["outcome"] == "NO_ACTION"


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


# ---- act: execute, or hand it to a human ----------------------------------

def auto_approve():
    return {"verdict": "PASS", "riskTier": "T2", "checks": [], "blocking": [],
            "requiresApproval": False}


def test_t3_queues_for_a_buyer_and_orders_nothing():
    platform = FakePlatform()          # defaults to T3
    out = run(FakeLLM([gathered_everything()], decision()), platform)

    assert out["execution"]["outcome"] == "NEEDS_APPROVAL"
    assert platform.approvals, "should have raised an approval"
    assert "/tools/create-po" not in platform.calls, "nothing may be ordered at T3"
    assert platform.approvals[0]["action"]["qty"] == 204


def test_t2_executes_and_the_write_is_verified():
    platform = FakePlatform(validation=auto_approve())
    out = run(FakeLLM([gathered_everything()], decision()), platform)

    assert out["execution"]["outcome"] == "VERIFIED"
    assert out["execution"]["poId"] == "PO-9001"
    assert platform.created[0]["qty"] == 204
    assert not platform.approvals


def test_same_intent_always_gets_the_same_idempotency_key():
    import execute
    a = execute.idempotency_key("run-1", "create_po", "SKU-MILK-1L", 204)
    b = execute.idempotency_key("run-1", "create_po", "SKU-MILK-1L", 204)
    c = execute.idempotency_key("run-1", "create_po", "SKU-MILK-1L", 240)
    assert a == b, "a retry must not become a second order"
    assert a != c


def test_a_mismatch_triggers_repair_not_a_shrug():
    shortfall = {"outcome": "MISMATCH", "notes": [],
                 "diffs": [{"level": "L1", "field": "qtyConfirmed", "intended": "204",
                            "actual": "120", "ok": False}]}
    platform = FakePlatform(validation=auto_approve(), verification=shortfall)
    llm = FakeLLM([gathered_everything()], decision(),
                  repair=tool_call("choose_repair", {"repair": "amend", "qty": 120,
                                                     "reasoning": "match what was confirmed"}))
    out = run(llm, platform)

    assert out["execution"]["outcome"] == "REPAIRED"
    assert platform.amended[0]["newQty"] == 120


def test_accept_as_is_is_refused_when_the_shelf_is_still_empty():
    """The option a model reaches for when it wants to be done. If L3 failed the
    goal was not met, so it must not be available."""
    stockout = {"outcome": "MISMATCH", "notes": ["still short: 2 days"],
                "diffs": [{"level": "L3", "field": "stockoutDays", "intended": "0",
                           "actual": "2", "ok": False}]}
    platform = FakePlatform(validation=auto_approve(), verification=stockout)
    llm = FakeLLM([gathered_everything()], decision(),
                  repair=tool_call("choose_repair", {"repair": "accept_as_is",
                                                     "reasoning": "close enough"}))
    out = run(llm, platform)

    assert out["execution"]["outcome"] == "ESCALATED"
    assert any("refused" in str(r.get("overridden", "")) for r in out["execution"]["repairs"])


def test_a_repair_outside_the_allowed_set_escalates():
    mismatch = {"outcome": "MISMATCH", "notes": [],
                "diffs": [{"level": "L1", "field": "qtyConfirmed", "intended": "204",
                           "actual": "120", "ok": False}]}
    platform = FakePlatform(validation=auto_approve(), verification=mismatch)
    llm = FakeLLM([gathered_everything()], decision(),
                  repair=tool_call("choose_repair", {"repair": "reorder_everything_twice",
                                                     "reasoning": "creative"}))
    out = run(llm, platform)

    assert out["execution"]["outcome"] == "ESCALATED"


# ---- approvals execute exactly what was approved --------------------------

def test_the_queued_action_carries_the_full_intent():
    platform = FakePlatform()          # T3
    run(FakeLLM([gathered_everything()], decision()), platform)

    action = platform.approvals[0]["action"]
    assert action["recommendedQty"] == 800, "resume needs it to re-run the same checks"
    assert action["decision"] == "MODIFY", "resume records the decision the agent made"


def test_resume_hands_the_approval_id_to_the_write():
    import execute
    platform = FakePlatform()
    action = {"sku": "SKU-MILK-1L", "nodeId": "NODE-BOG-01", "supplierId": "SUP-LACTEO",
              "qty": 204, "unitPrice": 0.95, "expectedDelivery": "2026-03-15"}
    out = execute.execute_and_verify(platform, FakeLLM([]), "r1", action, {}, 800,
                                     approval_id="AP-1")

    assert platform.created[0]["approvalId"] == "AP-1"
    assert out["outcome"] == "VERIFIED"


# ---- observability ---------------------------------------------------------

def test_every_model_call_says_which_node_made_it():
    llm = FakeLLM([gathered_everything()], decision())
    run(llm, FakePlatform())
    assert llm.labels == ["gather", "propose"]


def test_run_stats_are_recorded_with_the_decision():
    llm = FakeLLM([gathered_everything()], decision())
    platform = FakePlatform()
    run(llm, platform)

    d = platform.decisions[0]
    assert d["llmCalls"] == llm.calls == 2
    assert "tokensIn" in d and "durationMs" in d


def test_the_llm_reports_each_call_with_its_tokens(monkeypatch):
    import llm as llm_mod

    class NoLimit:
        def acquire(self):
            pass

    class FakeChat:
        def invoke(self, messages):
            return AIMessage(content="ok", usage_metadata={
                "input_tokens": 120, "output_tokens": 30, "total_tokens": 150})

    monkeypatch.setattr(llm_mod.cfg, "gemini_key", "test-key")
    seen = []
    g = llm_mod.Gemini(limiter=NoLimit(), on_call=lambda **kw: seen.append(kw))
    g._chat = FakeChat()

    g.invoke(["hi"], label="propose")

    assert seen[0]["label"] == "propose"
    assert (seen[0]["tokens_in"], seen[0]["tokens_out"]) == (120, 30)
    assert g.tokens_in == 120 and g.calls == 1


def test_a_broken_tracer_does_not_break_the_call(monkeypatch):
    import llm as llm_mod

    class NoLimit:
        def acquire(self):
            pass

    class FakeChat:
        def invoke(self, messages):
            return AIMessage(content="ok")

    def boom(**kw):
        raise RuntimeError("platform is down")

    monkeypatch.setattr(llm_mod.cfg, "gemini_key", "test-key")
    g = llm_mod.Gemini(limiter=NoLimit(), on_call=boom)
    g._chat = FakeChat()

    assert g.invoke(["hi"]).content == "ok"


# ---- retrieval at decision time --------------------------------------------

def test_retrieved_text_reaches_the_propose_prompt_whole():
    long_policy = "x" * 1200 + " the 1.5x hard limit"
    kb = FakeKB([{"ref": "POL-PERISH-02", "type": "policy", "similarity": 0.8,
                  "text": f'<retrieved_doc ref="POL-PERISH-02">{long_policy}</retrieved_doc>'}])
    llm = FakeLLM([gathered_everything()], decision())
    run(llm, FakePlatform(), kb=kb)

    propose_prompt = llm.prompts_seen[-1]
    # facts get cut to 800 chars - retrieved text must not, or a policy loses its limit
    assert "REFERENCE DOCUMENTS" in propose_prompt
    assert "the 1.5x hard limit" in propose_prompt


def test_two_searches_both_survive():
    hits = iter([
        [{"ref": "POL-PERISH-02", "type": "policy", "similarity": 0.8,
          "text": '<retrieved_doc ref="POL-PERISH-02">perish</retrieved_doc>'}],
        [{"ref": "SUP-LACTEO", "type": "supplier", "similarity": 0.7,
          "text": '<retrieved_doc ref="SUP-LACTEO">lacteo</retrieved_doc>'}],
    ])

    def kb(query, **f):
        # the third call is analyse's own policy lookup
        return {"ok": True, "data": next(hits, [])}

    llm = FakeLLM([AIMessage(content="", tool_calls=[
        *gathered_everything().tool_calls[:-1],
        {"name": "search_knowledge", "args": {"query": "perishable"}, "id": "s1"},
        {"name": "search_knowledge", "args": {"query": "lacteo"}, "id": "s2"},
    ])], decision())
    out = run(llm, FakePlatform(), kb=kb)

    # facts are keyed by tool name, so the second search used to overwrite the first
    assert any("perish" in t for t in out["retrieved"])
    assert any("lacteo" in t for t in out["retrieved"])


def test_skipping_the_knowledge_search_gets_nudged():
    reads_only = AIMessage(content="", tool_calls=gathered_everything().tool_calls[:-1])
    llm = FakeLLM([reads_only], decision())
    run(llm, FakePlatform())

    nudge = [p for p in llm.prompts_seen if "not checked" in p]
    assert nudge and "search-knowledge" in nudge[0]


def test_citations_end_up_in_the_recorded_explanation():
    cited = tool_call("record_decision", {
        "decision": "MODIFY", "qty": 204, "confidence": 0.8,
        "reasoning": "moq forces a small over-buy", "key_factors": [],
        "citations": ["POL-PERISH-02", "SUP-LACTEO"]})
    platform = FakePlatform()
    kb = FakeKB([
        {"ref": "POL-PERISH-02", "type": "policy", "similarity": 0.8,
         "text": '<retrieved_doc ref="POL-PERISH-02">perish</retrieved_doc>'},
        {"ref": "SUP-LACTEO", "type": "supplier", "similarity": 0.7,
         "text": '<retrieved_doc ref="SUP-LACTEO">lacteo</retrieved_doc>'}])
    out = run(FakeLLM([gathered_everything()], cited), platform, kb=kb)

    assert out["proposal"]["citations"] == ["POL-PERISH-02", "SUP-LACTEO"]
    assert platform.decisions[0]["explanation"].endswith("Sources: POL-PERISH-02, SUP-LACTEO")


def test_every_knowledge_search_is_traced_with_what_came_back():
    platform = FakePlatform()
    out = run(FakeLLM([gathered_everything()], decision()), platform)

    step = next(s for s in platform.steps if s["type"] == "RETRIEVAL")
    assert step["payload"]["query"] == "perishable over-buy"
    assert step["payload"]["hits"] == [{"ref": "POL-PERISH-02", "similarity": 0.81}]
    assert out["searches"] == [{"query": "perishable over-buy"}]


def test_a_reject_never_records_a_quantity():
    # the live run said "REJECT 204" - nothing was ordered, but the record lied
    out = run(FakeLLM([gathered_everything()], decision("REJECT", qty=204)), FakePlatform())
    assert out["proposal"]["qty"] == 0


# ---- the rules are looked up by code, not left to the model ---------------

def test_policies_are_looked_up_even_if_the_model_never_searches():
    kb = FakeKB()
    reads_only = AIMessage(content="", tool_calls=gathered_everything().tool_calls[:-1])
    llm = FakeLLM([reads_only, AIMessage(content="no thanks")], decision())
    platform = FakePlatform()
    run(llm, platform, kb=kb)

    policy_lookups = [q for q, f in kb.queries if f.get("doc_type") == "policy"]
    assert policy_lookups, "analyse must search the policies itself"
    assert "minimum order quantity" in policy_lookups[0]   # built from the planner's steps
    assert "POL-PERISH-02" in llm.prompts_seen[-1]
    assert any(s["name"] == "policy_lookup" for s in platform.steps)


# ---- doing nothing is not silent when the maths shows a need --------------

def test_a_reject_with_a_real_need_goes_to_a_buyer():
    platform = FakePlatform()          # plan: need 156, legal order 204
    out = run(FakeLLM([gathered_everything()], decision("REJECT", qty=0)), platform)

    assert out["execution"]["outcome"] == "NEEDS_APPROVAL"
    assert out["execution"]["guard"] == "declined_despite_need"
    assert platform.approvals[0]["action"]["qty"] == 204, "the buyer can approve the planner's order"
    assert "need of 156" in platform.approvals[0]["reason"]
    assert "/tools/create-po" not in platform.calls
    assert platform.decisions[0]["decision"] == "REJECT", "the model's own call is still recorded"


def test_a_reject_with_nothing_needed_stays_quiet():
    platform = FakePlatform(plan=covered_plan())
    out = run(FakeLLM([gathered_everything()], decision("REJECT", qty=0)), platform)
    assert out["execution"]["outcome"] == "NO_ACTION"
    assert not platform.approvals



# ---- scenario 2: a short shipment, and the choice of where the rest comes from

def short_shipment():
    return {"run_id": "r2", "scenario": "S2_PARTIAL", "facts": {}, "errors": [],
            "situation": {"sku": "SKU-COFFEE-500G", "node_id": "NODE-BOG-01",
                          "supplier_id": "SUP-ANDINA", "recommended_qty": None,
                          "po_id": "PO-0031"}}


def s2_gather():
    return AIMessage(content="", tool_calls=[
        {"name": "get_purchase_order", "args": {"po_id": "PO-0031"}, "id": "a"},
        {"name": "get_inventory_position", "args": {"sku": "SKU-COFFEE-500G", "node_id": "NODE-BOG-01"}, "id": "b"},
        {"name": "get_demand_forecast", "args": {"sku": "SKU-COFFEE-500G", "node_id": "NODE-BOG-01"}, "id": "c"},
        {"name": "get_suppliers", "args": {"sku": "SKU-COFFEE-500G"}, "id": "d"},
        {"name": "search_knowledge", "args": {"query": "partial", "doc_type": "policy"}, "id": "e"},
    ])


def picks(supplier_id, qty=None):
    args = {"decision": "MODIFY", "confidence": 0.8, "key_factors": [],
            "reasoning": "top up the shortfall", "supplier_id": supplier_id}
    if qty is not None:
        args["qty"] = qty
    return tool_call("record_decision", args)


def test_s2_ranks_suppliers_and_shows_them_to_the_model():
    llm = FakeLLM([s2_gather()], picks(None))
    platform = FakePlatform(validation=auto_approve())
    out = run(llm, platform, state=short_shipment())

    assert "/tools/supplier-options" in platform.calls
    assert "/tools/calculate-reorder" not in platform.calls
    assert "SUPPLIER OPTIONS" in llm.prompts_seen[-1]
    assert "SUP-GONE: not usable" in llm.prompts_seen[-1]
    assert out["analysis"]["recommendedQty"] == 150         # best ranked by default


def test_s2_choosing_another_supplier_switches_to_its_own_plan():
    llm = FakeLLM([s2_gather()], picks("SUP-CAFEBR"))
    platform = FakePlatform(validation=auto_approve())
    out = run(llm, platform, state=short_shipment())

    assert platform.validated["supplierId"] == "SUP-CAFEBR"
    assert platform.validated["qty"] == 400                 # cafebr's plan, not andina's
    assert platform.validated["unitPrice"] == 4.40
    assert platform.created[0]["supplierId"] == "SUP-CAFEBR"


def test_s2_a_supplier_that_was_not_offered_is_ignored():
    llm = FakeLLM([s2_gather()], picks("SUP-MADE-UP"))
    platform = FakePlatform(validation=auto_approve())
    out = run(llm, platform, state=short_shipment())

    assert platform.validated["supplierId"] == "SUP-ANDINA"
    assert any("SUP-MADE-UP" in e for e in out["errors"])



# ---- live run fixes: no order means no order, repairs are re-checked --------

def test_modify_with_qty_zero_does_not_place_the_planners_order():
    # live: "no additional order is required", decision MODIFY, qty left as 0 ->
    # the planner's 170 got ordered. 0 must mean 0
    platform = FakePlatform(validation=auto_approve())
    out = run(FakeLLM([gathered_everything()], decision("MODIFY", qty=0)), platform)

    assert "/tools/create-po" not in platform.calls
    assert out["execution"]["guard"] == "declined_despite_need"


def test_modify_without_a_qty_takes_the_planners_number():
    no_qty = tool_call("record_decision", {"decision": "MODIFY", "confidence": 0.8,
                                           "reasoning": "planner is right", "key_factors": []})
    out = run(FakeLLM([gathered_everything()], no_qty), FakePlatform())
    assert out["proposal"]["qty"] == 204


def mismatch(level="L3", field="stockoutDays"):
    return {"outcome": "MISMATCH", "notes": [],
            "diffs": [{"level": level, "field": field, "intended": "0", "actual": "8", "ok": False}]}


def test_a_repair_without_a_quantity_is_refused_not_amended_to_zero():
    platform = FakePlatform(validation=auto_approve(), verification=mismatch())
    llm = FakeLLM([gathered_everything()], decision(),
                  repair=tool_call("choose_repair", {"repair": "amend", "reasoning": "fix it"}))
    out = run(llm, platform)

    assert not platform.amended, "nothing may be amended to 0"
    assert out["execution"]["outcome"] == "ESCALATED"
    assert "needs a quantity" in out["execution"]["repairs"][0]["overridden"]


def test_a_repair_is_verified_again_before_it_counts():
    platform = FakePlatform(validation=auto_approve(), verification=mismatch())
    platform._reverify = mismatch()                  # the amend did not fix the shelf
    llm = FakeLLM([gathered_everything()], decision(),
                  repair=tool_call("choose_repair", {"repair": "amend", "qty": 240,
                                                     "reasoning": "a bit more"}))
    out = run(llm, platform)

    assert "/tools/verify-po" in platform.calls
    assert platform.reverified["qty"] == 240
    assert out["execution"]["outcome"] == "ESCALATED", "an amend that did not fix it is not REPAIRED"


def test_the_extraction_prompt_knows_today():
    import inbox
    from langchain_core.messages import AIMessage as AI

    class Seen:
        def invoke(self, messages, tools=None, label=None):
            self.prompt = messages[0].content
            return AI(content="")

    llm = Seen()
    inbox.extract(llm, "SUP-ANDINA", "", "arrives 14 March")
    assert "Today is 2026-03-10" in llm.prompt


# ---- citations are only ever what the agent was given ----------------------

def test_a_policy_named_in_the_reasoning_becomes_a_citation():
    # live: the model wrote "Per POL-PERISH-02 ..." but left citations empty
    named = tool_call("record_decision", {
        "decision": "MODIFY", "qty": 204, "confidence": 0.8, "key_factors": [],
        "reasoning": "Per POL-PERISH-02 a small over-buy is acceptable with approval."})
    out = run(FakeLLM([gathered_everything()], named), FakePlatform())
    assert out["proposal"]["citations"] == ["POL-PERISH-02"]


def test_a_citation_that_was_never_retrieved_is_dropped():
    made_up = tool_call("record_decision", {
        "decision": "MODIFY", "qty": 204, "confidence": 0.8, "key_factors": [],
        "reasoning": "fine", "citations": ["POL-PERISH-02", "POL-DOES-NOT-EXIST"]})
    out = run(FakeLLM([gathered_everything()], made_up), FakePlatform())

    assert out["proposal"]["citations"] == ["POL-PERISH-02"]
    assert any("POL-DOES-NOT-EXIST" in e for e in out["errors"])
