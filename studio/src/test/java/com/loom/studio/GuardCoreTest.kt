package com.loom.studio

import com.loom.studio.guard.GuardCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardCoreTest {

    @Test fun stepsUpOneLeverAtATimeWhenHot() {
        val g = GuardCore(0.75f)
        val c1 = g.step(0, 0.80f, 1)
        assertTrue(c1 != null && c1.up && c1.lever == GuardCore.Lever.BRIGHTNESS)
        assertTrue(g.step(5_000, 0.80f, 1) == null) // cooldown
        val c2 = g.step(10_000, 0.80f, 1)
        assertTrue(c2 != null && c2.lever == GuardCore.Lever.REFRESH)
        g.step(20_000, 0.80f, 1)
        assertEquals(3, g.level)
        assertTrue(g.step(30_000, 0.95f, 3) == null) // already at max
    }

    @Test fun severeStepsFaster() {
        val g = GuardCore(0.75f)
        g.step(0, 0.70f, 3)
        assertEquals(1, g.level)
        g.step(3_000, 0.70f, 3)
        assertEquals(2, g.level)
    }

    @Test fun releasesOnlyAfterHoldingCool() {
        val g = GuardCore(0.75f)
        g.step(0, 0.80f, 1)
        g.step(10_000, 0.80f, 1)
        assertEquals(2, g.level)
        // In the hysteresis band (0.67..0.75): hold.
        assertTrue(g.step(20_000, 0.70f, 1) == null)
        // Cool, but not for long enough yet.
        assertTrue(g.step(21_000, 0.60f, 0) == null)
        assertTrue(g.step(40_000, 0.60f, 0) == null)
        val c = g.step(51_000, 0.60f, 0)
        assertTrue(c != null && !c.up && c.lever == GuardCore.Lever.REFRESH)
        assertEquals(1, g.level)
        assertTrue(g.step(60_000, 0.60f, 0) == null)
        assertTrue(g.step(81_000, 0.60f, 0) != null)
        assertEquals(0, g.level)
    }

    @Test fun moderateStatusBlocksRelease() {
        val g = GuardCore(0.75f)
        g.step(0, 0.80f, 1)
        for (t in 1..100) g.step(t * 1000L, 0.50f, 2)
        assertEquals(1, g.level)
    }
}
