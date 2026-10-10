package com.loom.runtime.weaver

import com.loom.runtime.LoomPipeline
import com.loom.runtime.StressLoad
import com.loom.runtime.core.AdaptiveChiplet
import com.loom.runtime.core.Goal
import com.loom.runtime.core.Level
import com.loom.runtime.core.Mode
import com.loom.runtime.core.StressMode
import com.loom.runtime.core.higher
import com.loom.runtime.core.lower
import com.loom.runtime.telemetry.DeviceSignals
import com.loom.runtime.telemetry.TelemetryBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** Tunable policy (blueprint §5). Thresholds to be tuned on the device from logged data. */
data class Policy(
    val targetFps: Double = Goal.TARGET_FPS,
    val violationSeconds: Int = 3,       // FPS below target this long → downgrade one step
    val fpsTolerance: Double = 0.5,      // "below target" = more than this under it (ignores 14.99 vs 15)
    val slackSeconds: Int = 10,          // comfortable this long → upgrade one step
    val slackMarginFps: Double = 3.0,    // "comfortable" = capacity ≥ target + margin
    val cooldownSeconds: Int = 5,        // no decisions after any change
    val heatCeiling: Float = 0.80f,      // default heat limit (headroom or its 10 s forecast)
    val coolMargin: Float = 0.08f,       // upgrades only when heat < ceiling − margin
    val forecastLead: Float = 0.10f,     // the forecast may lead current headroom by at most this
    val paceMarginFps: Double = 3.0,     // pacing processes at most target + this
    val upgradeRegretSeconds: Int = 30,  // downgraded this soon after an upgrade → back off
    val backoffStartSeconds: Int = 30,
    val backoffMaxSeconds: Int = 240,
    val runSeconds: Int = 15 * 60,       // benchmark run length
)

/** What the dashboard shows about the controller. */
data class WeaverState(
    val mode: Mode = Mode.ADAPTIVE,
    val stress: StressMode = StressMode.OFF,
    val fps: Double = 0.0,
    val cameraFps: Double = 0.0,
    val capacityFps: Double = 0.0,
    val target: Double = Goal.TARGET_FPS,
    val effectiveTarget: Double = Goal.TARGET_FPS,
    val heatCeiling: Float? = 0.80f,
    val batterySaver: Boolean = false,
    val heat: Float = Float.NaN,
    val pacing: Boolean = true,
    val status: String = "starting",
    val lastDecision: String = "—",
    val decisions: Int = 0,
    val runLabel: String? = null,
)

/**
 * The Weaver: LOOM's control plane. Once per second it reads telemetry, compares it with the
 * declared goal, and changes at most one profile on one chiplet. It is not an AI stage.
 *
 * Goal: "hold ≥ target FPS with the best quality possible, without running hotter than the
 * heat ceiling."
 *
 * Signals
 *  - fps:      frames actually processed in the last second (processed-frame counter).
 *  - capacity: what the pipeline COULD process = min(camera at full rate, 1000 / p50 total ms).
 *              Used for upgrades, because pacing deliberately keeps fps just above the goal.
 *  - heat:     max(current thermal headroom, Android's 10-second forecast) — acting on the
 *              forecast lets LOOM cool down before the phone actually gets hot.
 *
 * Decisions (cheapest-to-lose quality first)
 *  - heat > ceiling            → screen dimmer → Lite2→Lite0 → fewer camera frames (the sensor
 *                                itself slows to 15 / 10 FPS when it can) → speak less often
 *                                (no GPU→CPU: on the iQOO 15 the CPU is ~3x slower and not cooler)
 *  - FPS < target − 0.5 for 3 s → Inference only (plus the narrator once it is a heavy LLM;
 *                                fewer frames never rescue FPS: they cap it)
 *  - 10 s comfortable          → GPU restored → voice → full frame rate → Lite2 → screen
 *  - 5 s cooldown after every change; upgrade back-off if an upgrade has to be undone.
 *
 * Pacing (Adaptive only): the Capture chiplet processes at most target + 3 frames per second.
 * The camera gives ~30; if the goal is 20, the extra frames are pure heat with no benefit.
 */
