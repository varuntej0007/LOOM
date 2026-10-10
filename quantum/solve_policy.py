"""
Solve LOOM's profile choice for every (FPS goal, heat level) with QAOA, check every answer by
brute force, and write the policy table the Weaver loads at startup.

    python quantum/solve_policy.py            # full run (15 problems)
    python quantum/solve_policy.py --quick    # p=3, fewer restarts
    python quantum/solve_policy.py --profiles studio_export.json   # one LOOM Studio model (Plan B)

Pipeline per problem
  1. loom_qubo.build()        constrained model  ->  QUBO (one-hot + budget penalties, slack qubits)
  2. QUBO -> Ising Hamiltonian (Qiskit SparsePauliOp)
  3. QAOA with an XY mixer (keeps exactly one profile per chiplet), p = 5 layers. Angles are
     trained on an exact statevector (energy expectation, COBYLA). The heat budget enters as a
     price per milliamp; a classical bisection finds the lowest price whose answer fits the
     budget (Lagrangian relaxation).
  4. The trained circuit is rebuilt gate by gate in Qiskit, checked against the statevector,
     and SAMPLED with Qiskit's StatevectorSampler (4,096 shots).
  5. Best measured answer that fits the budget  vs  brute force over all 54 configurations,
     and vs the minimum of the exact constrained QUBO (with slack qubits) over all 2^n strings.
Outputs
  app/src/main/assets/loom_policy.json   (read by the Weaver)
  quantum/results/report.json, report.md, qaoa_*.png
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import time

import numpy as np
from scipy.optimize import minimize

from qiskit import __version__ as QISKIT_VERSION
from qiskit.circuit.library import QAOAAnsatz
from qiskit.primitives import StatevectorSampler
from qiskit.quantum_info import Statevector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import loom_qubo as L  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "quantum", "results")
POLICY = os.path.join(ROOT, "app", "src", "main", "assets", "loom_policy.json")


# ----------------------------------------------------------------------------- QAOA
MAXITER = 800
# Constraint-preserving QAOA (Hadfield et al., "Quantum Alternating Operator Ansatz", 2019):
#  - each chiplet's one-hot qubits start in a W state (equal superposition of its levels) and are
#    mixed with XY (XX+YY) rotations, which move "the 1" between levels but never create a second
#    one — so every measured answer picks exactly one profile per chiplet;
#  - the budget's slack qubits start in |+> and use the ordinary X mixer;
#  - the cost layer is the full QUBO Hamiltonian (penalties included).

class Layout:
    def __init__(self, p):
        names = [v.name for v in p.qubo.variables]
        self.n = len(names)
        self.groups = [[names.index(L.var(c, lv)) for lv in L.ALLOWED[c]] for c in L.CHIPLETS]
        inside = {q for g in self.groups for q in g}
        self.slack = [i for i in range(self.n) if i not in inside]
        self.pairs = []                                   # XY ring inside each chiplet
        for g in self.groups:
            ring = [(g[i], g[(i + 1) % len(g)]) for i in range(len(g))] if len(g) > 2 else [(g[0], g[1])]
            self.pairs += ring
        idx = np.arange(1 << self.n)
        self.pair_idx = []
        for i, j in self.pairs:
            a = idx[(((idx >> i) & 1) == 0) & (((idx >> j) & 1) == 1)]
            self.pair_idx.append((a, a ^ (1 << i) ^ (1 << j)))
        # Configuration id of every basis state (-1 = not one profile per chiplet).
        cfg = np.zeros(1 << self.n, dtype=np.int64)
        ok = np.ones(1 << self.n, dtype=bool)
        mult = 1
        for g in self.groups:
            bits = np.stack([(idx >> q) & 1 for q in g])
            ok &= bits.sum(0) == 1
            cfg += mult * bits.argmax(0)
            mult *= len(g)
        cfg[~ok] = -1
        self.cfg_id = cfg
        self.n_cfg = mult

    def cfg_of(self, k: int) -> dict:
        out = {}
        for c, g in zip(L.CHIPLETS, self.groups):
            out[c] = L.ALLOWED[c][k % len(g)]
            k //= len(g)
        return out

    def initial(self) -> np.ndarray:
        """W state on every chiplet's qubits, |+> on the slack qubits = uniform over valid answers."""
        psi = (self.cfg_id >= 0).astype(complex)
        return psi / np.linalg.norm(psi)


