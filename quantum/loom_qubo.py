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


# Chiplets whose level caps the unit rate (FPS / tokens/s). app/: detector and camera.
THROUGHPUT = ["Inference", "Capture"]


def load_model(path: str | None = None) -> dict:
    with open(path or os.path.join(HERE, "loom_model.json"), encoding="utf-8") as f:
        m = json.load(f)
    configure(m)
    return m


def configure(m: dict) -> None:
    """Set the chiplet list from the model. A LOOM Studio export ("studio": 1) brings its own
    chiplets, levels and throughput chiplets; app/'s model keeps the original fixed setup.
    Call again inside worker processes (Windows spawns fresh interpreters)."""
    global CHIPLETS, ALLOWED, THROUGHPUT
    if not m.get("studio"):
        CHIPLETS = ["Display", "Inference", "Capture", "Narrator"]
        ALLOWED = {"Display": ["HIGH", "BALANCED", "ECO"], "Inference": ["HIGH", "BALANCED", "ECO"],
                   "Capture": ["HIGH", "BALANCED", "ECO"], "Narrator": ["HIGH", "BALANCED"]}
        THROUGHPUT = ["Inference", "Capture"]
        return
    CHIPLETS = list(m["chiplets"].keys())
    ALLOWED = {c: [lv for lv in LEVELS if lv in m["chiplets"][c]["levels"]] for c in CHIPLETS}
    THROUGHPUT = [c for c in m.get("roles", {}).get("throughput", []) if c in CHIPLETS][:2]


def _rate(m: dict, c: str, lv: str) -> float:
    lvl = m["chiplets"][c]["levels"][lv]
    v = lvl.get("max_fps", lvl.get("camera_fps"))
    return float("inf") if v is None else float(v)


def var(ch: str, lv: str) -> str:
    return f"{ch}.{lv}"


# ---------------------------------------------------------------- physics of one configuration
def power_mA(m: dict, cfg: dict) -> float:
    return m["base_power_mA"]["value"] + sum(m["chiplets"][c]["levels"][cfg[c]]["power_mA"] for c in CHIPLETS)


def fps(m: dict, cfg: dict) -> float:
    r = min((_rate(m, c, cfg[c]) for c in THROUGHPUT), default=float("inf"))
    return 0.0 if r == float("inf") else r


def _pair_penalty(m: dict, levels: dict, goal: float) -> float:
    """Shortfall for the throughput chiplets' levels (dict chiplet -> level)."""
    f = min((_rate(m, c, lv) for c, lv in levels.items()), default=float("inf"))
    if f == float("inf"):
        return 0.0
    return m["fps_shortfall_penalty_per_fps"] * max(0.0, goal - f)


def shortfall_penalty(m: dict, inf_lv: str, cap_lv: str, goal: float) -> float:
    """app/ signature (detector level, camera level)."""
    return _pair_penalty(m, dict(zip(THROUGHPUT, (inf_lv, cap_lv))), goal)


def shortfall_terms(m: dict, goal: float):
    """QUBO terms of the shortfall: quadratic for two throughput chiplets, linear for one."""
    lin, quad = {}, {}
    if len(THROUGHPUT) == 2:
        a, b = THROUGHPUT
        for la in ALLOWED[a]:
            for lb in ALLOWED[b]:
                pen = _pair_penalty(m, {a: la, b: lb}, goal)
                if pen > 0:
                    quad[(var(a, la), var(b, lb))] = round(pen, 3)
    elif len(THROUGHPUT) == 1:
        a = THROUGHPUT[0]
        for la in ALLOWED[a]:
            pen = _pair_penalty(m, {a: la}, goal)
            if pen > 0:
                lin[var(a, la)] = round(pen, 3)
    return lin, quad


def quality_loss(m: dict, cfg: dict) -> float:
    return sum(m["chiplets"][c]["levels"][cfg[c]]["quality_loss"] for c in CHIPLETS)


def objective(m: dict, cfg: dict, goal: float) -> float:
    return quality_loss(m, cfg) + _pair_penalty(m, {c: cfg[c] for c in THROUGHPUT}, goal)


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
    sl, sq = shortfall_terms(m, goal)
    for k, v in sl.items():
        linear[k] = linear.get(k, 0.0) + v
    quadratic.update(sq)
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
    sl, sq = shortfall_terms(m, goal)
    for k, v in sl.items():
        linear[k] = linear.get(k, 0.0) + v
    quadratic.update(sq)
    qp.minimize(linear=linear, quadratic=quadratic)
    for c in CHIPLETS:
        qp.linear_constraint({var(c, lv): 1 for lv in ALLOWED[c]}, "==", 1, name=f"one_{c}")
    conv = QuadraticProgramToQubo()
    return qp, conv.convert(qp), conv
