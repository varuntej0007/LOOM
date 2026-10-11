import { useEffect, useState } from "react";
import { useLoomStream, sendCommand, heatAt } from "./useLoomStream.js";
import LineChart from "./LineChart.jsx";

const fmt = (v, d = 1) => (v === null || v === undefined || Number.isNaN(v) ? "—" : Number(v).toFixed(d));
const clock = (ms) => new Date(ms).toTimeString().slice(0, 8);
const LEVELS = { HIGH: 3, BALANCED: 2, ECO: 1 };

// Re-render periodically so "in progress" timers and fades stay current.
function useNow(ms = 200) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), ms); return () => clearInterval(t); }, [ms]);
  return now;
}

/* ---------- icons (simple line glyphs) ---------- */
const Icon = ({ name }) => {
  const p = {
    Capture: <><rect x="3" y="7" width="18" height="13" rx="2" /><path d="M8 7l2-3h4l2 3" /><circle cx="12" cy="13.5" r="3.5" /></>,
    Preprocess: <><path d="M4 12a8 8 0 0 1 13.7-5.6L20 9" /><path d="M20 4v5h-5" /><path d="M20 12a8 8 0 0 1-13.7 5.6L4 15" /><path d="M4 20v-5h5" /></>,
    Inference: <><rect x="6" y="6" width="12" height="12" rx="2" /><path d="M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4" /><rect x="9.5" y="9.5" width="5" height="5" rx="1" /></>,
    Narrator: <><path d="M4 9v6h4l5 4V5L8 9H4z" /><path d="M16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12" /></>,
    Display: <><rect x="3" y="4" width="18" height="12" rx="2" /><path d="M8 20h8M12 16v4" /></>,
  }[name];
  return <svg className="icon" viewBox="0 0 24 24" aria-hidden="true">{p}</svg>;
};

const CHIPLETS = [
  ["Capture", "Frame pacing"],
  ["Preprocess", "Rotate upright"],
  ["Inference", "Object detection"],
  ["Narrator", "Speech output"],
  ["Display", "Boxes & screen"],
];

/* ---------- top bar ---------- */
function TopBar({ d, status }) {
  const sim = status.note.startsWith("SIMULATED");
  const live = status.connected && status.note.startsWith("Live from the phone");
  return (
    <header className="topbar">
      <div className="brand">
        <span className="logo"><img src="loom-logo.png" alt="LOOM" width="118" height="44" /></span>
        <span className="sub">Runtime Monitor</span>
      </div>
      <div className="chips">
        {d && <span className="chip neutral">{d.device}</span>}
        {d && <span className={`chip ${d.airplane ? "ok" : "warn"}`}>{d.airplane ? "Offline · airplane mode" : "Airplane mode off"}</span>}
        {d && <span className={`chip ${d.mode === "ADAPTIVE" ? "brand" : "warn"}`}>{d.mode === "ADAPTIVE" ? "LOOM adaptive" : d.mode === "FIXED" ? "Fixed · ordinary app" : "Manual"}</span>}
        {d && d.stress !== "OFF" && <span className="chip bad">Stress · {d.stress}</span>}
        {d?.battery_saver && <span className="chip warn">Battery saver</span>}
        {d?.run && <span className="chip bad">{d.run}</span>}
      </div>
      <div className={`conn ${live ? "live" : sim ? "sim" : "off"}`}>
        <i />{live ? status.note : sim ? "Simulated session" : "Not connected"}
      </div>
    </header>
  );
}

function Banner({ status }) {
  if (status.connected && status.note.startsWith("Live from the phone")) return null;
  const sim = status.note.startsWith("SIMULATED");
  return <div className={`banner ${sim ? "sim" : ""}`}>{status.note}</div>;
}

/* ---------- KPIs ---------- */
function Kpi({ label, value, unit, tone, bar, marker, sub }) {
  return (
    <div className="card kpi">
      <div className="label">{label}</div>
      <div className={`value ${tone || ""}`}>{value}{unit && <small>{unit}</small>}</div>
      {bar !== undefined && (
        <div className={`meter ${tone || ""}`}>
          <i style={{ width: `${Math.max(0, Math.min(100, bar))}%` }} />
          {marker !== undefined && <u style={{ left: `${Math.max(0, Math.min(100, marker))}%` }} />}
        </div>
      )}
      <div className="sub">{sub}</div>
    </div>
  );
}

