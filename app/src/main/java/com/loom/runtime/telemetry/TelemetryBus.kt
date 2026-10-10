package com.loom.runtime.telemetry

import com.loom.runtime.core.FrameSample
import com.loom.runtime.core.Goal
import com.loom.runtime.core.Mode
import com.loom.runtime.core.StressMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** What the per-frame part of the dashboard shows. Every number comes from the running app. */
data class DashboardState(
    val frameNo: Long = 0,
    val fps: Double = 0.0,
    val cameraFps: Double = 0.0,
    val captureMs: Double = 0.0,
    val preprocessMs: Double = 0.0,
    val inferenceMs: Double = 0.0,
    val narratorMs: Double? = null,
    val displayMs: Double = 0.0,
    val p50Ms: Double = 0.0,
    val p90Ms: Double = 0.0,
    val detections: Int = 0,
    val meanConf: Double = 0.0,
    val profileCapture: String = "—",
    val profileInference: String = "loading…",
    val profileNarrator: String = "—",
    val thermalStatus: String = "—",
    val thermalHeadroom: Float = Float.NaN,
    val thermalForecast: Float = Float.NaN,
    val batteryCurrentRaw: Long = Long.MIN_VALUE,
    val airplaneMode: Boolean = false,
    val lastEvent: String = "—",
    val lastSwap: String = "—",
    val framesSkipped: Long = 0,
    val framesPaced: Long = 0,
    val csvName: String = "",
)

/**
 * Collects per-frame samples and owns the CSV.
 *
 * record() and onFrameArrived() run only on the analyzer thread, so the rolling windows need
 * no locking. The Weaver reads the atomic counters and volatile fields from its own thread.
 */