def qaoa_state(lay: Layout, diag_n: np.ndarray, gammas, betas) -> np.ndarray:
    psi = lay.initial()
    for g, b in zip(gammas, betas):
        psi = psi * np.exp(-1j * g * diag_n)
        c, s = np.cos(b), -1j * np.sin(b)
        for a, bb in lay.pair_idx:                          # XY mixer, ring order
            x0, x1 = psi[a], psi[bb]
            psi[a], psi[bb] = c * x0 + s * x1, s * x0 + c * x1
        for k in lay.slack:                                 # X mixer on slack qubits
            v = psi.reshape(-1, 2, 1 << k)
            a0, a1 = v[:, 0, :].copy(), v[:, 1, :].copy()
            v[:, 0, :] = c * a0 + s * a1
            v[:, 1, :] = s * a0 + c * a1
    return psi


def qaoa_circuit(lay: Layout, H, scale: float, gammas, betas):
    """The same unitary as a Qiskit circuit (W-state prep, cost evolution, XX+YY and RX gates)."""
    from qiskit import QuantumCircuit
    from qiskit.circuit.library import PauliEvolutionGate, StatePreparation, XXPlusYYGate

    qc = QuantumCircuit(lay.n)
    for g in lay.groups:
        w = np.zeros(1 << len(g))
        for i in range(len(g)):
            w[1 << i] = 1 / np.sqrt(len(g))
        qc.append(StatePreparation(w), g)
    for q in lay.slack:
        qc.h(q)
    Hn = H * (1.0 / scale)
    for g, b in zip(gammas, betas):
        qc.append(PauliEvolutionGate(Hn, time=float(g)), range(lay.n))
        for i, j in lay.pairs:
            qc.append(XXPlusYYGate(2 * float(b)), [i, j])
        for q in lay.slack:
            qc.rx(2 * float(b), q)
    return qc.decompose(reps=4)


def cvar(probs: np.ndarray, values_sorted_idx: np.ndarray, values: np.ndarray, alpha: float) -> float:
    """Mean cost of the best alpha fraction of the outcome distribution (Barkoutsos et al. 2020)."""
    p = probs[values_sorted_idx]
    v = values[values_sorted_idx]
    cum = np.cumsum(p)
    k = min(int(np.searchsorted(cum, alpha)), len(p) - 1)
    before = cum[k - 1] if k > 0 else 0.0
    return float(((p[:k] * v[:k]).sum() + (alpha - before) * v[k]) / alpha)


def train(lay: Layout, diag: np.ndarray, reps: int, restarts: int, seed: int):
    inside = lay.cfg_id >= 0
    scale = diag[inside].std() or 1.0
    diag_n = diag / scale
    order = np.argsort(diag)
    rng = np.random.default_rng(seed)
    starts = []
    for d in (0.5, 1.0, 2.0)[:restarts]:                      # linear-ramp starts (annealing-like)
        t = (np.arange(reps) + 0.5) / reps
        starts.append(np.concatenate([d * t, d * (1 - t)]))
    while len(starts) < restarts:
        starts.append(rng.uniform(0, np.pi / 2, 2 * reps))
    evals = 0

    def f(theta):
        nonlocal evals
        evals += 1
        psi = qaoa_state(lay, diag_n, theta[:reps], theta[reps:])
        return cvar(np.abs(psi) ** 2, order, diag, 1.0)        # alpha = 1: plain energy expectation

    best = None
    for x0 in starts:
        r = minimize(f, x0, method="COBYLA", options={"maxiter": MAXITER, "rhobeg": 0.4})
        if best is None or r.fun < best.fun:
            best = r
    return best.x[:reps], best.x[reps:], scale, evals