class Weaver(
    private val pipeline: LoomPipeline,
    private val telemetry: TelemetryBus,
    private val signals: DeviceSignals,
    private val stress: StressLoad,
    val policy: Policy = Policy(),
) {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "loom-weaver") }

    private val _state = MutableStateFlow(WeaverState())
    val state: StateFlow<WeaverState> = _state

    // Everything below is touched only on the weaver thread.
    private var mode = Mode.ADAPTIVE
    private var target = policy.targetFps
    /** The goal actually defended: the user goal, lowered when the battery is low. */
    private var goal = policy.targetFps
    private var batterySaver = false
    private var heatCeiling: Float? = policy.heatCeiling
    private var pacing = true
    private var lastProcessed = 0L
    private var lastSensor = 0L
    /** Camera frames the sensor did NOT capture because LOOM slowed it (estimate vs. full rate). */
    private var notCaptured = 0L
    private var notCapturedFrac = 0.0
    private var lastTickNs = 0L
    private var belowSecs = 0
    private var hotSecs = 0
    private var slackSecs = 0
    private var cooldownLeft = 0
    private var decisions = 0
    private var lastDecision = "—"
    private var atFloorLogged = false

    /** Offline quantum plan (QAOA + brute-force check); null = reflexes only. */
    private val table: PolicyTable? = PolicyTable.load(pipeline.narrator.appContext)
    private var plan: PolicyTable.Plan? = null
    private var planSecs = 0

    private class Backoff(var blockedUntilNs: Long = 0, var lengthS: Int = 0, var lastUpgradeNs: Long = 0)
    private val backoff = mutableMapOf<String, Backoff>()

    fun start() {
        exec.scheduleWithFixedDelay(::safeTick, 1, 1, TimeUnit.SECONDS)
    }

    // ---------------- Commands from the UI (all run on the weaver thread) ----------------

    fun setMode(newMode: Mode) = exec.execute {
        if (newMode == mode) return@execute
        mode = newMode
        telemetry.mode = newMode
        resetWindows()
        when (newMode) {
            Mode.FIXED -> {
                // Benchmark Run A: everything at HIGH and held there, no pacing — an ordinary app.
                allChiplets().forEach { it.requestLevel(Level.HIGH, "mode Fixed HIGH") }
                note("Mode → Fixed HIGH (Weaver observing only)")
            }
            Mode.ADAPTIVE -> note("Mode → Adaptive (Weaver in control)")
            Mode.MANUAL -> note("Mode → Manual (Weaver paused)")
        }
        applyPacing()
        publish(status = statusFor(newMode))
    }

    /** A person tapped a profile chip: hand control to them. */
    fun manual(chiplet: AdaptiveChiplet, level: Level) = exec.execute {
        if (mode != Mode.MANUAL) {
            mode = Mode.MANUAL
            telemetry.mode = Mode.MANUAL
            note("Mode → Manual (Weaver paused)")
            applyPacing()
        }
        chiplet.requestLevel(level, "manual")
        publish(status = statusFor(mode))
    }

    fun setStress(m: StressMode) = exec.execute {
        stress.set(m)
        telemetry.stress = m
        publish()
    }

    fun setTarget(fps: Double) = exec.execute {
        target = fps
        goal = if (batterySaver) min(fps, Goal.LOW_BATTERY_FPS) else fps
        resetWindows()
        applyPacing()
        note("Goal → hold ≥ ${fmt(fps)} FPS")
        publish()
    }

    /** null = ignore heat. */
    fun setHeatCeiling(ceiling: Float?) = exec.execute {
        heatCeiling = ceiling
        resetWindows()
        note(if (ceiling == null) "Heat limit → off" else "Heat limit → headroom ≤ ${fmt(ceiling)}")
        publish()
    }

    fun setPacing(on: Boolean) = exec.execute {
        pacing = on
        applyPacing()
        note(if (on) "Pacing → on (process ≤ goal + ${policy.paceMarginFps.toInt()} FPS)" else "Pacing → off (process every frame)")
        publish()
    }

    fun toggleRun() = exec.execute {
        if (telemetry.runStartMs == null) {
            telemetry.startRun(mode.name)
            note("Benchmark run started: ${mode.label}, ${policy.runSeconds / 60} min")
        } else {
            telemetry.endRun()
            note("Benchmark run stopped by user")
        }
        publish()
    }

    // ---------------- The control loop ----------------

    private fun safeTick() {
        try {
            tick()
        } catch (t: Throwable) {
            android.util.Log.e("LOOM", "weaver tick", t)
        }
        try {
            emitLive()
        } catch (t: Throwable) {
            android.util.Log.e("LOOM", "live emit", t)
        }
    }

    /**
     * One JSON line per second to logcat (tag LOOM_LIVE). The laptop dashboard reads it over
     * USB with `adb logcat` — no network permission needed, the app stays fully offline.
     */
    private fun emitLive() {
        val w = _state.value
        val d = telemetry.state.value
        fun numD(v: Double): Any = if (v.isNaN() || v.isInfinite()) org.json.JSONObject.NULL else v
        fun numF(v: Float): Any = if (v.isNaN()) org.json.JSONObject.NULL else v.toDouble()
        val chiplets = org.json.JSONArray()
            .put(chip("Capture", d.profileCapture, d.captureMs, pipeline.capture.level))
            .put(chip("Preprocess", "rotate", d.preprocessMs, null))
            .put(chip("Inference", d.profileInference, d.inferenceMs, pipeline.inference.level))
            .put(chip("Narrator", d.profileNarrator, d.narratorMs ?: 0.0, pipeline.narrator.level))
            .put(chip("Display", pipeline.display.profileLabel, d.displayMs, pipeline.display.level))
        val j = org.json.JSONObject()
            .put("t", System.currentTimeMillis())
            .put("device", deviceName)
            .put("frame", d.frameNo)
            .put("mode", w.mode.name)
            .put("stress", w.stress.name)
            .put("fps", numD(w.fps))
            .put("camera_fps", numD(w.cameraFps))
            .put("camera_rate", pipeline.capture.cameraFps)
            .put("frame_cap", pipeline.capture.rateCap)
            .put("not_captured", notCaptured)
            .put("capacity_fps", numD(w.capacityFps))
            .put("goal", goal)
            .put("user_goal", target)
            .put("effective_goal", numD(w.effectiveTarget))
            .put("battery_saver", batterySaver)
            .put("heat", numF(w.heat))
            .put("headroom", numF(signals.thermalHeadroom))
            .put("forecast", numF(signals.thermalForecast))
            .put("heat_limit", heatCeiling?.toDouble() ?: org.json.JSONObject.NULL)
            .put("thermal_status", DeviceSignals.thermalName(signals.thermalStatus))
            .put("battery_ua", if (signals.batteryCurrentRaw == Long.MIN_VALUE) org.json.JSONObject.NULL else signals.batteryCurrentRaw)
            .put("battery_pct", signals.batteryPct)
            .put("charging", signals.charging)
            .put("airplane", signals.airplaneMode)
            .put("pacing", pacing && mode == Mode.ADAPTIVE)
            .put("processed", telemetry.processed.get())
            .put("received", telemetry.received.get())
            .put("skipped", d.framesSkipped)
            .put("paced", d.framesPaced)
            .put("p50_ms", numD(d.p50Ms))
            .put("p90_ms", numD(d.p90Ms))
            .put("detections", d.detections)
            .put("conf", numD(d.meanConf))
            .put("status", w.status)
            .put("policy", table?.let { if (it.verified) "quantum" else "quantum (unverified)" } ?: org.json.JSONObject.NULL)
            .put("plan", plan?.let { p ->
                org.json.JSONObject()
                    .put("bucket", p.bucket)
                    .put("goal", p.goal)
                    .put("heat_lo", p.heatLo.toDouble())
                    .put("heat_hi", p.heatHi.toDouble())
                    .put("text", p.describe())
                    .put("predicted_mA", p.predictedMa)
                    .put("reachable", p.limitReachable)
                    .put("p_optimal", if (p.pOptimal.isNaN()) org.json.JSONObject.NULL else p.pOptimal)
                    .put("levels", org.json.JSONObject().also { o -> p.levels.forEach { (k, v) -> o.put(k, v.name) } })
            } ?: org.json.JSONObject.NULL)
            .put("decision", w.lastDecision)
            .put("decisions", w.decisions)
            .put("last_swap", d.lastSwap)
            .put("run", w.runLabel ?: org.json.JSONObject.NULL)
            .put("csv", d.csvName)
            .put("chiplets", chiplets)
        android.util.Log.i(TelemetryBus.LIVE_TAG, j.toString())
    }

    private fun chip(name: String, profile: String, ms: Double, level: Level?) =
        org.json.JSONObject()
            .put("name", name)
            .put("profile", profile)
            .put("ms", if (ms.isNaN()) 0.0 else ms)
            .put("level", level?.name ?: "FIXED")

    private val deviceName: String by lazy {
        val named: String? = try {
            android.provider.Settings.Global.getString(
                pipeline.narrator.appContext.contentResolver, android.provider.Settings.Global.DEVICE_NAME
            )
        } catch (_: Throwable) {
            null
        }
        named ?: (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
    }

    private fun tick() {
        val now = System.nanoTime()

        // Measured rates over the last tick, from counters (robust to tick jitter).
        val processed = telemetry.processed.get()
        val sensor = telemetry.sensorFrames.get()
        val dt = if (lastTickNs == 0L) 0.0 else (now - lastTickNs) / 1e9
        val fps = if (dt > 0) (processed - lastProcessed) / dt else 0.0
        val camFps = if (dt > 0) (sensor - lastSensor) / dt else 0.0
        lastProcessed = processed
        // Frames the slowed camera never produced, compared with the full 30 FPS.
        if (dt in 0.2..3.0 && pipeline.capture.cameraFps < pipeline.capture.fullRateFps) {
            notCapturedFrac += max(0.0, pipeline.capture.fullRateFps * dt - (sensor - lastSensor))
            val whole = notCapturedFrac.toLong()
            notCaptured += whole
            notCapturedFrac -= whole
        }
        lastSensor = sensor
        lastTickNs = now
        telemetry.cameraFps = camFps

        checkRunExpiry()

        // Battery-aware goal: low battery and not charging → defend only the minimum useful FPS.
        val low = signals.batteryPct in 0 until Goal.LOW_BATTERY_PCT && !signals.charging
        if (low != batterySaver && mode == Mode.ADAPTIVE) {
            batterySaver = low
            note(if (low) "Battery ${signals.batteryPct}% → battery saver: goal ${fmt(min(target, Goal.LOW_BATTERY_FPS))} FPS"
                 else "Battery OK → goal back to ${fmt(target)} FPS")
            resetWindows()
        }
        goal = if (batterySaver && mode == Mode.ADAPTIVE) min(target, Goal.LOW_BATTERY_FPS) else target
        applyPacing()

        val cap = pipeline.capture
        // The goal can never exceed what the camera delivers or the Capture cap allows.
        val effTarget = if (camFps > 1) min(goal, 0.9 * min(camFps, cap.rateCap.toDouble())) else goal
        telemetry.targetFps = effTarget
        val p50 = telemetry.p50TotalMs
        // What the pipeline could process at full camera rate: camera vs. time per frame.
        // (Scaled back to the full rate — otherwise a slowed camera would block its own recovery.)
        val fullCam = if (camFps > 1) camFps * cap.fullRateFps / cap.cameraFps.coerceAtLeast(1) else Double.MAX_VALUE
        val capacity = min(fullCam, if (p50 > 0) 1000.0 / p50 else 0.0)

        // Act early on Android's 10 s forecast, but never let a forecast spike alone trigger
        // a cascade: it may lead the current headroom by at most forecastLead.
        val now0 = signals.thermalHeadroom
        val fc = signals.thermalForecast
        val heat = when {
            now0.isNaN() -> fc
            fc.isNaN() -> now0
            else -> max(now0, min(fc, now0 + policy.forecastLead))
        }
        val ceiling = heatCeiling
        // The quantum plan for this goal and heat. Upgrades look it up 0.02 hotter (hysteresis),
        // so a phone sitting on a band edge does not flip between two plans.
        plan = if (mode == Mode.ADAPTIVE && ceiling != null) table?.lookup(goal, heat) else null
        val planUp = if (mode == Mode.ADAPTIVE && ceiling != null) table?.lookup(goal, heat + 0.02f) else null

        if (mode != Mode.ADAPTIVE) {
            resetWindows()
            publish(fps, camFps, capacity, effTarget, heat, statusFor(mode))
            return
        }
        // Camera not delivering (app in background, screen off, camera reopening): FPS reads 0,
        // but that is not something a lighter profile can fix — wait instead of downgrading.
        if (camFps < 1.0) {
            resetWindows()
            publish(fps, camFps, capacity, effTarget, heat, "camera paused — waiting")
            return
        }
        if (!pipeline.inference.isReadyForControl()) {
            publish(fps, camFps, capacity, effTarget, heat, "waiting for detector")
            return
        }
        if (cooldownLeft > 0) {
            cooldownLeft--
            resetWindows()
            publish(fps, camFps, capacity, effTarget, heat, "cooldown ${cooldownLeft + 1} s")
            return
        }

        val below = fps < effTarget - policy.fpsTolerance
        val hot = ceiling != null && !heat.isNaN() && heat > ceiling
        val cool = ceiling == null || heat.isNaN() || heat < ceiling - policy.coolMargin
        val comfortable = capacity >= goal + policy.slackMarginFps && cool

        belowSecs = if (below) belowSecs + 1 else 0
        hotSecs = if (hot) hotSecs + 1 else 0
        slackSecs = if (comfortable) slackSecs + 1 else 0

        val n = pipeline.narrator
        val c = pipeline.capture
        val i = pipeline.inference
        val dsp = pipeline.display
        val planOrder = listOf<AdaptiveChiplet>(dsp, i, c, n)
        fun aboveTarget(ch: AdaptiveChiplet) = plan?.levelOf(ch.name)?.let { ch.level.ordinal < it.ordinal } ?: false
        fun belowTarget(ch: AdaptiveChiplet) = planUp?.levelOf(ch.name)?.let { ch.level.ordinal > it.ordinal } ?: true
        val overPlan = planOrder.filter(::aboveTarget)
        planSecs = if (overPlan.isNotEmpty() && !hot) planSecs + 1 else 0

        when {
            hotSecs >= 2 -> {
                val src = if (signals.thermalForecast >= signals.thermalHeadroom) "forecast" else "headroom"
                val why = "heat ($src) ${String.format(Locale.US, "%.3f", heat)} > ${fmt(ceiling!!)}"
                // Heat order — least valuable to a blind user first, voice last:
                // dimmer screen → smaller model → fewer frames → speak less often → GPU to CPU.
                // No GPU→CPU step: measured on the iQOO 15, Lite0 takes ~81 ms on the CPU vs ~24 ms
                // on the GPU — slower, and not cooler. CPU stays only as the GPU-failure fallback.
                // The quantum plan says how far to go: chiplets above it first, in one jump each.
                // If the phone is still too hot at the plan, the reflex order continues past it.
                val reflex = listOf<AdaptiveChiplet?>(
                    dsp,
                    i.takeIf { it.level == Level.HIGH },
                    c,
                    n.takeIf { it.level == Level.HIGH || it.heavy },
                    n.takeIf { it.heavy },
                ).filterNotNull()
                val order = (overPlan + reflex).distinct()
                if (!downgrade(order, why, now)) floor(fps, camFps, capacity, effTarget, heat, why)
            }
            belowSecs >= policy.violationSeconds -> {
                val why = "FPS ${String.format(Locale.US, "%.2f", fps)} < ${fmt(effTarget)} for ${belowSecs} s"
                // Only chiplets that cost frame time can rescue FPS: the detector, and the narrator
                // only when it is heavy (LLM). Never the screen or frame rate (that caps FPS).
                val order = listOfNotNull<AdaptiveChiplet>(n.takeIf { it.heavy }, i.takeIf { it.level == Level.HIGH })
                if (!downgrade(order, why, now)) floor(fps, camFps, capacity, effTarget, heat, why)
            }
            planSecs >= PLAN_SECONDS && !below -> {
                // Feed-forward: the plan for this heat band predicts the limit would be crossed
                // within 3 minutes at the current profiles — step toward it before it happens.
                val p = plan!!
                val why = "quantum plan · heat ${String.format(Locale.US, "%.3f", heat)} (${p.bucket}) → ${p.describe()}"
                downgrade(overPlan, why, now)
            }
            slackSecs >= policy.slackSeconds -> {
                val why = "capacity ${fmt(capacity)} ≥ ${fmt(goal + policy.slackMarginFps)} FPS" +
                    (if (ceiling == null || heat.isNaN()) "" else " and heat ${fmt(heat)} < ${fmt(ceiling - policy.coolMargin)}") +
                    " for ${slackSecs} s"
                // Restore most valuable first: GPU back → voice → full frame rate → Lite2 → screen.
                // Never above the quantum plan for this heat: it already priced in the next 3 minutes.
                val order = listOf<AdaptiveChiplet?>(
                    i.takeIf { it.level == Level.ECO },
                    n,
                    c,
                    i,
                    dsp,
                ).filterNotNull().filter(::belowTarget)
                if (!upgrade(order, why, now)) {
                    slackSecs = 0
                    publish(fps, camFps, capacity, effTarget, heat,
                        if (planUp != null) "at quantum plan (${planUp.bucket})" else "best quality, goal met")
                }
            }
            else -> {
                atFloorLogged = false
                val status = when {
                    hot -> "too warm ${hotSecs}/2 s"
                    below -> "below target ${belowSecs}/${policy.violationSeconds} s"
                    overPlan.isNotEmpty() -> "above quantum plan ${planSecs}/$PLAN_SECONDS s"
                    comfortable -> "slack ${slackSecs}/${policy.slackSeconds} s"
                    !cool -> "warm — holding, no upgrades"
                    else -> "holding"
                }
                publish(fps, camFps, capacity, effTarget, heat, status)
            }
        }
        if (cooldownLeft == policy.cooldownSeconds) {
            publish(fps, camFps, capacity, effTarget, heat, "changed · cooldown ${policy.cooldownSeconds} s")
        }
    }

    /** Pacing is on only in Adaptive mode: Fixed and Manual process every frame, like a normal app. */
    private fun applyPacing() {
        val pace = if (mode == Mode.ADAPTIVE && pacing) goal + policy.paceMarginFps else null
        pipeline.capture.paceFps = pace
        telemetry.paceFps = pace
    }

    /** Lower the first chiplet in [order] that can go lower. */
    private fun downgrade(order: List<AdaptiveChiplet>, why: String, now: Long): Boolean {
        val pick = order.firstOrNull { it.level.lower() != null } ?: return false
        val b = backoff.getOrPut(pick.name) { Backoff() }
        var suffix = ""
        if (b.lastUpgradeNs != 0L && (now - b.lastUpgradeNs) / 1e9 < policy.upgradeRegretSeconds) {
            b.lengthS = if (b.lengthS == 0) policy.backoffStartSeconds
            else min(b.lengthS * 2, policy.backoffMaxSeconds)
            b.blockedUntilNs = now + b.lengthS * 1_000_000_000L
            suffix = " (upgrades of ${pick.name} paused ${b.lengthS} s)"
        }
        apply(pick, pick.level.lower()!!, why + suffix)
        return true
    }

    /** Raise the first chiplet in [order] that can go higher and isn't backed off. */
    private fun upgrade(order: List<AdaptiveChiplet>, why: String, now: Long): Boolean {
        val pick = order.firstOrNull { ch ->
            ch.level.higher() != null && now >= (backoff[ch.name]?.blockedUntilNs ?: 0L)
        } ?: return false
        backoff.getOrPut(pick.name) { Backoff() }.lastUpgradeNs = now
        apply(pick, pick.level.higher()!!, why)
        return true
    }

    private fun apply(chiplet: AdaptiveChiplet, level: Level, reason: String) {
        // The chiplet emits "<reason> → <Chiplet>: old → new" into the CSV event column.
        chiplet.requestLevel(level, reason)
        decisions++
        lastDecision = "$reason → ${chiplet.name} ${level.name}"
        cooldownLeft = policy.cooldownSeconds
        resetWindows()
    }

    private fun floor(fps: Double, cam: Double, cap: Double, eff: Double, heat: Float, why: String) {
        if (!atFloorLogged) {
            note("$why, but every chiplet is already at its lowest profile")
            atFloorLogged = true
        }
        publish(fps, cam, cap, eff, heat, "at lowest profile — goal not reachable")
    }

    private fun checkRunExpiry() {
        val start = telemetry.runStartMs ?: return
        if (System.currentTimeMillis() - start >= policy.runSeconds * 1000L) {
            telemetry.endRun()
            note("Benchmark run complete (${policy.runSeconds / 60} min)")
        }
    }

    private fun allChiplets(): List<AdaptiveChiplet> =
        listOf(pipeline.narrator, pipeline.capture, pipeline.inference, pipeline.display)

    private fun resetWindows() {
        belowSecs = 0
        hotSecs = 0
        slackSecs = 0
        planSecs = 0
    }

    private fun note(msg: String) {
        pipeline.postEvent(msg)
        lastDecision = msg
    }

    private fun statusFor(m: Mode) = when (m) {
        Mode.ADAPTIVE -> "holding"
        Mode.FIXED -> "fixed HIGH — observing only"
        Mode.MANUAL -> "manual — Weaver paused"
    }

    private fun publish(
        fps: Double = _state.value.fps,
        camFps: Double = _state.value.cameraFps,
        capacity: Double = _state.value.capacityFps,
        effTarget: Double = _state.value.effectiveTarget,
        heat: Float = _state.value.heat,
        status: String = _state.value.status,
    ) {
        val runLabel = telemetry.runStartMs?.let { start ->
            val s = ((System.currentTimeMillis() - start) / 1000).toInt()
            "RUN ${telemetry.runName} " + String.format(Locale.US, "%02d:%02d", s / 60, s % 60) +
                " / " + String.format(Locale.US, "%02d:00", policy.runSeconds / 60)
        }
        _state.value = WeaverState(
            mode = mode,
            stress = stress.mode,
            fps = fps,
            cameraFps = camFps,
            capacityFps = if (capacity == Double.MAX_VALUE) 0.0 else capacity,
            target = target,
            effectiveTarget = effTarget,
            heatCeiling = heatCeiling,
            batterySaver = batterySaver,
            heat = heat,
            pacing = pacing,
            status = status,
            lastDecision = lastDecision,
            decisions = decisions,
            runLabel = runLabel,
        )
    }

    fun close() {
        exec.shutdownNow()
    }

    companion object {
        /** Seconds above the quantum plan (and not yet hot) before stepping toward it. */
        const val PLAN_SECONDS = 3
    }

    private fun maxValid(a: Float, b: Float): Float = when {
        a.isNaN() -> b
        b.isNaN() -> a
        else -> max(a, b)
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun fmt(v: Float) = String.format(Locale.US, "%.2f", v)
}