/* Real temperatures (°C) read from the phone's sensors by the laptop (adb). */
function tempLine(t) {
  if (!t) return null;
  const parts = [["battery", "Battery"], ["skin", "Skin"], ["cpu", "CPU"], ["gpu", "GPU"]]
    .filter(([k]) => typeof t[k] === "number")
    .map(([k, label]) => `${label} ${t[k].toFixed(1)} °C`);
  return parts.length ? parts.join(" · ") : null;
}

function Kpis({ d, base }) {
  if (!d) return <section className="kpis"><div className="card kpi skeleton" /><div className="card kpi skeleton" /><div className="card kpi skeleton" /><div className="card kpi skeleton" /></section>;
  const goal = d.effective_goal ?? d.goal;
  const ok = d.fps >= goal - 0.05;
  const fMax = Math.max(30, d.camera_fps || 30);
  const hot = d.heat !== null && d.heat_limit !== null && d.heat > d.heat_limit;
  const mA = d.battery_ua === null ? null : Math.abs(d.battery_ua) / 1000;
  // Frames the camera never captured (sensor slowed by LOOM) count as avoided work too.
  const nc = (d.not_captured ?? 0) - (base.not_captured ?? 0);
  const rec = d.received - base.received + nc;
  const avoided = d.paced - base.paced + (d.skipped - base.skipped) + nc;
  const pct = rec > 0 ? (avoided / rec) * 100 : 0;
  return (
    <section className="kpis" aria-label="Key figures">
      <Kpi label="Processed speed" value={fmt(d.fps)} unit="FPS" tone={ok ? "good" : "warn"}
        bar={(d.fps / fMax) * 100} marker={(goal / fMax) * 100}
        sub={`Goal ≥ ${fmt(goal, 0)}${d.battery_saver ? " (battery saver)" : ""} · camera ${fmt(d.camera_fps, 0)}${d.camera_rate && d.camera_rate < 30 ? ` (slowed to ${d.camera_rate})` : ""} · capacity ${fmt(d.capacity_fps, 0)}`} />
      <Kpi label="Heat · thermal headroom" value={fmt(d.heat, 2)} tone={hot ? "bad" : "good"}
        bar={d.heat === null ? 0 : d.heat * 100} marker={d.heat_limit === null ? undefined : d.heat_limit * 100}
        sub={<>
          {`Limit ${d.heat_limit === null ? "off" : fmt(d.heat_limit, 2)} · now ${fmt(d.headroom, 2)} → ${fmt(d.forecast, 2)} in 10 s`}
          {tempLine(d.temps) && <span className="temps">{tempLine(d.temps)}</span>}
        </>} />
      <Kpi label="Battery current" value={fmt(mA, 0)} unit="mA"
        sub={`${d.battery_pct >= 0 ? d.battery_pct + "%" : "—"} · ${d.charging ? "charging — unplug for honest numbers" : "on battery"}`} />
      <Kpi label="AI work avoided" value={fmt(pct, 0)} unit="%" tone="brand" bar={pct}
        sub={`${avoided.toLocaleString()} of ${rec.toLocaleString()} frames not needed${nc > 0 ? ` · ${nc.toLocaleString()} never captured` : ""}`} />
    </section>
  );
}

/* ---------- chiplets ---------- */
function activeTransition(state, name, now) {
  for (const id of state.order) {
    const t = state.transitions[id];
    if (!t || t.chiplet !== name || t.reason === "initial") continue;
    const p = t.phases;
    const inProgress = p.requested && !p.swapped && !p.applied && !p.failed;
    const end = p.swapped || p.applied || p.failed || p.requested;
    if (inProgress && now - p.requested < 10000) return { t, stage: "building" };
    if (now - end < 6000) return { t, stage: p.failed ? "failed" : "done" };
    return null;
  }
  return null;
}

const PLAN_ORDER = ["Display", "Inference", "Capture", "Narrator"];
function planLabel(plan, name) {
  if (!plan?.text) return null;
  const i = PLAN_ORDER.indexOf(name);
  return i < 0 ? null : plan.text.split(" · ")[i] ?? null;
}

