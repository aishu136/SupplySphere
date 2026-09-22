"""Policy-search RL agent for replenishment (cross-entropy method) and the SOP benchmark policy.

Why policy search: tabular Q-learning was tried first and was unreliable here. Lost-sales costs
land a full lead time (7-21 days) after the ordering decision, and value estimates drowned in Poisson
demand noise, so results swung widely between random seeds. Searching directly over interpretable
(reorder point, order quantity) policies is stable, trains in about a second, and yields a policy
planners can read and audit.
"""
from dataclasses import dataclass

import numpy as np

from app.rl.env import InventoryConfig, InventoryEnv, simulate_reorder_policies


@dataclass(frozen=True)
class ReorderPolicy:
    """Order `order_quantity` units whenever inventory position (on hand + on order) <= `reorder_point`."""

    reorder_point: float
    order_quantity: float

    def __call__(self, env: InventoryEnv) -> int:
        return int(round(self.order_quantity)) if env.inventory_position <= self.reorder_point else 0

    def to_dict(self) -> dict:
        return {"reorder_point": round(self.reorder_point, 1), "order_quantity": round(self.order_quantity, 1)}


def sop_policy(config: InventoryConfig) -> ReorderPolicy:
    """SOP-INV-004: order the standard reorder quantity at the configured reorder point."""
    return ReorderPolicy(config.reorder_point, config.reorder_quantity)


class CrossEntropyAgent:
    """Learns a ReorderPolicy by interacting with the simulator using the cross-entropy method.

    Each iteration samples a population of candidate policies from a Gaussian, runs them all on the
    same batch of simulated demand paths, and refits the Gaussian to the lowest-cost elite. The search
    starts at the SOP policy, which stays in the population, so the agent refines current practice
    rather than starting from scratch.
    """

    def __init__(self, config: InventoryConfig):
        self.config = config
        self.policy = sop_policy(config)

    def train(self, iterations: int = 20, population: int = 40, elite: int = 8, episodes: int = 100,
              seed: int = 0) -> list[float]:
        """Returns the elite mean cost per iteration (the learning curve)."""
        c = self.config
        rng = np.random.default_rng(seed)
        mean = np.array([c.reorder_point, c.reorder_quantity], dtype=float)
        std = np.array([max(c.reorder_point, 10) * 0.5, c.reorder_quantity * 0.5])
        curve = []
        for _ in range(iterations):
            candidates = rng.normal(mean, std, (population, 2))
            candidates[0] = mean  # keep the incumbent so a good policy is never lost
            candidates[:, 0] = np.clip(candidates[:, 0], 0, None)
            candidates[:, 1] = np.clip(candidates[:, 1], 1, c.max_order)
            demand = rng.poisson(c.mean_daily_demand, (c.horizon_days, episodes))
            costs, _ = simulate_reorder_policies(c, candidates[:, 0], candidates[:, 1], demand)
            best = np.argsort(costs)[:elite]
            mean = candidates[best].mean(axis=0)
            # Smoothed std with a floor keeps exploring instead of collapsing early.
            std = 0.9 * np.maximum(candidates[best].std(axis=0), [0.5, 1.0]) + 0.1 * std
            curve.append(float(costs[best].mean()))
        self.policy = ReorderPolicy(float(mean[0]), float(mean[1]))
        return curve


@dataclass
class Evaluation:
    avg_cost: float
    fill_rate: float
    avg_on_hand: float
    orders_per_episode: float

    def to_dict(self) -> dict:
        return {k: round(v, 3) for k, v in self.__dict__.items()}


def evaluate(config: InventoryConfig, policy: ReorderPolicy, episodes: int = 50, seed: int = 10_000) -> Evaluation:
    """Runs the policy in the reference Gymnasium env on held-out seeds (never used in training).
    All policies see the same demand seeds (common random numbers), so comparisons are fair."""
    env = InventoryEnv(config)
    costs, sold, demand, on_hand, orders = [], 0, 0, [], 0
    for ep in range(episodes):
        env.reset(seed=seed + ep)
        total, done = 0.0, False
        while not done:
            obs, reward, terminated, truncated, info = env.step(policy(env))
            total -= reward
            sold += info["sold"]
            demand += info["demand"]
            on_hand.append(obs[0])
            orders += info["ordered"] > 0
            done = terminated or truncated
        costs.append(total)
    return Evaluation(
        avg_cost=float(np.mean(costs)),
        fill_rate=sold / demand if demand else 1.0,
        avg_on_hand=float(np.mean(on_hand)),
        orders_per_episode=orders / episodes,
    )
