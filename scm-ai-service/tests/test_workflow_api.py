from fastapi.testclient import TestClient

from app import main
from tests.test_exception_workflow import alert, calls  # noqa: F401  (fixture: stubbed services)


def test_pending_approval_survives_restart_and_resumes_over_http(calls, monkeypatch, tmp_path):  # noqa: F811
    monkeypatch.setattr(main.get_settings(), "workflow_db_file", tmp_path / "workflows.sqlite")
    monkeypatch.setattr(main.get_settings(), "kafka_enabled", False)

    with TestClient(main.app) as client:
        started = client.post("/ai/workflows/exceptions", json={"alert": alert("LOW_STOCK", "SKU-3002", "api-1")}).json()
        assert started["status"] == "awaiting_approval"
        assert "human_approval" in client.get("/ai/workflows/exceptions/graph").text

    # New app lifespan = service restart; the SQLite checkpoint still holds the paused workflow.
    with TestClient(main.app) as client:
        [pending] = [r for r in client.get("/ai/workflows/exceptions").json() if r["threadId"] == "exception-api-1"]
        assert pending["status"] == "awaiting_approval"

        done = client.post(f"/ai/workflows/exceptions/{pending['threadId']}/decision", json={"approved": True}).json()
        assert done["status"] == "completed"
        assert calls == [("po", "SKU-3002", "WH-EAST", 64)]

        again = client.post(f"/ai/workflows/exceptions/{pending['threadId']}/decision", json={"approved": True})
        assert again.status_code == 409  # already decided