/* The profile set chosen offline by QAOA for the current goal and heat band. */
function QuantumPlan({ d }) {
  if (!d) return null;
  if (!d.policy) return null;
  const p = d.plan;
  const band = p ? `heat ${p.heat_lo.toFixed(2)}–${Math.min(p.heat_hi, 1).toFixed(2)}` : null;
  return (
    <div className="qplan">
      <span className="tag">Quantum plan</span>
      {p ? (
        <>
          <span className="qband">{band} · goal {p.goal} FPS</span>
          <span className="qtext">{p.text}</span>
          <span className="qmeta">
            {p.predicted_mA} mA predicted{p.reachable ? "" : " · coolest possible"}
            {p.p_optimal != null && <> · found by QAOA (in {Math.max(1, Math.round(p.p_optimal * 100))}% of measurements) · brute-force checked</>}
          </span>
        </>
      ) : (
        <span className="qmeta">{d.mode === "ADAPTIVE" ? "heat limit off — reflexes only" : "inactive outside LOOM adaptive mode"}</span>
      )}
    </div>
  );
}

function ChipletCard({ name, role, c, tr, now, target, targetLabel, cameraFps }) {
  const level = c?.level ?? "—";
  const q = LEVELS[level];
  const cls = tr ? (tr.stage === "building" ? "is-building" : tr.stage === "failed" ? "is-failed" : "is-changed") : "";
  return (
    <article className={`card chiplet ${cls}`}>
      <header>
        <span className="ic"><Icon name={name} /></span>
        <div>
          <h3>{name}</h3>
          <p>{role}</p>
        </div>
        <span className={`lvl lvl-${level}`}>{level === "FIXED" ? "Always on" : level}</span>
      </header>
      <div className="profile">{c?.profile ?? "—"}</div>
      {target && (
        <div className={`target ${target === level ? "on" : ""}`}>
          {target === level ? "✓ at quantum plan" : <>plan: {targetLabel ?? target}</>}
        </div>
      )}
      <div className="row">
        {/* Capture's per-frame cost is only a keep/skip decision (microseconds); its real work is in
            the camera hardware, so show the frame rate it delivers instead of milliseconds. */}
        {name === "Capture" && cameraFps != null
          ? <span className="ms">{fmt(cameraFps, 0)}<small>frames / s from camera</small></span>
          : <span className="ms">{fmt(c?.ms, 1)}<small>ms / frame</small></span>}
        <span className="quality" title="Quality level">
          {[1, 2, 3].map((i) => <i key={i} className={q && i <= q ? "on" : ""} />)}
        </span>
      </div>
      {tr && (
        <div className={`change ${tr.stage}`}>
          <div className="fromto"><span>{tr.t.from}</span><b>→</b><span>{tr.t.to}</span></div>
          <div className="how">
            {tr.stage === "building" && (name === "Capture"
              ? <>Asking the camera sensor · {Math.round(now - tr.t.phases.requested)} ms · preview keeps running</>
              : <>Building in background · {Math.round(now - tr.t.phases.requested)} ms · old one still serving</>)}
            {tr.stage === "done" && tr.t.phases.swapped && <>Swapped between frames · build {fmt(tr.t.buildMs, 0)} ms · gap {fmt(tr.t.gapMs, 2)} ms</>}
            {tr.stage === "done" && tr.t.phases.applied && tr.t.phases.requested && <>Camera sensor switched in {Math.round(tr.t.phases.applied - tr.t.phases.requested)} ms · frames never captured cost no heat</>}
            {tr.stage === "done" && tr.t.phases.applied && !tr.t.phases.requested && <>Applied at the next frame</>}
            {tr.stage === "failed" && <>Kept the old profile: {tr.t.failReason}</>}
          </div>
        </div>
      )}
    </article>
  );
}

function ChipletBoard({ state, now }) {
  const d = state.live;
  const byName = Object.fromEntries((d?.chiplets ?? []).map((c) => [c.name, c]));
  return (
    <section className="card board" aria-label="Virtual chiplets">
      <div className="board-head">
        <h2>Virtual chiplets</h2>
        <span className="hint">One pipeline, five independently tunable blocks. A block glows while the Weaver changes it.</span>
      </div>
      <div className="weaver">
        <span className="tag">Weaver · control plane</span>
        <span className="wstatus">{d?.status ?? "—"}</span>
        <span className="wreason">{d?.decision ?? "Waiting for data…"}</span>
        <span className="wcount">{d?.decisions ?? 0} decisions</span>
      </div>
      <QuantumPlan d={d} />
      <div className="pipeline">
        {CHIPLETS.map(([name, role]) => (
          <ChipletCard key={name} name={name} role={role} c={byName[name]} tr={activeTransition(state, name, now)} now={now}
            cameraFps={d?.camera_fps ?? null}
            target={d?.plan?.levels?.[name]} targetLabel={planLabel(d?.plan, name)} />
        ))}
      </div>
    </section>
  );
}

