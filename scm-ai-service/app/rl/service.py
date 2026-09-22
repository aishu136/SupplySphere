"""Trains one RL agent per live inventory position and serves guarded replenishment recommendations."""
import asyncio
import json
import logging

from app import scm_client
from app.config import get_settings
from app.rl.agent import CrossEntropyAgent, ReorderPolicy, evaluate, sop_policy
from app.rl.env import InventoryConfig, InventoryEnv

log = logging.getLogger(__name__)

OPEN_PO_STATUSES = {"CREATED", "APPROVED", "SHIPPED"}
_lock = asyncio.Lock()


def estimate_daily_demand(item: dict) -> float:
    """Inverts SOP-INV-004: reorder point = daily demand x lead time + safety stock
    (7 days of demand for Electronics, 4 days otherwise)."""
    lead_time = item["product"]["supplier"]["leadTimeDays"]
    safety_days = 7 if item["product"]["category"] == "Electronics" else 4
    return max(item["reorderPoint"] / (lead_time + safety_days), 0.1)


def config_for(item: dict) -> InventoryConfig:
    return InventoryConfig(
        mean_daily_demand=round(estimate_daily_demand(item), 3),
        lead_time_days=item["product"]["supplier"]["leadTimeDays"],
        reorder_point=item["reorderPoint"],
        reorder_quantity=item["reorderQuantity"],
        unit_price=float(item["product"]["unitPrice"]),
        initial_on_hand=item["reorderPoint"],
    )


def key_for(item: dict) -> str:
    return f'{item["product"]["sku"]}@{item["warehouseCode"]}'


def _load() -> dict:
    path = get_settings().rl_policy_file
    return json.loads(path.read_text()) if path.exists() else {}


def _save(policies: dict) -> None:
    path = get_settings().rl_policy_file
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(policies, indent=1))


def train_position(item: dict, iterations: int) -> dict:
    config = config_for(item)
    agent = CrossEntropyAgent(config)
    curve = agent.train(iterations=iterations)
    rl_eval = evaluate(config, agent.policy)
    baseline_eval = evaluate(config, sop_policy(config))
    log.info("RL %s: %s cost %.0f vs SOP %.0f", key_for(item), agent.policy, rl_eval.avg_cost, baseline_eval.avg_cost)
    return {
        "config": config.to_dict(),
        "iterations": iterations,
        "policy": agent.policy.to_dict(),
        "learning_curve": [round(v, 1) for v in curve],
        "rl": rl_eval.to_dict(),
        "baseline": baseline_eval.to_dict(),
    }


async def train_all(iterations: int | None = None, only_missing: bool = False) -> dict:
    iterations = iterations or get_settings().rl_train_iterations
    items = await scm_client.inventory()
    async with _lock:
        policies = _load()
        for item in items:
            key = key_for(item)
            stale = policies.get(key, {}).get("config") != config_for(item).to_dict()
            if not only_missing or stale:
                policies[key] = await asyncio.to_thread(train_position, item, iterations)
        _save(policies)
    return policies


async def recommendations() -> list[dict]:
    items = await scm_client.inventory()
    orders = await scm_client.purchase_orders()
    policies = await train_all(only_missing=True)

    on_order: dict[str, int] = {}
    for po in orders:
        if po["status"] in OPEN_PO_STATUSES:
            k = f'{po["product"]["sku"]}@{po["warehouseCode"]}'
            on_order[k] = on_order.get(k, 0) + po["quantity"]

    results = []
    for item in items:
        key = key_for(item)
        trained = policies[key]
        config = InventoryConfig(**trained["config"])
        learned = ReorderPolicy(**trained["policy"])
        baseline = sop_policy(config)

        # Put the live state into the environment so both policies decide on the same position.
        env = InventoryEnv(config)
        env.reset(options={"initial_on_hand": item["quantity"], "on_order": on_order.get(key, 0)})
        rl_qty, baseline_qty = learned(env), baseline(env)

        # Guardrail: act on the learned policy only where it beat the SOP on held-out simulations.
        rl_better = trained["rl"]["avg_cost"] < trained["baseline"]["avg_cost"]
        base_cost = trained["baseline"]["avg_cost"]
        results.append({
            "sku": item["product"]["sku"],
            "productName": item["product"]["name"],
            "warehouseCode": item["warehouseCode"],
            "onHand": item["quantity"],
            "onOrder": on_order.get(key, 0),
            "reorderPoint": item["reorderPoint"],
            "reorderQuantity": item["reorderQuantity"],
            "estimatedDailyDemand": config.mean_daily_demand,
            "learnedPolicy": learned.to_dict(),
            "rlOrderQuantity": rl_qty,
            "baselineOrderQuantity": baseline_qty,
            "recommendedQuantity": rl_qty if rl_better else baseline_qty,
            "policyUsed": "rl" if rl_better else "baseline",
            "rl": trained["rl"],
            "baseline": trained["baseline"],
            "costSavingPct": round(100 * (1 - trained["rl"]["avg_cost"] / base_cost), 1) if base_cost else 0.0,
        })
    return sorted(results, key=lambda r: (-r["recommendedQuantity"], r["sku"]))
