"""MCP server exposing supply chain operations as tools.

Any MCP client (the LangChain agent in this service, Claude Desktop, Claude Code, an AgentCore
Gateway, ...) can use these tools. They call the Spring Boot REST API, so business rules stay there.

Run:  python -m app.mcp_server                       (streamable HTTP on :8001/mcp)
      python -m app.mcp_server --transport stdio     (for desktop MCP clients)
"""
import argparse
from typing import Literal

from mcp.server.fastmcp import FastMCP

from app import observability, rag, scm_client
from app.config import get_settings
from app.rl import service as rl_service

observability.configure()  # policy retrieval and RL calls made through MCP are traced too
settings = get_settings()
mcp = FastMCP("scm-supply-chain", host=settings.mcp_host, port=settings.mcp_port)

ShipmentStatus = Literal["CREATED", "IN_TRANSIT", "DELAYED", "DELIVERED", "DAMAGED"]
StorageProfile = Literal["FROZEN", "CHILLED", "COOL"]
COLD_CHAIN_ALERTS = {"COLD_CHAIN_BREACH", "DOCK_DOOR_OPEN"}


@mcp.tool()
async def get_dashboard_summary() -> dict:
    """Headline KPIs: SKU count, units on hand, low-stock items, open orders, shipments in transit/delayed."""
    return await scm_client.dashboard()


@mcp.tool()
async def get_inventory(sku: str | None = None) -> list[dict]:
    """Stock levels per warehouse. Pass a SKU (e.g. SKU-1001) to filter, or omit for all inventory."""
    return await scm_client.inventory(sku)


@mcp.tool()
async def list_low_stock() -> list[dict]:
    """Inventory positions at or below their reorder point, most critical first."""
    return await scm_client.low_stock()


@mcp.tool()
async def list_shipments(delayed_only: bool = False) -> list[dict]:
    """All shipments ordered by ETA, or only those delayed / past ETA when delayed_only is true."""
    return await scm_client.shipments(delayed_only)


@mcp.tool()
async def get_shipment(tracking_number: str) -> dict:
    """Details of one shipment, including its purchase order and any vision inspection notes."""
    return await scm_client.shipment(tracking_number)


@mcp.tool()
async def update_shipment_status(tracking_number: str, status: ShipmentStatus) -> dict:
    """Change a shipment's status. DELIVERED automatically receives the linked purchase order into stock."""
    return await scm_client.update_shipment_status(tracking_number, status)


@mcp.tool()
async def list_purchase_orders() -> list[dict]:
    """Purchase orders, newest first."""
    return await scm_client.purchase_orders()


@mcp.tool()
async def create_purchase_order(sku: str, warehouse_code: str, quantity: int) -> dict:
    """Raise a purchase order with the SKU's supplier for delivery to a warehouse (e.g. WH-EAST)."""
    return await scm_client.create_purchase_order(sku, warehouse_code, quantity)


@mcp.tool()
async def list_suppliers() -> list[dict]:
    """Suppliers with country, rating (0-5) and standard lead time in days."""
    return await scm_client.suppliers()


@mcp.tool()
async def recent_alerts() -> list[dict]:
    """Recent operational alerts (low stock, delays, demand spikes, damaged shipments, cold-chain breaches,
    reefer dock doors left open), newest first."""
    return await scm_client.alerts()


@mcp.tool()
async def get_replenishment_recommendations() -> list[dict]:
    """Order quantities from the reinforcement-learning replenishment agents (one per SKU x warehouse).
    Each agent learned its own reorder point and order quantity ('learnedPolicy') in simulation and was
    benchmarked against the SOP rule on held-out demand. 'policyUsed' says whether the RL policy or the
    SOP rule backs the recommendation; costs are simulated 120-day totals."""
    return await rl_service.recommendations()


@mcp.tool()
async def search_policies(query: str) -> str:
    """Search supplier contracts and SOPs (reorder policy, returns, damaged goods, cold chain receiving and
    reefer dock doors, SLAs, penalties).
    Returns matching excerpts labelled with their source document."""
    return rag.format_docs(await rag.search(query))


@mcp.tool()
async def list_cold_chain_products() -> list[dict]:
    """Products that must be kept cold, with their storage range: profile (FROZEN, CHILLED or COOL) and
    minC / maxC in Celsius. Products not listed are ambient."""
    return [p for p in await scm_client.products() if p.get("temperatureRange")]


@mcp.tool()
async def set_product_temperature_range(sku: str, profile: StorageProfile, min_c: float | None = None,
                                        max_c: float | None = None) -> dict:
    """Set a product's cold-chain storage range. Limits left out use the profile's defaults
    (FROZEN -30 to -18, CHILLED 0 to 4, COOL 8 to 15 °C). Inspections of its shipments use this range."""
    return await scm_client.set_temperature_range(sku, profile, min_c, max_c)


@mcp.tool()
async def list_dock_door_monitors() -> list[dict]:
    """Live reefer dock door camera monitors: whether each door is open now, for how long, its limit in
    seconds, openings seen and DOCK_DOOR_OPEN alerts raised since the monitor started."""
    return await scm_client.dock_door_monitors()


@mcp.tool()
async def get_cold_chain_status() -> dict:
    """Cold-chain overview: shipments of temperature-controlled products with their storage range,
    status and last inspection notes; recent COLD_CHAIN_BREACH and DOCK_DOOR_OPEN alerts; and the live
    dock door monitors. Use for questions like 'which reefer loads are at risk' or 'which doors were
    open too long'."""
    return await cold_chain_status()


async def cold_chain_status() -> dict:
    ranges = {p["sku"]: p for p in await scm_client.products() if p.get("temperatureRange")}
    order_skus = {o["orderNumber"]: o["product"]["sku"] for o in await scm_client.purchase_orders()}
    shipments = []
    for s in await scm_client.shipments():
        sku = order_skus.get((s.get("purchaseOrder") or {}).get("orderNumber"))
        if sku in ranges:
            shipments.append({
                **{k: s.get(k) for k in ("trackingNumber", "status", "carrier", "origin", "destination", "eta",
                                         "inspectionNotes")},
                "sku": sku, "productName": ranges[sku]["name"],
                "temperatureRange": ranges[sku]["temperatureRange"],
            })
    try:
        monitors = await scm_client.dock_door_monitors()
    except Exception as e:  # the AI service may be down; the rest of the overview still helps
        monitors = {"unavailable": str(e)}
    return {
        "coldChainShipments": shipments,
        "recentAlerts": [a for a in await scm_client.alerts() if a.get("type") in COLD_CHAIN_ALERTS],
        "dockDoorMonitors": monitors,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--transport", choices=["streamable-http", "stdio", "sse"], default="streamable-http")
    mcp.run(transport=parser.parse_args().transport)
