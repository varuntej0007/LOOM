package com.loom.studio

import com.loom.studio.core.Level
import com.loom.studio.core.Mode
import com.loom.studio.weaver.ChipState
import com.loom.studio.weaver.Decision
import com.loom.studio.weaver.Sense
import com.loom.studio.weaver.WeaverCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Weaver decisions on a recorded-style trace: same input → same decisions (brief §14). */
class WeaverCoreTest {

    /** A tiny simulated phone: levels change as the Weaver decides; heat follows a trace. */
    private fun replay(heat: List<Float>, fps: Double = 20.0, cap: Double = 25.0): List<String> {
        val core = WeaverCore().apply { goal = 15.0; heatLimit = 0.8f; cutOrder = listOf("display", "inference", "source") }
        val levels = mutableMapOf("display" to Level.HIGH, "inference" to Level.HIGH, "source" to Level.HIGH)
        val log = mutableListOf<String>()
        heat.forEachIndexed { t, h ->
            val chips = levels.map { (k, l) -> ChipState(k, k, l, l != Level.ECO, l != Level.HIGH, lowerIsFaster = k == "inference") }
            when (val d = core.step(Sense(fps, cap, h, 0, true, true), chips, t.toLong())) {
                is Decision.Lower -> { levels[d.key] = Level.entries[levels[d.key]!!.ordinal + 1]; log += "$t lower ${d.key}" }
                is Decision.Raise -> { levels[d.key] = Level.entries[levels[d.key]!!.ordinal - 1]; log += "$t raise ${d.key}" }
                is Decision.Floor -> log += "$t floor"
                else -> Unit
            }
        }
        return log
    }

    private val trace: List<Float> = List(10) { 0.6f } + List(40) { 0.86f } + List(60) { 0.55f }

    @Test fun deterministic() {
        assertEquals(replay(trace), replay(trace))
    }

    @Test fun hotStepsDownInCutOrderOneAtATimeWithCooldown() {
        val log = replay(trace)
        val lowers = log.filter { "lower" in it }
        assertEquals("display", lowers[0].substringAfter("lower "))
        assertEquals("display", lowers[1].substringAfter("lower "))   // display HIGH→BAL→ECO first
        assertEquals("inference", lowers[2].substringAfter("lower "))
        // at least 6 s between decisions (2 s hot window after a 5 s cooldown)
        val times = lowers.map { it.substringBefore(" ").toInt() }
        times.zipWithNext().forEach { (a, b) -> assertTrue("decisions at $a and $b too close", b - a >= 6) }
    }

    @Test fun coolAndComfortableRaisesMostValuableFirst() {
        val log = replay(trace)
        val firstRaise = log.first { "raise" in it }
        // reverse cut order: source, then inference, then display
        assertTrue(firstRaise, firstRaise.endsWith("source") || firstRaise.endsWith("inference"))
    }

    @Test fun belowGoalLowersOnlyMeasuredFasterChiplet() {
        val core = WeaverCore().apply { goal = 15.0 }
        val chips = listOf(
            ChipState("display", "Display", Level.HIGH, true, false),
            ChipState("inference", "Inference", Level.HIGH, true, false, lowerIsFaster = true),
        )
        var d: Decision = Decision.Hold("")
        for (t in 0..5) {
            d = core.step(Sense(9.0, 10.0, 0.5f, 0, true, true), chips, t.toLong())
            if (d !is Decision.Hold) break
        }
        assertTrue(d is Decision.Lower && d.key == "inference")
    }

    @Test fun safetyGuardLeavesFixedAfter20sSevere() {
        val core = WeaverCore().apply { mode = Mode.FIXED }
        var switched = -1
        for (t in 0 until 25) {
            val d = core.step(Sense(10.0, 10.0, 1.1f, 3, true, true), emptyList(), t.toLong())
            if (d is Decision.SwitchMode && switched < 0) switched = t
        }
        assertEquals(19, switched)
        assertEquals(Mode.ADAPTIVE, core.mode)
    }

    @Test fun followsThePlanBeforeItGetsHot() {
        val core = WeaverCore().apply { goal = 15.0; heatLimit = 0.8f; cutOrder = listOf("display", "inference", "source") }
        val chips = listOf(
            ChipState("display", "Display", Level.HIGH, true, false, planLevel = Level.HIGH),
            ChipState("source", "Capture", Level.HIGH, true, false, rateCap = 30.0, planLevel = Level.BALANCED),
        )
        var d: Decision = Decision.Hold("")
        for (t in 0..5) {
            d = core.step(Sense(20.0, 25.0, 0.70f, 0, true, true), chips, t.toLong())
            if (d !is Decision.Hold) break
        }
        assertTrue(d.toString(), d is Decision.Lower && d.key == "source")
    }

    @Test fun neverRaisesAboveThePlan() {
        val core = WeaverCore().apply { goal = 10.0 }
        val chips = listOf(ChipState("source", "Capture", Level.BALANCED, true, true, rateCap = 15.0, planLevel = Level.BALANCED, planUpLevel = Level.BALANCED))
        for (t in 0..20) {
            val d = core.step(Sense(12.0, 30.0, 0.5f, 0, true, true), chips, t.toLong())
            assertTrue(d.toString(), d !is Decision.Raise)
        }
    }

    /** Hot with every chiplet at its floor: rest halves the processed rate, then lifts it once cool. */
    @Test fun restWhenHotAtFloorThenLift() {
        val core = WeaverCore().apply { goal = 15.0; heatLimit = 0.8f }
        val chips = listOf(ChipState("inference", "Inference", Level.ECO, false, true))
        val rests = mutableListOf<Double?>()
        var t = 0L
        repeat(60) { val d = core.step(Sense(10.0, 12.0, 0.95f, 2, true, true), chips, t++); if (d is Decision.Rest) rests += d.pace }
        assertEquals(listOf<Double?>(5.0, 2.5, 1.25, 1.0, 0.2), rests)
        assertEquals(0.2, core.restPace!!, 1e-9)
        // Cool: eases ×2 every ~10 s until the goal is reachable again, then rest is lifted.
        val eases = mutableListOf<Double?>()
        repeat(200) { val d = core.step(Sense(0.2, 12.0, 0.6f, 0, true, true), chips, t++); if (d is Decision.Rest) eases += d.pace }
        assertEquals(null, core.restPace)
        assertEquals(listOf<Double?>(0.4, 0.8, 1.6, 3.2, 6.4, 12.8, null), eases)
        // While resting it never raised a chiplet.
    }
}
