"""Eval runner.

Two modes, and the difference matters:

  replay (default)  assert against recorded runs in recorded/. costs nothing,
                    needs no api key, gives the same answer on any machine.
                    proves the assertions and the pipeline are real.

  --live            actually call the model and rewrite the recordings. proves
                    the model still behaves. costs quota, and the gemini free
                    tier daily allowance is small enough that this is a handful
                    of cases per day, not the whole suite.

Replay is not a substitute for live. It measures recorded behaviour, not fresh
sampling, and the report says so rather than implying otherwise.

What gets asserted is deliberately not the answer text. A correct answer reached
by luck is not a working agent, so the checks are about whether it looked things
up, respected the constraints, and did something legal.
"""
import argparse
import json
import os
import pathlib
import sys
import time
from datetime import datetime, timezone

import httpx

HERE = pathlib.Path(__file__).parent
RECORDED = HERE / "recorded"
PLATFORM = os.getenv("PLATFORM_BASE_URL", "http://localhost:8080")
AGENT = os.getenv("AGENT_BASE_URL", "http://localhost:8100")


# ---- assertions ------------------------------------------------------------

def check(case, run):
    """Returns a list of (dimension, ok, detail)."""
    e = case["expect"]
    out = []
    decision = run.get("decision")
    qty = run.get("qty") or 0
    validation = run.get("validation") or {}
    tools = run.get("toolsCalled") or []
    execution = run.get("execution") or {}

    if "decision" in e:
        ok = decision in e["decision"]
        out.append(("decision class", ok,
                    "%s (allowed: %s)" % (decision, "/".join(e["decision"]))))

    if "qty_range" in e:
        lo, hi = e["qty_range"]
        out.append(("quantity band", lo <= qty <= hi, "%s in [%s, %s]" % (qty, lo, hi)))

    if "required_tools" in e:
        missing = [t for t in e["required_tools"] if t not in tools]
        out.append(("tool coverage", not missing,
                    "all present" if not missing else "never called: " + ", ".join(missing)))

    if "constraint_verdict_not" in e:
        v = validation.get("verdict")
        # NOT_APPLICABLE means no purchase was proposed, which is not a violation
        ok = v is None or v == "NOT_APPLICABLE" or v not in e["constraint_verdict_not"]
        out.append(("constraint integrity", ok, str(v)))

    if "risk_tier" in e:
        out.append(("risk tier", validation.get("riskTier") == e["risk_tier"],
                    str(validation.get("riskTier"))))

    if "must_mention" in e:
        text = (json.dumps(run.get("reasoning") or "")
                + json.dumps(run.get("keyFactors") or [])).lower()
        # each entry is a list of acceptable phrasings - "moq" counts as
        # "minimum order". the assertion is about whether the driver was
        # surfaced, not about which words were used for it.
        missing = [alts[0] for alts in e["must_mention"]
                   if not any(a.lower() in text for a in alts)]
        out.append(("explains the drivers", not missing,
                    "all mentioned" if not missing else "never mentioned: " + ", ".join(missing)))

    # The numbers come from the planner, so they are right. The prose around
    # them is the model's own and can still misstate what they mean - this run
    # said "position (680) plus the in-transit order (400)" when the 400 is
    # already inside the 680. Worth catching, worth not failing the run over.
    text = ((run.get("reasoning") or "") + " ".join(run.get("keyFactors") or [])).lower()
    plan = run.get("analysis") or {}
    pos, it = plan.get("inventoryPosition"), plan.get("inTransit")
    if pos and it and ("plus" in text or "added" in text):
        near = ("%d" % pos) in text and ("%d" % it) in text
        if near:
            out.append(("prose does not double count", False,
                        "describes %d and %d as additive, but %d already includes %d"
                        % (pos, it, pos, it)))


    if "max_gather_turns" in e:
        turns = run.get("gatherTurns") or 0
        out.append(("batched its reads", turns <= e["max_gather_turns"],
                    "%s turns (limit %s)" % (turns, e["max_gather_turns"])))

    if e.get("expect_no_purchase"):
        bought = execution.get("outcome") in ("VERIFIED", "REPAIRED", "ACCEPTED_WITH_VARIANCE")
        out.append(("ordered nothing", not bought, execution.get("outcome") or "no action"))

    if "forbidden_outcome" in e:
        out.append(("no runaway order", execution.get("outcome") not in e["forbidden_outcome"],
                    str(execution.get("outcome"))))

    if "anomaly_verdict" in e:
        # read straight from the platform, not from anything the model said
        verdict = run.get("_anomalyVerdict")
        out.append(("anomaly verdict", verdict == e["anomaly_verdict"],
                    "%s (expected %s)" % (verdict, e["anomaly_verdict"])))

    return out


