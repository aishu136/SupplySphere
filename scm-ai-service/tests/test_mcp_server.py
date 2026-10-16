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


def test_mcp_exposes_cold_chain_tools():
    names = {t.name for t in asyncio.run(mcp.list_tools())}
    assert {"list_cold_chain_products", "set_product_temperature_range", "list_dock_door_monitors",
            "get_cold_chain_status"} <= names


def test_cold_chain_status_joins_shipments_to_product_ranges(monkeypatch):
    from app import mcp_server

    frozen = {"profile": "FROZEN", "minC": -30, "maxC": -18}

    async def products():
        return [{"sku": "SKU-4001", "name": "Frozen salmon", "temperatureRange": frozen},
                {"sku": "SKU-1001", "name": "Hex bolts", "temperatureRange": None}]

    async def purchase_orders():
        return [{"orderNumber": "PO-4", "product": {"sku": "SKU-4001"}},
                {"orderNumber": "PO-3", "product": {"sku": "SKU-1001"}}]

    async def shipments(delayed_only=False):
        return [{"trackingNumber": "TRK-4", "status": "IN_TRANSIT", "purchaseOrder": {"orderNumber": "PO-4"}},
                {"trackingNumber": "TRK-3", "status": "IN_TRANSIT", "purchaseOrder": {"orderNumber": "PO-3"}},
                {"trackingNumber": "TRK-X", "status": "CREATED", "purchaseOrder": None}]

    async def alerts():
        return [{"type": "DOCK_DOOR_OPEN", "entityId": "WH-EAST/D3"}, {"type": "LOW_STOCK", "entityId": "SKU-1"}]

    async def monitors_down():
        raise RuntimeError("AI service unreachable")

    for name, fn in (("products", products), ("purchase_orders", purchase_orders), ("shipments", shipments),
                     ("alerts", alerts), ("dock_door_monitors", monitors_down)):
        monkeypatch.setattr(mcp_server.scm_client, name, fn)

    status = asyncio.run(mcp_server.cold_chain_status())
    [shipment] = status["coldChainShipments"]
    assert (shipment["trackingNumber"], shipment["sku"], shipment["temperatureRange"]) == ("TRK-4", "SKU-4001", frozen)
    assert [a["type"] for a in status["recentAlerts"]] == ["DOCK_DOOR_OPEN"]
    assert "unavailable" in status["dockDoorMonitors"]
