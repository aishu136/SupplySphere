"""LangSmith evaluation of policy Q&A (RAG over the SOPs and supplier contracts).

    python -m evals.policy_qa            # needs LANGSMITH_API_KEY and AWS Bedrock access

Creates (once) the dataset "supplysphere-policy-qa", runs rag.answer on every question, scores each
answer, and uploads the experiment to LangSmith so prompt, model or retrieval changes can be
compared side by side. The questions and expected facts come straight from knowledge_base/.
"""
import asyncio
import re

from langsmith import Client, aevaluate

from app import observability, rag

DATASET = "supplysphere-policy-qa"

EXAMPLES = [
    {"inputs": {"question": "What is Acme's standard lead time, and what does expedited shipping cost?"},
     "outputs": {"facts": ["7", "3", "12%"], "source": "supplier_contract_acme.md"}},
    {"inputs": {"question": "What penalty applies if an Acme delivery is late?"},
     "outputs": {"facts": ["2%", "10%"], "source": "supplier_contract_acme.md"}},
    {"inputs": {"question": "How soon must the carrier be notified about damaged goods?"},
     "outputs": {"facts": ["24 hours"], "source": "damaged_goods_policy.md"}},
    {"inputs": {"question": "How many days of safety stock do Electronics items carry?"},
     "outputs": {"facts": ["7 days"], "source": "reorder_policy.md"}},
    {"inputs": {"question": "When can a humidity-exposed Initech lot be rejected?"},
     "outputs": {"facts": ["10%"], "source": "supplier_contract_initech.md"}},
    {"inputs": {"question": "Within how many hours must a delayed shipment get a revised ETA?"},
     "outputs": {"facts": ["4 hours"], "source": "shipment_delay_sop.md"}},
]


# ---------------------------------------------------------------- evaluators (pure, unit-tested)

def mentions_expected_facts(outputs: dict, reference_outputs: dict) -> dict:
    """Share of the expected facts (numbers, durations) that appear in the answer."""
    answer = outputs.get("answer", "").lower()
    facts = reference_outputs["facts"]
    found = sum(1 for f in facts if f.lower() in answer)
    return {"key": "facts_covered", "score": found / len(facts)}


def cites_correct_source(outputs: dict, reference_outputs: dict) -> dict:
    """The answer cites the document the fact lives in, as [file.md]."""
    cited = set(re.findall(r"\[([\w.-]+\.md)\]", outputs.get("answer", "")))
    return {"key": "cites_source", "score": 1.0 if reference_outputs["source"] in cited else 0.0}


def retrieved_correct_source(outputs: dict, reference_outputs: dict) -> dict:
    """Retrieval quality on its own: the right document was among the retrieved chunks."""
    return {"key": "retrieval_hit", "score": 1.0 if reference_outputs["source"] in outputs.get("sources", []) else 0.0}


EVALUATORS = [mentions_expected_facts, cites_correct_source, retrieved_correct_source]


# ---------------------------------------------------------------- runner

def ensure_dataset(client: Client) -> None:
    if not client.has_dataset(dataset_name=DATASET):
        dataset = client.create_dataset(DATASET, description="SupplySphere policy Q&A with expected facts")
        client.create_examples(dataset_id=dataset.id, examples=EXAMPLES)


async def target(inputs: dict) -> dict:
    return await rag.answer(inputs["question"])


async def main() -> None:
    observability.configure()
    if not observability.enabled():
        raise SystemExit("Set LANGSMITH_TRACING=true and LANGSMITH_API_KEY to run the evaluation.")
    client = Client()
    ensure_dataset(client)
    results = await aevaluate(target, data=DATASET, evaluators=EVALUATORS, experiment_prefix="policy-qa",
                              metadata={"retriever": "titan-v2 + in-memory", "k": 4}, max_concurrency=2,
                              client=client)
    print(f"Experiment uploaded: {results.experiment_name}")


if __name__ == "__main__":
    asyncio.run(main())
