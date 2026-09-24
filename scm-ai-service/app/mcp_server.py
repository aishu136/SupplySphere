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
    """Recent operational alerts (low stock, delays, demand spikes, damaged shipments), newest first."""
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
    """Search supplier contracts and SOPs (reorder policy, returns, damaged goods, SLAs, penalties).
    Returns matching excerpts labelled with their source document."""
    return rag.format_docs(await rag.search(query))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--transport", choices=["streamable-http", "stdio", "sse"], default="streamable-http")
    mcp.run(transport=parser.parse_args().transport)
