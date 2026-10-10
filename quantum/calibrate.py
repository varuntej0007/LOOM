"""
Re-measure LOOM's model from the dashboard's recorded sessions (live_logs/*.jsonl).

    python quantum/calibrate.py            # print the fit and per-profile battery draw
    python quantum/calibrate.py --write    # also store the thermal fit in loom_model.json

Thermal model (first order):  dh/dt = a*P - b*(h - h_idle)
  h = Android thermal headroom, P = battery draw (mA). Fitted on one-minute windows of
  unplugged, stress-free running.
"""
import argparse
import collections
import glob
import json
import os
import statistics as st

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL = os.path.join(ROOT, "quantum", "loom_model.json")


def live_rows(path):
    for line in open(path, encoding="utf-8"):
        try:
            r = json.loads(line)
        except ValueError:
            continue
        if r.get("kind") == "live":
            yield r["data"]


def usable(d):
    return (not d.get("charging") and d.get("stress") == "OFF"
            and d.get("battery_ua") is not None and d.get("headroom") is not None)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--logs", default=os.path.join(ROOT, "live_logs"))
    args = ap.parse_args()
    files = sorted(glob.glob(os.path.join(args.logs, "*.jsonl")))
    if not files:
        raise SystemExit(f"No sessions in {args.logs} — run live_dashboard.bat with the phone first.")

    X, Y, seconds = [], [], 0
    by_profile = collections.defaultdict(list)
    for f in files:
        seg = []

        def flush():
            if len(seg) < 120:
                return
            t = np.array([d["t"] / 1000 for d in seg])
            h = np.array([d["headroom"] for d in seg])
            p = np.array([-d["battery_ua"] / 1000 for d in seg])
            for i in range(0, len(seg) - 60, 15):
                j = i + 60
                if t[j] - t[i] > 90:
                    continue
                X.append([p[i:j].mean(), h[i:j].mean(), 1.0])
                Y.append((h[j - 5:j].mean() - h[i:i + 5].mean()) / (t[j - 3] - t[i + 2]))

        for d in live_rows(f):
            if usable(d):
                seconds += 1
                c = {x["name"]: x["profile"] for x in d["chiplets"]}
                key = (c["Display"].replace("boxes · ", ""), c["Inference"], f"camera {d.get('camera_rate', 30)}/s",
                       c["Narrator"])
                by_profile[key].append(-d["battery_ua"] / 1000)
                if seg and d["t"] - seg[-1]["t"] > 5000:
                    flush()
                    seg = []
                seg.append(d)
            else:
                flush()
                seg = []
        flush()

    print(f"{len(files)} sessions, {seconds} usable seconds (unplugged, no stress)\n")
    print("Battery draw by profile set (median mA, seconds):")
    for k, v in sorted(by_profile.items(), key=lambda kv: -len(kv[1])):
        if len(v) >= 10:
            print(f"  {st.median(v):6.0f} mA  {len(v):5d} s   {' · '.join(k)}")
    if len(Y) < 10:
        raise SystemExit("\nNot enough continuous running for a thermal fit (need several minutes).")
    X, Y = np.array(X), np.array(Y)
    (a, mb, c), *_ = np.linalg.lstsq(X, Y, rcond=None)
    r2 = 1 - ((Y - X @ np.array([a, mb, c])) ** 2).sum() / ((Y - Y.mean()) ** 2).sum()
    b = -mb
    h_idle = c / b
    print(f"\nThermal fit on {len(Y)} one-minute windows: a={a:.3e}/mA/s  b={b:.4f}/s  h_idle={h_idle:.3f}  R²={r2:.2f}")
    print(f"  time constant {1 / b:.0f} s; settles at {h_idle + a * 600 / b:.3f} with 600 mA, {h_idle + a * 1050 / b:.3f} with 1,050 mA")
    print(f"  draw that holds headroom at 0.80 indefinitely: {(0.80 - h_idle) * b / a:.0f} mA")
    if args.write:
        m = json.load(open(MODEL, encoding="utf-8"))
        m["thermal"].update({"a_per_mA_s": float(f"{a:.4e}"), "b_per_s": round(float(b), 5),
                             "h_idle": round(float(h_idle), 4), "fit_r2": round(float(r2), 2),
                             "source": f"least-squares fit, {len(Y)} one-minute windows from {seconds} s (quantum/calibrate.py)"})
        json.dump(m, open(MODEL, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
        print("\nWrote the thermal fit to quantum/loom_model.json. Run quantum_solve.bat to re-plan.")


if __name__ == "__main__":
    main()
