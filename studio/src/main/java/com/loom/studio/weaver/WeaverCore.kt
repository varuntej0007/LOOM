package com.loom.studio.weaver

import com.loom.studio.core.Level
import com.loom.studio.core.Mode
import java.util.Locale

/** Tunable thresholds, same defaults as app/'s Weaver (brief §8). */
data class Policy(
    val violationSeconds: Int = 3,      // below goal this long → act
    val tolerance: Double = 0.5,        // "below goal" = more than this under it
    val slackSeconds: Int = 10,         // comfortable this long → raise one chiplet
    val slackMargin: Double = 3.0,      // comfortable = capacity ≥ goal + margin
    val hotSeconds: Int = 2,            // over the heat limit this long → lower one chiplet
    val cooldownSeconds: Int = 5,       // no decisions after any change
    val coolMargin: Float = 0.08f,      // raises only when heat < limit − margin (hysteresis)
    val regretSeconds: Int = 30,        // lowered this soon after a raise → back off raising it
    val backoffStartSeconds: Int = 30,
    val backoffMaxSeconds: Int = 240,
    val severeSafetySeconds: Int = 20,  // Fixed mode at SEVERE this long → switch to Adaptive
)

/** What the Weaver knows about one adaptive chiplet at this tick. */
data class ChipState(
    val key: String,
    val name: String,
    val level: Level,
    val canLower: Boolean,
    val canRaise: Boolean,
    /** Measured (profiles.json): is the next lower profile faster per unit? null = unknown. */
    val lowerIsFaster: Boolean? = null,
    /** For the source: the unit-rate cap of the current profile (e.g. camera 15/s). */
    val rateCap: Double? = null,
)

/** One second of sensing. */
data class Sense(
    val metric: Double,        // units/s actually processed
    val capacity: Double,      // units/s the chain could do (1000 / p50)
    val heat: Float,           // max(headroom, forecast limited) — NaN when unknown
    val thermalStatus: Int,    // PowerManager.THERMAL_STATUS_*
    val sourceActive: Boolean, // camera delivering / generic loop running
    val ready: Boolean,        // inference loaded
)

sealed class Decision {
    abstract val status: String
    data class Hold(override val status: String) : Decision()
    data class Lower(val key: String, val reason: String, override val status: String = "changed · cooldown") : Decision()
    data class Raise(val key: String, val reason: String, override val status: String = "changed · cooldown") : Decision()
    data class SwitchMode(val mode: Mode, val reason: String, override val status: String = "safety guard") : Decision()
    data class Floor(val reason: String, override val status: String = "at lowest profile — goal not reachable") : Decision()
}

/**
 * The Weaver's decision logic, free of Android so it can be unit-tested on a recorded trace
 * (same input → same decisions). One call per second; at most one chiplet changes per call.
 *
 *  - hot (heat > limit for 2 s)        → lower ONE chiplet, first in the cut order that can go lower
 *  - below goal for 3 s, heat OK       → raise the source if its rate cap is below the goal;
 *                                        else lower a chiplet whose lower profile is MEASURED faster
 *  - comfortable for 10 s and cool     → raise ONE chiplet, most valuable first (reverse cut order)
 *  - 5 s cooldown after any change, upgrade back-off when an upgrade had to be undone
 *  - Fixed mode at SEVERE for 20 s     → switch to Adaptive (Android otherwise closes the camera)
 */
class WeaverCore(val policy: Policy = Policy()) {
    var mode: Mode = Mode.ADAPTIVE
    var goal: Double = 15.0
    /** null = ignore heat. */
    var heatLimit: Float? = 0.80f
    var cutOrder: List<String> = listOf("display", "inference", "source")

    private var belowSecs = 0
    private var hotSecs = 0
    private var slackSecs = 0
    private var severeSecs = 0
    private var cooldown = 0
    private var floorNoted = false
    private class Backoff(var blockedUntil: Long = 0, var length: Int = 0, var lastRaise: Long = -1_000_000)
    private val backoff = HashMap<String, Backoff>()

    fun resetWindows() {
        belowSecs = 0; hotSecs = 0; slackSecs = 0
    }