# ----------------------------------------------------------------------------- one problem
def run_qaoa(m, goal, price, reps, restarts, seed):
    """QAOA on the priced QUBO. Returns the layout, trained angles and exact outcome probabilities."""
    qp, qubo, conv = L.build_priced(m, goal, price)

    class P:  # minimal stand-in for Layout()
        pass
    P.qubo = qubo
    lay = Layout(P)
    H, offset = qubo.to_ising()
    diag = np.asarray(H.to_matrix(sparse=True).diagonal()).real
    rng = np.random.default_rng(seed)
    for i in rng.integers(0, 1 << lay.n, 32):                # Hamiltonian == QUBO, spot check
        x = [(int(i) >> k) & 1 for k in range(lay.n)]
        assert abs(qubo.objective.evaluate(x) - (diag[i] + offset)) < 1e-6
    gammas, betas, scale, evals = train(lay, diag, reps, restarts, seed)
    psi = qaoa_state(lay, diag / scale, gammas, betas)
    probs = np.abs(psi) ** 2
    valid = lay.cfg_id >= 0
    p_cfg = np.bincount(lay.cfg_id[valid], weights=probs[valid], minlength=lay.n_cfg)
    return dict(lay=lay, H=H, diag=diag, scale=scale, gammas=gammas, betas=betas,
                probs=probs, p_cfg=p_cfg, evals=evals, qubo=qubo)


