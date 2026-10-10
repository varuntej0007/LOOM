package com.loom.studio.telemetry

import android.content.Context
import android.util.Log
import com.loom.studio.core.Mode
import com.loom.studio.core.TaskKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** One chiplet as the dashboard draws it: same fields as app/'s LOOM_LIVE "chiplets" array. */
data class ChipletLive(
    val name: String,
    val profile: String,
    val ms: Double,
    /** HIGH / BALANCED / ECO, or "FIXED" for a stage with no profiles. */
    val level: String,
)

/**
 * What the running model + Weaver report. Written by the pipeline / Weaver (from M2/M4),
 * read once per second by [TelemetryBus.emitLive]. In M0 nothing runs, so it stays idle.
 */
data class RunContext(
    val model: String? = null,
    val task: TaskKind? = null,
    /** "fps" | "tokens_per_s" | "latency_ms" (brief §8). */
    val goalMetric: String? = null,
    val goalValue: Double? = null,
    val metricValue: Double? = null,
    val mode: Mode = Mode.ADAPTIVE,
    val status: String = "idle — no model running",
    val decision: String = "—",
    val decisions: Int = 0,
    val lastSwap: String = "—",
    val heatLimit: Float? = 0.80f,
    val policySource: String? = null,
    val units: Long = 0,
    val p50Ms: Double = 0.0,
    val p90Ms: Double = 0.0,
    val chiplets: List<ChipletLive> = emptyList(),
    // --- pipeline counters (same meaning as app/) ---
    val fps: Double = 0.0,
    val cameraFps: Double = 0.0,
    val cameraRate: Int = 0,
    val frameCap: Int = 0,
    val notCaptured: Long = 0,
    val capacityFps: Double = 0.0,
    val pacing: Boolean = false,
    val received: Long = 0,
    val skipped: Long = 0,
    val paced: Long = 0,
    val detections: Int = 0,
    val conf: Double = 0.0,
    val stress: String = "OFF",
    val adpf: Boolean = false,
)

/** What the in-app LOOM pill shows; the same numbers that go out on LOOM_LIVE. */
data class LiveSnapshot(
    val t: Long = 0,
    val heat: Float = Float.NaN,
    val headroom: Float = Float.NaN,
    val forecast: Float = Float.NaN,
    val thermalStatus: String = "—",
    val batteryUa: Long? = null,
    val batteryPct: Int = -1,
    val charging: Boolean = false,
    val lines: Long = 0,
    val run: RunContext = RunContext(),
)

/**
 * LOOM Studio telemetry. Wire format is IDENTICAL to app/ so tools/live_dashboard.py works
 * unchanged: logcat tag LOOM_LIVE (one JSON line per second), LOOM_EVT (events), and
 * LOOM_TRX (chiplet transitions, emitted by core.Transitions). Every key app/ sends is sent
 * here too; Studio adds: app, model, task, goal_metric, goal_value, metric_value, units.
 *
 * Keys that only make sense for a camera pipeline (camera_fps, frame_cap, …) are sent as 0 /
 * null until a vision pipeline fills them (M2).
 */
class TelemetryBus(private val appContext: Context) {

    @Volatile var run: RunContext = RunContext()

    private val _live = MutableStateFlow(LiveSnapshot())
    val live: StateFlow<LiveSnapshot> = _live

    @Volatile var enabled: Boolean = true
    private var lines = 0L

