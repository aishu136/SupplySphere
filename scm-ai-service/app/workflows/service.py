"""Runs exception-resolution workflows: start, inspect, list, and resume after human approval."""
import logging
from datetime import datetime, timedelta, timezone
from typing import Any

from langgraph.types import Command

from app.config import get_settings
from app.workflows.exception_graph import SUPPORTED, build_graph

log = logging.getLogger(__name__)

THREAD_PREFIX = "exception-"
_graph = None
_checkpointer = None


def init(checkpointer: Any) -> None:
    """Called at startup with the durable checkpointer (SQLite), or an in-memory one in tests."""
    global _graph, _checkpointer
    _checkpointer = checkpointer
    _graph = build_graph(checkpointer)


def graph():
    if _graph is None:
        raise RuntimeError("Workflows not initialised")
    return _graph


def _config(thread_id: str) -> dict:
    return {"configurable": {"thread_id": thread_id}}


async def view(thread_id: str) -> dict | None:
    snapshot = await graph().aget_state(_config(thread_id))
    if not snapshot.values:
        return None
    values = snapshot.values
    pending = [i.value for t in snapshot.tasks for i in t.interrupts]
    return {
        "threadId": thread_id,
        "status": "awaiting_approval" if pending else values.get("status", "running"),
        "alert": values.get("alert"),
        "kind": values.get("kind"),
        "context": values.get("context"),
        "plan": values.get("plan"),
        "planner": values.get("planner"),
        "decision": values.get("decision"),
        "results": values.get("results", []),
        "outcome": values.get("outcome"),
        "updatedAt": snapshot.created_at,
    }


async def start(alert: dict) -> dict:
    """Idempotent per alert: the alert id is the thread id, so a redelivered alert reuses its run."""
    thread_id = THREAD_PREFIX + alert["alertId"]
    existing = await view(thread_id)
    if existing:
        return existing
    await graph().ainvoke({"alert": alert}, _config(thread_id))
    return await view(thread_id)


async def decide(thread_id: str, approved: bool, actions: list[dict] | None = None, comment: str = "") -> dict:
    current = await view(thread_id)
    if current is None:
        raise KeyError(thread_id)
    if current["status"] != "awaiting_approval":
        raise ValueError(f"Workflow {thread_id} is {current['status']}, not awaiting approval")
    await graph().ainvoke(Command(resume={"approved": approved, "actions": actions, "comment": comment}),
                          _config(thread_id))
    return await view(thread_id)


async def list_runs(limit: int = 50) -> list[dict]:
    thread_ids: list[str] = []
    async for checkpoint in _checkpointer.alist(None):
        tid = checkpoint.config["configurable"]["thread_id"]
        if tid.startswith(THREAD_PREFIX) and tid not in thread_ids:
            thread_ids.append(tid)
    runs = [v for tid in thread_ids if (v := await view(tid))]
    runs.sort(key=lambda r: (r["status"] != "awaiting_approval", -_ts(r["updatedAt"])))
    return runs[:limit]


def _ts(created_at: str | None) -> float:
    try:
        return datetime.fromisoformat(created_at).timestamp() if created_at else 0.0
    except ValueError:
        return 0.0


def mermaid() -> str:
    return graph().get_graph().draw_mermaid()


def is_stale(alert: dict, now: datetime | None = None) -> bool:
    raw = alert.get("timestamp")
    if not raw:
        return False
    try:
        ts = datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
    except ValueError:
        return False
    if ts.tzinfo is None:
        ts = ts.replace(tzinfo=timezone.utc)
    age = (now or datetime.now(timezone.utc)) - ts
    return age > timedelta(minutes=get_settings().workflow_max_alert_age_minutes)


async def on_alert(alert: dict) -> None:
    """Kafka trigger: start a workflow for every fresh, supported alert (parks at human approval)."""
    if alert.get("type") in SUPPORTED and alert.get("alertId"):
        if is_stale(alert):
            log.info("Ignoring stale alert %s from %s", alert["alertId"], alert.get("timestamp"))
            return
        try:
            run = await start(alert)
            log.info("Workflow %s for %s: %s", run["threadId"], alert["type"], run["status"])
        except Exception:
            log.exception("Workflow for alert %s failed", alert.get("alertId"))
