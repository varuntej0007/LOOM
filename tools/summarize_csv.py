"""Summarise a LOOM telemetry CSV pulled from the phone.

Usage:
    adb pull /sdcard/Android/data/com.loom.runtime/files/ ./csv/
    python tools/summarize_csv.py csv/files/loom_YYYYMMDD_HHMMSS.csv [--target 20]

Prints per-inference-profile latency, FPS, detections and confidence, the share of seconds
meeting the FPS target, and every logged event. Every number comes from the CSV.
"""
import argparse

import pandas as pd


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--target", type=float, default=20.0)
    args = ap.parse_args()

    df = pd.read_csv(args.csv)
    if df.empty:
        print("No rows.")
        return
    t0 = df["timestamp_ms"].iloc[0]
    df["t_s"] = (df["timestamp_ms"] - t0) / 1000.0
    duration = df["t_s"].iloc[-1]
    print(f"{args.csv}: {len(df)} frames over {duration:.1f} s "
          f"(frame_no {df['frame_no'].iloc[0]}..{df['frame_no'].iloc[-1]})")

    # Headline metric: % of whole seconds where processed FPS >= target.
    per_sec = df.groupby(df["t_s"].astype(int)).size()
    per_sec = per_sec.iloc[1:-1] if len(per_sec) > 2 else per_sec  # drop partial edge seconds
    met = (per_sec >= args.target).mean() * 100 if len(per_sec) else float("nan")
    print(f"Seconds at or above {args.target:g} FPS: {met:.1f}% of {len(per_sec)} full seconds\n")

    g = df.groupby(["profile_inference", "profile_capture"])
    summary = g.agg(
        frames=("frame_no", "count"),
        inference_p50=("inference_ms", "median"),
        inference_p90=("inference_ms", lambda s: s.quantile(0.9)),
        total_p50=("total_ms", "median"),
        total_p90=("total_ms", lambda s: s.quantile(0.9)),
        fps_mean=("fps_1s", "mean"),
        detections_mean=("detections", "mean"),
        conf_mean=("mean_conf", "mean"),
        battery_raw_mean=("battery_current_ua", "mean"),
    )
    with pd.option_context("display.width", 200, "display.max_columns", 20):
        print(summary.round(2))

    events = df[df["weaver_event"].notna()][["t_s", "frame_no", "weaver_event", "swap_gap_ms"]]
    print(f"\nEvents ({len(events)}):")
    for _, r in events.iterrows():
        gap = "" if pd.isna(r["swap_gap_ms"]) else f"  [swap gap {r['swap_gap_ms']:.2f} ms]"
        print(f"  t={r['t_s']:7.1f}s  #{int(r['frame_no'])}  {r['weaver_event']}{gap}")

    print("\nThermal status seen:", ", ".join(df["thermal_status"].dropna().unique()))


if __name__ == "__main__":
    main()