class TelemetryBus(
    private val logDir: File,
    private val signals: DeviceSignals,
) {
    @Volatile private var csv = CsvLogger(logDir, "loom_free")

    private val _state = MutableStateFlow(DashboardState(csvName = csv.file.name))
    val state: StateFlow<DashboardState> = _state

    /** Frames fully processed (through inference). The Weaver derives FPS from this. */
    val processed = AtomicLong(0)
    /** Camera frames delivered to the analyzer (before stride). */
    val received = AtomicLong(0)
    /**
     * Frames the camera SENSOR produced (counted by a Camera2 capture callback). This is the
     * true camera rate: frames reaching the analyzer are fewer whenever processing is slow,
     * because KEEP_ONLY_LATEST drops the rest before they arrive.
     */
    val sensorFrames = AtomicLong(0)

    /** True camera rate, computed once per second by the Weaver from [sensorFrames]. */
    @Volatile var cameraFps: Double = 0.0

    // Context written by the Weaver / UI, stamped on every CSV row.
    @Volatile var mode: Mode = Mode.ADAPTIVE
    @Volatile var stress: StressMode = StressMode.OFF
    @Volatile var targetFps: Double = Goal.TARGET_FPS
    @Volatile var paceFps: Double? = null

    /** Median total pipeline time over the last 120 frames — the Weaver's capacity estimate. */
    @Volatile var p50TotalMs: Double = 0.0
        private set

    // Benchmark run (blueprint §9): a fixed-length run with its own CSV.
    @Volatile var runStartMs: Long? = null
        private set
    @Volatile var runName: String? = null
        private set

    private val frameTimesMs = ArrayDeque<Long>()
    private val totals = ArrayDeque<Double>()
    private var lastUiUpdateMs = 0L
    private var lastSwap = "—"
    private var lastEvent = "—"

    /** Analyzer thread: every camera frame, before stride. */
    fun onFrameArrived() {
        received.incrementAndGet()
    }

    /** Analyzer thread: one fully processed frame. */
    fun record(s: FrameSample, framesSkipped: Long, framesPaced: Long) {
        processed.incrementAndGet()
        val nowMs = System.currentTimeMillis()

        // fps_1s = frames fully processed in the trailing 1000 ms window.
        frameTimesMs.addLast(nowMs)
        while (frameTimesMs.isNotEmpty() && nowMs - frameTimesMs.first() > 1000) frameTimesMs.removeFirst()
        val fps = frameTimesMs.size.toDouble()

        totals.addLast(s.totalMs)
        if (totals.size > PERCENTILE_WINDOW) totals.removeFirst()

        val thermal = signals.thermalStatus
        val headroom = signals.thermalHeadroom
        val forecast = signals.thermalForecast
        val current = signals.batteryCurrentRaw
        val log = csv

        log.log(
            listOf(
                nowMs, s.frameNo, s.captureMs, s.preprocessMs, s.inferenceMs,
                s.narratorMs, s.totalMs, fps, s.detections, s.meanConf,
                s.profileCapture, s.profileInference, s.profileNarrator,
                DeviceSignals.thermalName(thermal), headroom,
                if (current == Long.MIN_VALUE) null else current,
                s.event, s.swapGapMs,
                s.displayMs, cameraFps, mode.name, stress.name, targetFps, s.narration,
                forecast, paceFps,
            )
        )

        if (s.event != null) {
            lastEvent = s.event
            android.util.Log.i(LIVE_EVENT_TAG, org.json.JSONObject()
                .put("t", nowMs).put("frame", s.frameNo).put("event", s.event)
                .put("swap_gap_ms", s.swapGapMs ?: JSONNULL).toString())
        }
        if (s.swapGapMs != null && s.event != null) lastSwap = s.event

        if (s.event == null && s.narration == null && nowMs - lastUiUpdateMs < UI_INTERVAL_MS) return
        lastUiUpdateMs = nowMs

        val sorted = totals.sorted()
        p50TotalMs = percentile(sorted, 0.50)
        _state.value = DashboardState(
            frameNo = s.frameNo,
            fps = fps,
            cameraFps = cameraFps,
            captureMs = s.captureMs,
            preprocessMs = s.preprocessMs,
            inferenceMs = s.inferenceMs,
            narratorMs = s.narratorMs ?: _state.value.narratorMs,
            displayMs = s.displayMs,
            p50Ms = percentile(sorted, 0.50),
            p90Ms = percentile(sorted, 0.90),
            detections = s.detections,
            meanConf = s.meanConf,
            profileCapture = s.profileCapture,
            profileInference = s.profileInference,
            profileNarrator = s.profileNarrator,
            thermalStatus = DeviceSignals.thermalName(thermal),
            thermalHeadroom = headroom,
            thermalForecast = forecast,
            batteryCurrentRaw = current,
            airplaneMode = signals.airplaneMode,
            lastEvent = lastEvent,
            lastSwap = lastSwap,
            framesSkipped = framesSkipped,
            framesPaced = framesPaced,
            csvName = log.file.name,
        )
    }

    /** Start a benchmark run: a fresh CSV named after the mode. */
    @Synchronized
    fun startRun(label: String) {
        rotate("loom_run_" + label.lowercase())
        runName = label
        runStartMs = System.currentTimeMillis()
    }

    /** End the run; logging continues into a new free-running CSV. */
    @Synchronized
    fun endRun() {
        if (runStartMs == null) return
        runStartMs = null
        runName = null
        rotate("loom_free")
    }

    private fun rotate(prefix: String) {
        val old = csv
        csv = CsvLogger(logDir, prefix)
        old.close()
        _state.value = _state.value.copy(csvName = csv.file.name)
    }

    fun close() {
        csv.close()
    }

    private fun percentile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        return sorted[((sorted.size - 1) * q).toInt()]
    }

    companion object {
        /** Tags the laptop dashboard listens to with `adb logcat`. */
        const val LIVE_TAG = "LOOM_LIVE"
        const val LIVE_EVENT_TAG = "LOOM_EVT"
        private val JSONNULL = org.json.JSONObject.NULL
        const val PERCENTILE_WINDOW = 120
        const val UI_INTERVAL_MS = 100L
    }
}
