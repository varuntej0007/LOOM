package com.loom.studio.chiplets

import com.loom.studio.importer.Head
import com.loom.studio.importer.ModelCard
import com.loom.studio.importer.TensorSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** A detection in normalised [0,1] coordinates of the upright frame. */
data class Det(val label: String, val score: Float, val left: Float, val top: Float, val right: Float, val bottom: Float)
data class Keypoint(val x: Float, val y: Float, val score: Float)

/** What one unit produced, in a task-neutral form the Display and the profiler understand. */
data class UnitResult(
    val unit: Long,
    val boxes: List<Det> = emptyList(),
    /** Classifier / segmentation top labels with score (or pixel share). */
    val labels: List<Pair<String, Float>> = emptyList(),
    val keypoints: List<Keypoint> = emptyList(),
    /** Segmentation: small class-id grid (w × h) for a coarse overlay. */
    val mask: IntArray? = null,
    val maskW: Int = 0,
    val maskH: Int = 0,
    /** Generic: first numbers of output 0. */
    val numbers: List<Float> = emptyList(),
) {
    val count: Int get() = when { boxes.isNotEmpty() -> boxes.size; keypoints.isNotEmpty() -> keypoints.count { it.score > 0.3f }; else -> labels.size }
    val meanScore: Double get() = when {
        boxes.isNotEmpty() -> boxes.map { it.score.toDouble() }.average()
        labels.isNotEmpty() -> labels.first().second.toDouble()
        keypoints.isNotEmpty() -> keypoints.map { it.score.toDouble() }.average()
        else -> 0.0
    }
}

/**
 * Postprocess chiplet: raw output tensors → [UnitResult] according to the model's head.
 * Detector (SSD 4-tensor and YOLO single-tensor), classifier (top-5, softmax if needed),
 * segmentation (argmax, class shares, coarse mask), pose (K × (y, x, score)), generic numbers.
 */
