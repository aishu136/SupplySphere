"""Thin async client for the supply chain microservices, through the API gateway."""
from typing import Any

import httpx

from app.config import get_settings


class ScmApiError(RuntimeError):
    pass


async def _request(method: str, path: str, json: dict | None = None) -> Any:
    async with httpx.AsyncClient(base_url=get_settings().scm_api_url, timeout=15) as client:
        resp = await client.request(method, path, json=json)
    if resp.status_code >= 400:
        try:
            detail = resp.json().get("detail", resp.text)
        except ValueError:
            detail = resp.text
        raise ScmApiError(f"{method} {path} failed ({resp.status_code}): {detail}")
    return resp.json() if resp.content else None


async def dashboard() -> dict:
    return await _request("GET", "/api/dashboard")


async def inventory(sku: str | None = None) -> list[dict]:
    return await _request("GET", f"/api/inventory/{sku}" if sku else "/api/inventory")


async def low_stock() -> list[dict]:
    return await _request("GET", "/api/inventory/low-stock")


async def shipments(delayed_only: bool = False) -> list[dict]:
    return await _request("GET", "/api/shipments/delayed" if delayed_only else "/api/shipments")


async def shipment(tracking_number: str) -> dict:
    return await _request("GET", f"/api/shipments/{tracking_number}")


async def update_shipment_status(tracking_number: str, status: str) -> dict:
    return await _request("PATCH", f"/api/shipments/{tracking_number}/status", {"status": status})


async def products() -> list[dict]:
    return await _request("GET", "/api/products")


async def product(sku: str) -> dict:
    return await _request("GET", f"/api/products/{sku}")


async def set_temperature_range(sku: str, profile: str, min_c: float | None, max_c: float | None) -> dict:
    return await _request("PUT", f"/api/products/{sku}/temperature-range",
                          {"profile": profile, "minC": min_c, "maxC": max_c})


async def dock_door_monitors() -> list[dict]:
    """Live dock door monitors, which run inside the AI service (not behind the gateway)."""
    async with httpx.AsyncClient(base_url=get_settings().scm_ai_url, timeout=10) as client:
        resp = await client.get("/ai/vision/dock-doors/monitors")
    if resp.status_code >= 400:
        raise ScmApiError(f"GET /ai/vision/dock-doors/monitors failed ({resp.status_code})")
    return resp.json()


async def purchase_orders() -> list[dict]:
    return await _request("GET", "/api/orders")


async def create_purchase_order(sku: str, warehouse_code: str, quantity: int) -> dict:
    return await _request("POST", "/api/orders", {"sku": sku, "warehouseCode": warehouse_code, "quantity": quantity})


async def suppliers() -> list[dict]:
    return await _request("GET", "/api/suppliers")


async def alerts() -> list[dict]:
    return await _request("GET", "/api/alerts")


async def raise_alert(type_: str, severity: str, message: str, entity_id: str | None) -> dict:
    return await _request(
        "POST", "/api/alerts", {"type": type_, "severity": severity, "message": message, "entityId": entity_id}
    )
