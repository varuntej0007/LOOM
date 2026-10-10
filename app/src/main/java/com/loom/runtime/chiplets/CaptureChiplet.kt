package com.loom.runtime.chiplets

import com.loom.runtime.core.AdaptiveChiplet
import com.loom.runtime.core.Level
import com.loom.runtime.core.Profiles
import com.loom.runtime.core.Transitions
import kotlin.math.min

/**
 * Capture VC. Decides how many camera frames exist and which of them are processed.
 *
 * Profile = a frame-rate cap: HIGH 30/s, BALANCED 15/s, ECO 10/s (full rate / 1, 2, 3).
 *  - Hardware lever (preferred): the CAMERA is asked for the lowest frame rate it supports that
 *    still covers the cap. Frames that are never captured cost the sensor and image processor
 *    nothing — this is the part of the heat that skipping frames in software cannot touch.
 *  - Software lever: whatever the camera still delivers above the cap is skipped evenly.
 *  - Pacing (Weaver, Adaptive mode): additionally process no more than goal + 3 frames per second.
 * Both software limits share one credit counter, so processed frames are spread evenly.
 */
class CaptureChiplet(private val onEvent: (String) -> Unit) : AdaptiveChiplet {
    override val name = "Capture"

    /** Installed by MainActivity once the camera is bound. */
    interface HardwareRate {
        /** Frame rates the camera can be asked for (top of a supported range), including full rate. */
        val rates: List<Int>
        /** Ask the camera for [fps]; [done] gets true when the camera accepted it. Must not block. */
        fun request(fps: Int, done: (Boolean) -> Unit)
    }

    @Volatile override var level: Level = Level.HIGH
        private set

    /** Full camera rate (the 30 FPS lock). */
    val fullRateFps: Int = FULL_RATE

    /** Processing cap of the current profile, frames per second. */
    @Volatile var rateCap: Int = capFor(Level.HIGH)
        private set

    /** Frame rate the camera has confirmed (full rate until the hardware lever is used). */
    @Volatile var cameraFps: Int = FULL_RATE
        private set

    @Volatile private var hardware: HardwareRate? = null

    /** Max processed frames per second set by the Weaver, or null for no pacing. */
    @Volatile var paceFps: Double? = null

    // Analyzer thread only.
    private var credit = 1.0
    private var lastNs = 0L

    /** Camera frames dropped by the profile cap since launch (analyzer thread only). */
    var skipped = 0L
        private set

    /** Camera frames dropped by pacing since launch (analyzer thread only). */
    var paced = 0L
        private set

    override val profileLabel: String
        get() {
            val base = when {
                cameraFps < fullRateFps -> "camera ${cameraFps}/s" + if (rateCap < cameraFps) ", use ≤$rateCap/s" else ""
                rateCap < fullRateFps -> "use ≤$rateCap/s of 30"
                else -> "every frame"
            }
            return paceFps?.let { "$base ≤${it.toInt()}/s" } ?: base
        }

    private fun capFor(level: Level) = fullRateFps / Profiles.captureStride.getValue(level)

    private fun label(cap: Int) = if (cap >= fullRateFps) "${fullRateFps}/s" else "$cap/s"

    /** Called once the camera is bound and its frame rates are known. */
    fun attachHardware(rate: HardwareRate) {
        hardware = rate
        applyHardware(rateCap, "camera ready")
    }

    override fun requestLevel(level: Level, reason: String) {
        val newCap = capFor(level)
        val oldCap = rateCap
        if (newCap == oldCap && level == this.level) return
        this.level = level
        rateCap = newCap // the software cap takes effect at the next frame
        Transitions.emit(Transitions.nextId(), name, "applied", "frames ${label(oldCap)}", "frames ${label(newCap)}", reason)
        onEvent("$reason → Capture: frames ${label(oldCap)} → ${label(newCap)}")
        applyHardware(newCap, reason)
    }

    /** Lowest camera rate that still covers [cap]. */
    private fun cameraRateFor(cap: Int, hw: HardwareRate): Int =
        hw.rates.filter { it >= cap && it <= fullRateFps }.minOrNull() ?: fullRateFps

    private fun applyHardware(cap: Int, reason: String) {
        val hw = hardware ?: return
        val want = cameraRateFor(cap, hw)
        if (want == cameraFps) return
        val from = cameraFps
        // Speeding the camera up: the cap already limits processing, so nothing is lost meanwhile.
        val id = Transitions.nextId()
        Transitions.emit(id, name, "requested", "camera ${from}/s", "camera ${want}/s", "camera sensor follows ($reason)")
        hw.request(want) { ok ->
            if (cameraRateFor(rateCap, hw) != want) return@request // superseded
            if (ok) {
                cameraFps = want
                Transitions.emit(id, name, "applied", "camera ${from}/s", "camera ${want}/s", "camera sensor follows ($reason)")
                onEvent("Camera sensor: ${from} → ${want} FPS")
            } else {
                // Camera refused: stop using the hardware lever; the software cap still works.
                hardware = null
                Transitions.emit(id, name, "failed", "camera ${from}/s", "camera ${want}/s", "camera refused — software only")
                onEvent("Camera refused $want FPS → frames skipped in software only")
            }
        }
    }

    /** Called at the top of every analyze() call. */
    fun shouldProcess(nowNs: Long): Boolean {
        val cap = rateCap.toDouble()
        val pace = paceFps
        val dt = if (lastNs == 0L) 0.0 else (nowNs - lastNs) / 1e9
        lastNs = nowNs
        // No limit below what the camera delivers → process everything.
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
        // Earn `limit` credits per second of camera time; spend one per processed frame.
        // Capped at 2 so a slow stretch never causes a burst afterwards.
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
