import asyncio
from pathlib import Path

import pytest

from app import observability, vision
from app.workflows import service as workflows
from evals import policy_qa
from tests.test_exception_workflow import alert, calls  # noqa: F401  (fixture: stubbed services)


@pytest.fixture(autouse=True)
def fresh_langsmith_env():
    """Tests change LANGSMITH_* variables; the SDK caches them, so reset before and after each test."""
    observability.refresh()
    yield
    observability.refresh()


def test_tracing_is_off_without_configuration(monkeypatch):
    monkeypatch.delenv("LANGSMITH_TRACING", raising=False)
    monkeypatch.delenv("LANGSMITH_API_KEY", raising=False)
    observability.refresh()
    assert not observability.enabled()
    assert observability.feedback("some-run", "user_rating", 1.0) is False  # no-op, no network call


@pytest.fixture
def feedback_log(monkeypatch):
    recorded = []
    monkeypatch.setattr(observability, "feedback", lambda run_id, key, score, comment=None:
                        recorded.append((run_id, key, score)) or True)
    monkeypatch.setattr(observability, "current_run_id", lambda: "plan-run-1")
    return recorded


def test_approval_is_recorded_on_the_plan_trace(calls, feedback_log):  # noqa: F811
    async def flow():
        run = await workflows.start(alert("LOW_STOCK", "SKU-3002", "ls-1"))
        assert run["planRunId"] == "plan-run-1"
        await workflows.decide(run["threadId"], True, None, "")

    asyncio.run(flow())
    assert feedback_log == [("plan-run-1", "plan_approved", 1.0), ("plan-run-1", "plan_edited", 0.0)]


def test_edited_approval_and_rejection_feedback(calls, feedback_log):  # noqa: F811
    async def flow():
        run = await workflows.start(alert("LOW_STOCK", "SKU-3002", "ls-2"))
        await workflows.decide(run["threadId"], True, [{**run["plan"]["actions"][0], "quantity": 80}], "")
        run = await workflows.start(alert("LOW_STOCK", "SKU-3002", "ls-3"))
        await workflows.decide(run["threadId"], False, None, "transfer instead")

    asyncio.run(flow())
    assert feedback_log == [("plan-run-1", "plan_approved", 1.0), ("plan-run-1", "plan_edited", 1.0),
                            ("plan-run-1", "plan_approved", 0.0)]


def test_with_tracing_on_the_plan_node_runs_inside_a_langsmith_trace(calls, monkeypatch):  # noqa: F811
    import uuid

    # Tracing on, pointed at a closed local port: traces are built but go nowhere.
    monkeypatch.setenv("LANGSMITH_TRACING", "true")
    monkeypatch.setenv("LANGSMITH_API_KEY", "test-key")
    monkeypatch.setenv("LANGSMITH_ENDPOINT", "http://127.0.0.1:9")
    observability.refresh()
    recorded = []
    monkeypatch.setattr(observability, "feedback", lambda *a, **k: recorded.append(a) or True)

    async def flow():
        run = await workflows.start(alert("LOW_STOCK", "SKU-3002", "traced-1"))
        await workflows.decide(run["threadId"], True, None, "")
        return run

    run = asyncio.run(flow())
    uuid.UUID(run["planRunId"])  # a real LangSmith run ID from inside the draft_plan node
    assert recorded[0][:2] == (run["planRunId"], "plan_approved")


def test_vision_traces_never_contain_image_bytes():
    shown = vision.trace_inputs({"data": b"\xff\xd8" * 5000, "mime_type": "image/jpeg", "use_llm": True})
    assert shown == {"image_bytes": 10000, "mime_type": "image/jpeg", "use_llm": True}
    result = vision.InspectionResult(damaged=False, summary="ok", item_counts={}, detections=[], codes=[],
                                     annotated_image="A" * 100_000)
    assert "annotated_image" not in vision.trace_outputs(result)


def test_policy_qa_evaluators():
    ref = {"facts": ["7", "3", "12%"], "source": "supplier_contract_acme.md"}
    good = {"answer": "Standard lead time is 7 days; expedited is 3 days at a 12% surcharge "
                      "[supplier_contract_acme.md].", "sources": ["supplier_contract_acme.md"]}
    bad = {"answer": "About two weeks [reorder_policy.md].", "sources": ["reorder_policy.md"]}

    assert policy_qa.mentions_expected_facts(good, ref)["score"] == 1.0
    assert policy_qa.cites_correct_source(good, ref)["score"] == 1.0
    assert policy_qa.retrieved_correct_source(good, ref)["score"] == 1.0
    assert policy_qa.mentions_expected_facts(bad, ref)["score"] == 0.0
    assert policy_qa.cites_correct_source(bad, ref)["score"] == 0.0
    assert policy_qa.retrieved_correct_source(bad, ref)["score"] == 0.0


def test_eval_dataset_is_grounded_in_the_knowledge_base():
    kb = Path(__file__).resolve().parent.parent / "knowledge_base"
    for example in policy_qa.EXAMPLES:
        text = (kb / example["outputs"]["source"]).read_text(encoding="utf-8")
        for fact in example["outputs"]["facts"]:
            assert fact in text, f"{fact!r} not in {example['outputs']['source']}"
