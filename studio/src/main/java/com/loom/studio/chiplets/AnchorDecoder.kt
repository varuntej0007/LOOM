package com.loom.studio.chiplets

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Decoder for "raw" anchor-based detectors: two outputs, box offsets [1,N,4] and class scores
 * [1,N,C], with no NMS inside the model. EfficientDet-Lite models from MediaPipe / Model Maker
 * ship like this (MediaPipe decodes them with anchors described in its own metadata).
 *
 * Anchors are the EfficientDet scheme: feature levels 3..7 (strides 8..128), 3 octave scales ×
 * 3 aspect ratios per cell, cell-major order. Anchor scale 3.0 is what these exports use. That
 * was checked against a reference image: the decoded boxes land within a few px of the ground truth.
 * If N does not match that anchor count, the box tensor is read as already-decoded
 * [ymin, xmin, ymax, xmax] (normalised, or in input pixels).
 *
 * Pure Kotlin (no Android), so it is unit-tested on the laptop.
 */
object AnchorDecoder {

    /** Primitive reader (a fun interface, so no Float boxing in the 1.7 M-value inner loop). */
    fun interface Reader { fun at(i: Int): Float }

    data class Raw(val cls: Int, val score: Float, val left: Float, val top: Float, val right: Float, val bottom: Float)

    private val cache = HashMap<Long, FloatArray?>()

    /** Normalised anchors (yc, xc, h, w) × N, or null if [n] is not an EfficientDet anchor count. */
    @Synchronized
    fun efficientDetAnchors(inputH: Int, inputW: Int, n: Int, anchorScale: Float = 3f): FloatArray? {
        val key = (inputH.toLong() shl 40) or (inputW.toLong() shl 20) or n.toLong()
        if (cache.containsKey(key)) return cache[key]
        var count = 0
        for (l in 3..7) {
            val s = 1 shl l
            count += ceil(inputH / s.toDouble()).toInt() * ceil(inputW / s.toDouble()).toInt() * 9
        }
        if (count != n) { cache[key] = null; return null }
        val a = FloatArray(n * 4)
        val aspects = arrayOf(floatArrayOf(1f, 1f), floatArrayOf(1.4f, 0.7f), floatArrayOf(0.7f, 1.4f)) // (x, y)
        var k = 0
        for (l in 3..7) {
            val s = (1 shl l).toFloat()
            val gh = ceil(inputH / s.toDouble()).toInt()
            val gw = ceil(inputW / s.toDouble()).toInt()
            for (yy in 0 until gh) for (xx in 0 until gw) for (o in 0 until 3) for (ar in aspects) {
                val base = anchorScale * s * 2.0.pow(o / 3.0).toFloat()
                a[k++] = (yy + 0.5f) * s / inputH
                a[k++] = (xx + 0.5f) * s / inputW
                a[k++] = base * ar[1] / inputH
                a[k++] = base * ar[0] / inputW
            }
        }
        cache[key] = a
        return a
    }

    /**
     * [box] reads box value j of anchor i as box.at(i*4+j); [score] reads class c of anchor i as
     * score.at(i*C+c). Scores may be probabilities or logits (detected from a sample).
     */
    fun decode(
        n: Int, c: Int, inputH: Int, inputW: Int, threshold: Float,
        box: Reader, score: Reader,
        maxOut: Int = 25, iouThr: Float = 0.5f,
    ): List<Raw> {
        if (n <= 0 || c <= 0) return emptyList()
        // Logits or probabilities? Sample a spread of values.
        var logits = false
        val total = n * c
        val step = max(1, total / 4096)
        var i = 0
        while (i < total) { val v = score.at(i); if (v < 0f || v > 1f) { logits = true; break }; i += step }
        val thr = if (logits) ln(threshold / (1f - threshold)) else threshold

        // Best class per anchor above threshold.
        val candIdx = ArrayList<Int>()
        val candCls = ArrayList<Int>()
        val candS = ArrayList<Float>()
        for (a in 0 until n) {
            var best = -1
            var bs = thr
            val off = a * c
            for (k in 0 until c) { val v = score.at(off + k); if (v > bs) { bs = v; best = k } }
            if (best >= 0) { candIdx += a; candCls += best; candS += if (logits) (1f / (1f + exp(-bs))) else bs }
        }
        if (candIdx.isEmpty()) return emptyList()
        val order = candIdx.indices.sortedByDescending { candS[it] }.take(300)

        val anchors = efficientDetAnchors(inputH, inputW, n)
        var pixel = false
        if (anchors == null) {
            for (j in 0 until min(n * 4, 4000)) if (box.at(j) > 1.5f) { pixel = true; break }
        }
        val out = ArrayList<Raw>()
        for (o in order) {
            val a = candIdx[o]
            val r: FloatArray = if (anchors != null) {
                val ty = box.at(a * 4); val tx = box.at(a * 4 + 1); val th = box.at(a * 4 + 2); val tw = box.at(a * 4 + 3)
                val ya = anchors[a * 4]; val xa = anchors[a * 4 + 1]; val ha = anchors[a * 4 + 2]; val wa = anchors[a * 4 + 3]
                val yc = ty * ha + ya; val xc = tx * wa + xa
                val h = exp(th.toDouble()).toFloat() * ha; val w = exp(tw.toDouble()).toFloat() * wa
                floatArrayOf(xc - w / 2, yc - h / 2, xc + w / 2, yc + h / 2)
            } else {
                val sy = if (pixel) inputH.toFloat() else 1f
                val sx = if (pixel) inputW.toFloat() else 1f
                floatArrayOf(box.at(a * 4 + 1) / sx, box.at(a * 4) / sy, box.at(a * 4 + 3) / sx, box.at(a * 4 + 2) / sy)
            }
            val d = Raw(candCls[o], candS[o], clamp(r[0]), clamp(r[1]), clamp(r[2]), clamp(r[3]))
            if (d.right - d.left <= 0f || d.bottom - d.top <= 0f) continue
            if (out.none { it.cls == d.cls && iou(it, d) > iouThr }) out += d
            if (out.size >= maxOut) break
        }
        return out
    }

    private fun clamp(v: Float) = v.coerceIn(0f, 1f)

    private fun iou(a: Raw, b: Raw): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - inter
        return if (union <= 0f) 0f else inter / union
    }
}