    fun step(s: Sense, chips: List<ChipState>, nowSec: Long): Decision {
        // Safety guard first: works in every mode.
        severeSecs = if (s.thermalStatus >= THERMAL_SEVERE) severeSecs + 1 else 0
        if (mode == Mode.FIXED && severeSecs >= policy.severeSafetySeconds) {
            severeSecs = 0
            return switchModeOnce(Mode.ADAPTIVE, "thermal SEVERE for ${policy.severeSafetySeconds} s in Fixed mode")
        }
        if (mode != Mode.ADAPTIVE) {
            resetWindows()
            return Decision.Hold(if (mode == Mode.FIXED) "fixed HIGH — observing only" else "manual — Weaver paused")
        }
        if (!s.ready) return Decision.Hold("waiting for the model")
        if (!s.sourceActive) { resetWindows(); return Decision.Hold("source paused — waiting") }
        if (cooldown > 0) {
            cooldown--
            resetWindows()
            return Decision.Hold("cooldown ${cooldown + 1} s")
        }

        val limit = heatLimit
        val heatKnown = !s.heat.isNaN()
        val hot = limit != null && heatKnown && s.heat > limit
        val cool = limit == null || !heatKnown || s.heat < limit - policy.coolMargin
        val below = s.metric < goal - policy.tolerance
        val comfortable = s.capacity >= goal + policy.slackMargin && cool

        hotSecs = if (hot) hotSecs + 1 else 0
        belowSecs = if (below && !hot) belowSecs + 1 else 0
        slackSecs = if (comfortable && !below) slackSecs + 1 else 0

        val byKey = chips.associateBy { it.key }
        val order = cutOrder.mapNotNull { byKey[it] } + chips.filter { it.key !in cutOrder }

        if (hotSecs >= policy.hotSeconds) {
            val why = "heat ${f3(s.heat)} > ${f2(limit!!)}"
            val pick = order.firstOrNull { it.canLower } ?: return floor(why)
            return lower(pick, why, nowSec)
        }
        if (belowSecs >= policy.violationSeconds) {
            val why = "${f2(s.metric)}/s < goal ${f1(goal)} for $belowSecs s"
            val src = order.firstOrNull { it.rateCap != null && it.rateCap < goal && it.canRaise }
            if (src != null && cool) return raise(src, "$why — source capped at ${f1(src.rateCap!!)}/s", nowSec)
            val pick = order.firstOrNull { it.key != "source" && it.key != "display" && it.canLower && it.lowerIsFaster == true }
                ?: return floor("$why (no measured faster profile)")
            return lower(pick, why, nowSec)
        }
        if (slackSecs >= policy.slackSeconds) {
            val why = "capacity ${f1(s.capacity)} ≥ ${f1(goal + policy.slackMargin)}/s" +
                (if (limit != null && heatKnown) " and heat ${f2(s.heat)} < ${f2(limit - policy.coolMargin)}" else "") + " for $slackSecs s"
            val pick = order.reversed().firstOrNull { it.canRaise && nowSec >= (backoff[it.key]?.blockedUntil ?: 0L) }
            if (pick == null) {
                slackSecs = 0
                return Decision.Hold("best quality, goal met")
            }
            return raise(pick, why, nowSec)
        }
        floorNoted = false
        return Decision.Hold(
            when {
                hot -> "too warm $hotSecs/${policy.hotSeconds} s"
                below -> "below goal $belowSecs/${policy.violationSeconds} s"
                comfortable -> "slack $slackSecs/${policy.slackSeconds} s"
                !cool -> "warm — holding, no upgrades"
                else -> "holding"
            }
        )
    }

    private fun switchModeOnce(m: Mode, why: String): Decision {
        mode = m
        resetWindows()
        cooldown = policy.cooldownSeconds
        return Decision.SwitchMode(m, why)
    }

    private fun lower(c: ChipState, why: String, now: Long): Decision {
        val b = backoff.getOrPut(c.key) { Backoff() }
        var suffix = ""
        if (now - b.lastRaise < policy.regretSeconds) {
            b.length = if (b.length == 0) policy.backoffStartSeconds else minOf(b.length * 2, policy.backoffMaxSeconds)
            b.blockedUntil = now + b.length
            suffix = " (raising ${c.name} paused ${b.length} s)"
        }
        cooldown = policy.cooldownSeconds
        resetWindows()
        return Decision.Lower(c.key, why + suffix)
    }

    private fun raise(c: ChipState, why: String, now: Long): Decision {
        backoff.getOrPut(c.key) { Backoff() }.lastRaise = now
        cooldown = policy.cooldownSeconds
        resetWindows()
        return Decision.Raise(c.key, why)
    }

    private fun floor(why: String): Decision {
        resetWindows()
        val first = !floorNoted
        floorNoted = true
        return if (first) Decision.Floor("$why, but every chiplet is at its lowest useful profile") else Decision.Hold("at lowest profile — goal not reachable")
    }

    companion object {
        const val THERMAL_SEVERE = 3
        fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
        fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
        fun f2(v: Float) = String.format(Locale.US, "%.2f", v)
        fun f3(v: Float) = String.format(Locale.US, "%.3f", v)
    }
}
