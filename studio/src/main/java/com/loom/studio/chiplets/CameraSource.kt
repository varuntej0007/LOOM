package com.loom.studio.chiplets

import com.loom.studio.core.Level
import com.loom.studio.core.Transitions
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * CameraSource chiplet (dashboard name "Capture"), copied from app/'s CaptureChiplet.
 *
 * Profile = frame-rate cap 30 / 15 / 10 per second.
 *  - Hardware lever: the camera SENSOR is asked for the lowest rate it supports that still
 *    covers the cap (CONTROL_AE_TARGET_FPS_RANGE via Camera2CameraControl, no rebind).
 *    Frames never captured cost the sensor and ISP nothing.
 *  - Software lever: anything above the cap is skipped evenly; pacing (Adaptive) also limits
 *    processing to goal + 3 per second.
 */
class CameraSource(private val onEvent: (String) -> Unit) : KnobChiplet(
    name = "Capture",
    key = "source",
    profiles = listOf(
        Profile(Level.HIGH, "camera 30/s", mapOf("fps" to 30)),
        Profile(Level.BALANCED, "camera 15/s", mapOf("fps" to 15)),
        Profile(Level.ECO, "camera 10/s", mapOf("fps" to 10)),
    ),
    onEvent = onEvent,
) {
    interface HardwareRate {
        val rates: List<Int>
        fun request(fps: Int, done: (Boolean) -> Unit)
    }

    val fullRateFps = FULL_RATE
    @Volatile var rateCap: Int = FULL_RATE
        private set
    @Volatile var cameraFps: Int = FULL_RATE
        private set
    @Volatile private var hardware: HardwareRate? = null
    /** Max processed frames per second set by the Weaver (Adaptive), null = no pacing. */
    @Volatile var paceFps: Double? = null

    /** Frames the sensor produced (Camera2 capture callback). */
    val sensorFrames = AtomicLong(0)
    /** Frames delivered to the analyzer. */
    val received = AtomicLong(0)

    private var credit = 1.0
    private var lastNs = 0L
    @Volatile var skipped = 0L
        private set
    @Volatile var paced = 0L
        private set

    private var lastCostNs = 0L
    private var lastCostFrames = 0L
    @Volatile private var lastRate = 0.0

    override fun cost(): CostSample {
        val now = System.nanoTime()
        val f = sensorFrames.get()
        if (lastCostNs != 0L && now - lastCostNs > 500_000_000L) {
            lastRate = (f - lastCostFrames) / ((now - lastCostNs) / 1e9)
        }
        if (lastCostNs == 0L || now - lastCostNs > 500_000_000L) {
            lastCostNs = now
            lastCostFrames = f
        }
        return CostSample(0.0, lastRate)
    }

    override fun quality(): Double = rateCap.toDouble() / fullRateFps

    fun attachHardware(rate: HardwareRate) {
        hardware = rate
        applyHardware(rateCap, "camera ready")
    }

    override fun apply(p: Profile, reason: String) {
        rateCap = (p.knobs["fps"] as? Int) ?: FULL_RATE
        applyHardware(rateCap, reason)
    }

    private fun cameraRateFor(cap: Int, hw: HardwareRate): Int =
        hw.rates.filter { it >= cap && it <= fullRateFps }.minOrNull() ?: fullRateFps

    private fun applyHardware(cap: Int, reason: String) {
        val hw = hardware ?: return
        val want = cameraRateFor(cap, hw)
        if (want == cameraFps) return
        val from = cameraFps
        val id = Transitions.nextId()
        Transitions.emit(id, name, "requested", "sensor ${from}/s", "sensor ${want}/s", "camera sensor follows ($reason)")
        hw.request(want) { ok ->
            if (cameraRateFor(rateCap, hw) != want) return@request
            if (ok) {
                cameraFps = want
                Transitions.emit(id, name, "applied", "sensor ${from}/s", "sensor ${want}/s", "camera sensor follows ($reason)")
                onEvent("Camera sensor: $from → $want FPS")
            } else {
                hardware = null
                Transitions.emit(id, name, "failed", "sensor ${from}/s", "sensor ${want}/s", "camera refused — software skipping only")
                onEvent("Camera refused $want FPS → frames skipped in software only")
            }
        }
    }

    /** Analyzer thread, top of every frame: process this one? */
    fun shouldProcess(nowNs: Long): Boolean {
        received.incrementAndGet()
        val cap = rateCap.toDouble()
        val pace = paceFps
        val dt = if (lastNs == 0L) 0.0 else (nowNs - lastNs) / 1e9
        lastNs = nowNs
        val capBinds = cap < cameraFps - 0.5
        val limit = when {
            pace != null && capBinds -> min(pace, cap)
            pace != null -> pace
            capBinds -> cap
            else -> null
        }
        if (limit == null) {
            credit = 1.0
            return true
        }
        credit = min(2.0, credit + limit * dt)
        if (credit >= 1.0) {
            credit -= 1.0
            return true
        }
        if (capBinds && (pace == null || cap <= pace)) skipped++ else paced++
        return false
    }

    companion object {
        const val FULL_RATE = 30
    }
}
