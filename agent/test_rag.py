"""Knowledge base tests. No database, no api key - chunking is pure python, and
search is tested against a fake store that records what it was asked."""
from langchain_core.documents import Document

import rag

chunks = rag.load_chunks()


def of_type(t):
    return [c for c in chunks if c.metadata["doc_type"] == t]


# ---- chunking --------------------------------------------------------------

def test_each_policy_is_one_whole_chunk():
    # a policy cut in half could lose its threshold - "1.25x" in one chunk and
    # "1.5x" in another - so policies must never be split
    pol = [c for c in of_type("policy") if "policy_id" in c.metadata]
    ids = [c.metadata["policy_id"] for c in pol]
    assert sorted(ids) == sorted(set(ids))
    assert len(ids) == 7
    perish = next(c for c in pol if c.metadata["policy_id"] == "POL-PERISH-02")
    assert "1.25" in perish.page_content and "1.5" in perish.page_content


def test_long_sections_are_split_and_nothing_is_too_big():
    assert max(len(c.page_content) for c in chunks) <= 800
    sections = [c.metadata["section"] for c in of_type("playbook")]
    assert any(sections.count(s) > 1 for s in sections), "some playbook section should need splitting"


def test_every_chunk_can_be_cited_and_upserted():
    assert all(c.metadata.get("ref") for c in chunks)
    ids = [c.metadata["id"] for c in chunks]
    assert len(ids) == len(set(ids)), "ids must be unique or upserts overwrite each other"


def test_supplier_and_product_chunks_carry_their_ids():
    assert {c.metadata["supplier_id"] for c in of_type("supplier")} == {
        "SUP-LACTEO", "SUP-ANDINA", "SUP-CAFEBR", "SUP-SNACKCO",
        "SUP-BEBIDAS", "SUP-GRANOS", "SUP-ARROZMX"}
    assert "SKU-MILK-1L" in {c.metadata.get("sku") for c in of_type("product")}


# ---- search ----------------------------------------------------------------

class FakeStore:
    def __init__(self, hits=None, fail=False):
        self.hits = hits or []
        self.fail = fail
        self.filter = "not called"

    def similarity_search_with_score(self, query, k=4, filter=None):
        if self.fail:
            raise ConnectionError("vectordb is down")
        self.filter = filter
        return self.hits


def test_no_filter_one_filter_and_several():
    s = FakeStore()
    rag.search("q", store=s)
    assert s.filter is None

    rag.search("q", doc_type="policy", store=s)
    assert s.filter == {"doc_type": {"$eq": "policy"}}

    # pgvector only ANDs conditions when told to
    rag.search("q", doc_type="decision", sku="SKU-MILK-1L", store=s)
    assert s.filter == {"$and": [{"doc_type": {"$eq": "decision"}},
                                 {"sku": {"$eq": "SKU-MILK-1L"}}]}


def test_results_are_tagged_as_reference_text_with_a_similarity():
    doc = Document(page_content="Amend the order down first.",
                   metadata={"ref": "POL-PARTIAL-01", "doc_type": "policy"})
    out = rag.search("short shipment", store=FakeStore([(doc, 0.18)]))

    hit = out["data"][0]
    assert hit["ref"] == "POL-PARTIAL-01"
    assert hit["similarity"] == 0.82          # cosine distance 0.18
    assert hit["text"].startswith('<retrieved_doc ref="POL-PARTIAL-01">')


def test_a_broken_store_is_an_error_value_not_a_crash():
    out = rag.search("anything", store=FakeStore(fail=True))
    assert out["ok"] is False
    assert out["error"] == "KNOWLEDGE_UNAVAILABLE"


def test_title_only_chunks_are_dropped():
    assert not any(c.metadata["ref"].endswith("> Product catalogue") for c in chunks)
    assert all(c.metadata["doc_type"] != "policy" or "policy_id" in c.metadata for c in chunks)


# ---- ingest only embeds what changed ---------------------------------------

class RecordingStore:
    def __init__(self):
        self.added = []
        self.deleted = []

    def add_documents(self, docs, ids):
        self.added.extend(ids)

    def delete(self, ids):
        self.deleted.extend(ids)


def test_unchanged_chunks_are_not_embedded_again(monkeypatch):
    # every embedded chunk is one request against a 100 a minute free tier
    stored = {c.metadata["id"]: c.metadata["hash"] for c in chunks}
    changed = chunks[3].metadata["id"]
    stored[changed] = "an old hash"
    monkeypatch.setattr(rag, "_existing_hashes", lambda: stored)
    monkeypatch.setattr(rag, "_ensure_index", lambda: None)

    s = RecordingStore()
    done, total = rag.ingest(store=s)

    assert s.added == [changed]
    assert (done, total) == (1, len(chunks))


def test_an_empty_index_gets_everything(monkeypatch):
    monkeypatch.setattr(rag, "_existing_hashes", lambda: {})
    monkeypatch.setattr(rag, "_ensure_index", lambda: None)
    s = RecordingStore()
    rag.ingest(store=s)
    assert len(s.added) == len(chunks)


def test_chunks_that_no_longer_exist_are_deleted_but_memory_is_kept(monkeypatch):
    stored = {c.metadata["id"]: c.metadata["hash"] for c in chunks}
    stored["policies/buying-policies.md#99"] = "left over from a longer version"
    stored["run:abc"] = None
    monkeypatch.setattr(rag, "_existing_hashes", lambda: stored)
    monkeypatch.setattr(rag, "_ensure_index", lambda: None)

    s = RecordingStore()
    rag.ingest(store=s)

    assert s.deleted == ["policies/buying-policies.md#99"]
    assert s.added == []
