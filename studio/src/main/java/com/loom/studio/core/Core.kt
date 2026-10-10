package com.loom.studio.core

import android.os.SystemClock

// Copied from app/ (com.loom.runtime.core.Core.kt) and generalised for Studio.
// The EfficientDet-specific profiles are gone: in Studio, profiles come from data
// (the pipeline template + profiles.json measured on this phone), starting in M2/M3.

/** Quality gears shared by every adaptable chiplet. Order matters: HIGH is best quality. */
enum class Level { HIGH, BALANCED, ECO }

fun Level.lower(): Level? = Level.entries.getOrNull(ordinal + 1)
fun Level.higher(): Level? = Level.entries.getOrNull(ordinal - 1)

/** Who is in charge of the profiles. Same names as app/, so LOOM_LIVE "mode" matches. */
enum class Mode(val label: String) {
    ADAPTIVE("Adaptive"),   // the Weaver decides
    FIXED("Fixed HIGH"),    // baseline: everything HIGH, Weaver observes only
    MANUAL("Manual"),       // a person picked profiles; Weaver paused
}

/** What kind of AI a model is (brief §5). Used in telemetry ("task") from M0. */
enum class TaskKind { VISION, LLM, AUDIO, TEXT, GENERIC }

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

object Clock {
    fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()
    fun ms(fromNs: Long, toNs: Long): Double = (toNs - fromNs) / 1_000_000.0
}

/**
 * Structured record of every chiplet change, phase by phase, for the laptop dashboard
 * (logcat tag LOOM_TRX, read over USB). Same format as app/. Phases:
 *   requested → built → swapped      (background build while the old one keeps serving,
 *                                     swapped between two units: frame / token / chunk)
 *   applied                          (knob changes that take effect at the next unit)
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
