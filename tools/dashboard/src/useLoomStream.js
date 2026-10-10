import { useEffect, useReducer } from "react";

// Live data from tools/live_dashboard.py, which reads the phone over USB (adb logcat).
// Message kinds: status · live (1/s) · event (text log) · transition (structured chiplet change
// phases: requested → built → swapped, or applied / failed) · reset (replay restarted).
export const WINDOW = 180; // seconds of history kept for the charts

const emptyHistory = () => ({ t: [], fps: [], goal: [], cap: [], heat: [], forecast: [], limit: [], battery: [] });

const initial = {
  status: { connected: false, note: "Connecting to the dashboard server…" },
  live: null,
  base: null,
  history: emptyHistory(),
  transitions: {}, // id -> { id, chiplet, from, to, reason, phases: {requested, built, swapped, applied, failed}, buildMs, gapMs, heatAt }
  order: [], // transition ids, newest first
  latestSwapId: null, // latest Inference transition (for the hot-swap timeline)
};

function push(arr, v) {
  const next = arr.length >= WINDOW ? arr.slice(arr.length - WINDOW + 1) : arr.slice();
  next.push(v);
  return next;
}

function reducer(state, msg) {
  switch (msg.kind) {
    case "status":
      return { ...state, status: msg.data };
    case "reset":
      return { ...initial, status: state.status };
    case "transition": {
      const x = msg.data;
      const prev = state.transitions[x.id];
      const tr = prev
        ? { ...prev, phases: { ...prev.phases } }
        : { id: x.id, chiplet: x.chiplet, from: x.from, to: x.to, reason: x.reason, phases: {}, heatAt: state.live?.heat ?? null };
      tr.phases[x.phase] = x.t;
      if (x.build_ms !== null && x.build_ms !== undefined) tr.buildMs = x.build_ms;
      if (x.swap_gap_ms !== null && x.swap_gap_ms !== undefined) tr.gapMs = x.swap_gap_ms;
      if (x.phase === "failed") tr.failReason = x.reason;
      const order = prev ? state.order : [x.id, ...state.order].slice(0, 60);
      const transitions = { ...state.transitions, [x.id]: tr };
      // keep the map small
      if (!prev && Object.keys(transitions).length > 80) {
        const keep = new Set(order);
        Object.keys(transitions).forEach((k) => { if (!keep.has(Number(k))) delete transitions[k]; });
      }
      return {
        ...state,
        transitions,
        order,
        latestSwapId: x.chiplet === "Inference" && x.from !== "none" ? x.id : state.latestSwapId,
      };
    }
    case "live": {
      const d = msg.data;
      const h = state.history;
      const goal = d.effective_goal ?? d.goal;
      const mA = d.battery_ua === null ? null : Math.abs(d.battery_ua) / 1000;
      return {
        ...state,
        live: d,
        base: state.base ?? { received: d.received, paced: d.paced, skipped: d.skipped, not_captured: d.not_captured ?? 0 },
        history: {
          t: push(h.t, d.t),
          fps: push(h.fps, d.fps),
          goal: push(h.goal, goal),
          cap: push(h.cap, d.capacity_fps),
          heat: push(h.heat, d.heat),
          forecast: push(h.forecast, d.forecast),
          limit: push(h.limit, d.heat_limit),
          battery: push(h.battery, mA),
        },
      };
    }
    default:
      return state;
  }
}

export function useLoomStream() {
  const [state, dispatch] = useReducer(reducer, initial);
  useEffect(() => {
    const es = new EventSource("/stream");
    es.onmessage = (m) => dispatch(JSON.parse(m.data));
    es.onerror = () =>
      dispatch({ kind: "status", data: { connected: false, note: "Dashboard server stopped — run live_dashboard.bat again" } });
    return () => es.close();
  }, []);
  return state;
}

/** Heat value recorded closest to time t (ms), or null. */
export function heatAt(history, t) {
  let best = null, bestDt = Infinity;
  for (let i = 0; i < history.t.length; i++) {
    const dt = Math.abs(history.t[i] - t);
    if (dt < bestDt && history.heat[i] !== null) { bestDt = dt; best = history.heat[i]; }
  }
  return bestDt < 2500 ? best : null;
}

export async function sendCommand(cmd, value) {
  const r = await fetch("/cmd", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ cmd, value }),
  });
  return r.json();
}
