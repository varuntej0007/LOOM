package com.loom.studio

import com.loom.studio.core.Mode
import com.loom.studio.telemetry.DeviceSignals
import com.loom.studio.telemetry.TelemetryBus
import com.loom.studio.weaver.Weaver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Screen 5 (brief §11): the same workload for N minutes in Fixed HIGH, a cool-down, then N minutes
 * in Adaptive. Every second is recorded; the report compares battery, heat, speed and quality.
 * Numbers are whatever the phone measured — nothing is tuned for the demo.
 */
class CompareRunner(private val weaver: Weaver, private val minutes: Int, private val outDir: File) {

    enum class Phase { FIXED, COOLDOWN, ADAPTIVE, DONE, CANCELLED }

    data class Row(val t: Long, val phase: Phase, val heat: Float, val headroom: Float, val thermal: Int,
                   val ma: Double?, val metric: Double, val goal: Double, val quality: Double?, val charging: Boolean)

    data class Summary(val phase: String, val seconds: Int, val avgMa: Double?, val mAh: Double?, val avgHeat: Double,
                       val maxHeat: Double, val maxThermal: String, val avgMetric: Double, val goalMetPct: Double, val avgQuality: Double?)

    data class State(
        val phase: Phase = Phase.FIXED,
        val secondsLeft: Int = 0,
        val note: String = "",
        val fixed: Summary? = null,
        val adaptive: Summary? = null,
        val batteryDeltaPct: Double? = null,
        val reportPath: String? = null,
    )

    private val _state = MutableStateFlow(State(secondsLeft = minutes * 60, note = "Fixed HIGH run (baseline)"))
    val state: StateFlow<State> = _state
    private val rows = mutableListOf<Row>()
    private var phaseSec = 0
    private var coolStartHeat = Float.NaN
    private var startHeat = Float.NaN
    private var prevMode = weaver.core.mode
    val label: String get() = "COMPARE ${_state.value.phase.name} ${_state.value.secondsLeft / 60}:${"%02d".format(_state.value.secondsLeft % 60)}"

    init {
        weaver.setMode(Mode.FIXED)
    }

    /** Once per second from the runtime tick (after the Weaver ticked). */
    fun tick(s: DeviceSignals): Boolean {
        val st = _state.value
        if (st.phase == Phase.DONE || st.phase == Phase.CANCELLED) return false
        val heat = TelemetryBus.combinedHeat(s.thermalHeadroom, s.thermalForecast)
        val w = weaver.state.value
        if (startHeat.isNaN()) startHeat = heat
        rows += Row(System.currentTimeMillis(), st.phase, heat, s.thermalHeadroom, s.thermalStatus,
            s.batteryCurrentRaw.takeIf { it != Long.MIN_VALUE }?.let { abs(it) / 1000.0 }, w.metric, w.goal, w.quality, s.charging)
        phaseSec++
        when (st.phase) {
            Phase.FIXED -> if (phaseSec >= minutes * 60) {
                phaseSec = 0
                coolStartHeat = heat
                weaver.setMode(Mode.MANUAL)
                weaver.pipeline.paused = true
                _state.value = st.copy(phase = Phase.COOLDOWN, secondsLeft = COOL_MAX_S, note = "Cooling down before the Adaptive run (model paused)")
            } else _state.value = st.copy(secondsLeft = minutes * 60 - phaseSec)
            Phase.COOLDOWN -> {
                val cooled = !heat.isNaN() && !startHeat.isNaN() && heat <= startHeat + 0.02f
                if (cooled || phaseSec >= COOL_MAX_S) {
                    phaseSec = 0
                    weaver.pipeline.paused = false
                    weaver.setMode(Mode.ADAPTIVE)
                    _state.value = st.copy(phase = Phase.ADAPTIVE, secondsLeft = minutes * 60,
                        note = if (cooled) "Adaptive run (cooled back to the start heat)" else "Adaptive run (cool-down hit ${COOL_MAX_S / 60} min limit)")
                } else _state.value = st.copy(secondsLeft = COOL_MAX_S - phaseSec)
            }
            Phase.ADAPTIVE -> if (phaseSec >= minutes * 60) finish() else _state.value = st.copy(secondsLeft = minutes * 60 - phaseSec)
            else -> Unit
        }
        return true
    }

    fun cancel() {
        weaver.pipeline.paused = false
        weaver.setMode(prevMode)
        _state.value = _state.value.copy(phase = Phase.CANCELLED, note = "Cancelled")
    }

