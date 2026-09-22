import asyncio

import pytest
from langchain_core.embeddings import DeterministicFakeEmbedding

from app import rag


@pytest.fixture
def offline_rag(monkeypatch, tmp_path):
    monkeypatch.setattr(rag, "get_embeddings", lambda: DeterministicFakeEmbedding(size=64))
    monkeypatch.setattr(rag.get_settings(), "vector_index_file", tmp_path / "index.json")
    monkeypatch.setattr(rag, "_store", None)
    return tmp_path / "index.json"


def test_knowledge_base_is_chunked_indexed_and_persisted(offline_rag):
    store = rag.build_index()
    assert offline_rag.exists()
    sources = {doc["metadata"]["source"] for doc in store.store.values()}
    assert {"reorder_policy.md", "damaged_goods_policy.md", "supplier_contract_acme.md"} <= sources


def test_search_returns_cited_excerpts_from_persisted_index(offline_rag):
    rag.build_index()
    rag._store = None  # force a reload from disk
    docs = asyncio.run(rag.search("late delivery penalty", k=3))
    assert len(docs) == 3
    formatted = rag.format_docs(docs)
    assert formatted.startswith("[") and ".md]" in formatted
