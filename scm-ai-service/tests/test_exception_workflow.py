import asyncio

import pytest
from langgraph.checkpoint.memory import InMemorySaver

from app.workflows import exception_graph as g
from app.workflows import service as workflows

SUPPLIER = {"name": "Initech Electronics", "leadTimeDays": 21}
INVENTORY = {
    "SKU-3002": [{"warehouseCode": "WH-EAST", "quantity": 30, "reorderPoint": 40, "reorderQuantity": 100,
                  "lowStock": True, "product": {"sku": "SKU-3002", "supplier": SUPPLIER}}],
    "SKU-3001": [{"warehouseCode": "WH-EAST", "quantity": 38, "reorderPoint": 50, "reorderQuantity": 300,
                  "lowStock": True, "product": {"sku": "SKU-3001", "supplier": SUPPLIER}}],
}
ORDERS = [{"orderNumber": "PO-DEMO0002", "warehouseCode": "WH-EAST", "quantity": 300, "status": "SHIPPED",
           "expectedDelivery": "2026-09-22", "product": {"sku": "SKU-3001"}, "supplier": {"name": "Initech Electronics"}}]
SHIPMENT = {"trackingNumber": "TRK-DEMO000002", "carrier": "Maersk", "origin": "Kaohsiung, TW",
            "destination": "Newark, NJ", "status": "IN_TRANSIT", "eta": "2026-09-22T08:00:00Z",
            "purchaseOrder": {"orderNumber": "PO-DEMO0002"}}


@pytest.fixture
def calls(monkeypatch):
    """Stubs the microservices and RL/RAG; records every write the workflow makes."""
    made = []

    async def inventory(sku=None):
        return INVENTORY[sku]

    async def purchase_orders():
        return ORDERS

    async def shipment(tn):
        return SHIPMENT

    async def create_purchase_order(sku, wh, qty):
        made.append(("po", sku, wh, qty))
        return {"orderNumber": "PO-NEW00001"}

    async def update_shipment_status(tn, status):
        made.append(("status", tn, status))
        return {}

    async def recommendations():
        return [{"sku": "SKU-3002", "warehouseCode": "WH-EAST", "onHand": 30, "onOrder": 0, "recommendedQuantity": 64,
                 "policyUsed": "rl", "learnedPolicy": {"reorder_point": 42, "order_quantity": 64}, "costSavingPct": 11.3}]

    async def no_rag(*_, **__):
        raise RuntimeError("no AWS credentials")

    async def no_claude(_state):
        raise RuntimeError("Bedrock unreachable")

    for name, fn in [("inventory", inventory), ("purchase_orders", purchase_orders), ("shipment", shipment),
                     ("create_purchase_order", create_purchase_order),
                     ("update_shipment_status", update_shipment_status)]:
        monkeypatch.setattr(g.scm_client, name, fn)
    monkeypatch.setattr(g.rl_service, "recommendations", recommendations)
    monkeypatch.setattr(g.rag, "search", no_rag)
    monkeypatch.setattr(g, "plan_with_claude", no_claude)
    workflows.init(InMemorySaver())
    return made


def alert(type_, entity, alert_id):
    return {"alertId": alert_id, "type": type_, "severity": "HIGH", "message": "test", "entityId": entity,
            "source": "scm-stream-processor"}


def run(coro):
    return asyncio.run(coro)


async def _start_and(alert_, *decision):
    run_ = await workflows.start(alert_)
    if decision:
        run_ = await workflows.decide(run_["threadId"], *decision)
    return run_


def test_low_stock_pauses_for_approval_with_rl_quantity(calls):
    r = run(_start_and(alert("LOW_STOCK", "SKU-3002", "a1")))
    assert r["status"] == "awaiting_approval"
    assert r["planner"] == "rules"  # Claude unavailable -> rule-based fallback
    [action] = r["plan"]["actions"]
    assert (action["type"], action["sku"], action["warehouse_code"], action["quantity"]) == \
           ("create_purchase_order", "SKU-3002", "WH-EAST", 64)  # the RL agent's quantity
    assert calls == []  # nothing executed before approval


