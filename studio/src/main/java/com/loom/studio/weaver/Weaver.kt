package com.loom.studio.weaver

import com.loom.studio.chiplets.CameraSource
import com.loom.studio.chiplets.StudioChiplet
import com.loom.studio.core.Level
import com.loom.studio.core.Mode
import com.loom.studio.importer.ModelSettings
import com.loom.studio.pipeline.GenericPipeline
import com.loom.studio.pipeline.Pipeline
import com.loom.studio.profiler.ProfileTable
import com.loom.studio.telemetry.ChipletLive
import com.loom.studio.telemetry.DeviceSignals
import com.loom.studio.telemetry.RunContext
import com.loom.studio.telemetry.TelemetryBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the UI's Weaver panel shows. */
data class WeaverState(
    val mode: Mode = Mode.ADAPTIVE,
    val metric: Double = 0.0,
    val cameraFps: Double = 0.0,
    val capacity: Double = 0.0,
    val goal: Double = 15.0,
    val heat: Float = Float.NaN,
    val heatLimit: Float? = 0.80f,
    val thermalStatus: String = "—",
    val batteryUa: Long? = null,
    val status: String = "starting",
    val lastDecision: String = "—",
    val decisions: Int = 0,
    val stress: StressMode = StressMode.OFF,
    val levels: Map<String, Level> = emptyMap(),
    val goalMet: Boolean = false,
)

/**
 * The generalised Weaver (brief §8): LOOM's control plane for ONE running model.
 * Sense every second (heat + 10 s forecast, thermal status, battery, goal metric) →
 * [WeaverCore] decides → at most one chiplet changes (hot-swapped, logged in LOOM_TRX).
 * The goal metric is pluggable (units/s here; tokens/s for LLMs in drop 2) and the cut order
 * comes from the model's settings, not from code.
 */
