"""Compare a FIXED benchmark run with an ADAPTIVE one and draw the pitch charts (blueprint §9).

Usage (on the laptop, after pull_logs.bat):
    pip install pandas matplotlib
    python tools/compare_runs.py csv/files/loom_run_fixed_*.csv csv/files/loom_run_adaptive_*.csv

Writes charts/ next to the current directory:
    fps_over_time.png        FPS per second, fixed vs adaptive, Weaver events as vertical lines
    time_at_target.png       % of seconds at or above the goal  (headline number)
    inference_latency.png    inference p50/p90 per detector profile
    confidence.png           mean detection confidence per detector profile (quality trade-off)
    battery_current.png      mean raw battery current per detector profile (energy story)
    heat_over_time.png       thermal headroom over time, fixed vs adaptive (heat story)
Every number comes from the CSVs.
"""
import argparse
import os

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import pandas as pd  # noqa: E402

FIXED_C, ADAPT_C, EVENT_C, GRID_C = "#8A94A6", "#1B9E8F", "#E0A100", "#E6E8EC"


def load(path: str) -> pd.DataFrame:
    df = pd.read_csv(path)
    df["t_s"] = (df["timestamp_ms"] - df["timestamp_ms"].iloc[0]) / 1000.0
    df["sec"] = df["t_s"].astype(int)
    return df


def per_second(df: pd.DataFrame) -> pd.Series:
    s = df.groupby("sec").size()
    s = s.reindex(range(int(df["sec"].max()) + 1), fill_value=0)  # seconds with 0 frames count
    return s.iloc[1:-1] if len(s) > 2 else s  # drop partial edge seconds


