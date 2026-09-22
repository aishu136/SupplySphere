"""RAG over supplier contracts and SOPs: Titan embeddings on Bedrock + a vector store, answered by Claude.

The knowledge base is a few dozen chunks, so LangChain's in-memory store (exact cosine search, persisted
to JSON) is enough. For thousands of documents, swap in OpenSearch Serverless or Bedrock Knowledge Bases.
"""
import asyncio
import logging

from langchain_core.documents import Document
from langchain_core.vectorstores import InMemoryVectorStore
from langchain_text_splitters import RecursiveCharacterTextSplitter

from app.config import get_settings
from app.llm import get_chat_model, get_embeddings, message_text

log = logging.getLogger(__name__)

_store: InMemoryVectorStore | None = None
_lock = asyncio.Lock()

ANSWER_PROMPT = """You answer questions for supply chain operators using only the policy excerpts below.
Cite the source file name in square brackets after each fact, e.g. [returns_sop.md].
If the excerpts do not contain the answer, say so plainly.

<excerpts>
{context}
</excerpts>

Question: {question}"""


def load_documents() -> list[Document]:
    kb = get_settings().knowledge_base_dir
    return [Document(page_content=p.read_text(encoding="utf-8"), metadata={"source": p.name})
            for p in sorted(kb.glob("**/*.md"))]


def build_index() -> InMemoryVectorStore:
    s = get_settings()
    docs = load_documents()
    if not docs:
        raise RuntimeError(f"No documents found in {s.knowledge_base_dir}")
    chunks = RecursiveCharacterTextSplitter(chunk_size=800, chunk_overlap=120).split_documents(docs)
    store = InMemoryVectorStore(get_embeddings())
    store.add_documents(chunks)
    s.vector_index_file.parent.mkdir(parents=True, exist_ok=True)
    store.dump(str(s.vector_index_file))
    log.info("Indexed %d chunks from %d documents", len(chunks), len(docs))
    return store


def _load_or_build() -> InMemoryVectorStore:
    path = get_settings().vector_index_file
    if path.exists():
        return InMemoryVectorStore.load(str(path), get_embeddings())
    return build_index()


async def get_store(rebuild: bool = False) -> InMemoryVectorStore:
    global _store
    async with _lock:
        if rebuild:
            _store = await asyncio.to_thread(build_index)
        elif _store is None:
            _store = await asyncio.to_thread(_load_or_build)
        return _store


async def search(query: str, k: int = 4) -> list[Document]:
    store = await get_store()
    return await store.asimilarity_search(query, k=k)


def format_docs(docs: list[Document]) -> str:
    return "\n\n".join(f"[{d.metadata.get('source', 'unknown')}]\n{d.page_content}" for d in docs)


async def answer(question: str) -> dict:
    docs = await search(question)
    reply = await get_chat_model().ainvoke(ANSWER_PROMPT.format(context=format_docs(docs), question=question))
    return {"answer": message_text(reply), "sources": sorted({d.metadata.get("source", "unknown") for d in docs})}
