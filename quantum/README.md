# LOOM quantum policy

The Weaver reacts to heat one step at a time. This folder answers a bigger question **ahead of time**:

> For each FPS goal and each heat level, which profile should every chiplet use, so that detection stays as good as possible while the phone stays under the heat limit for the next 3 minutes?

That is a choice among 3 × 3 × 3 × 2 = 54 profile sets. Here it is solved as a **QUBO** with **QAOA** in Qiskit, and every answer is checked by **brute force**. The result is a table, `app/src/main/assets/loom_policy.json`, which the Weaver loads when the app starts.

## Files

| File | What it does |
|---|---|
| `loom_model.json` | The inputs: battery draw of each profile, detector speed, quality weights and the thermal model. Each number states its source. |
| `calibrate.py` | Re-measures battery draw per profile and refits the thermal model from the dashboard's recordings (`live_logs/`). |
| `loom_qubo.py` | Builds the QUBO, with an exact brute-force reference. |
| `solve_policy.py` | Runs QAOA for 15 problems (3 goals × 5 heat bands), checks each answer, and writes the policy table and report. |
| `results/` | `report.md`, `report.json`, and charts of QAOA's answer probabilities. |
| `../quantum_solve.bat` | Runs everything on the laptop. |

## The model (measured on the iQOO 15, 10 Oct 2026)

- **Heat:** `dh/dt = a·P − b·(h − h_idle)`, where `h` is Android's thermal headroom and `P` is battery draw in mA. The fit uses 117 one-minute windows of unplugged running, R² = 0.82.
  - It predicts the heat settles at 0.84 at LOOM's lowest settings (570 mA). The phone actually settled at 0.84 in the 15-minute run.
  - At 1,050 mA (an ordinary app) it predicts 0.975. The phone reached 0.98.
- **Battery draw** per profile comes from the recorded runs. For example, everything at the highest setting draws 1,056 mA; the smaller detector draws 927 mA; slowing the camera to 15 and then 10 frames per second draws 742 and then 616 mA.
- **Heat budget:** the largest draw that keeps the predicted headroom at or under the limit 3 minutes from now, starting from the current heat.

## The QUBO

- **Variables:** one qubit per (chiplet, level), i.e. one-hot. That is 11 decision qubits, because speech is never paused for heat.
- **Cost:**
  - quality loss of each chosen level;
  - an FPS-shortfall penalty, which is quadratic because the speed depends on the *pair* (detector, camera rate);
  - plus the heat price × battery draw.
- **Constraints:**
  - exactly one level per chiplet;
  - draw ≤ heat budget.
- **How the budget is enforced:** it enters as a price per milliamp, i.e. a Lagrange multiplier. A classical bisection finds the lowest price at which QAOA's answer fits the budget. The exact constrained form, with 5–6 slack qubits (16–17 qubits in total), is also built, and its minimum over all 2¹⁶–2¹⁷ bit strings is checked against the brute-force result.

## QAOA

- **Mixer:** an XY mixer inside each chiplet, starting from a W state, as in the Quantum Alternating Operator Ansatz. Every measurement is therefore a valid answer: exactly one profile per chiplet. Plain QAOA with an X mixer spent almost all of its probability on invalid answers at 17 qubits, so it was replaced.
- **Depth and training:** p = 5 layers. The angles are trained with COBYLA on the energy expectation of an exact statevector.
- **Checks:** the same circuit is rebuilt gate by gate in Qiskit (state preparation, Pauli-evolution, XX+YY and RX gates) and must match the statevector to better than 1e-12. It is then sampled with Qiskit's `StatevectorSampler` (4,096 shots).
- **Reading the answer:** the best measured profile set that fits the budget is the answer. `p_optimal` is how often QAOA measures the true optimum, compared with 1/54 = 1.9% for a random pick.

## Honest limits

- This runs on a simulator, not on quantum hardware. With 54 options, brute force is instant; QAOA is used here to show that LOOM's decision has the right shape for a quantum optimiser, and every answer is verified.
- The quality weights in `loom_model.json` are design choices: screen < detector size < frame rate < speech. Change them and re-run.
- The plan treats the heat limit as reachable only if the predicted draw allows it. Where it isn't, the table says so (⚠ in `report.md`). In the measured room, holding 0.80 indefinitely needs about 450 mA, below LOOM's current floor of 570 mA.
