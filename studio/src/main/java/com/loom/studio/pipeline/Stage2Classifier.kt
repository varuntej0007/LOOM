package com.loom.studio.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.loom.studio.chiplets.CostSample
import com.loom.studio.chiplets.KnobChiplet
import com.loom.studio.chiplets.Postprocess
import com.loom.studio.chiplets.Preprocess
import com.loom.studio.chiplets.Profile
import com.loom.studio.chiplets.UnitResult
import com.loom.studio.core.Level
import com.loom.studio.importer.Classifier
import com.loom.studio.importer.ModelInspector
import com.loom.studio.runtime.LiteRtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Second stage of a loom.json chain: a classifier run on the crops of the detector's top boxes;
 * its top label is appended to each box ("car → taxi 91%"). Its own chiplet with a power knob:
 * every frame / every 3rd frame / off.
 */
class Stage2Classifier(private val file: File, onEvent: (String) -> Unit) : KnobChiplet(
    name = "Classifier",
    key = "stage2",
    profiles = listOf(
        Profile(Level.HIGH, "classify every frame", mapOf("every" to 1)),
        Profile(Level.BALANCED, "classify every 3rd frame", mapOf("every" to 3)),
        Profile(Level.ECO, "classifier off", mapOf("every" to 0)),
    ),
    onEvent = onEvent,
) {
    private var session: LiteRtSession? = null
    private var w = 0
    private var h = 0
    private var float = false
    private var input: ByteBuffer? = null
    private var labels: List<String> = emptyList()
    private var outCount = 0
    private var outFloat = true
    private val pixels by lazy { IntArray(w * h) }
    private val crop by lazy { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var frame = 0L
    private val cache = HashMap<String, String>()
    @Volatile private var lastMs = 0.0

    override fun cost() = CostSample(lastMs, 0.0)
    override fun apply(p: Profile, reason: String) {}

    fun start() {
        try {
            val s = LiteRtSession.create(file, mapOf("delegate" to "cpu", "threads" to 2))
            session = s
            val ip = org.tensorflow.lite.Interpreter(file)
            ip.use {
                val t = it.getInputTensor(0)
                val spec = com.loom.studio.importer.TensorSpec("in", t.shape(), ModelInspector.dtype(t.dataType()))
                val img = Classifier.imageLayout(spec) ?: error("classifier input is not an image")
                w = img.w; h = img.h
                float = spec.dtype == "float32"
                outCount = it.getOutputTensor(0).numElements()
                outFloat = ModelInspector.dtype(it.getOutputTensor(0).dataType()) == "float32"
            }
            input = ByteBuffer.allocateDirect(w * h * 3 * (if (float) 4 else 1)).order(ByteOrder.nativeOrder())
            labels = try {
                java.util.zip.ZipFile(file).use { z ->
                    z.entries().toList().firstOrNull { it.name.endsWith(".txt") }?.let { e -> z.getInputStream(e).bufferedReader().readLines() }
                } ?: emptyList()
            } catch (_: Throwable) { emptyList() }
        } catch (t: Throwable) {
            session = null
        }
    }

    fun refine(r: UnitResult, pre: Preprocess): UnitResult {
        val every = (current.knobs["every"] as? Int) ?: 0
        val s = session ?: return r
        if (every == 0 || r.boxes.isEmpty()) return r
        val src = pre.lastFrame() ?: return r
        val run = frame++ % every == 0L
        val t0 = System.nanoTime()
        val boxes = r.boxes.mapIndexed { i, b ->
            val key = "${b.label}#$i"
            if (i < 3 && run) {
                val rect = Rect((b.left * src.width).toInt(), (b.top * src.height).toInt(), (b.right * src.width).toInt(), (b.bottom * src.height).toInt())
                if (rect.width() > 4 && rect.height() > 4) {
                    Canvas(crop).drawBitmap(src, rect, Rect(0, 0, w, h), paint)
                    crop.getPixels(pixels, 0, w, 0, 0, w, h)
                    val buf = input!!
                    buf.rewind()
                    for (p in pixels) {
                        val rr = (p shr 16) and 0xFF; val gg = (p shr 8) and 0xFF; val bb = p and 0xFF
                        if (float) { buf.putFloat((rr - 127.5f) / 127.5f); buf.putFloat((gg - 127.5f) / 127.5f); buf.putFloat((bb - 127.5f) / 127.5f) }
                        else { buf.put(rr.toByte()); buf.put(gg.toByte()); buf.put(bb.toByte()) }
                    }
                    val out = s.run(mapOf("0" to buf))["0"] as? ByteBuffer
                    if (out != null) {
                        out.rewind()
                        val scores = FloatArray(outCount) { if (outFloat) out.float else (out.get().toInt() and 0xFF) / 255f }
                        val v = if (scores.any { it < 0f || it > 1f }) Postprocess.softmax(scores) else scores
                        val best = v.indices.maxByOrNull { v[it] } ?: -1
                        if (best >= 0) cache[key] = "${labels.getOrElse(best) { "class $best" }} ${(v[best] * 100).toInt()}%"
                    }
                }
            }
            cache[key]?.let { b.copy(label = "${b.label} → $it") } ?: b
        }
        lastMs = (System.nanoTime() - t0) / 1e6
        return r.copy(boxes = boxes)
    }

    fun close() {
        session?.close()
        session = null
    }
}
