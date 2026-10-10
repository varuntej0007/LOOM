package com.loom.studio.chiplets

import com.loom.studio.core.Level
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Display chiplet. Two real levers:
 *  - overlay refresh: every result / ≤15 per s / ≤5 per s (less Compose + GPU composition work)
 *  - screen brightness: system / 35% / 8% (the display is one of the largest power draws)
 */
class Display(onEvent: (String) -> Unit) : KnobChiplet(
    name = "Display",
    key = "display",
    profiles = listOf(
        Profile(Level.HIGH, "overlay live · screen normal", mapOf("hz" to 60, "brightness" to -1f)),
        Profile(Level.BALANCED, "overlay 15/s · screen 35%", mapOf("hz" to 15, "brightness" to 0.35f)),
        Profile(Level.ECO, "overlay 5/s · screen 8%", mapOf("hz" to 5, "brightness" to 0.08f)),
    ),
    onEvent = onEvent,
) {
    private val _result = MutableStateFlow<UnitResult?>(null)
    val result: StateFlow<UnitResult?> = _result

    /** Window brightness for the activity: null = follow the system. */
    private val _brightness = MutableStateFlow<Float?>(null)
    val brightness: StateFlow<Float?> = _brightness

    /** Upright frame size of the published result (overlay scaling). */
    @Volatile var frameW = 0
    @Volatile var frameH = 0

    private var lastPublishNs = 0L
    @Volatile private var lastMs = 0.0
    @Volatile private var minIntervalNs = 0L

    override fun cost() = CostSample(lastMs, 0.0)
    override fun quality(): Double = when (level) { Level.HIGH -> 1.0; Level.BALANCED -> 0.9; Level.ECO -> 0.7 }

    override fun apply(p: Profile, reason: String) {
        val hz = (p.knobs["hz"] as? Int) ?: 60
        minIntervalNs = if (hz >= 60) 0L else 1_000_000_000L / hz
        val b = (p.knobs["brightness"] as? Float) ?: -1f
        _brightness.value = if (b < 0f) null else b
    }

    fun publish(r: UnitResult, w: Int, h: Int) {
        val t0 = System.nanoTime()
        if (minIntervalNs > 0 && t0 - lastPublishNs < minIntervalNs) return
        lastPublishNs = t0
        frameW = w
        frameH = h
        _result.value = r
        lastMs = (System.nanoTime() - t0) / 1e6
    }

    fun clear() {
        _result.value = null
        _brightness.value = null
    }
}
