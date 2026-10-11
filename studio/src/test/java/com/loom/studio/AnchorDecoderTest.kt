package com.loom.studio

import com.loom.studio.chiplets.AnchorDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorDecoderTest {

    @Test fun efficientDetLite0AnchorCount() {
        // 320×320: (40² + 20² + 10² + 5² + 3²) × 9 = 19206, as in efficientdet_lite0.tflite
        assertTrue(AnchorDecoder.efficientDetAnchors(320, 320, 19206) != null)
        assertTrue(AnchorDecoder.efficientDetAnchors(320, 320, 19000) == null)
    }

    @Test fun zeroOffsetsReturnTheAnchorBox() {
        val n = 19206
        val c = 3
        val target = 100 // some anchor
        val a = AnchorDecoder.efficientDetAnchors(320, 320, n)!!
        val boxes = FloatArray(n * 4)
        val scores = FloatArray(n * c)
        scores[target * c + 2] = 0.9f
        val r = AnchorDecoder.decode(n, c, 320, 320, 0.4f, { boxes[it] }, { scores[it] })
        assertEquals(1, r.size)
        assertEquals(2, r[0].cls)
        assertEquals(0.9f, r[0].score, 1e-6f)
        assertEquals((a[target * 4 + 1] - a[target * 4 + 3] / 2).coerceIn(0f, 1f), r[0].left, 1e-5f)
        assertEquals((a[target * 4] + a[target * 4 + 2] / 2).coerceIn(0f, 1f), r[0].bottom, 1e-5f)
    }

    @Test fun logitsAreDetected() {
        val n = 19206
        val c = 2
        val boxes = FloatArray(n * 4)
        val scores = FloatArray(n * c) { -8f }
        scores[5 * c] = 3f // sigmoid ≈ 0.95
        val r = AnchorDecoder.decode(n, c, 320, 320, 0.5f, { boxes[it] }, { scores[it] })
        assertEquals(1, r.size)
        assertEquals(0.9526f, r[0].score, 1e-3f)
    }
}
