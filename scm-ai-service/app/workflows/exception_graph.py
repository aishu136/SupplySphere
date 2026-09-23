"""Exception-resolution workflow as a LangGraph state machine.

    START -> classify --(LOW_STOCK)--------> low_stock_context --> retrieve_policies -> draft_plan
                      --(SHIPMENT_DELAYED)-> shipment_context ---^
                      --(other)------------> finish
    draft_plan --(actions)--> human_approval --(approved)--> execute -> finish -> END
               --(none)-----> finish        --(rejected)--> finish

- Context comes live from the microservices (through the API gateway) and the RL replenishment agents.
- Claude drafts the plan as structured output, grounded in that context and the SOP excerpts. If
  Claude is unreachable, a rule-based plan built from the RL recommendation is used instead.
- The graph pauses at human_approval (LangGraph interrupt) until a person approves, edits or
  rejects the plan. State is checkpointed, so a pending approval survives restarts.
- Only allow-listed actions with validated parameters are ever executed.
"""
import json
import logging
from typing import Any, Literal, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.types import interrupt
from pydantic import BaseModel, Field

from app import rag, scm_client
from app.llm import get_chat_model
from app.rl import service as rl_service

log = logging.getLogger(__name__)

SUPPORTED = {"LOW_STOCK": "low_stock", "SHIPMENT_DELAYED": "shipment_delay"}
SHIPMENT_STATUSES = ("CREATED", "IN_TRANSIT", "DELAYED", "DELIVERED", "DAMAGED")
OPEN_PO_STATUSES = {"CREATED", "APPROVED", "SHIPPED"}


class Action(BaseModel):
    """One change to make in the supply chain systems."""

    type: Literal["create_purchase_order", "update_shipment_status"]
    sku: str | None = Field(None, description="For create_purchase_order")
    warehouse_code: str | None = Field(None, description="For create_purchase_order, e.g. WH-EAST")
    quantity: int | None = Field(None, description="For create_purchase_order: units to order")
    tracking_number: str | None = Field(None, description="For update_shipment_status")
    status: Literal["CREATED", "IN_TRANSIT", "DELAYED", "DELIVERED", "DAMAGED"] | None = Field(
        None, description="For update_shipment_status")
    reason: str = Field(description="Why this action, citing figures or policy")


class Plan(BaseModel):
    """Proposed resolution for a supply chain exception."""

    summary: str = Field(description="One-sentence description of the situation")
    rationale: str = Field(description="Reasoning behind the actions, citing context figures and SOPs")
    actions: list[Action] = Field(description="Actions to take; empty if nothing should change")


class ExceptionState(TypedDict, total=False):
    alert: dict
    kind: str
    context: dict
    policies: str
    plan: dict
    planner: str
    decision: dict
    results: list[dict]
    status: str
    outcome: str


# ---------------------------------------------------------------------------- nodes

def classify(state: ExceptionState) -> ExceptionState:
    return {"kind": SUPPORTED.get(state["alert"].get("type", ""), "unsupported")}


def route_by_kind(state: ExceptionState) -> str:
    return {"low_stock": "low_stock_context", "shipment_delay": "shipment_context"}.get(state["kind"], "finish")


async def low_stock_context(state: ExceptionState) -> ExceptionState:
    sku = state["alert"]["entityId"]
    positions = await scm_client.inventory(sku)
    orders = await scm_client.purchase_orders()
    try:
        recs = [r for r in await rl_service.recommendations() if r["sku"] == sku]
    except Exception as e:  # RL is advisory; the workflow continues without it
        log.warning("RL recommendations unavailable: %s", e)
        recs = []
    return {"context": {
        "sku": sku,
        "positions": [{"warehouseCode": p["warehouseCode"], "onHand": p["quantity"],
                       "reorderPoint": p["reorderPoint"], "reorderQuantity": p["reorderQuantity"],
                       "lowStock": p["lowStock"],
                       "supplier": (p["product"].get("supplier") or {}).get("name"),
                       "leadTimeDays": (p["product"].get("supplier") or {}).get("leadTimeDays")}
                      for p in positions],
        "openOrders": [{"orderNumber": o["orderNumber"], "warehouseCode": o["warehouseCode"],
                        "quantity": o["quantity"], "status": o["status"], "expectedDelivery": o["expectedDelivery"]}
                       for o in orders if o["product"]["sku"] == sku and o["status"] in OPEN_PO_STATUSES],
        "rlRecommendations": [{k: r[k] for k in ("warehouseCode", "onHand", "onOrder", "recommendedQuantity",
                                                 "policyUsed", "learnedPolicy", "costSavingPct")} for r in recs],
    }}


