package com.loom.runtime.chiplets

import com.loom.runtime.core.AdaptiveChiplet
import com.loom.runtime.core.Box
import com.loom.runtime.core.FrameResult
import com.loom.runtime.core.Level
import com.loom.runtime.core.Transitions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.min

/**
 * Display VC.
 *
 * 1. Steady boxes: a light tracker matches each detection to last frame's box (same label,
 *    overlap ≥ 30%), smooths its position, and keeps it for 2 missed frames. Boxes stop
 *    flickering and jumping without any extra AI work.
 * 2. Screen brightness is a real power/heat knob — the display is one of the largest power
 *    draws on a phone, and a visually impaired user does not need a bright screen:
 *      HIGH = system brightness · BALANCED = 35% · ECO = 8%.
 */
class DisplayChiplet(private val onEvent: (String) -> Unit) : AdaptiveChiplet {
    override val name = "Display"

    @Volatile override var level: Level = Level.HIGH
        private set

    private val _frames = MutableStateFlow<FrameResult?>(null)
    val frames: StateFlow<FrameResult?> = _frames

    /** Window brightness to apply: null = follow the system setting. Read by the activity. */
    private val _brightness = MutableStateFlow<Float?>(null)
    val brightness: StateFlow<Float?> = _brightness

    override val profileLabel: String
        get() = when (level) {
            Level.HIGH -> "boxes · screen normal"
            Level.BALANCED -> "boxes · screen 35%"
            Level.ECO -> "boxes · screen 8%"
        }

    override fun requestLevel(level: Level, reason: String) {
        if (level == this.level) return
        val old = profileLabel
        this.level = level
        _brightness.value = when (level) {
            Level.HIGH -> null
            Level.BALANCED -> 0.35f
            Level.ECO -> 0.08f
        }
        Transitions.emit(Transitions.nextId(), name, "applied", old, profileLabel, reason)
        onEvent("$reason → Display: $old → $profileLabel")
    }

    // ---- steady boxes (analyzer thread only) ----
    private class Track(var box: Box, var misses: Int)
    private val tracks = mutableListOf<Track>()

    fun publish(result: FrameResult) {
        _frames.value = result.copy(boxes = smooth(result.boxes))
    }

    private fun smooth(boxes: List<Box>): List<Box> {
        val matched = BooleanArray(tracks.size)
        val fresh = mutableListOf<Track>()
        for (b in boxes.sortedByDescending { it.score }) {
            var best = -1
            var bestIou = MATCH_IOU
            for ((i, t) in tracks.withIndex()) {
                if (matched[i] || t.box.label != b.label) continue
                val o = iou(t.box, b)
                if (o >= bestIou) {
                    bestIou = o
                    best = i
                }
            }
            if (best >= 0) {
                matched[best] = true
                val t = tracks[best]
                t.box = blend(t.box, b)
                t.misses = 0
            } else {
                fresh += Track(b, 0)
            }
        }
        for ((i, t) in tracks.withIndex()) if (!matched[i]) t.misses++
        tracks.removeAll { it.misses > KEEP_MISSES }
        tracks += fresh
        return tracks.map { it.box }
    }

    private fun blend(old: Box, new: Box) = Box(
        new.label, new.score,
        old.left + (new.left - old.left) * ALPHA,
        old.top + (new.top - old.top) * ALPHA,
        old.right + (new.right - old.right) * ALPHA,
        old.bottom + (new.bottom - old.bottom) * ALPHA,
    )

    private fun iou(a: Box, b: Box): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - inter
        return if (union <= 0f) 0f else inter / union
    }

    companion object {
        const val MATCH_IOU = 0.3f
        const val ALPHA = 0.5f      // how far a box moves toward the new detection each frame
        const val KEEP_MISSES = 2   // frames a box survives without a detection
    }
}
