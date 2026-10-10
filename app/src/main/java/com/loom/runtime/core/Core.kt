package com.loom.runtime.core

import android.os.SystemClock

/** The declared goal the Weaver will defend (Phase 2). Shown on the dashboard from day one. */
object Goal {
    /** Enough for walking guidance; every frame above "enough" is wasted heat and battery. */
    const val TARGET_FPS = 15.0
    /** Goal used when the battery is low and the phone is not charging. */
    const val LOW_BATTERY_FPS = 10.0
    const val LOW_BATTERY_PCT = 30
}

/** Quality gears shared by every adaptable chiplet. Order matters: HIGH is best quality. */
enum class Level { HIGH, BALANCED, ECO }

enum class Compute { GPU, CPU }

/** An inference profile is a real compute knob: model size and compute unit (blueprint §5). */
data class InferenceProfile(val level: Level, val modelAsset: String, val compute: Compute) {
    val label: String
        get() = modelAsset.removePrefix("efficientdet_").removeSuffix(".tflite") + "/" + compute
}

object Profiles {
    val inference: Map<Level, InferenceProfile> = mapOf(
        Level.HIGH to InferenceProfile(Level.HIGH, "efficientdet_lite2.tflite", Compute.GPU),
        Level.BALANCED to InferenceProfile(Level.BALANCED, "efficientdet_lite0.tflite", Compute.GPU),
        Level.ECO to InferenceProfile(Level.ECO, "efficientdet_lite0.tflite", Compute.CPU),
    )

    /** Capture frame reduction N: use at most 30/N frames per second (the camera slows down when it can). */
    val captureStride: Map<Level, Int> = mapOf(
        Level.HIGH to 1,
        Level.BALANCED to 2,
        Level.ECO to 3,
    )

    /** Narrator interval in ms; null = paused (detection only). */
    val narratorIntervalMs: Map<Level, Long?> = mapOf(
        Level.HIGH to 3_000L,
        Level.BALANCED to 6_000L,
        Level.ECO to null,
    )
}

fun Level.lower(): Level? = Level.entries.getOrNull(ordinal + 1)
fun Level.higher(): Level? = Level.entries.getOrNull(ordinal - 1)

/** Who is in charge of the profiles. */
enum class Mode(val label: String) {
    ADAPTIVE("Adaptive"),   // the Weaver decides
    FIXED("Fixed HIGH"),    // benchmark Run A: everything HIGH, Weaver observes only
    MANUAL("Manual"),       // a person tapped a profile chip; Weaver paused
}

/** Controlled extra load, so a genuine profile change can always be demonstrated. */
enum class StressMode(val label: String) { OFF("Off"), GPU("GPU"), CPU("CPU") }

/** A virtual chiplet: an independent pipeline stage that reports its own profile. */
interface Chiplet {
    val name: String
    val profileLabel: String
}

/** A chiplet the Weaver may reconfigure at runtime. */
interface AdaptiveChiplet : Chiplet {
    val level: Level
    fun requestLevel(level: Level, reason: String)
}

/** One detected object, in the coordinates of the upright (rotation-corrected) frame. */
data class Box(
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class FrameResult(
    val frameNo: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val boxes: List<Box>,
)

/** Everything measured for one processed frame; one CSV row. */
data class FrameSample(
    val frameNo: Long,
    val captureMs: Double,
    val preprocessMs: Double,
    val inferenceMs: Double,
    val narratorMs: Double?, // null until the Narrator chiplet exists
    val displayMs: Double,
    val totalMs: Double,
    val detections: Int,
    val meanConf: Double,
    val profileCapture: String,
    val profileInference: String,
    val profileNarrator: String,
    val event: String?,
    val swapGapMs: Double?,
    val narration: String?,
)

object Clock {
    fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()
    fun ms(fromNs: Long, toNs: Long): Double = (toNs - fromNs) / 1_000_000.0
}

/**
 * Structured record of every chiplet change, phase by phase, for the laptop dashboard
 * (logcat tag LOOM_TRX, read over USB). Phases:
 *   requested → built → swapped      (Inference: new model built in the background while the
 *                                     old one keeps serving, swapped between two frames)
 *   applied                          (Capture / Narrator / Display: take effect next frame)
 *   failed                           (a build failed; the old profile stays live)
 */
object Transitions {
    const val TAG = "LOOM_TRX"
    private val seq = java.util.concurrent.atomic.AtomicLong(0)

    fun nextId(): Long = seq.incrementAndGet()

    fun emit(
        id: Long,
        chiplet: String,
        phase: String,
        from: String,
        to: String,
        reason: String,
        buildMs: Double? = null,
        swapGapMs: Double? = null,
    ) {
        val j = org.json.JSONObject()
            .put("id", id)
            .put("t", System.currentTimeMillis())
            .put("chiplet", chiplet)
            .put("phase", phase)
            .put("from", from)
            .put("to", to)
            .put("reason", reason)
            .put("build_ms", buildMs ?: org.json.JSONObject.NULL)
            .put("swap_gap_ms", swapGapMs ?: org.json.JSONObject.NULL)
        android.util.Log.i(TAG, j.toString())
    }
}
