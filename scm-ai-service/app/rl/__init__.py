"""Reinforcement learning for inventory replenishment.

env.py      Gymnasium environment simulating one SKU at one warehouse (stochastic demand, supplier lead
            time, lost sales), plus a vectorized twin used for fast training.
agent.py    Cross-entropy-method policy-search agent that learns a (reorder point, order quantity)
            policy, and the SOP reorder-point policy used as the benchmark.
service.py  Trains one agent per live inventory position and serves guarded order recommendations.
"""