def style(ax, title, ylabel):
    ax.set_title(title, loc="left", fontsize=12, fontweight="bold")
    ax.set_ylabel(ylabel)
    ax.grid(axis="y", color=GRID_C, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("fixed")
    ap.add_argument("adaptive")
    ap.add_argument("--target", type=float, default=None, help="default: target_fps column")
    ap.add_argument("--out", default="charts")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    fx, ad = load(a.fixed), load(a.adaptive)
    target = a.target or float(ad["target_fps"].dropna().median() if "target_fps" in ad else 20)
    fps_fx, fps_ad = per_second(fx), per_second(ad)
    pct_fx = (fps_fx >= target).mean() * 100
    pct_ad = (fps_ad >= target).mean() * 100

    # 1. FPS over time
    fig, ax = plt.subplots(figsize=(10, 4))
    ax.plot(fps_fx.index / 60, fps_fx.values, color=FIXED_C, lw=1.2, label="Fixed HIGH")
    ax.plot(fps_ad.index / 60, fps_ad.values, color=ADAPT_C, lw=1.6, label="LOOM adaptive")
    ax.axhline(target, color="#333", ls="--", lw=1)
    ax.text(0, target + 0.4, f"goal ≥ {target:g} FPS", fontsize=9, color="#333")
    ev = ad[ad["weaver_event"].notna() & ad["weaver_event"].str.contains("→", na=False)]
    for t in ev["t_s"]:
        ax.axvline(t / 60, color=EVENT_C, lw=0.8, alpha=0.7)
    ax.plot([], [], color=EVENT_C, lw=0.8, label="Weaver decision")
    ax.set_xlabel("minutes")
    style(ax, "Processed FPS over time", "FPS")
    ax.legend(frameon=False, loc="upper center", bbox_to_anchor=(0.5, -0.2), ncol=3)
    fig.tight_layout()
    fig.savefig(f"{a.out}/fps_over_time.png", dpi=160)

    # 2. Headline: % of seconds at target
    fig, ax = plt.subplots(figsize=(5, 4))
    bars = ax.bar(["Fixed HIGH", "LOOM adaptive"], [pct_fx, pct_ad], color=[FIXED_C, ADAPT_C], width=0.55)
    for b, v in zip(bars, [pct_fx, pct_ad]):
        ax.text(b.get_x() + b.get_width() / 2, v + 1.5, f"{v:.1f}%", ha="center", fontweight="bold")
    ax.set_ylim(0, 105)
    style(ax, f"Seconds at ≥ {target:g} FPS", "% of seconds")
    fig.tight_layout()
    fig.savefig(f"{a.out}/time_at_target.png", dpi=160)

    # 3–5. Per detector profile (both runs pooled — the profile, not the run, is the unit)
    both = pd.concat([fx, ad])
    order = [p for p in ["lite2/GPU", "lite0/GPU", "lite0/CPU"] if p in set(both["profile_inference"])]
    g = both.groupby("profile_inference")

    fig, ax = plt.subplots(figsize=(6, 4))
    p50 = g["inference_ms"].median().reindex(order)
    p90 = g["inference_ms"].quantile(0.9).reindex(order)
    x = range(len(order))
    ax.bar([i - 0.18 for i in x], p50, width=0.36, color=ADAPT_C, label="p50")
    ax.bar([i + 0.18 for i in x], p90, width=0.36, color=FIXED_C, label="p90")
    ax.set_xticks(list(x), order)
    style(ax, "Inference latency per profile", "ms")
    ax.legend(frameon=False)
    fig.tight_layout()
    fig.savefig(f"{a.out}/inference_latency.png", dpi=160)

    fig, ax = plt.subplots(figsize=(6, 4))
    conf = both[both["detections"] > 0].groupby("profile_inference")["mean_conf"].mean().reindex(order)
    ax.bar(order, conf, color=ADAPT_C, width=0.55)
    for i, v in enumerate(conf):
        ax.text(i, v + 0.01, f"{v:.2f}", ha="center")
    style(ax, "Mean detection confidence per profile", "confidence")
    fig.tight_layout()
    fig.savefig(f"{a.out}/confidence.png", dpi=160)

    if both["battery_current_ua"].notna().any():
        fig, ax = plt.subplots(figsize=(6, 4))
        cur = g["battery_current_ua"].mean().reindex(order)
        ax.bar(order, cur, color=ADAPT_C, width=0.55)
        style(ax, "Mean battery current per profile (raw units — calibrate sign/units)", "raw")
        fig.tight_layout()
        fig.savefig(f"{a.out}/battery_current.png", dpi=160)

    # 6. Heat: thermal headroom over time (higher = closer to throttling)
    if fx["thermal_headroom"].notna().any() or ad["thermal_headroom"].notna().any():
        fig, ax = plt.subplots(figsize=(10, 4))
        for df, col, lab in ((fx, FIXED_C, "Fixed HIGH"), (ad, ADAPT_C, "LOOM adaptive")):
            h = df.groupby("sec")["thermal_headroom"].mean().dropna()
            ax.plot(h.index / 60, h.values, color=col, lw=1.6, label=lab)
        ax.axhline(1.0, color="#333", ls="--", lw=1)
        ax.text(0, 1.005, "1.0 ≈ throttling point", fontsize=9, color="#333")
        ax.set_xlabel("minutes")
        style(ax, "Thermal headroom over time (lower is cooler)", "headroom")
        ax.legend(frameon=False, loc="upper center", bbox_to_anchor=(0.5, -0.2), ncol=2)
        fig.tight_layout()
        fig.savefig(f"{a.out}/heat_over_time.png", dpi=160)

    def work(df):
        minutes = max(df["t_s"].iloc[-1] / 60, 1e-9)
        return len(df) / minutes

    def last_headroom(df):
        h = df["thermal_headroom"].dropna()
        return float(h.iloc[-60:].mean()) if len(h) else float("nan")

    print(f"Goal: >= {target:g} FPS")
    print(f"Fixed HIGH    : {pct_fx:5.1f}% of {len(fps_fx)} s at goal, mean FPS {fps_fx.mean():.1f}")
    print(f"LOOM adaptive : {pct_ad:5.1f}% of {len(fps_ad)} s at goal, mean FPS {fps_ad.mean():.1f}, "
          f"{len(ev)} Weaver decisions")
    print(f"Work done     : fixed {work(fx):.0f} frames/min vs adaptive {work(ad):.0f} frames/min")
    print(f"Ending heat   : fixed headroom {last_headroom(fx):.2f} vs adaptive {last_headroom(ad):.2f} (lower = cooler)")
    if fx["battery_current_ua"].notna().any():
        print(f"Battery (raw) : fixed {fx['battery_current_ua'].mean():.0f} vs adaptive {ad['battery_current_ua'].mean():.0f}")
    print(f"Charts written to {a.out}/")


if __name__ == "__main__":
    main()