def solve(m: dict, goal: float, bucket: dict, reps: int, restarts: int, shots: int, seed: int) -> dict:
    t0 = time.time()
    p = L.build(m, goal, bucket["plan_at"])            # budget numbers (and the exact slack form)
    budget = p.budget_used_mA
    bf_cfg, bf_obj, ranked = L.brute_force(m, p)

    def fits(cfg):
        return L.power_mA(m, cfg) <= budget + 1e-6

    # Outer loop (classical): bisection on the heat price. Every QAOA run is kept; the one used is
    # the run whose MOST LIKELY answer is the best profile set that fits the budget.
    lo, hi = 0.0, 2.0
    trace, evals, runs = [], 0, []

    def attempt(price):
        nonlocal evals
        r = run_qaoa(m, goal, price, reps, restarts, seed)
        evals += r["evals"]
        cfg = r["lay"].cfg_of(int(np.argmax(r["p_cfg"])))
        trace.append((price, cfg))
        runs.append((price, r, cfg))
        return fits(cfg)

    if not attempt(lo):
        while not attempt(hi) and hi < 64:      # raise the price until the answer fits
            lo, hi = hi, hi * 2
        for _ in range(8):
            mid = (lo + hi) / 2
            if attempt(mid):
                hi = mid
            else:
                lo = mid
        if not any(fits(c) for _, _, c in runs):
            attempt(hi)
    good = [(L.objective(m, c, goal), L.power_mA(m, c), pr, r) for pr, r, c in runs if fits(c)]
    good.sort(key=lambda g: (g[0], g[1], -float(np.max(g[3]["p_cfg"]))))
    price, final_run = good[0][2], good[0][3]
    lay, p_cfg = final_run["lay"], final_run["p_cfg"]

    # Classical score of each configuration under the REAL constraint (for reading the answer).
    cfg_cost = np.full(lay.n_cfg, np.inf)
    for k in range(lay.n_cfg):
        cfg = lay.cfg_of(k)
        if fits(cfg):
            cfg_cost[k] = L.objective(m, cfg, goal)
    opt_cfgs = np.isclose(cfg_cost, bf_obj)
    p_opt = float(p_cfg[opt_cfgs].sum())

    # Same angles as a real Qiskit circuit: check it matches, then sample it.
    circ = qaoa_circuit(lay, final_run["H"], final_run["scale"], final_run["gammas"], final_run["betas"])
    circuit_match = float(np.abs(Statevector(circ).probabilities() - final_run["probs"]).max())
    meas = circ.copy()
    meas.measure_all()
    counts = StatevectorSampler(seed=seed).run([meas], shots=shots).result()[0].data.meas.get_counts()
    sampled, shots_opt, shots_valid = {}, 0, 0
    for bits, cnt in counts.items():
        k = int(lay.cfg_id[int(bits, 2)])
        if k < 0:
            continue
        shots_valid += cnt
        shots_opt += cnt if opt_cfgs[k] else 0
        sampled[k] = sampled.get(k, 0) + cnt
    # The answer: the best measured profile set that fits the budget (ties -> lower draw).
    ok_k = sorted((k for k in sampled if np.isfinite(cfg_cost[k])),
                  key=lambda k: (cfg_cost[k], L.power_mA(m, lay.cfg_of(k))))
    q_cfg = lay.cfg_of(ok_k[0]) if ok_k else None
    q_obj = float(cfg_cost[ok_k[0]]) if ok_k else None
    matches = q_cfg is not None and abs(q_obj - bf_obj) < 1e-9
    final = q_cfg if matches else bf_cfg

    # Independent check of the exact constrained QUBO (slack form): its minimum over all 2^n.
    Hs, _ = p.qubo.to_ising()
    ds = np.asarray(Hs.to_matrix(sparse=True).diagonal()).real
    g = int(np.argmin(ds))
    gs_cfg = L.decode(p, [(g >> k) & 1 for k in range(p.qubo.get_num_vars())])

    rand = float(opt_cfgs.sum()) / lay.n_cfg
    return {
        "goal": goal, "bucket": bucket["name"], "heat_lo": bucket["lo"], "heat_hi": bucket["hi"],
        "plan_at": bucket["plan_at"],
        "levels": final,
        "labels": {c: m["chiplets"][c]["levels"][final[c]]["label"] for c in L.CHIPLETS},
        "predicted_mA": round(L.power_mA(m, final)),
        "predicted_fps": L.fps(m, final),
        "predicted_heat_3min": round(L.predicted_heat(m, bucket["plan_at"], L.power_mA(m, final)), 3),
        "budget_mA": round(p.budget_mA),
        "limit_reachable": p.reachable,
        "quality_loss": L.objective(m, final, goal),
        "qaoa": {
            "qubits": lay.n,
            "mixer": "XY ring per chiplet (keeps one profile per chiplet)",
            "reps": reps,
            "heat_price": round(price, 4),
            "price_search": [{"price": round(pr, 4), "answer": c} for pr, c in trace],
            "energy_evaluations": evals,
            "gammas": [round(float(x), 4) for x in final_run["gammas"]],
            "betas": [round(float(x), 4) for x in final_run["betas"]],
            "p_optimal": round(p_opt, 4),
            "p_optimal_random_guess": round(rand, 4),
            "amplification": round(p_opt / rand, 1),
            "shots": shots,
            "shots_optimal": int(shots_opt),
            "shots_valid": int(shots_valid),
            "top_answer": lay.cfg_of(int(np.argmax(p_cfg))),
            "top_answer_cost": float(cfg_cost[int(np.argmax(p_cfg))]),
            "top_answer_probability": round(float(np.max(p_cfg)), 4),
            "best_sampled": q_cfg,
            "best_sampled_cost": q_obj,
            "circuit_vs_statevector_max_diff": circuit_match,
        },
        "check": {
            "configs": lay.n_cfg,
            "configs_within_budget": len(ranked),
            "brute_force_best": bf_cfg,
            "brute_force_cost": bf_obj,
            "exact_qubo_with_slack_qubits": p.qubo.get_num_vars(),
            "exact_qubo_ground_state": gs_cfg,
            "exact_qubo_ground_state_cost": L.objective(m, gs_cfg, goal) if gs_cfg else None,
            "qaoa_matches_brute_force": bool(matches),
        },
        "seconds": round(time.time() - t0, 1),
        "_p_cfg": p_cfg, "_lay": lay,
    }


