package com.loom.studio.guard

import android.content.Context
import com.loom.studio.telemetry.DeviceSignals
import com.loom.studio.telemetry.TelemetryBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** What the UI, the notification and LOOM_LIVE show about Guard. */
data class GuardState(
    val enabled: Boolean = false,
    val level: Int = 0,
    val levers: List<String> = emptyList(),
    val limit: Float = 0.75f,
    val foreground: String? = null,
    val last: String = "—",
    val decisions: Int = 0,
    val perms: Perms = Perms(),
) {
    data class Perms(
        val overlay: Boolean = false,
        val writeSettings: Boolean = false,
        val secure: Boolean = false,
        val usage: Boolean = false,
    )

    fun json(): JSONObject = JSONObject()
        .put("on", enabled).put("level", level).put("levers", JSONArray(levers))
        .put("limit", limit.toDouble()).put("fg_app", foreground ?: JSONObject.NULL)
        .put("last", last).put("decisions", decisions)
        .put("perms", JSONObject().put("overlay", perms.overlay).put("write_settings", perms.writeSettings)
            .put("secure", perms.secure).put("usage", perms.usage))
}

/**
 * LOOM Guard: phone-wide cooling while ANY app runs. Ticked at 1 Hz by the run service
 * (which keeps running in the background as a foreground service). Decisions come from the
 * pure [GuardCore]; [SystemLevers] applies them; every change is a LOOM_EVT line.
 */
class Guard(private val ctx: Context, private val telemetry: TelemetryBus) {

    private val prefs = ctx.getSharedPreferences("loom_guard", Context.MODE_PRIVATE)
    val levers = SystemLevers(ctx) { telemetry.event(it) }
    private val overlay = GuardOverlay(ctx)
    private val fg = ForegroundApp(ctx)
    private val core = GuardCore(prefs.getFloat("limit", 0.75f))
    private var decisions = 0
    private var last = "—"
    private var verifyRefreshAt = 0L

    private val _state = MutableStateFlow(GuardState())
    val state: StateFlow<GuardState> = _state

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) {
            if (v == enabled) return
            prefs.edit().putBoolean("enabled", v).apply()
            if (v) {
                telemetry.event("Guard on: cooling any app above heat ${fmt(core.limit)}")
                overlay.show()
            } else {
                stopCooling("Guard off")
                overlay.hide()
            }
            publish()
        }

    var limit: Float
        get() = core.limit
        set(v) { core.limit = v; prefs.edit().putFloat("limit", v).apply(); publish() }

    /** Service start: restore anything a crash left on, then resume. */
    fun onServiceStart() {
        val left = levers.releaseAll()
        if (left.isNotEmpty()) telemetry.event("Guard recovery: ${left.joinToString(" · ")}")
        core.reset()
        if (enabled) overlay.show()
        publish()
    }

    /** Service stop: never leave the phone dimmed / capped / in saver. */
    fun onServiceStop() {
        stopCooling("run service stopped")
        overlay.hide()
    }

    private fun stopCooling(why: String) {
        val r = levers.releaseAll()
        core.reset()
        if (r.isNotEmpty()) { last = "$why → ${r.joinToString(" · ")}"; telemetry.event("Guard: $last") }
    }

    fun tick(s: DeviceSignals) {
        if (!enabled) { publish(); return }
        val now = System.currentTimeMillis()
        val heat = TelemetryBus.combinedHeat(s.thermalHeadroom, s.thermalForecast)
        val change = core.step(now, heat, s.thermalStatus)
        if (change != null) {
            val result = if (change.up) levers.apply(change.lever) else levers.release(change.lever)
            decisions++
            val app = fg.current(now)?.let { " · front app $it" } ?: ""
            last = "${change.reason} · $result$app"
            telemetry.event("Guard L${change.level}: $last")
            if (change.up && change.lever == GuardCore.Lever.REFRESH) verifyRefreshAt = now + 3_000
        }
        if (verifyRefreshAt in 1..now) {
            verifyRefreshAt = 0
            val hz = levers.observedRefreshHz()
            if (GuardCore.Lever.REFRESH.name in levers.applied && hz > 61f) {
                last = "60 Hz cap set, but the display still runs at ${"%.0f".format(Locale.US, hz)} Hz (this phone's own refresh setting overrides it)"
                telemetry.event("Guard: $last")
            }
        }
        overlay.show()
        val st = publish(now)
        overlay.update(
            "LOOM ${fmt(heat)} · " + if (st.levers.isEmpty()) "cool" else st.levers.joinToString(" · "),
            heat, core.limit,
        )
    }

    private fun publish(now: Long = System.currentTimeMillis()): GuardState {
        val st = GuardState(
            enabled = enabled,
            level = core.level,
            levers = core.activeLevers.filter { it.name in levers.applied }.map { it.label },
            limit = core.limit,
            foreground = if (enabled) fg.current(now) else null,
            last = last,
            decisions = decisions,
            perms = GuardState.Perms(overlay.allowed, levers.canWriteSettings, levers.hasSecure, fg.allowed),
        )
        _state.value = st
        return st
    }

    private fun fmt(v: Float) = if (v.isNaN()) "—" else String.format(Locale.US, "%.2f", v)
}
