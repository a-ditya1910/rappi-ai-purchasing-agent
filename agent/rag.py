"""What the agent can look up at decision time.

Buying policies, the replenishment playbook, supplier profiles, product notes,
and its own past decisions - chunked, embedded with gemini, stored in pgvector.

This is a separate postgres from the platform's mysql. mysql is the system of
record and the agent still has no credentials for it; this index only holds
reference text the agent reads, plus notes it writes about its own runs.
"""
import hashlib
import logging
import re
from pathlib import Path

from langchain_core.documents import Document
from langchain_text_splitters import MarkdownHeaderTextSplitter, RecursiveCharacterTextSplitter

from config import cfg

log = logging.getLogger(__name__)

KNOWLEDGE = Path(__file__).resolve().parent / "knowledge"
COLLECTION = "buyer_knowledge"
DIM = 768          # gemini defaults to 3072, but pgvector's hnsw index stops at 2000

TYPES = {"policies": "policy", "playbooks": "playbook", "suppliers": "supplier",
         "products": "product"}
HEADING_ID = re.compile(r"^((POL|SKU|SUP)-[A-Z0-9-]+)")
ID_FIELD = {"POL": "policy_id", "SKU": "sku", "SUP": "supplier_id"}


def load_chunks(root=KNOWLEDGE):
    # split on headings first so a policy is one chunk and never gets cut in half,
    # then split anything still long. overlap keeps a sentence cut at the edge
    # whole in one of the two pieces
    by_heading = MarkdownHeaderTextSplitter([("#", "title"), ("##", "section")],
                                            strip_headers=False)
    by_size = RecursiveCharacterTextSplitter(chunk_size=800, chunk_overlap=100)

    chunks = []
    for path in sorted(root.rglob("*.md")):
        rel = path.relative_to(root).as_posix()
        n = 0
        for part in by_heading.split_text(path.read_text(encoding="utf-8")):
            # a file's title plus its one intro line is noise in search results,
            # and the title is repeated in every section chunk below it anyway
            if "section" not in part.metadata and len(part.page_content) < 300:
                continue
            section = part.metadata.get("section") or part.metadata.get("title", "")
            meta = {"source": rel, "doc_type": TYPES.get(path.parent.name, "other"),
                    "section": section}
            if path.parent.name == "suppliers":
                meta["supplier_id"] = path.stem
            m = HEADING_ID.match(section)
            if m:
                meta[ID_FIELD[m.group(2)]] = m.group(1)
            # what the model cites - a policy or sku id when there is one
            meta["ref"] = (meta.get("policy_id") or meta.get("sku")
                           or meta.get("supplier_id") or f"{rel} > {section}")
            for piece in by_size.split_text(part.page_content):
                h = hashlib.sha1((meta["ref"] + piece).encode()).hexdigest()[:16]
                chunks.append(Document(page_content=piece,
                                       metadata={**meta, "id": f"{rel}#{n}", "hash": h}))
                n += 1
    return chunks


_store = None


def get_store():
    global _store
    if _store is None:
        from langchain_google_genai import GoogleGenerativeAIEmbeddings
        from langchain_postgres import PGVector

        emb = GoogleGenerativeAIEmbeddings(model=cfg.embed_model, google_api_key=cfg.gemini_key,
                                           output_dimensionality=DIM)
        _store = PGVector(embeddings=emb, connection=cfg.vector_db_url,
                          collection_name=COLLECTION, embedding_length=DIM, use_jsonb=True)
    return _store


def ingest(store=None):
    """Returns (embedded, total). Only new or changed chunks get embedded."""
    store = store or get_store()
    chunks = load_chunks()

    # every chunk embedded counts against the free tier's 100 requests a minute,
    # so re-embedding all of them on each restart wasted half the budget. compare
    # content hashes and only send what changed.
    have = _existing_hashes()
    todo = [c for c in chunks if have.get(c.metadata["id"]) != c.metadata["hash"]]
    if todo:
        # stable ids, so changed chunks update their row instead of adding one
        store.add_documents(todo, ids=[c.metadata["id"] for c in todo])

    # a doc that got shorter leaves its old tail chunks behind, which would keep
    # turning up in searches. memory rows (run:...) are not docs, leave them alone
    current = {c.metadata["id"] for c in chunks}
    stale = [i for i in have if i not in current and not i.startswith("run:")]
    if stale:
        store.delete(ids=stale)
    _ensure_index()
    return len(todo), len(chunks)


def _connect():
    import psycopg
    return psycopg.connect(cfg.vector_db_url.replace("+psycopg", ""))


def _existing_hashes():
    with _connect() as conn:
        rows = conn.execute("SELECT id, cmetadata->>'hash' FROM langchain_pg_embedding").fetchall()
    return dict(rows)


def _ensure_index():
    # ponytail: ~40 chunks, postgres scans them all anyway. the hnsw index is what
    # keeps search fast at a few hundred thousand, and it costs one statement.
    with _connect() as conn:
        conn.execute("CREATE INDEX IF NOT EXISTS ix_knowledge_hnsw "
                     "ON langchain_pg_embedding USING hnsw (embedding vector_cosine_ops)")


def search(query, doc_type=None, sku=None, supplier_id=None, k=4, store=None):
    conds = [{f: {"$eq": v}} for f, v in
             (("doc_type", doc_type), ("sku", sku), ("supplier_id", supplier_id)) if v]
    if not doc_type:
        # past decisions only come back when asked for by name. a live run once
        # pulled up its own earlier wrong answer from an unfiltered search and
        # cited it as evidence - memory is history, not reference material
        conds.append({"doc_type": {"$ne": "decision"}})
    filt = conds[0] if len(conds) == 1 else {"$and": conds}
    try:
        hits = (store or get_store()).similarity_search_with_score(query, k=k, filter=filt)
    except Exception as e:
        # errors are data here like everywhere else - the agent carries on without it
        log.warning("knowledge search failed: %s", e)
        return {"ok": False, "error": "KNOWLEDGE_UNAVAILABLE", "detail": str(e)[:200]}

    return {"ok": True, "data": [{
        "ref": d.metadata.get("ref"),
        "type": d.metadata.get("doc_type"),
        "similarity": round(1 - dist, 3),          # pgvector gives cosine distance
        # tagged so the prompt can say: reference text, never instructions
        "text": f'<retrieved_doc ref="{d.metadata.get("ref")}">{d.page_content}</retrieved_doc>',
    } for d, dist in hits]}


def remember(ref, text, store=None, **meta):
    # the agent's own history goes in the same index, so "what happened the last
    # time we bought this" is one search away. same ref = same row, so an approval
    # outcome later overwrites the run's note instead of adding a second one
    try:
        (store or get_store()).add_documents([Document(page_content=text, metadata={
            "doc_type": "decision", "ref": ref, "source": "memory", **meta})], ids=[ref])
    except Exception as e:
        log.warning("could not remember %s: %s", ref, e)


if __name__ == "__main__":
    logging.basicConfig(level=logging.WARNING)
    done, total = ingest()
    print(f"{done} of {total} chunks embedded, the rest were unchanged")
