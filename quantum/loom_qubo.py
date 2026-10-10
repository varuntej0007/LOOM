"""
LOOM's profile choice as a QUBO.

Decision: one level for each adaptive chiplet (Display, Inference, Capture, Narrator).
          Encoded one-hot: x[chiplet, level] = 1 if that level is chosen.
Minimise: quality loss + FPS-shortfall penalty
Subject:  exactly one level per chiplet                      (one-hot, equality)
          predicted battery draw <= heat budget for this state (inequality -> slack qubits)

The heat budget comes from the measured thermal model: the largest draw that keeps the phone's
thermal headroom under the limit over the planning horizon, starting from the current heat.
Speech is never paused for heat (the Narrator's "paused" level is not offered).
"""
from __future__ import annotations

import itertools
import json
import math
import os
from dataclasses import dataclass

from qiskit_optimization import QuadraticProgram
from qiskit_optimization.converters import QuadraticProgramToQubo

HERE = os.path.dirname(os.path.abspath(__file__))
CHIPLETS = ["Display", "Inference", "Capture", "Narrator"]
LEVELS = ["HIGH", "BALANCED", "ECO"]
# Levels the planner may choose. Pausing speech is a safety decision, not a heat lever.
ALLOWED = {
    "Display": ["HIGH", "BALANCED", "ECO"],
    "Inference": ["HIGH", "BALANCED", "ECO"],
    "Capture": ["HIGH", "BALANCED", "ECO"],
    "Narrator": ["HIGH", "BALANCED"],
}


def load_model(path: str | None = None) -> dict:
    with open(path or os.path.join(HERE, "loom_model.json"), encoding="utf-8") as f:
        return json.load(f)


def var(ch: str, lv: str) -> str:
    return f"{ch}.{lv}"


# ---------------------------------------------------------------- physics of one configuration
def power_mA(m: dict, cfg: dict) -> float:
    return m["base_power_mA"]["value"] + sum(m["chiplets"][c]["levels"][cfg[c]]["power_mA"] for c in CHIPLETS)


def fps(m: dict, cfg: dict) -> float:
    inf = m["chiplets"]["Inference"]["levels"][cfg["Inference"]]["max_fps"]
    cam = m["chiplets"]["Capture"]["levels"][cfg["Capture"]]["camera_fps"]
    return min(inf, cam)


def shortfall_penalty(m: dict, inf_lv: str, cap_lv: str, goal: float) -> float:
    f = min(m["chiplets"]["Inference"]["levels"][inf_lv]["max_fps"],
            m["chiplets"]["Capture"]["levels"][cap_lv]["camera_fps"])
    return m["fps_shortfall_penalty_per_fps"] * max(0.0, goal - f)


def quality_loss(m: dict, cfg: dict) -> float:
    return sum(m["chiplets"][c]["levels"][cfg[c]]["quality_loss"] for c in CHIPLETS)


def objective(m: dict, cfg: dict, goal: float) -> float:
    return quality_loss(m, cfg) + shortfall_penalty(m, cfg["Inference"], cfg["Capture"], goal)


def predicted_heat(m: dict, h_now: float, p_mA: float) -> float:
    """Headroom after the planning horizon, from the fitted first-order thermal model."""
    t = m["thermal"]
    h_eq = t["h_idle"] + t["a_per_mA_s"] * p_mA / t["b_per_s"]
    e = math.exp(-t["b_per_s"] * m["plan_horizon_s"])
    return h_eq + (h_now - h_eq) * e


def heat_budget_mA(m: dict, h_now: float) -> float:
    """Largest draw whose predicted headroom after the horizon stays at or under the limit."""
    t = m["thermal"]
    e = math.exp(-t["b_per_s"] * m["plan_horizon_s"])
    h_eq_max = (m["heat_limit"] - h_now * e) / (1 - e)
    return (h_eq_max - t["h_idle"]) * t["b_per_s"] / t["a_per_mA_s"]


def all_configs():
    for combo in itertools.product(*(ALLOWED[c] for c in CHIPLETS)):
        yield dict(zip(CHIPLETS, combo))


def floor_power(m: dict) -> float:
    """Lowest draw the allowed profiles can reach."""
    return min(power_mA(m, c) for c in all_configs())


# ---------------------------------------------------------------- the QUBO
@dataclass
class Problem:
    goal: float
    h_now: float
    budget_mA: float          # from the thermal model
    budget_used_mA: float     # raised to the floor if unreachable, rounded down to the power unit
    reachable: bool
    qp: QuadraticProgram      # constrained form (one-hot + budget)
    qubo: QuadraticProgram    # unconstrained QUBO (penalties + slack bits)
    converter: QuadraticProgramToQubo