class Postprocess(card: ModelCard, private val scoreThreshold: Float = 0.4f) :
    FixedChiplet("Postprocess", "postprocess", headLabel(card.head)) {

    private val head = card.head
    private val outputs = card.outputs
    private val inputW = card.inputs.firstOrNull()?.shape?.let { if (it.size == 4) (if (it[1] == 3) it[3] else it[2]) else 1 } ?: 1
    private val inputH = card.inputs.firstOrNull()?.shape?.let { if (it.size == 4) (if (it[1] == 3) it[2] else it[1]) else 1 } ?: 1
    private val rawLabels = card.labels ?: emptyList()
    /** COCO-style label files start with "???" / "background" for class 0 of the raw model. */
    private val labelOffset = if (rawLabels.firstOrNull()?.lowercase() in setOf("???", "background", "__background__")) 1 else 0
    @Volatile private var lastMs = 0.0

    override fun cost() = CostSample(lastMs, 0.0)

    fun label(i: Int): String = rawLabels.getOrNull(i + labelOffset) ?: "class $i"
    private fun plainLabel(i: Int): String = rawLabels.getOrNull(i) ?: "class $i"

    fun process(unit: Long, out: Map<String, Any>): UnitResult {
        val t0 = System.nanoTime()
        val r = try {
            when (head) {
                Head.DETECTOR -> detect(unit, out)
                Head.CLASSIFIER -> classify(unit, out)
                Head.SEGMENTER -> segment(unit, out)
                Head.POSE -> pose(unit, out)
                Head.NONE -> UnitResult(unit, numbers = floats(out, 0).take(8))
            }
        } catch (t: Throwable) {
            UnitResult(unit, numbers = emptyList())
        }
        lastMs = (System.nanoTime() - t0) / 1e6
        return r
    }

    // ---------------- readers ----------------

    fun floats(out: Map<String, Any>, index: Int): FloatArray {
        val buf = (out[index.toString()] as? ByteBuffer) ?: return FloatArray(0)
        val spec = outputs.getOrNull(index)
        val b = buf.duplicate().order(ByteOrder.nativeOrder())
        b.rewind()
        return when (spec?.dtype) {
            "uint8" -> FloatArray(b.remaining()) { i -> deq((b.get(i).toInt() and 0xFF), spec) }
            "int8" -> FloatArray(b.remaining()) { i -> deq(b.get(i).toInt(), spec) }
            "int32" -> { val ib = b.asIntBuffer(); FloatArray(ib.remaining()) { ib.get(it).toFloat() } }
            "int64" -> { val lb = b.asLongBuffer(); FloatArray(lb.remaining()) { lb.get(it).toFloat() } }
            else -> { val fb = b.asFloatBuffer(); FloatArray(fb.remaining()) { fb.get(it) } }
        }
    }

    private fun deq(q: Int, s: TensorSpec): Float = if (s.scale > 0f) (q - s.zeroPoint) * s.scale else q.toFloat()

    // ---------------- detector ----------------

    private fun detect(unit: Long, out: Map<String, Any>): UnitResult {
        val boxIdx = outputs.indexOfFirst { it.shape.size == 3 && it.shape[2] == 4 }
        if (boxIdx >= 0 && outputs.size >= 3) return detectSsd(unit, out, boxIdx)
        return detectYolo(unit, out)
    }

    private fun detectSsd(unit: Long, out: Map<String, Any>, boxIdx: Int): UnitResult {
        val boxes = floats(out, boxIdx)
        val n = outputs[boxIdx].shape[1]
        val perBox = outputs.indices.filter { it != boxIdx && outputs[it].shape.size == 2 && outputs[it].shape[1] == n }
        val arrays = perBox.map { it to floats(out, it) }
        // Prefer tensor names ("…classes…", "…scores…"); else classes are whole numbers.
        val byName = { word: String -> arrays.firstOrNull { (i, _) -> word in outputs[i].name.lowercase() }?.second }
        val integral = { a: FloatArray -> a.isNotEmpty() && a.all { v -> v == Math.floor(v.toDouble()).toFloat() } }
        val classes = byName("class") ?: arrays.firstOrNull { (_, a) -> integral(a) && a.any { it >= 1f } }?.second
            ?: arrays.getOrNull(0)?.second ?: FloatArray(n)
        val scores = byName("score")?.takeIf { it !== classes } ?: arrays.firstOrNull { (_, a) -> a !== classes }?.second ?: FloatArray(n)
        val countIdx = outputs.indices.firstOrNull { outputs[it].shape.size == 1 || (outputs[it].shape.size == 2 && outputs[it].shape[1] == 1 && n > 1) }
        val count = countIdx?.let { floats(out, it).firstOrNull()?.toInt() } ?: n
        val dets = ArrayList<Det>()
        for (i in 0 until min(n, count.coerceIn(0, n))) {
            val s = scores.getOrElse(i) { 0f }
            if (s < scoreThreshold) continue
            val top = boxes.getOrElse(4 * i) { 0f }
            val left = boxes.getOrElse(4 * i + 1) { 0f }
            val bottom = boxes.getOrElse(4 * i + 2) { 0f }
            val right = boxes.getOrElse(4 * i + 3) { 0f }
            dets += Det(label(classes.getOrElse(i) { 0f }.toInt()), s, clamp(left), clamp(top), clamp(right), clamp(bottom))
        }
        return UnitResult(unit, boxes = dets.sortedByDescending { it.score }.take(25))
    }

    private fun detectYolo(unit: Long, out: Map<String, Any>): UnitResult {
        val s = outputs[0].shape
        val data = floats(out, 0)
        // [1, 4+C, N] (channels first) or [1, N, 4+C]
        val chFirst = s[1] < s[2]
        val n = if (chFirst) s[2] else s[1]
        val ch = if (chFirst) s[1] else s[2]
        fun at(box: Int, c: Int) = if (chFirst) data[c * n + box] else data[box * ch + c]
        val raw = ArrayList<Det>()
        var maxCoord = 0f
        for (i in 0 until n) maxCoord = max(maxCoord, at(i, 2))
        val pixelCoords = maxCoord > 1.5f
        for (i in 0 until n) {
            var best = -1
            var bestS = scoreThreshold
            for (c in 4 until ch) {
                val v = at(i, c)
                if (v > bestS) { bestS = v; best = c - 4 }
            }
            if (best < 0) continue
            var cx = at(i, 0); var cy = at(i, 1); var w = at(i, 2); var h = at(i, 3)
            if (pixelCoords) { cx /= inputW; cy /= inputH; w /= inputW; h /= inputH }
            raw += Det(plainLabel(best), bestS, clamp(cx - w / 2), clamp(cy - h / 2), clamp(cx + w / 2), clamp(cy + h / 2))
        }
        return UnitResult(unit, boxes = nms(raw, 0.5f).take(25))
    }

    // ---------------- classifier / segmentation / pose ----------------

    private fun classify(unit: Long, out: Map<String, Any>): UnitResult {
        var v = floats(out, 0)
        if (v.isEmpty()) return UnitResult(unit)
        val sum = v.sum()
        if (v.any { it < 0f || it > 1f } || sum > 1.5f || sum < 0.5f) v = softmax(v)
        val top = v.indices.sortedByDescending { v[it] }.take(5)
        return UnitResult(unit, labels = top.map { plainLabel(it) to v[it] })
    }

    private fun segment(unit: Long, out: Map<String, Any>): UnitResult {
        val s = outputs[0].shape
        val v = floats(out, 0)
        val nchw = s[1] < s[3] && s[1] <= 64
        val h = if (nchw) s[2] else s[1]
        val w = if (nchw) s[3] else s[2]
        val c = if (nchw) s[1] else s[3]
        val gw = min(64, w)
        val gh = min(64, h)
        val grid = IntArray(gw * gh)
        val counts = IntArray(c)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val y = gy * h / gh
            val x = gx * w / gw
            var best = 0
            var bv = -Float.MAX_VALUE
            for (k in 0 until c) {
                val idx = if (nchw) (k * h + y) * w + x else (y * w + x) * c + k
                val value = v.getOrElse(idx) { -Float.MAX_VALUE }
                if (value > bv) { bv = value; best = k }
            }
            grid[gy * gw + gx] = best
            counts[best]++
        }
        val total = (gw * gh).toFloat()
        val top = counts.indices.filter { counts[it] > 0 }.sortedByDescending { counts[it] }.take(3)
        return UnitResult(unit, labels = top.map { plainLabel(it) to counts[it] / total }, mask = grid, maskW = gw, maskH = gh)
    }

    private fun pose(unit: Long, out: Map<String, Any>): UnitResult {
        val v = floats(out, 0)
        val k = v.size / 3
        return UnitResult(unit, keypoints = (0 until k).map { i -> Keypoint(clamp(v[3 * i + 1]), clamp(v[3 * i]), v[3 * i + 2]) })
    }

    companion object {
        fun headLabel(h: Head) = when (h) {
            Head.DETECTOR -> "boxes (score ≥ 0.4)"
            Head.CLASSIFIER -> "top-5 labels"
            Head.SEGMENTER -> "class map"
            Head.POSE -> "keypoints"
            Head.NONE -> "raw numbers"
        }

        private fun clamp(v: Float) = v.coerceIn(0f, 1f)

        fun softmax(v: FloatArray): FloatArray {
            val m = v.maxOrNull() ?: 0f
            val e = FloatArray(v.size) { exp((v[it] - m).toDouble()).toFloat() }
            val s = e.sum()
            return FloatArray(v.size) { e[it] / s }
        }

        fun iou(a: Det, b: Det): Float {
            val w = min(a.right, b.right) - max(a.left, b.left)
            val h = min(a.bottom, b.bottom) - max(a.top, b.top)
            if (w <= 0f || h <= 0f) return 0f
            val inter = w * h
            val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - inter
            return if (union <= 0f) 0f else inter / union
        }

        fun nms(dets: List<Det>, thr: Float): List<Det> {
            val keep = ArrayList<Det>()
            for (d in dets.sortedByDescending { it.score }) {
                if (keep.none { it.label == d.label && iou(it, d) > thr }) keep += d
                if (keep.size >= 100) break
            }
            return keep
        }
    }
}