# ----------------------------------------------------------------------------- outputs
def plot_case(r: dict, m: dict, path: str):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    p_cfg, lay = r["_p_cfg"], r["_lay"]
    top = np.argsort(p_cfg)[::-1][:10][::-1]
    labels = [" · ".join(m["chiplets"][c]["levels"][lay.cfg_of(int(k))[c]]["label"] for c in L.CHIPLETS) for k in top]
    best = " · ".join(r["labels"][c] for c in L.CHIPLETS)
    fig, ax = plt.subplots(figsize=(9, 4.6), dpi=150)
    ax.barh(labels, p_cfg[top], color=["#0f766e" if l == best else "#94a3b8" for l in labels])
    rg = 1 / lay.n_cfg
    ax.axvline(rg, color="#b45309", ls="--", lw=1)
    ax.text(rg, len(top) - 0.4, f" random pick {rg:.1%}", color="#b45309", fontsize=8)
    ax.set_xlabel("probability of measuring this profile set after QAOA")
    ax.set_title(f"Goal {r['goal']:g} FPS, heat {r['heat_lo']:.2f}–{min(r['heat_hi'], 1):.2f}: "
                 f"best answer (green) in {r['qaoa']['p_optimal']:.0%} of measurements", fontsize=10, loc="left")
    ax.tick_params(axis="y", labelsize=7.5)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def solve_job(maxiter, m, g, b, reps, restarts, shots, seed):
    global MAXITER
    MAXITER = maxiter                                   # set inside the worker process
    L.configure(m)                                      # chiplet list of THIS model (spawned workers)
    return solve(m, g, b, reps, restarts, shots, seed)


MAXITER_RUN = 800


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--profiles", help="LOOM Studio export (studio_export.json): solve that model's chiplets")
    ap.add_argument("--out", help="policy output path (default: quantum/results/studio_policy.json with --profiles)")
    args = ap.parse_args()
    global MAXITER, MAXITER_RUN
    reps, restarts, shots = (3, 2, 2048) if args.quick else (5, 3, 4096)
    MAXITER = MAXITER_RUN = 300 if args.quick else 800

    m = L.load_model(args.profiles)
    studio = bool(m.get("studio"))
    policy_path = args.out or (os.path.join(OUT, "studio_policy.json") if studio else POLICY)
    os.makedirs(OUT, exist_ok=True)
    if studio:
        print(f"LOOM Studio model: {m.get('model')} · chiplets {', '.join(L.CHIPLETS)} · throughput {', '.join(L.THROUGHPUT)}")
    print(f"Qiskit {QISKIT_VERSION} · QAOA p={reps} · {shots} shots per problem\n")
    jobs = [(MAXITER_RUN, m, g, b, reps, restarts, shots, args.seed)
            for b in m["heat_buckets"] for g in m["goals_fps"]]
    import multiprocessing as mp
    workers = max(1, min(4, (os.cpu_count() or 1)))
    print(f"{len(jobs)} problems on {workers} worker(s) ...\n", flush=True)
    with mp.Pool(workers) as pool:                      # works on Windows (spawn) and Linux
        results = pool.starmap(solve_job, jobs)
    for r in results:
        b = next(x for x in m["heat_buckets"] if x["name"] == r["bucket"])
        g = r["goal"]
        q = r["qaoa"]
        print(f"goal {g:>2} FPS · heat {b['name']:<4} ({b['lo']:.2f}-{min(b['hi'],1):.2f})  "
              f"{q['qubits']} qubits  P(opt) {q['p_optimal']:6.1%} (random pick {q['p_optimal_random_guess']:.1%})  "
              f"brute force {'MATCH' if r['check']['qaoa_matches_brute_force'] else 'DIFF '}  "
              f"-> {' · '.join(r['labels'][c] for c in L.CHIPLETS)}  {r['predicted_mA']} mA  [{r['seconds']} s]")

    ok = all(r["check"]["qaoa_matches_brute_force"] for r in results)
    stamp = dt.datetime.now().astimezone().isoformat(timespec="seconds")

    # Charts for the two most instructive cases.
    if not studio:
        for r in results:
            if (r["goal"], r["bucket"]) in ((15, "cool"), (15, "mild")):
                plot_case(r, m, os.path.join(OUT, f"qaoa_goal{int(r['goal'])}_{r['bucket']}.png"))

    clean = [{k: v for k, v in r.items() if not k.startswith("_")} for r in results]
    with open(os.path.join(OUT, "studio_report.json" if studio else "report.json"), "w", encoding="utf-8") as f:
        json.dump({"generated": stamp, "qiskit": QISKIT_VERSION, "all_match_brute_force": ok,
                   "model": m, "problems": clean}, f, indent=2, ensure_ascii=False)

    policy = {
        "version": 1,
        "generated": stamp,
        "method": f"QUBO solved with QAOA (Qiskit {QISKIT_VERSION}, p={reps}, {shots} shots), "
                  f"every answer checked by brute force: {'all match' if ok else 'MISMATCH'}",
        "device": m["device"],
        "heat_limit": m["heat_limit"],
        "plan_horizon_s": m["plan_horizon_s"],
        "verified": ok,
        "plans": [{
            "goal": r["goal"], "bucket": r["bucket"], "heat_lo": r["heat_lo"], "heat_hi": r["heat_hi"],
            "levels": r["levels"], "labels": r["labels"],
            "predicted_mA": r["predicted_mA"], "predicted_fps": r["predicted_fps"],
            "predicted_heat_3min": r["predicted_heat_3min"], "limit_reachable": r["limit_reachable"],
            "p_optimal": r["qaoa"]["p_optimal"],
        } for r in results],
    }
    os.makedirs(os.path.dirname(os.path.abspath(policy_path)), exist_ok=True)
    with open(policy_path, "w", encoding="utf-8") as f:
        json.dump(policy, f, indent=2, ensure_ascii=False)

    if not studio:
        write_markdown(clean, m, ok, stamp, reps, shots)
    print(f"\n{'All' if ok else 'NOT all'} {len(results)} QAOA answers match brute force.")
    print(f"Policy table  -> {os.path.relpath(policy_path, ROOT)}")
    if studio:
        print("Import it in LOOM Studio: Run → LOOM panel → Import policy  (or adb push it to the phone's Download folder)")
    print(f"Report        -> {os.path.relpath(os.path.join(OUT, 'studio_report.json' if studio else 'report.md'), ROOT)}")
    return 0 if ok else 1