def build(m: dict, goal: float, h_now: float) -> Problem:
    unit = m["power_unit_mA"]
    budget = heat_budget_mA(m, h_now)
    floor = floor_power(m)
    reachable = budget >= floor
    used = max(budget, floor)

    qp = QuadraticProgram(f"loom_goal{goal:g}_h{h_now:.3f}")
    for c in CHIPLETS:
        for lv in ALLOWED[c]:
            qp.binary_var(var(c, lv))

    linear, quadratic = {}, {}
    for c in CHIPLETS:
        for lv in ALLOWED[c]:
            q = m["chiplets"][c]["levels"][lv]["quality_loss"]
            if q:
                linear[var(c, lv)] = float(q)
    # FPS depends on the PAIR (detector, camera rate): a quadratic term.
    for il in ALLOWED["Inference"]:
        for cl in ALLOWED["Capture"]:
            pen = shortfall_penalty(m, il, cl, goal)
            if pen > 0:
                quadratic[(var("Inference", il), var("Capture", cl))] = round(pen, 3)
    qp.minimize(linear=linear, quadratic=quadratic)

    for c in CHIPLETS:
        qp.linear_constraint({var(c, lv): 1 for lv in ALLOWED[c]}, "==", 1, name=f"one_{c}")

    # Budget: sum(power deltas) <= used - base. Integer coefficients in power units.
    deltas = {}
    for c in CHIPLETS:
        for lv in ALLOWED[c]:
            d = m["chiplets"][c]["levels"][lv]["power_mA"]
            if d:
                if d % unit:
                    raise ValueError(f"{c} {lv}: {d} mA is not a multiple of {unit} mA")
                deltas[var(c, lv)] = d // unit
    rhs = math.floor((used - m["base_power_mA"]["value"]) / unit + 1e-9)
    qp.linear_constraint(deltas, "<=", rhs, name="heat_budget")

    conv = QuadraticProgramToQubo()
    qubo = conv.convert(qp)
    used_rounded = m["base_power_mA"]["value"] + rhs * unit
    return Problem(goal, h_now, budget, used_rounded, reachable, qp, qubo, conv)


def decode(p: Problem, x) -> dict | None:
    """QUBO bit vector -> configuration, or None if it breaks a constraint."""
    orig = p.converter.interpret(x)
    names = [v.name for v in p.qp.variables]
    chosen = {n for n, b in zip(names, orig) if round(b) == 1}
    cfg = {}
    for c in CHIPLETS:
        lv = [l for l in ALLOWED[c] if var(c, l) in chosen]
        if len(lv) != 1:
            return None
        cfg[c] = lv[0]
    if not p.qp.is_feasible(orig):
        return None
    return cfg


def brute_force(m: dict, p: Problem) -> tuple[dict, float, list]:
    """Exhaustive check over every allowed configuration (54). Ties go to the lower draw."""
    ranked = []
    for cfg in all_configs():
        if power_mA(m, cfg) <= p.budget_used_mA + 1e-6:
            ranked.append((objective(m, cfg, p.goal), power_mA(m, cfg), cfg))
    ranked.sort(key=lambda r: (r[0], r[1]))
    best = ranked[0]
    return best[2], best[0], ranked


# ---------------------------------------------------------------- priced QUBO (what QAOA solves)
def build_priced(m: dict, goal: float, price: float):
    """QUBO without slack qubits: the heat budget becomes a PRICE.

    minimise  quality loss + FPS shortfall + price * (battery draw / power unit)
    subject   exactly one level per chiplet (kept by the XY mixer, also penalised in the QUBO)

    The price is the Lagrange multiplier of the budget constraint; solve_policy.py tunes it
    (bisection) until the plan fits the heat budget.
    """
    unit = m["power_unit_mA"]
    qp = QuadraticProgram(f"loom_priced_goal{goal:g}")
    for c in CHIPLETS:
        for lv in ALLOWED[c]:
            qp.binary_var(var(c, lv))
    linear, quadratic = {}, {}
    for c in CHIPLETS:
        for lv in ALLOWED[c]:
            lvl = m["chiplets"][c]["levels"][lv]
            v = lvl["quality_loss"] + price * lvl["power_mA"] / unit
            if v:
                linear[var(c, lv)] = float(v)
    for il in ALLOWED["Inference"]:
        for cl in ALLOWED["Capture"]:
            pen = shortfall_penalty(m, il, cl, goal)
            if pen > 0:
                quadratic[(var("Inference", il), var("Capture", cl))] = round(pen, 3)
    qp.minimize(linear=linear, quadratic=quadratic)
    for c in CHIPLETS:
        qp.linear_constraint({var(c, lv): 1 for lv in ALLOWED[c]}, "==", 1, name=f"one_{c}")
    conv = QuadraticProgramToQubo()
    return qp, conv.convert(qp), conv
