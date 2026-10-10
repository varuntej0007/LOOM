# LOOM quantum policy — results

Generated 2026-10-09T22:30:19+00:00 · Qiskit QAOA p=5 · 4096 shots per problem · **all answers match brute force**

Each row is one problem: *given this FPS goal and this heat, which profile for each chiplet?* Battery draw and heat are predicted from the model fitted to the iQOO runs (loom_model.json).

| Goal | Heat | Display | Inference | Capture | Narrator | Draw | FPS | Heat in 3 min | Qubits | P(optimum) | vs random | QAOA top answer | Brute force |
|---:|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---|---|
| 10 | 0.00–0.65 | screen normal | lite2/GPU | camera 15/s | every 3 s | 870 mA | 13.9 | 0.78 | 11 | 37.0% | ×20.0 | +3 quality | match |
| 15 | 0.00–0.65 | screen normal | lite0/GPU | camera 30/s | every 3 s | 920 mA | 21 | 0.79 | 11 | 38.8% | ×21.0 | optimum | match |
| 20 | 0.00–0.65 | screen normal | lite0/GPU | camera 30/s | every 3 s | 920 mA | 21 | 0.79 | 11 | 37.9% | ×20.4 | +1 quality | match |
| 10 | 0.65–0.70 | screen normal | lite2/GPU | camera 10/s | every 3 s | 760 mA | 10 | 0.79 | 11 | 54.6% | ×29.5 | optimum | match |
| 15 | 0.65–0.70 | screen normal | lite0/GPU | camera 15/s | every 3 s | 740 mA | 15 | 0.79 | 11 | 55.9% | ×30.2 | optimum | match |
| 20 | 0.65–0.70 | screen normal | lite0/GPU | camera 15/s | every 3 s | 740 mA | 15 | 0.79 | 11 | 10.2% | ×5.5 | +1 quality | match |
| 10 | 0.70–0.75 | screen normal | lite0/GPU | camera 10/s | every 3 s | 630 mA | 10 | 0.80 | 11 | 56.7% | ×30.6 | optimum | match |
| 15 | 0.70–0.75 | screen normal | lite0/GPU | camera 10/s | every 3 s | 630 mA | 10 | 0.80 | 11 | 0.6% | ×0.3 | +1 quality | match |
| 20 | 0.70–0.75 | screen normal | lite0/GPU | camera 10/s | every 3 s | 630 mA | 10 | 0.80 | 11 | 2.4% | ×1.3 | +9 quality | match |
| 10 | 0.75–0.80 | screen 8% | lite0/GPU | camera 10/s | every 6 s | 570 mA | 10 | 0.81 ⚠ | 11 | 40.9% | ×22.1 | optimum | match |
| 15 | 0.75–0.80 | screen 8% | lite0/GPU | camera 10/s | every 6 s | 570 mA | 10 | 0.81 ⚠ | 11 | 36.0% | ×19.4 | optimum | match |
| 20 | 0.75–0.80 | screen 8% | lite0/GPU | camera 10/s | every 6 s | 570 mA | 10 | 0.81 ⚠ | 11 | 33.0% | ×17.8 | optimum | match |
| 10 | 0.80–1.00 | screen 8% | lite0/GPU | camera 10/s | every 6 s | 570 mA | 10 | 0.83 ⚠ | 11 | 40.9% | ×22.1 | optimum | match |
| 15 | 0.80–1.00 | screen 8% | lite0/GPU | camera 10/s | every 6 s | 570 mA | 10 | 0.83 ⚠ | 11 | 36.0% | ×19.4 | optimum | match |
| 20 | 0.80–1.00 | screen 8% | lite0/GPU | camera 10/s | every 6 s | 570 mA | 10 | 0.83 ⚠ | 11 | 33.0% | ×17.8 | optimum | match |

⚠ = the heat limit cannot be held for 3 minutes with any allowed profile; the plan is the coolest one.

Speech is never paused for heat: the Narrator's 'paused' level is not offered to the optimiser.

**P(optimum)** = how often one QAOA measurement returns the brute-force optimum (a random pick: 1/54 = 1.9%). **QAOA top answer** = its single most likely answer; '+1 quality' means one quality point worse than the optimum. That happens where no single heat price makes the optimum the cheapest choice (a duality gap of the Lagrangian form); the optimum is then still found among the 4,096 measurements, and the table uses it.