    private fun summary(p: Phase): Summary? {
        val r = rows.filter { it.phase == p }
        if (r.isEmpty()) return null
        val ma = r.mapNotNull { it.ma }.takeIf { it.isNotEmpty() }?.average()
        val heats = r.map { it.heat.toDouble() }.filter { !it.isNaN() }
        val goalMet = r.count { it.metric >= it.goal - 0.5 } * 100.0 / r.size
        return Summary(p.name, r.size, ma, ma?.let { it * r.size / 3600.0 }, heats.average(), heats.maxOrNull() ?: Double.NaN,
            DeviceSignals.thermalName(r.maxOf { it.thermal }), r.map { it.metric }.average(), goalMet,
            r.mapNotNull { it.quality }.takeIf { it.isNotEmpty() }?.average())
    }

    private fun finish() {
        val f = summary(Phase.FIXED)
        val a = summary(Phase.ADAPTIVE)
        val delta = if (f?.avgMa != null && a?.avgMa != null && f.avgMa > 0) (a.avgMa - f.avgMa) / f.avgMa * 100 else null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = "compare_${weaver.pipeline.card.displayName.replace(Regex("[^A-Za-z0-9]+"), "_")}_$stamp"
        outDir.mkdirs()
        val csv = File(outDir, "$base.csv")
        csv.bufferedWriter().use { w ->
            w.write("t_ms,phase,heat,headroom,thermal_status,battery_mA,metric,goal,quality,charging\n")
            rows.forEach { r -> w.write("${r.t},${r.phase},${r.heat},${r.headroom},${DeviceSignals.thermalName(r.thermal)},${r.ma ?: ""},${"%.3f".format(Locale.US, r.metric)},${r.goal},${r.quality ?: ""},${r.charging}\n") }
        }
        fun js(s: Summary?) = s?.let {
            JSONObject().put("seconds", it.seconds).put("avg_mA", it.avgMa ?: JSONObject.NULL).put("mAh", it.mAh ?: JSONObject.NULL)
                .put("avg_heat", it.avgHeat).put("max_heat", it.maxHeat).put("max_thermal_status", it.maxThermal)
                .put("avg_metric", it.avgMetric).put("goal_met_pct", it.goalMetPct).put("avg_quality", it.avgQuality ?: JSONObject.NULL)
        } ?: JSONObject.NULL
        val json = JSONObject()
            .put("model", weaver.pipeline.card.displayName).put("task", weaver.pipeline.card.kind.name)
            .put("metric", weaver.pipeline.metricName).put("goal", weaver.core.goal).put("minutes_per_run", minutes)
            .put("charging_any", rows.any { it.charging })
            .put("fixed", js(f)).put("adaptive", js(a))
            .put("battery_delta_pct", delta ?: JSONObject.NULL)
            .put("note", "Measured on the phone; battery current is meaningful only when not charging.")
        File(outDir, "$base.json").writeText(json.toString(2))
        weaver.setMode(prevMode)
        _state.value = _state.value.copy(phase = Phase.DONE, secondsLeft = 0, fixed = f, adaptive = a, batteryDeltaPct = delta,
            reportPath = csv.absolutePath.removeSuffix(".csv"),
            note = delta?.let { String.format(Locale.US, "Adaptive used %+.0f%% battery vs Fixed", it) } ?: "Done (no battery data)")
    }

    companion object {
        const val COOL_MAX_S = 300

        /** Latest report summary for a model ("−38% battery vs Fixed"), for the Library card. */
        fun lastResult(dir: File, modelName: String): String? = try {
            val key = "compare_${modelName.replace(Regex("[^A-Za-z0-9]+"), "_")}_"
            dir.listFiles { f -> f.name.startsWith(key) && f.name.endsWith(".json") }?.maxByOrNull { it.lastModified() }?.let { f ->
                val j = JSONObject(f.readText())
                if (j.isNull("battery_delta_pct")) null else String.format(Locale.US, "%+.0f%% battery vs Fixed", j.getDouble("battery_delta_pct"))
            }
        } catch (_: Throwable) { null }
    }
}

/** Copies a file into Download/LOOM (MediaStore, no storage permission needed). */
object Exporter {
    fun toDownloads(ctx: android.content.Context, file: File, mime: String): String? = try {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/LOOM")
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
        ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
        "Download/LOOM/${file.name}"
    } catch (t: Throwable) {
        null
    }
}

@Suppress("unused") private val keepJsonArray = JSONArray()
