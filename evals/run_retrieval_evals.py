"""Retrieval eval - does the right document come back for a question?

No model in the loop, just embeddings and pgvector, so it isolates retrieval
from reasoning. If the agent cites the wrong policy, this says whether the
search handed it the wrong one or the model ignored the right one.

Every question runs twice:

  unfiltered  plain similarity over everything
  filtered    with the doc_type the agent is told to use for that kind of question

The gap between the two is the finding. The playbook covers the same topics as
the policies, so unfiltered, the playbook section often outranks the rule it
explains. Metadata filtering is what fixes that, not better embeddings.

  hit@3  the expected doc is somewhere in the top 3
  MRR    mean of 1/rank of the expected doc (1.0 = always first, 0 = never found)

Needs the vector db up and indexed (python agent/rag.py) and a gemini key.
Two embedding calls per question.
"""
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).parent
sys.path.insert(0, str(HERE.parent / "agent"))

import rag  # noqa: E402

K = 3


def score(cases, filtered):
    hits, rr = 0, 0.0
    for c in cases:
        res = rag.search(c["query"], doc_type=c["doc_type"] if filtered else None, k=K)
        if not res["ok"]:
            raise SystemExit("knowledge store unavailable: %s" % res.get("detail"))
        refs = [h["ref"] for h in res["data"]]
        rank = refs.index(c["expect"]) + 1 if c["expect"] in refs else None
        hits += rank is not None
        rr += 1 / rank if rank else 0
        print("  %-4s %-48s rank %-4s got %s" % (
            "ok" if rank else "MISS", c["query"][:48], rank or "-",
            ", ".join(r.split(" > ")[-1][:28] for r in refs)))
    return hits, rr / len(cases)


def main():
    cases = json.loads((HERE / "retrieval_cases.json").read_text(encoding="utf-8"))
    n = len(cases)

    print()
    print("Retrieval eval   top %d" % K)
    print("=" * 74)
    print("unfiltered")
    raw = score(cases, filtered=False)
    print("filtered by doc_type")
    flt = score(cases, filtered=True)
    print("=" * 74)
    print("unfiltered   hit@%d %d/%d   MRR %.2f" % (K, raw[0], n, raw[1]))
    print("filtered     hit@%d %d/%d   MRR %.2f" % (K, flt[0], n, flt[1]))
    print()
    # the agent searches filtered, so that is the number that has to hold
    return 0 if flt[0] == n else 1


if __name__ == "__main__":
    sys.exit(main())