def top_note(r: dict) -> str:
    gap = r["qaoa"]["top_answer_cost"] - r["quality_loss"]
    return "optimum" if abs(gap) < 1e-9 else f"+{gap:g} quality"


def write_markdown(rs, m, ok, stamp, reps, shots):
    lines = [
        "# LOOM quantum policy — results", "",
        f"Generated {stamp} · Qiskit QAOA p={reps} · {shots} shots per problem · "
        f"**{'all answers match brute force' if ok else 'MISMATCH — see report.json'}**", "",
        "Each row is one problem: *given this FPS goal and this heat, which profile for each chiplet?* "
        "Battery draw and heat are predicted from the model fitted to the iQOO runs (loom_model.json).", "",
        "| Goal | Heat | Display | Inference | Capture | Narrator | Draw | FPS | Heat in 3 min | Qubits | P(optimum) | vs random | QAOA top answer | Brute force |",
        "|---:|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---|---|",
    ]
    for r in rs:
        q = r["qaoa"]
        lines.append(
            f"| {r['goal']:g} | {r['heat_lo']:.2f}–{min(r['heat_hi'],1):.2f} | {r['labels']['Display']} | {r['labels']['Inference']} | "
            f"{r['labels']['Capture']} | {r['labels']['Narrator']} | {r['predicted_mA']} mA | {r['predicted_fps']:g} | "
            f"{r['predicted_heat_3min']:.2f}{'' if r['limit_reachable'] else ' ⚠'} | {q['qubits']} | {q['p_optimal']:.1%} | "
            f"×{q['amplification']} | {top_note(r)} | "
            f"{'match' if r['check']['qaoa_matches_brute_force'] else 'DIFF'} |")
    lines += ["", "⚠ = the heat limit cannot be held for 3 minutes with any allowed profile; the plan is the coolest one.",
              "", "Speech is never paused for heat: the Narrator's 'paused' level is not offered to the optimiser.",
              "", "**P(optimum)** = how often one QAOA measurement returns the brute-force optimum (a random pick: 1/54 = 1.9%). "
              "**QAOA top answer** = its single most likely answer; '+1 quality' means one quality point worse than the optimum. "
              "That happens where no single heat price makes the optimum the cheapest choice (a duality gap of the "
              "Lagrangian form); the optimum is then still found among the 4,096 measurements, and the table uses it."]
    with open(os.path.join(OUT, "report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    sys.exit(main())
