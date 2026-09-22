import asyncio

from app import events
from app.mcp_server import mcp


def test_mcp_exposes_supply_chain_tools():
    names = {t.name for t in asyncio.run(mcp.list_tools())}
    assert {
        "get_dashboard_summary", "get_inventory", "list_low_stock", "list_shipments", "get_shipment",
        "update_shipment_status", "list_purchase_orders", "create_purchase_order", "list_suppliers",
        "recent_alerts", "search_policies", "get_replenishment_recommendations",
    } <= names


def test_event_envelope_matches_java_contract():
    e = events.envelope("INSPECTION_COMPLETED", "TRK-1", {"damaged": True})
    assert set(e) == {"eventId", "type", "source", "entityId", "timestamp", "data"}
    assert e["timestamp"].endswith("Z")