    /** Called once per second by the run service (later: by the Weaver tick). */
    fun emitLive(signals: DeviceSignals) {
        val r = run
        val headroom = signals.thermalHeadroom
        val forecast = signals.thermalForecast
        val heat = combinedHeat(headroom, forecast)
        val current = signals.batteryCurrentRaw.takeIf { it != Long.MIN_VALUE }
        val now = System.currentTimeMillis()
        val goal = r.goalValue ?: 0.0
        val metric = r.metricValue

        val chiplets = JSONArray()
        r.chiplets.forEach { c ->
            chiplets.put(
                JSONObject()
                    .put("name", c.name)
                    .put("profile", c.profile)
                    .put("ms", if (c.ms.isNaN()) 0.0 else c.ms)
                    .put("level", c.level)
            )
        }

        val j = JSONObject()
            // --- keys identical to app/ ---
            .put("t", now)
            .put("device", deviceName)
            .put("frame", r.units)
            .put("mode", r.mode.name)
            .put("stress", r.stress)
            .put("fps", numD(r.fps))
            .put("camera_fps", numD(r.cameraFps))
            .put("camera_rate", r.cameraRate)
            .put("frame_cap", r.frameCap)
            .put("not_captured", r.notCaptured)
            .put("capacity_fps", numD(r.capacityFps))
            .put("goal", goal)
            .put("user_goal", goal)
            .put("effective_goal", goal)
            .put("battery_saver", false)
            .put("heat", numF(heat))
            .put("headroom", numF(headroom))
            .put("forecast", numF(forecast))
            .put("heat_limit", r.heatLimit?.toDouble() ?: NULL)
            .put("thermal_status", DeviceSignals.thermalName(signals.thermalStatus))
            .put("battery_ua", current ?: NULL)
            .put("battery_pct", signals.batteryPct)
            .put("charging", signals.charging)
            .put("airplane", signals.airplaneMode)
            .put("pacing", r.pacing)
            .put("processed", r.units)
            .put("received", r.received)
            .put("skipped", r.skipped)
            .put("paced", r.paced)
            .put("p50_ms", numD(r.p50Ms))
            .put("p90_ms", numD(r.p90Ms))
            .put("detections", r.detections)
            .put("conf", numD(r.conf))
            .put("status", r.status)
            .put("policy", r.policySource ?: NULL)
            .put("plan", NULL)
            .put("decision", r.decision)
            .put("decisions", r.decisions)
            .put("last_swap", r.lastSwap)
            .put("run", NULL)
            .put("csv", "")
            .put("chiplets", chiplets)
            // --- Studio additions ---
            .put("app", "studio")
            .put("model", r.model ?: NULL)
            .put("task", r.task?.name ?: NULL)
            .put("goal_metric", r.goalMetric ?: NULL)
            .put("goal_value", r.goalValue ?: NULL)
            .put("metric_value", metric?.let { numD(it) } ?: NULL)
            .put("units", r.units)
            .put("adpf", r.adpf)

        if (enabled) Log.i(LIVE_TAG, j.toString())
        lines++
        _live.value = LiveSnapshot(
            t = now,
            heat = heat,
            headroom = headroom,
            forecast = forecast,
            thermalStatus = DeviceSignals.thermalName(signals.thermalStatus),
            batteryUa = current,
            batteryPct = signals.batteryPct,
            charging = signals.charging,
            lines = lines,
            run = r,
        )
    }

    /** One LOOM_EVT line, same keys as app/ (t, frame, event, swap_gap_ms). */
    fun event(msg: String, swapGapMs: Double? = null) {
        if (!enabled) return
        Log.i(
            LIVE_EVENT_TAG,
            JSONObject()
                .put("t", System.currentTimeMillis())
                .put("frame", run.units)
                .put("event", msg)
                .put("swap_gap_ms", swapGapMs ?: NULL)
                .toString()
        )
    }

    val deviceName: String by lazy {
        val named: String? = try {
            android.provider.Settings.Global.getString(
                appContext.contentResolver, android.provider.Settings.Global.DEVICE_NAME
            )
        } catch (_: Throwable) {
            null
        }
        named ?: (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
    }

    companion object {
        /** Tags the laptop dashboard listens to with `adb logcat` (same as app/). */
        const val LIVE_TAG = "LOOM_LIVE"
        const val LIVE_EVENT_TAG = "LOOM_EVT"

        const val METRIC_FPS = "fps"
        const val METRIC_TOKENS = "tokens_per_s"
        const val METRIC_LATENCY = "latency_ms"

        /** The forecast may lead current headroom by at most this (same rule as app/'s Weaver). */
        const val FORECAST_LEAD = 0.10f

        private val NULL = JSONObject.NULL

        /**
         * The heat signal the Weaver acts on, exactly as app/ computes it: current headroom, raised
         * toward Android's 10 s forecast, but a forecast spike alone can lead by at most 0.10.
         */
        fun combinedHeat(headroom: Float, forecast: Float): Float = when {
            headroom.isNaN() -> forecast
            forecast.isNaN() -> headroom
            else -> max(headroom, min(forecast, headroom + FORECAST_LEAD))
        }

        private fun numD(v: Double): Any = if (v.isNaN() || v.isInfinite()) NULL else v
        private fun numF(v: Float): Any = if (v.isNaN()) NULL else v.toDouble()
    }
}