# ---- running ---------------------------------------------------------------

def run_live(case):
    http = httpx.Client(timeout=180)
    body = dict(case["input"])

    r = http.post(PLATFORM + "/runs", json={
        "scenario": body["scenario"], "sku": body.get("sku"),
        "nodeId": body.get("node_id"), "supplierId": body.get("supplier_id"),
        "recommendedQty": body.get("recommended_qty")})
    r.raise_for_status()
    run_id = r.json()["runId"]

    body["run_id"] = run_id
    started = time.time()
    res = http.post(AGENT + "/decide", json=body)
    res.raise_for_status()
    out = res.json()
    out["_elapsedSec"] = round(time.time() - started, 1)

    # anomaly verdict comes from the platform so the assertion cannot be
    # satisfied by the model merely claiming something
    if body.get("sku"):
        try:
            a = http.get(PLATFORM + "/tools/demand-anomaly",
                         params={"sku": body["sku"], "nodeId": body["node_id"]})
            out["_anomalyVerdict"] = (a.json().get("data") or {}).get("verdict")
        except Exception:
            out["_anomalyVerdict"] = None

    out["_recordedAt"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    out["_model"] = os.getenv("GEMINI_MODEL", "unknown")
    return out


def load_recorded(case_id):
    p = RECORDED / (case_id + ".json")
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None


def save_recorded(case_id, run):
    RECORDED.mkdir(exist_ok=True)
    (RECORDED / (case_id + ".json")).write_text(
        json.dumps(run, indent=2, default=str), encoding="utf-8")


# ---- report ----------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--live", action="store_true",
                    help="call the real model and rewrite the recordings")
    ap.add_argument("--only", help="run a single case by id")
    args = ap.parse_args()

    cases = json.loads((HERE / "cases.json").read_text(encoding="utf-8"))
    if args.only:
        cases = [c for c in cases if c["id"] == args.only]
        if not cases:
            print("no case called", args.only)
            return 1

    mode = "LIVE" if args.live else "REPLAY"
    print()
    print("AI Purchasing Agent - eval report        %s mode" % mode)
    print("=" * 74)

    totals = {}
    failures = []
    skipped = []

    for case in cases:
        if args.live:
            try:
                run = run_live(case)
                save_recorded(case["id"], run)
            except Exception as ex:
                skipped.append((case["id"], str(ex)[:110]))
                continue
        else:
            run = load_recorded(case["id"])
            if run is None:
                skipped.append((case["id"], "no recording yet - run with --live"))
                continue

        results = check(case, run)
        passed = sum(1 for _, ok, _ in results if ok)
        mark = "PASS" if passed == len(results) else "FAIL"

        print()
        print("%-42s %s  %d/%d" % (case["id"], mark, passed, len(results)))
        print("   %s" % case["why"])
        print("   decision %s qty %s | tier %s | %s llm calls, %s tools" % (
            run.get("decision"), run.get("qty"),
            (run.get("validation") or {}).get("riskTier"),
            run.get("llmCalls"), len(run.get("toolsCalled") or [])))
        for dim, ok, detail in results:
            print("     %-22s %-4s %s" % (dim, "ok" if ok else "FAIL", detail))
            totals.setdefault(dim, [0, 0])
            totals[dim][1] += 1
            if ok:
                totals[dim][0] += 1
            else:
                failures.append((case["id"], dim, detail))

    print()
    print("=" * 74)
    print("BY DIMENSION")
    for dim, (ok, total) in sorted(totals.items()):
        print("  %-24s %d/%d" % (dim, ok, total))

    if failures:
        print()
        print("FAILURES")
        for cid, dim, detail in failures:
            print("  %-40s %-22s %s" % (cid, dim, detail))

    if skipped:
        print()
        print("SKIPPED")
        for cid, why in skipped:
            print("  %-40s %s" % (cid, why))

    ran = sum(t for _, t in totals.values()) if totals else 0
    ok_total = sum(o for o, _ in totals.values()) if totals else 0
    print()
    print("%d checks, %d passed%s" % (ran, ok_total,
                                      "" if not skipped else ", %d cases skipped" % len(skipped)))
    if not args.live:
        print()
        print("Replay mode asserts against recorded runs. It shows the assertions and")
        print("the pipeline work; it does not prove the model behaves the same today.")
        print("Use --live for that, quota permitting.")
    print()
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