async def shipment_context(state: ExceptionState) -> ExceptionState:
    shipment = await scm_client.shipment(state["alert"]["entityId"])
    order_number = (shipment.get("purchaseOrder") or {}).get("orderNumber")
    order, stock = None, []
    if order_number:
        order = next((o for o in await scm_client.purchase_orders() if o["orderNumber"] == order_number), None)
    if order:
        stock = [{"warehouseCode": p["warehouseCode"], "onHand": p["quantity"], "reorderPoint": p["reorderPoint"],
                  "reorderQuantity": p["reorderQuantity"], "lowStock": p["lowStock"]}
                 for p in await scm_client.inventory(order["product"]["sku"])]
    return {"context": {
        "shipment": {k: shipment.get(k) for k in ("trackingNumber", "carrier", "origin", "destination", "status", "eta")},
        "order": None if not order else {"orderNumber": order["orderNumber"], "sku": order["product"]["sku"],
                                         "warehouseCode": order["warehouseCode"], "quantity": order["quantity"],
                                         "supplier": order["supplier"]["name"], "status": order["status"]},
        "destinationStock": stock,
    }}


async def retrieve_policies(state: ExceptionState) -> ExceptionState:
    query = {"low_stock": "reorder policy stock-out order quantity supplier lead time",
             "shipment_delay": "shipment delay escalation expedite late delivery penalties"}[state["kind"]]
    try:
        return {"policies": rag.format_docs(await rag.search(query, k=3))}
    except Exception as e:  # e.g. no AWS credentials for embeddings
        log.warning("Policy retrieval unavailable: %s", e)
        return {"policies": ""}


PLANNER_PROMPT = """You resolve supply chain exceptions for an operations team.
Propose the smallest set of actions that resolves the exception, grounded in the live context and the
company SOP excerpts below. Use only these action types:
- create_purchase_order (sku, warehouse_code, quantity)
- update_shipment_status (tracking_number, status)
Prefer the RL recommendation's quantity when one is given and its policyUsed is "rl". Do not order for a
position whose on-hand plus open orders already covers the reorder point. A person approves every action
before it runs.

<alert>{alert}</alert>
<context>{context}</context>
<sop_excerpts>{policies}</sop_excerpts>"""


async def plan_with_claude(state: ExceptionState) -> Plan:
    model = get_chat_model().with_structured_output(Plan)
    return await model.ainvoke(PLANNER_PROMPT.format(
        alert=json.dumps(state["alert"]), context=json.dumps(state["context"]),
        policies=state.get("policies") or "(unavailable)"))


def plan_with_rules(state: ExceptionState) -> Plan:
    """Fallback when Claude is unavailable: the RL recommendation (or SOP reorder rule) as the plan."""
    ctx = state["context"]
    actions: list[Action] = []
    if state["kind"] == "low_stock":
        recs = {r["warehouseCode"]: r for r in ctx.get("rlRecommendations", [])}
        for p in ctx["positions"]:
            if not p["lowStock"]:
                continue
            rec = recs.get(p["warehouseCode"])
            qty = rec["recommendedQuantity"] if rec else p["reorderQuantity"]
            if qty > 0:
                source = f"{rec['policyUsed'].upper()} policy" if rec else "SOP reorder quantity"
                actions.append(Action(type="create_purchase_order", sku=ctx["sku"], warehouse_code=p["warehouseCode"],
                                      quantity=qty, reason=f"{p['onHand']} on hand vs reorder point {p['reorderPoint']}; "
                                                           f"quantity from {source}"))
        summary = f"{ctx['sku']} is below its reorder point"
    else:
        shipment = ctx["shipment"]
        if shipment["status"] in ("CREATED", "IN_TRANSIT"):
            actions.append(Action(type="update_shipment_status", tracking_number=shipment["trackingNumber"],
                                  status="DELAYED", reason="Passed its ETA without delivery (SOP-LOG-007 step 1)"))
        order = ctx.get("order")
        for p in ctx.get("destinationStock", []):
            if order and p["warehouseCode"] == order["warehouseCode"] and p["lowStock"]:
                actions.append(Action(type="create_purchase_order", sku=order["sku"], warehouse_code=p["warehouseCode"],
                                      quantity=p["reorderQuantity"],
                                      reason="Destination is below its reorder point while the inbound order is late"))
        summary = f"Shipment {shipment['trackingNumber']} is late"
    return Plan(summary=summary, rationale="Rule-based plan (Claude unavailable).", actions=actions)