/* ---------- hot-swap timeline ---------- */
function HotSwap({ state, now }) {
  const t = state.latestSwapId ? state.transitions[state.latestSwapId] : null;
  if (!t) {
    return (
      <section className="card panel">
        <h2>Hot-swap, step by step</h2>
        <p className="empty">The next time the Weaver changes the detector, this shows how LOOM swaps models without stopping: the new one is built in the background while the old one keeps serving, then they trade places between two frames.</p>
      </section>
    );
  }
  const p = t.phases;
  const t0 = p.requested ?? p.built ?? p.swapped;
  const tb = p.built ?? (p.swapped ? p.swapped : null);
  const ts = p.swapped ?? null;
  const tEnd = ts ?? now;
  const span = Math.max(400, (tEnd - t0) * 1.3);
  const pct = (x) => `${Math.max(0, Math.min(100, ((x - t0) / span) * 100))}%`;
  const buildEnd = tb ?? now;
  const steps = [
    ["Requested", p.requested, t.reason],
    ["Built in the background", p.built, p.built ? `${fmt(t.buildMs, 0)} ms on a separate thread — ${t.from} kept serving every frame` : "building…"],
    ["Swapped at a frame boundary", p.swapped, p.swapped ? `pointer swap took ${fmt(t.gapMs, 2)} ms — no frame dropped, counter never reset` : "waiting for the next frame"],
    ["Old model released", p.swapped, p.swapped ? `${t.from} closed only after nothing could use it` : "—"],
  ];
  return (
    <section className="card panel">
      <h2>Hot-swap, step by step <span className="when">{clock(t0)}</span></h2>
      <div className="lanes">
        <div className="lane">
          <span className="lname">{t.from}</span>
          <div className="track">
            <i className="serving" style={{ left: 0, width: ts ? pct(ts) : "100%" }} />
            {ts && <i className="released" style={{ left: pct(ts) }} />}
          </div>
        </div>
        <div className="lane">
          <span className="lname">{t.to}</span>
          <div className="track">
            <i className="building" style={{ left: 0, width: `calc(${pct(buildEnd)} - 0%)` }} />
            {tb && ts && <i className="ready" style={{ left: pct(tb), width: `calc(${pct(ts)} - ${pct(tb)})` }} />}
            {ts && <i className="serving" style={{ left: pct(ts), right: 0 }} />}
          </div>
        </div>
        <div className="lane axis">
          <span className="lname" />
          <div className="track ticks"><span style={{ left: 0 }}>0 ms</span><span style={{ left: "auto", right: 0 }}>{Math.round(span)} ms</span></div>
        </div>
        <div className="key"><span className="k serving">serving frames</span><span className="k building">building</span><span className="k ready">ready, waiting for frame boundary</span></div>
      </div>
      <ol className="steps">
        {steps.map(([title, at, detail], i) => (
          <li key={title} className={at ? "done" : i === 1 && p.requested && !p.built ? "now" : ""}>
            <b>{title}</b>
            <span>{detail}</span>
            {at && <time>+{Math.round(at - t0)} ms</time>}
          </li>
        ))}
      </ol>
    </section>
  );
}

/* ---------- cooling ---------- */
function heatColor(h) {
  if (h === null || h === undefined) return "#cbd5e1";
  const stops = [[0.4, [59, 130, 246]], [0.65, [20, 184, 166]], [0.8, [245, 158, 11]], [0.95, [239, 68, 68]]];
  if (h <= stops[0][0]) return `rgb(${stops[0][1]})`;
  for (let i = 1; i < stops.length; i++) {
    if (h <= stops[i][0]) {
      const [a, ca] = stops[i - 1], [b, cb] = stops[i];
      const f = (h - a) / (b - a);
      return `rgb(${ca.map((v, k) => Math.round(v + (cb[k] - v) * f)).join(",")})`;
    }
  }
  return `rgb(${stops[stops.length - 1][1]})`;
}