class Weaver(
    val pipeline: Pipeline,
    settings: ModelSettings,
    @Volatile var profiles: ProfileTable?,
    private val stress: StressLoad,
    private val modelFile: java.io.File,
) {
    val core = WeaverCore().apply {
        goal = settings.goalFps
        heatLimit = settings.heatLimit
        cutOrder = settings.cutOrder
    }
    private val lock = Any()
    private val _state = MutableStateFlow(WeaverState(goal = settings.goalFps, heatLimit = settings.heatLimit))
    val state: StateFlow<WeaverState> = _state

    private var lastNs = 0L
    private var lastProcessed = 0L
    private var lastSensor = 0L
    private var notCaptured = 0L
    private var notCapturedFrac = 0.0
    private var sec = 0L
    private var decisions = 0
    private var lastDecision = "—"
    private var lastStatus = "starting"

    private fun note(msg: String) {
        lastDecision = msg
        com.loom.studio.StudioApp.instance.telemetry.event(msg)
    }

    // ---------------- commands (UI, dashboard) ----------------

    fun setMode(m: Mode): Unit = synchronized(lock) {
        if (m == core.mode) return@synchronized
        core.mode = m
        core.resetWindows()
        if (m == Mode.FIXED) adaptive().forEach { c -> c.profiles.firstOrNull()?.let { c.requestLevel(it.level, "mode Fixed HIGH") } }
        note("Mode → ${m.label}")
        applyPacing()
    }

    fun manual(key: String, level: Level): Unit = synchronized(lock) {
        if (core.mode != Mode.MANUAL) {
            core.mode = Mode.MANUAL
            note("Mode → Manual (Weaver paused)")
            applyPacing()
        }
        pipeline.chiplets.firstOrNull { it.key == key }?.requestLevel(level, "manual")
        Unit
    }

    fun setGoal(v: Double): Unit = synchronized(lock) {
        core.goal = v
        core.resetWindows()
        applyPacing()
        note("Goal → ≥ ${WeaverCore.f1(v)} ${pipeline.unitName}/s")
    }

    fun setHeatLimit(v: Float?): Unit = synchronized(lock) {
        core.heatLimit = v
        core.resetWindows()
        note(if (v == null) "Heat limit → off" else "Heat limit → ${WeaverCore.f2(v)}")
    }

    fun setCutOrder(order: List<String>): Unit = synchronized(lock) {
        core.cutOrder = order
        note("Cut order → ${order.joinToString(" → ")}")
    }

    fun setStress(m: StressMode) {
        stress.set(m, pipeline.card, modelFile)
    }

    private fun adaptive(): List<StudioChiplet> = pipeline.chiplets.filter { it.adaptive }

    private fun applyPacing() {
        val pace = if (core.mode == Mode.ADAPTIVE) core.goal + 3.0 else null
        pipeline.setPace(pace)
        pipeline.adpf.setTarget(1000.0 / (pace ?: 60.0))
    }

    // ---------------- the 1 Hz loop (run service thread) ----------------

    fun tick(signals: DeviceSignals, telemetry: TelemetryBus): Unit = synchronized(lock) {
        sec++
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.0 else (now - lastNs) / 1e9
        lastNs = now
        val processed = pipeline.processed.get()
        val metric = if (dt > 0) (processed - lastProcessed) / dt else 0.0
        lastProcessed = processed
        val cam = pipeline.camera()
        val sensor = cam?.sensorFrames?.get() ?: 0L
        val camFps = if (dt > 0 && cam != null) (sensor - lastSensor) / dt else 0.0
        lastSensor = sensor
        if (cam != null && dt in 0.2..3.0 && cam.cameraFps < cam.fullRateFps) {
            notCapturedFrac += maxOf(0.0, cam.fullRateFps * dt - camFps * dt)
            val whole = notCapturedFrac.toLong()
            notCaptured += whole
            notCapturedFrac -= whole
        }
        val heat = TelemetryBus.combinedHeat(signals.thermalHeadroom, signals.thermalForecast)
        val sourceActive = when {
            pipeline.paused -> false
            cam != null -> camFps >= 1.0
            pipeline is GenericPipeline -> true
            else -> true
        }
        val chips = adaptive().map { c ->
            ChipState(
                key = c.key,
                name = c.name,
                level = c.level,
                canLower = c.canLower(),
                canRaise = c.canRaise(),
                lowerIsFaster = lowerIsFaster(c),
                rateCap = (c as? CameraSource)?.rateCap?.toDouble(),
            )
        }
        val sense = Sense(metric, pipeline.capacity, heat, signals.thermalStatus, sourceActive, pipeline.inference.isReady)
        val d = core.step(sense, chips, sec)
        when (d) {
            is Decision.Lower -> apply(d.key, lower = true, d.reason)
            is Decision.Raise -> apply(d.key, lower = false, d.reason)
            is Decision.SwitchMode -> note("Safety guard: ${d.reason} → ${d.mode.label}")
            is Decision.Floor -> note(d.reason)
            is Decision.Hold -> Unit
        }
        lastStatus = d.status
        applyPacing()

        val chipLive = pipeline.chiplets.map { c ->
            ChipletLive(c.name, c.profileLabel, pipeline.stageMs[c.key] ?: c.cost().msPerUnit,
                if (c.adaptive) c.level.name else "FIXED")
        }
        telemetry.run = RunContext(
            model = pipeline.card.displayName,
            task = pipeline.card.kind,
            goalMetric = pipeline.metricName,
            goalValue = core.goal,
            metricValue = metric,
            mode = core.mode,
            status = lastStatus,
            decision = lastDecision,
            decisions = decisions,
            lastSwap = pipeline.lastSwap,
            heatLimit = core.heatLimit,
            policySource = if (profiles != null) "measured profiles + reflex" else "reflex (not profiled yet)",
            units = processed,
            p50Ms = pipeline.p50Ms,
            p90Ms = pipeline.p90Ms,
            chiplets = chipLive,
            fps = metric,
            cameraFps = camFps,
            cameraRate = cam?.cameraFps ?: 0,
            frameCap = cam?.rateCap ?: 0,
            notCaptured = notCaptured,
            capacityFps = pipeline.capacity,
            pacing = core.mode == Mode.ADAPTIVE,
            received = cam?.received?.get() ?: processed,
            skipped = cam?.skipped ?: 0,
            paced = cam?.paced ?: 0,
            detections = pipeline.detections,
            conf = pipeline.meanConf,
            stress = stress.mode.name,
            adpf = pipeline.adpf.active,
        )
        _state.value = WeaverState(
            mode = core.mode,
            metric = metric,
            cameraFps = camFps,
            capacity = pipeline.capacity,
            goal = core.goal,
            heat = heat,
            heatLimit = core.heatLimit,
            thermalStatus = DeviceSignals.thermalName(signals.thermalStatus),
            batteryUa = signals.batteryCurrentRaw.takeIf { it != Long.MIN_VALUE },
            status = lastStatus,
            lastDecision = lastDecision,
            decisions = decisions,
            stress = stress.mode,
            levels = adaptive().associate { it.key to it.level },
            goalMet = metric >= core.goal - core.policy.tolerance,
        )
    }

    private fun apply(key: String, lower: Boolean, reason: String) {
        val c = pipeline.chiplets.firstOrNull { it.key == key } ?: return
        val target = (if (lower) c.lowerLevel() else c.higherLevel()) ?: return
        c.requestLevel(target, reason)
        decisions++
        lastDecision = "$reason → ${c.name} ${target.name}"
    }

    /** From profiles.json: is the next lower inference profile measured faster per unit? */
    private fun lowerIsFaster(c: StudioChiplet): Boolean? {
        val table = profiles ?: return null
        val lowerLevel = c.lowerLevel() ?: return null
        val curRow = table.row(c.key, c.current.label) ?: return null
        val lowerLabel = c.profiles.firstOrNull { it.level == lowerLevel }?.label ?: return null
        val lowRow = table.row(c.key, lowerLabel) ?: return null
        val a = curRow.p50Ms ?: return null
        val b = lowRow.p50Ms ?: return null
        return b < a * 0.9
    }

    fun close() {
        stress.stop()
    }
}