def validate(actions: list[Action]) -> list[dict]:
    """Allow-list and sanity-check every action before it can be approved or executed."""
    valid = []
    for a in actions:
        if a.type == "create_purchase_order" and a.sku and a.warehouse_code and a.quantity and 0 < a.quantity <= 100_000:
            valid.append(a.model_dump())
        elif a.type == "update_shipment_status" and a.tracking_number and a.status in SHIPMENT_STATUSES:
            valid.append(a.model_dump())
        else:
            log.warning("Dropping invalid action %s", a)
    return valid


async def draft_plan(state: ExceptionState) -> ExceptionState:
    try:
        plan, planner = await plan_with_claude(state), "claude"
    except Exception as e:
        log.warning("Claude planner unavailable (%s); using rule-based plan", e)
        plan, planner = plan_with_rules(state), "rules"
    return {"plan": {**plan.model_dump(), "actions": validate(plan.actions)}, "planner": planner}


def route_after_plan(state: ExceptionState) -> str:
    return "human_approval" if state["plan"]["actions"] else "finish"


def human_approval(state: ExceptionState) -> ExceptionState:
    """Pauses the graph. Resumed with {"approved": bool, "actions": [...optional edits], "comment": str}."""
    decision = interrupt({"alert": state["alert"], "plan": state["plan"], "planner": state["planner"]})
    approved = bool(decision.get("approved"))
    actions = state["plan"]["actions"]
    if approved and decision.get("actions") is not None:
        actions = validate([Action(**a) for a in decision["actions"]])
    return {"decision": {"approved": approved, "actions": actions, "comment": decision.get("comment", "")}}


def route_after_approval(state: ExceptionState) -> str:
    return "execute" if state["decision"]["approved"] else "finish"


async def execute(state: ExceptionState) -> ExceptionState:
    results = []
    for a in state["decision"]["actions"]:
        try:
            if a["type"] == "create_purchase_order":
                po = await scm_client.create_purchase_order(a["sku"], a["warehouse_code"], a["quantity"])
                results.append({"action": a, "ok": True, "detail": f"Created {po['orderNumber']}"})
            else:
                await scm_client.update_shipment_status(a["tracking_number"], a["status"])
                results.append({"action": a, "ok": True, "detail": f"{a['tracking_number']} set to {a['status']}"})
        except Exception as e:
            results.append({"action": a, "ok": False, "detail": str(e)})
    return {"results": results}


def finish(state: ExceptionState) -> ExceptionState:
    if state["kind"] == "unsupported":
        return {"status": "no_action", "outcome": f"No workflow for alert type {state['alert'].get('type')}"}
    if not state.get("plan", {}).get("actions"):
        return {"status": "no_action", "outcome": "Nothing to change: " + state.get("plan", {}).get("summary", "")}
    if not state.get("decision", {}).get("approved"):
        return {"status": "rejected", "outcome": "Plan rejected" + (
            f": {state['decision'].get('comment')}" if state.get("decision", {}).get("comment") else "")}
    results = state.get("results", [])
    ok = sum(r["ok"] for r in results)
    return {"status": "completed" if ok == len(results) else "partially_failed",
            "outcome": f"{ok}/{len(results)} actions succeeded: " + "; ".join(r["detail"] for r in results)}


# ---------------------------------------------------------------------------- graph

def build_graph(checkpointer: Any):
    g = StateGraph(ExceptionState)
    g.add_node("classify", classify)
    g.add_node("low_stock_context", low_stock_context)
    g.add_node("shipment_context", shipment_context)
    g.add_node("retrieve_policies", retrieve_policies)
    g.add_node("draft_plan", draft_plan)
    g.add_node("human_approval", human_approval)
    g.add_node("execute", execute)
    g.add_node("finish", finish)

    g.add_edge(START, "classify")
    g.add_conditional_edges("classify", route_by_kind, ["low_stock_context", "shipment_context", "finish"])
    g.add_edge("low_stock_context", "retrieve_policies")
    g.add_edge("shipment_context", "retrieve_policies")
    g.add_edge("retrieve_policies", "draft_plan")
    g.add_conditional_edges("draft_plan", route_after_plan, ["human_approval", "finish"])
    g.add_conditional_edges("human_approval", route_after_approval, ["execute", "finish"])
    g.add_edge("execute", "finish")
    g.add_edge("finish", END)
    return g.compile(checkpointer=checkpointer)