function Cooling({ state, now }) {
  const d = state.live;
  const h = state.history;
  const heat = d?.heat ?? null;
  const trend = d && d.forecast !== null && d.headroom !== null ? d.forecast - d.headroom : null;
  const cooling = trend !== null && trend < -0.005;
  const warming = trend !== null && trend > 0.005;
  const display = d?.chiplets?.find((c) => c.name === "Display");
  const actions = state.order
    .map((id) => state.transitions[id])
    .filter((t) => t && (t.reason.startsWith("heat") || t.reason.startsWith("quantum")))
    .map((t) => {
      const at = t.phases.swapped || t.phases.applied || t.phases.requested;
      const before = heatAt(h, at) ?? t.heatAt;
      const after = now - at >= 30000 ? heatAt(h, at + 30000) : null;
      return { t, at, before, after };
    })
    // only decisions this page has heat history for (a tab opened mid-session can't know older ones)
    .filter((a) => a.before !== null && (now - a.at < 30000 || a.after !== null))
    .slice(0, 5);
  const fill = heatColor(heat);
  return (
    <section className="card panel cooling">
      <h2>Cooling</h2>
      <div className="cool-top">
        <svg className="phone" viewBox="0 0 120 220" role="img" aria-label={`Phone heat ${fmt(heat, 2)}`}>
          <defs>
            <linearGradient id="heatgrad" x1="0" y1="1" x2="0" y2="0">
              <stop offset="0%" stopColor={fill} stopOpacity="0.95" />
              <stop offset="100%" stopColor={fill} stopOpacity="0.25" />
            </linearGradient>
          </defs>
          <rect x="6" y="6" width="108" height="208" rx="20" className="body" />
          <rect x="14" y="22" width="92" height="176" rx="10" fill="url(#heatgrad)" className="screen" />
          <rect x="48" y="12" width="24" height="4" rx="2" className="speaker" />
          <text x="60" y="108" textAnchor="middle" className="hv">{fmt(heat, 2)}</text>
          <text x="60" y="128" textAnchor="middle" className="hl">headroom</text>
          {cooling && [0, 1, 2].map((i) => <path key={i} className="flow down" style={{ animationDelay: `${i * 0.4}s` }} d={`M${40 + i * 20} 150 l0 26 m-6 -6 l6 6 l6 -6`} />)}
          {warming && [0, 1, 2].map((i) => <path key={i} className="flow up" style={{ animationDelay: `${i * 0.4}s` }} d={`M${40 + i * 20} 176 l0 -26 m-6 6 l6 -6 l6 6`} />)}
        </svg>
        <div className="cool-facts">
          <div className={`trend ${cooling ? "good" : warming ? "bad" : ""}`}>
            {trend === null ? "Trend —" : cooling ? `Cooling ${fmt(trend, 2)} in 10 s` : warming ? `Warming +${fmt(trend, 2)} in 10 s` : "Steady"}
          </div>
          <dl>
            <div><dt>Limit</dt><dd>{d?.heat_limit === null || !d ? "off" : fmt(d.heat_limit, 2)}</dd></div>
            <div><dt>Margin</dt><dd>{d && d.heat_limit !== null && heat !== null ? `${heat > d.heat_limit ? "+" : "−"}${fmt(Math.abs(heat - d.heat_limit), 2)}` : "—"}</dd></div>
            <div><dt>Screen</dt><dd>{display ? display.profile.replace("boxes · ", "") : "—"}</dd></div>
            <div><dt>Android</dt><dd>{d?.thermal_status ?? "—"}</dd></div>
            {typeof d?.temps?.battery === "number" && <div><dt>Battery temp</dt><dd>{d.temps.battery.toFixed(1)} °C</dd></div>}
            {typeof d?.temps?.skin === "number" && <div><dt>Skin temp</dt><dd>{d.temps.skin.toFixed(1)} °C</dd></div>}
          </dl>
        </div>
      </div>
      <h3 className="mini">Heat decisions · heat at the decision → 30 s later</h3>
      <ul className="effects">
        {actions.length === 0 && <li className="empty">None yet. When heat passes the limit, each step LOOM takes appears here with the heat 30 seconds later.</li>}
        {actions.map(({ t, at, before, after }) => {
          const delta = after !== null && before !== null ? after - before : null;
          return (
            <li key={t.id}>
              <span className="what"><b>{t.chiplet}</b> {t.from} → {t.to}</span>
              <span className="eff">
                {fmt(before, 2)} → {after === null ? <em>measuring… {Math.max(0, Math.ceil((30000 - (now - at)) / 1000))} s</em> : fmt(after, 2)}
                {delta !== null && <b className={delta < 0 ? "good" : "bad"}>{delta < 0 ? "" : "+"}{fmt(delta, 2)}</b>}
              </span>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/* ---------- decision log ---------- */
function DecisionLog({ state }) {
  const rows = state.order.map((id) => state.transitions[id]).filter((t) => t && t.reason !== "initial");
  return (
    <section className="card panel">
      <h2>Decision log <span className="when">{rows.length}</span></h2>
      <ul className="log">
        {rows.length === 0 && <li className="empty">Every change the Weaver makes is listed here with its reason.</li>}
        {rows.map((t) => {
          const at = t.phases.requested || t.phases.applied || t.phases.swapped;
          const kind = t.reason.startsWith("heat") ? "heat" : t.reason.startsWith("quantum") ? "plan" : t.reason.startsWith("FPS") ? "fps" : t.reason.startsWith("capacity") ? "up" : "other";
          return (
            <li key={t.id} className={kind}>
              <div className="l1">
                <time>{clock(at)}</time>
                <span className="who">{t.chiplet}</span>
                <span className="ft">{t.from} → {t.to}</span>
              </div>
              <div className="l2">{t.reason}{t.phases.swapped && ` · build ${fmt(t.buildMs, 0)} ms · swap gap ${fmt(t.gapMs, 2)} ms`}</div>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/* ---------- charts ---------- */
function Charts({ state }) {
  const h = state.history;
  const markers = state.order
    .map((id) => state.transitions[id])
    .filter((t) => t && t.reason !== "initial")
    .map((t) => ({
      t: t.phases.swapped || t.phases.applied || t.phases.requested,
      color: t.reason.startsWith("heat") ? "--amber" : t.reason.startsWith("quantum") ? "--violet" : t.reason.startsWith("FPS") ? "--blue" : "--muted",
    }));
  const fMax = Math.max(30, ...h.fps.filter((v) => v !== null), ...h.cap.filter((v) => v !== null && v < 200));
  const top = Math.ceil(fMax / 10) * 10;
  const bTop = Math.max(500, Math.ceil(Math.max(0, ...h.battery.filter((v) => v !== null)) / 250) * 250);
  return (
    <section className="charts">
      <div className="card"><LineChart title="Speed" unit="FPS" lo={0} hi={top} ticks={[0, top / 2, top]} times={h.t} markers={markers}
        legend={[["processed", "--brand"], ["goal", "--text", true], ["capacity", "--muted", true]]}
        series={[
          { data: h.cap, color: "--muted", width: 1.2, dash: [2, 3] },
          { data: h.goal, color: "--text", width: 1.2, dash: [6, 4] },
          { data: h.fps, color: "--brand", width: 2.2, dot: true, fill: "--brand-wash" },
        ]} /></div>
      <div className="card"><LineChart title="Heat" unit="headroom" lo={0.3} hi={1.1} ticks={[0.4, 0.6, 0.8, 1.0]} times={h.t} markers={markers}
        legend={[["heat", "--red"], ["10 s forecast", "--amber", true], ["limit", "--text", true]]}
        series={[
          { data: h.limit, color: "--text", width: 1.2, dash: [6, 4] },
          { data: h.forecast, color: "--amber", width: 1.4, dash: [3, 3] },
          { data: h.heat, color: "--red", width: 2.2, dot: true, fill: "--red-wash" },
        ]} /></div>
      <div className="card"><LineChart title="Battery current" unit="mA" lo={0} hi={bTop} ticks={[0, bTop / 2, bTop]} times={h.t} markers={markers}
        legend={[["draw", "--blue"], ["heat decision", "--amber", true], ["speed decision", "--blue", true]]}
        series={[{ data: h.battery, color: "--blue", width: 2.2, dot: true, fill: "--blue-wash" }]} /></div>
    </section>
  );
}

/* ---------- controls ---------- */
const GROUPS = [
  ["Stress", "stress", [["Off", "off"], ["GPU", "gpu"], ["CPU", "cpu"]]],
  ["Mode", "mode", [["LOOM adaptive", "adaptive"], ["Fixed (ordinary app)", "fixed"]]],
  ["Goal", "goal", [["10 FPS", "10"], ["15 FPS", "15"], ["20 FPS", "20"]]],
  ["Heat limit", "heat", [["Off", "off"], ["0.75", "0.75"], ["0.80", "0.80"], ["0.85", "0.85"]]],
];

function Controls({ d, sim }) {
  const [toast, setToast] = useState("");
  const [sel, setSel] = useState({});
  const send = async (cmd, value) => {
    setToast(`Sending ${cmd} ${value}…`);
    try {
      const j = await sendCommand(cmd, value);
      setToast(j.ok ? `Sent: ${cmd} ${value}` : `Not sent: ${j.error || j.out || "the phone did not answer"}`);
      if (j.ok) setSel((s) => ({ ...s, [cmd]: value }));
    } catch {
      setToast("Not sent: the dashboard server is not running");
    }
  };
  return (
    <section className="card controls" aria-label="Demo controls">
      <h2>Demo controls <span className="hint">{sim ? "available when a phone is connected" : "sent to the phone over USB"}</span></h2>
      <div className="groups">
        {GROUPS.map(([title, cmd, opts]) => (
          <div className="group" key={cmd}>
            <span>{title}</span>
            <div className="seg">
              {opts.map(([label, value]) => (
                <button key={value} className={sel[cmd] === value ? "sel" : ""} onClick={() => send(cmd, value)}>{label}</button>
              ))}
            </div>
          </div>
        ))}
        <div className="group"><span>Benchmark</span>
          <button className="solo" onClick={() => send("run", "toggle")}>{d?.run ? "Stop run" : "Start 15-min run"}</button>
        </div>
        {d?.app === "studio" && (
          <div className="group"><span>Guard (any app)</span>
            <div className="seg">
              {[["On", "on"], ["Off", "off"], ["0.70", "0.70"], ["0.75", "0.75"], ["0.80", "0.80"]].map(([label, value]) => (
                <button key={value} className={sel.guard === value ? "sel" : ""} onClick={() => send("guard", value)}>{label}</button>
              ))}
            </div>
          </div>
        )}
        <div className="group"><span>Phone</span>
          <button className="solo" onClick={() => send("overlay", "toggle")}>Engineering view</button>
        </div>
      </div>
      <div className="toast" role="status">{toast}</div>
    </section>
  );
}

/* LOOM Guard (Studio): phone-wide cooling while any app runs. */
function GuardPanel({ d }) {
  const g = d?.guard;
  if (!g) return null;
  const perms = g.perms || {};
  const missing = [["overlay", "heat pill"], ["write_settings", "brightness"], ["secure", "60 Hz + saver"], ["usage", "front app"]]
    .filter(([k]) => !perms[k] && !(k === "write_settings" && perms.secure)).map(([, l]) => l);
  return (
    <section className="card guard" aria-label="LOOM Guard">
      <span className="tag">LOOM Guard · any app</span>
      <span className="gstate">{!g.on ? "off" : g.levers.length ? `cooling: ${g.levers.join(" · ")}` : "watching · nothing to cool"}</span>
      <span className="gmeta">
        limit {Number(g.limit).toFixed(2)}{g.fg_app ? ` · front app ${g.fg_app}` : ""} · {g.decisions} decisions
        {missing.length ? ` · missing: ${missing.join(", ")}` : ""}
      </span>
      {g.on && g.last && g.last !== "—" && <span className="glast">{g.last}</span>}
    </section>
  );
}

export default function App() {
  const s = useLoomStream();
  const now = useNow(200);
  const sim = s.status.note.startsWith("SIMULATED");
  return (
    <>
      <TopBar d={s.live} status={s.status} />
      <main className="wrap">
        <Banner status={s.status} />
        <Kpis d={s.live} base={s.base} />
        <GuardPanel d={s.live} />
        <ChipletBoard state={s} now={now} />
        <section className="trio">
          <HotSwap state={s} now={now} />
          <Cooling state={s} now={now} />
          <DecisionLog state={s} />
        </section>
        <Charts state={s} />
        <Controls d={s.live} sim={sim} />
      </main>
    </>
  );
}
