package com.loom.studio

import com.loom.studio.chiplets.Det
import com.loom.studio.chiplets.UnitResult
import com.loom.studio.profiler.QualityProxy
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityProxyTest {
    private val a = Det("person", 0.9f, 0.1f, 0.1f, 0.5f, 0.9f)
    private val b = Det("car", 0.8f, 0.6f, 0.5f, 0.9f, 0.8f)

    @Test fun identicalIsOne() = assertEquals(1.0, QualityProxy.boxF1(listOf(a, b), listOf(a, b)), 1e-9)
    @Test fun halfMissingIsTwoThirds() = assertEquals(2.0 / 3, QualityProxy.boxF1(listOf(a, b), listOf(a)), 1e-9)
    @Test fun bothEmptyAgree() = assertEquals(1.0, QualityProxy.one(UnitResult(0), UnitResult(1)), 1e-9)
    @Test fun top1() = assertEquals(0.0, QualityProxy.one(UnitResult(0, labels = listOf("cat" to 0.9f)), UnitResult(0, labels = listOf("dog" to 0.6f))), 1e-9)
}
