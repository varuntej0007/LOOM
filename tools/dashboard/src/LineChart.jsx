import { useEffect, useRef } from "react";
import { WINDOW } from "./useLoomStream.js";

const cssVar = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

/**
 * Plain canvas line chart (no chart library, so the page works fully offline).
 * `markers` are vertical lines at Weaver decisions: [{ t, color }], placed using `times`.
 */
export default function LineChart({ title, series, lo, hi, ticks, unit, legend, times = [], markers = [], height = 168 }) {
  const ref = useRef(null);

  useEffect(() => {
    const draw = () => {
      const cv = ref.current;
      if (!cv) return;
      const r = cv.getBoundingClientRect();
      const dpr = window.devicePixelRatio || 1;
      cv.width = Math.round(r.width * dpr);
      cv.height = Math.round(r.height * dpr);
      const g = cv.getContext("2d");
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      const W = r.width, H = r.height, L = 44, R = 10, T = 10, B = 18;
      const pw = W - L - R, ph = H - T - B;
      const y = (v) => T + ph - ((Math.max(lo, Math.min(hi, v)) - lo) / (hi - lo)) * ph;
      const xAt = (i, n) => L + ((i + (WINDOW - n)) / (WINDOW - 1)) * pw;

      g.clearRect(0, 0, W, H);
      g.font = `11px ${cssVar("--font")}`;
      g.lineWidth = 1;
      ticks.forEach((t) => {
        g.strokeStyle = cssVar("--grid");
        g.beginPath(); g.moveTo(L, y(t) + 0.5); g.lineTo(L + pw, y(t) + 0.5); g.stroke();
        g.fillStyle = cssVar("--muted");
        g.textAlign = "right";
        g.fillText(String(t), L - 8, y(t) + 4);
      });
      g.textAlign = "left";

      // Weaver decision markers
      const n = times.length;
      if (n > 1) {
        markers.forEach((m) => {
          if (m.t < times[0] || m.t > times[n - 1] + 1000) return;
          let i = 0;
          while (i < n - 1 && times[i + 1] <= m.t) i++;
          const frac = i < n - 1 ? (m.t - times[i]) / Math.max(1, times[i + 1] - times[i]) : 0;
          const x = xAt(i + Math.min(1, Math.max(0, frac)), n);
          g.strokeStyle = cssVar(m.color);
          g.globalAlpha = 0.55;
          g.setLineDash([2, 3]);
          g.beginPath(); g.moveTo(x, T); g.lineTo(x, T + ph); g.stroke();
          g.setLineDash([]);
          g.globalAlpha = 1;
        });
      }

      series.forEach((s) => {
        const color = cssVar(s.color);
        const len = s.data.length;
        if (s.fill) {
          g.fillStyle = cssVar(s.fill);
          g.beginPath();
          let started = false, lastX = 0, firstX = 0;
          s.data.forEach((v, i) => {
            if (v === null || v === undefined || Number.isNaN(v)) return;
            const x = xAt(i, len);
            if (!started) { g.moveTo(x, T + ph); g.lineTo(x, y(v)); firstX = x; started = true; } else g.lineTo(x, y(v));
            lastX = x;
          });
          if (started) { g.lineTo(lastX, T + ph); g.lineTo(firstX, T + ph); g.fill(); }
        }
        g.strokeStyle = color;
        g.lineWidth = s.width || 2;
        g.setLineDash(s.dash || []);
        g.lineJoin = "round";
        g.beginPath();
        let started = false;
        s.data.forEach((v, i) => {
          if (v === null || v === undefined || Number.isNaN(v)) { started = false; return; }
          const x = xAt(i, len);
          if (!started) { g.moveTo(x, y(v)); started = true; } else g.lineTo(x, y(v));
        });
        g.stroke();
        g.setLineDash([]);
        if (s.dot) {
          const last = [...s.data].reverse().find((v) => v !== null && v !== undefined && !Number.isNaN(v));
          if (last !== undefined) {
            g.fillStyle = cssVar("--surface");
            g.beginPath(); g.arc(L + pw, y(last), 4.5, 0, Math.PI * 2); g.fill();
            g.fillStyle = color;
            g.beginPath(); g.arc(L + pw, y(last), 3, 0, Math.PI * 2); g.fill();
          }
        }
      });
    };
    draw();
    window.addEventListener("resize", draw);
    return () => window.removeEventListener("resize", draw);
  });

  return (
    <figure className="chart">
      <figcaption>
        <span className="chart-title">{title}<span className="unit">{unit}</span></span>
        <span className="legend">
          {legend.map(([label, color, dashed]) => (
            <span key={label} className={dashed ? "dashed" : ""} style={{ "--c": `var(${color})` }}>{label}</span>
          ))}
        </span>
      </figcaption>
      <canvas ref={ref} style={{ height }} />
    </figure>
  );
}
