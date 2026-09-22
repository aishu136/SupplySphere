"""Single-echelon inventory environment for replenishment learning."""
from dataclasses import asdict, dataclass

import gymnasium as gym
import numpy as np
from gymnasium import spaces


@dataclass(frozen=True)
class InventoryConfig:
    mean_daily_demand: float
    lead_time_days: int
    reorder_point: int
    reorder_quantity: int
    unit_price: float
    initial_on_hand: int
    horizon_days: int = 120
    order_cost: float = 50.0            # fixed cost per purchase order (admin, freight booking)
    holding_rate_per_year: float = 0.25  # carrying cost as a fraction of unit value per year
    stockout_multiplier: float = 1.5     # lost-sale penalty as a multiple of unit price

    @property
    def holding_cost(self) -> float:
        return self.unit_price * self.holding_rate_per_year / 365

    @property
    def stockout_cost(self) -> float:
        return self.unit_price * self.stockout_multiplier

    @property
    def max_order(self) -> int:
        return 3 * self.reorder_quantity

    def to_dict(self) -> dict:
        return asdict(self)


class InventoryEnv(gym.Env):
    """Each step is one day: receive due orders, place today's order, then serve Poisson demand.

    Action: units to order today (0 = no order). Observation: [on_hand, on_order].
    Reward: -(holding + lost-sales penalty + fixed ordering cost). Unmet demand is lost (no backorders),
    as for most B2B spare-part and packaging items.
    """

    metadata = {"render_modes": []}

    def __init__(self, config: InventoryConfig):
        self.config = config
        self.action_space = spaces.Discrete(config.max_order + 1)
        self.observation_space = spaces.Box(low=0, high=np.inf, shape=(2,), dtype=np.float32)
        self._pipeline: list[tuple[int, int]] = []  # (arrival_day, qty)
        self._on_hand = 0
        self._day = 0

    @property
    def on_hand(self) -> int:
        return self._on_hand

    @property
    def on_order(self) -> int:
        return sum(q for _, q in self._pipeline)

    @property
    def inventory_position(self) -> int:
        return self._on_hand + self.on_order

    def _obs(self) -> np.ndarray:
        return np.array([self._on_hand, self.on_order], dtype=np.float32)

    def reset(self, *, seed: int | None = None, options: dict | None = None):
        """options: initial_on_hand (int) and on_order (int, arriving after a full lead time)."""
        super().reset(seed=seed)
        options = options or {}
        self._on_hand = int(options.get("initial_on_hand", self.config.initial_on_hand))
        on_order = int(options.get("on_order", 0))
        self._pipeline = [(self.config.lead_time_days, on_order)] if on_order else []
        self._day = 0
        return self._obs(), {}

    def step(self, action: int):
        c = self.config
        received = sum(q for day, q in self._pipeline if day <= self._day)
        self._pipeline = [(day, q) for day, q in self._pipeline if day > self._day]
        self._on_hand += received

        qty = int(np.clip(action, 0, c.max_order))
        if qty > 0:
            self._pipeline.append((self._day + c.lead_time_days, qty))

        demand = int(self.np_random.poisson(c.mean_daily_demand))
        sold = min(self._on_hand, demand)
        lost = demand - sold
        self._on_hand -= sold

        cost = c.holding_cost * self._on_hand + c.stockout_cost * lost + (c.order_cost if qty > 0 else 0.0)
        self._day += 1
        truncated = self._day >= c.horizon_days
        info = {"demand": demand, "sold": sold, "lost": lost, "ordered": qty, "received": received, "cost": cost}
        return self._obs(), -cost, False, truncated, info


def simulate_reorder_policies(config: InventoryConfig, reorder_points: np.ndarray, order_quantities: np.ndarray,
                              demand: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Vectorized twin of InventoryEnv for (s, Q) policies, used to train fast.

    Runs every policy i on every demand path j (demand: horizon x paths, common random numbers so
    policies are compared on identical days). Returns (mean cost, fill rate) per policy.
    """
    c = config
    n_policies, n_paths = len(reorder_points), demand.shape[1]
    n = n_policies * n_paths
    s = np.repeat(reorder_points, n_paths)
    q = np.repeat(np.rint(order_quantities).astype(np.int64), n_paths)
    on_hand = np.full(n, c.initial_on_hand, dtype=np.int64)
    # Column 0 arrives at the start of the next day, matching InventoryEnv's arrival_day <= day rule.
    pipeline = np.zeros((n, max(c.lead_time_days, 1)), dtype=np.int64)
    cost = np.zeros(n)
    sold_total = np.zeros(n)
    for t in range(c.horizon_days):
        on_hand += pipeline[:, 0]
        pipeline[:, :-1] = pipeline[:, 1:]
        pipeline[:, -1] = 0
        order = np.where(on_hand + pipeline.sum(axis=1) <= s, q, 0)
        pipeline[:, -1] += order
        d = np.tile(demand[t], n_policies)
        sold = np.minimum(on_hand, d)
        on_hand -= sold
        sold_total += sold
        cost += c.holding_cost * on_hand + c.stockout_cost * (d - sold) + c.order_cost * (order > 0)
    total_demand = max(demand.sum(), 1)
    return cost.reshape(n_policies, n_paths).mean(axis=1), sold_total.reshape(n_policies, n_paths).sum(axis=1) / total_demand
