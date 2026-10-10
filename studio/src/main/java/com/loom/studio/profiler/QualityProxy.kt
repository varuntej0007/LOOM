package com.loom.studio.profiler

import com.loom.studio.chiplets.Det
import com.loom.studio.chiplets.Postprocess
import com.loom.studio.chiplets.UnitResult
import kotlin.math.sqrt

/**
 * How close a lower profile's output is to HIGH's on the SAME inputs (brief §7). HIGH = 1.0.
 *  - detector: box agreement (same label, IoU ≥ 0.5) as F1
 *  - classifier / segmentation: top-1 label agreement
 *  - pose: 1 − mean keypoint distance / 0.1 (normalised coordinates)
 *  - generic: cosine similarity of the output numbers
 */
object QualityProxy {

    fun agreement(ref: List<UnitResult>, test: List<UnitResult>): Double {
        val n = minOf(ref.size, test.size)
        if (n == 0) return Double.NaN
        return (0 until n).map { one(ref[it], test[it]) }.average()
    }

    fun one(a: UnitResult, b: UnitResult): Double = when {
        a.boxes.isNotEmpty() || b.boxes.isNotEmpty() -> boxF1(a.boxes, b.boxes)
        a.keypoints.isNotEmpty() -> pose(a, b)
        a.labels.isNotEmpty() || b.labels.isNotEmpty() ->
            if (a.labels.firstOrNull()?.first == b.labels.firstOrNull()?.first) 1.0 else 0.0
        a.numbers.isNotEmpty() -> cosine(a.numbers, b.numbers)
        else -> 1.0 // both empty: they agree
    }

    fun boxF1(a: List<Det>, b: List<Det>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val used = BooleanArray(b.size)
        var matches = 0
        for (x in a.sortedByDescending { it.score }) {
            var best = -1
            var bestIou = 0.5f
            for ((j, y) in b.withIndex()) {
                if (used[j] || y.label != x.label) continue
                val o = Postprocess.iou(x, y)
                if (o >= bestIou) { bestIou = o; best = j }
            }
            if (best >= 0) { used[best] = true; matches++ }
        }
        return 2.0 * matches / (a.size + b.size)
    }

    private fun pose(a: UnitResult, b: UnitResult): Double {
        val n = minOf(a.keypoints.size, b.keypoints.size)
        if (n == 0) return 0.0
        val d = (0 until n).map { i ->
            val p = a.keypoints[i]; val q = b.keypoints[i]
            sqrt(((p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y)).toDouble())
        }.average()
        return (1.0 - d / 0.1).coerceIn(0.0, 1.0)
    }

    fun cosine(a: List<Float>, b: List<Float>): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in 0 until n) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0.0 && nb == 0.0) return 1.0
        if (na == 0.0 || nb == 0.0) return 0.0
        return (dot / (sqrt(na) * sqrt(nb))).coerceIn(-1.0, 1.0)
    }
}
