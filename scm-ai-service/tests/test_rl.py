import asyncio

import numpy as np
import pytest

from app.rl import service
from app.rl.agent import CrossEntropyAgent, ReorderPolicy, evaluate, sop_policy
from app.rl.env import InventoryConfig, InventoryEnv, simulate_reorder_policies

CONFIG = InventoryConfig(mean_daily_demand=10, lead_time_days=7, reorder_point=110, reorder_quantity=300,
                         unit_price=12.5, initial_on_hand=110, horizon_days=90)


def test_order_arrives_after_lead_time():
    env = InventoryEnv(InventoryConfig(**{**CONFIG.to_dict(), "mean_daily_demand": 0.0}))
    env.reset(seed=1)
    _, _, _, _, info = env.step(300)
    assert info["ordered"] == 300 and env.on_order == 300
    for _ in range(6):
        _, _, _, _, info = env.step(0)
        assert info["received"] == 0
    _, _, _, _, info = env.step(0)
    assert info["received"] == 300 and env.on_order == 0


def test_lost_sales_are_penalised():
    env = InventoryEnv(CONFIG)
    env.reset(seed=3, options={"initial_on_hand": 0})
    _, reward, _, _, info = env.step(0)
    assert info["lost"] == info["demand"] > 0
    assert reward == pytest.approx(-CONFIG.stockout_cost * info["lost"])


def test_vectorized_trainer_matches_reference_env():
    """Training runs on the vectorized simulator; it must agree with the Gymnasium env used for evaluation."""
    policy = sop_policy(CONFIG)
    demand = np.random.default_rng(7).poisson(CONFIG.mean_daily_demand, (CONFIG.horizon_days, 400))
    fast_cost, fast_fill = simulate_reorder_policies(
        CONFIG, np.array([policy.reorder_point]), np.array([policy.order_quantity]), demand)
    reference = evaluate(CONFIG, policy, episodes=400)
    assert fast_cost[0] == pytest.approx(reference.avg_cost, rel=0.05)
    assert fast_fill[0] == pytest.approx(reference.fill_rate, abs=0.01)


def test_agent_learns_a_cheaper_policy_than_the_sop_rule():
    agent = CrossEntropyAgent(CONFIG)
    curve = agent.train(iterations=15, seed=0)

    rl = evaluate(CONFIG, agent.policy)          # held-out demand seeds
    sop = evaluate(CONFIG, sop_policy(CONFIG))

    assert curve[-1] <= curve[0]
    assert rl.avg_cost < sop.avg_cost
    assert rl.fill_rate > 0.98


def test_recommendations_use_live_state_and_guardrail(monkeypatch, tmp_path):
    item = {
        "product": {"sku": "SKU-X", "name": "Widget", "category": "Hardware", "unitPrice": 10,
                    "supplier": {"leadTimeDays": 7}},
        "warehouseCode": "WH-EAST", "quantity": 20, "reorderPoint": 110, "reorderQuantity": 300,
    }

    async def inventory():
        return [item]

    async def purchase_orders():
        return [{"status": "CREATED", "product": {"sku": "SKU-X"}, "warehouseCode": "WH-EAST", "quantity": 50},
                {"status": "RECEIVED", "product": {"sku": "SKU-X"}, "warehouseCode": "WH-EAST", "quantity": 999}]

    monkeypatch.setattr(service.scm_client, "inventory", inventory)
    monkeypatch.setattr(service.scm_client, "purchase_orders", purchase_orders)
    monkeypatch.setattr(service.get_settings(), "rl_policy_file", tmp_path / "policies.json")
    monkeypatch.setattr(service.get_settings(), "rl_train_iterations", 10)

    [rec] = asyncio.run(service.recommendations())

    assert rec["onOrder"] == 50  # received POs are not counted as on order
    assert rec["estimatedDailyDemand"] == pytest.approx(110 / 11, rel=1e-3)  # ROP / (lead time 7 + 4 safety days)
    assert rec["baselineOrderQuantity"] == 300  # position 20 + 50 = 70 <= reorder point 110
    learned = ReorderPolicy(**rec["learnedPolicy"])
    assert rec["rlOrderQuantity"] == (round(learned.order_quantity) if 70 <= learned.reorder_point else 0)
    expected = rec["rlOrderQuantity"] if rec["rl"]["avg_cost"] < rec["baseline"]["avg_cost"] else 300
    assert rec["recommendedQuantity"] == expected
    assert (tmp_path / "policies.json").exists()