def test_approval_with_edited_quantity_executes_the_edit(calls):
    async def flow():
        r = await workflows.start(alert("LOW_STOCK", "SKU-3002", "a2"))
        edited = [{**r["plan"]["actions"][0], "quantity": 80}]
        return await workflows.decide(r["threadId"], True, edited, "rounding up to a full pallet")

    r = run(flow())
    assert r["status"] == "completed"
    assert calls == [("po", "SKU-3002", "WH-EAST", 80)]
    assert "PO-NEW00001" in r["outcome"]


def test_rejection_executes_nothing(calls):
    r = run(_start_and(alert("LOW_STOCK", "SKU-3002", "a3"), False, None, "stock transfer instead"))
    assert r["status"] == "rejected"
    assert "stock transfer instead" in r["outcome"]
    assert calls == []


def test_delayed_shipment_branch_marks_delayed_and_expedites_low_destination(calls):
    r = run(_start_and(alert("SHIPMENT_DELAYED", "TRK-DEMO000002", "a4"), True))
    assert r["kind"] == "shipment_delay"
    assert calls == [("status", "TRK-DEMO000002", "DELAYED"), ("po", "SKU-3001", "WH-EAST", 300)]
    assert r["status"] == "completed"


def test_unsupported_alert_ends_without_action(calls):
    r = run(_start_and(alert("DEMAND_SPIKE", "SKU-1001", "a5")))
    assert r["status"] == "no_action"
    assert calls == []


def test_same_alert_twice_is_one_workflow(calls):
    async def flow():
        first = await workflows.start(alert("LOW_STOCK", "SKU-3002", "a6"))
        second = await workflows.start(alert("LOW_STOCK", "SKU-3002", "a6"))
        return first, second, await workflows.list_runs()

    first, second, runs = run(flow())
    assert first["threadId"] == second["threadId"] == "exception-a6"
    assert [r["threadId"] for r in runs].count("exception-a6") == 1


def test_claude_plan_is_validated_before_approval(calls, monkeypatch):
    async def claude(_state):
        return g.Plan(summary="SKU-3002 low", rationale="RL says 64", actions=[
            g.Action(type="create_purchase_order", sku="SKU-3002", warehouse_code="WH-EAST", quantity=64, reason="RL"),
            g.Action(type="create_purchase_order", sku="SKU-3002", warehouse_code="WH-EAST", quantity=0, reason="bad"),
        ])

    monkeypatch.setattr(g, "plan_with_claude", claude)
    r = run(_start_and(alert("LOW_STOCK", "SKU-3002", "a7")))
    assert r["planner"] == "claude"
    assert [a["quantity"] for a in r["plan"]["actions"]] == [64]  # zero-quantity action dropped


def test_kafka_trigger_ignores_stale_alerts(calls):
    from datetime import datetime, timedelta, timezone

    now = datetime(2026, 9, 23, 17, 0, tzinfo=timezone.utc)
    fresh = {**alert("LOW_STOCK", "SKU-3002", "f1"), "timestamp": "2026-09-23T16:55:00.123456789Z"}  # Java Instant
    old = {**alert("LOW_STOCK", "SKU-3002", "o1"), "timestamp": "2026-09-20T09:00:00Z"}
    assert not workflows.is_stale(fresh, now)
    assert workflows.is_stale(old, now)
    assert not workflows.is_stale(alert("LOW_STOCK", "SKU-3002", "n1"), now)  # no timestamp: treat as fresh

    stale_now = {**old, "timestamp": (datetime.now(timezone.utc) - timedelta(days=2)).isoformat()}
    run(workflows.on_alert(stale_now))
    assert run(workflows.list_runs()) == []


def test_graph_structure_renders_as_mermaid(calls):
    diagram = workflows.mermaid()
    for node in ("classify", "low_stock_context", "shipment_context", "draft_plan", "human_approval", "execute"):
        assert node in diagram
