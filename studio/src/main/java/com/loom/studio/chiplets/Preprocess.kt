package com.loom.studio.chiplets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageProxy
import com.loom.studio.importer.Classifier
import com.loom.studio.importer.ModelCard
import com.loom.studio.importer.TensorSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Preprocess chiplet: camera RGBA frame → upright bitmap → model input tensor
 * (resize to the model's H×W, NHWC or NCHW, uint8 / int8 / float with (x − mean) / std).
 * All bitmaps and buffers are allocated once and reused: no per-frame garbage.
 */
class Preprocess(card: ModelCard) : FixedChiplet("Preprocess", "preprocess", "rotate + resize") {

    private var spec = card.inputs.first()
    private var img = Classifier.imageLayout(spec) ?: error("model input ${spec.shapeText} is not an image")
    var inputW = img.w
        private set
    var inputH = img.h
        private set
    private var channels = img.c
    private val mean = card.normMean
    private val std = if (card.normStd == 0f) 1f else card.normStd

    private fun bytesPer(dtype: String) = when (dtype) { "float32" -> 4; "float16" -> 2; else -> 1 }
    /** The tensor handed to Inference. Valid until the next frame. */
    var input: ByteBuffer = ByteBuffer.allocateDirect(inputW * inputH * channels * bytesPer(spec.dtype)).order(ByteOrder.nativeOrder())
        private set

    private var raw: Bitmap? = null
    private var upright: Bitmap? = null
    private var scaled: Bitmap = Bitmap.createBitmap(inputW, inputH, Bitmap.Config.ARGB_8888)
    private var pixels = IntArray(inputW * inputH)

    /**
     * Follow the model that is live now. A project can switch between models with different
     * input sizes (e.g. detector_large 448² ↔ detector_small 320²). Pipeline thread only.
     * Returns true if the tensor layout changed.
     */
    fun retarget(newSpec: TensorSpec): Boolean {
        if (newSpec == spec) return false
        val l = Classifier.imageLayout(newSpec) ?: return false
        spec = newSpec
        img = l
        inputW = l.w
        inputH = l.h
        channels = l.c
        input = ByteBuffer.allocateDirect(inputW * inputH * channels * bytesPer(spec.dtype)).order(ByteOrder.nativeOrder())
        scaled = Bitmap.createBitmap(inputW, inputH, Bitmap.Config.ARGB_8888)
        pixels = IntArray(inputW * inputH)
        return true
    }
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    @Volatile private var lastMs = 0.0

    override fun cost() = CostSample(lastMs, 0.0)

    /** Upright frame of the last call (shared with the overlay for sizing). */
    var frameW = 0
        private set
    var frameH = 0
        private set

    fun process(image: ImageProxy): ByteBuffer {
        val t0 = System.nanoTime()
        val frame = upright(image)
        frameW = frame.width
        frameH = frame.height
        fill(frame)
        lastMs = (System.nanoTime() - t0) / 1e6
        return input
    }

    /** For the profiler: re-fill the tensor from a stored bitmap. */
    fun fill(frame: Bitmap): ByteBuffer {
        Canvas(scaled).drawBitmap(frame, null, android.graphics.Rect(0, 0, inputW, inputH), paint)
        scaled.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        input.rewind()
        val plane = inputW * inputH
        when (spec.dtype) {
            "float32" -> {
                val fb = input.asFloatBuffer()
                if (img.nchw) {
                    for (i in 0 until plane) {
                        val p = pixels[i]
                        fb.put(i, (((p shr 16) and 0xFF) - mean) / std)
                        if (channels > 1) {
                            fb.put(plane + i, (((p shr 8) and 0xFF) - mean) / std)
                            fb.put(2 * plane + i, ((p and 0xFF) - mean) / std)
                        }
                    }
                } else {
                    var o = 0
                    for (i in 0 until plane) {
                        val p = pixels[i]
                        if (channels == 1) {
                            fb.put(o++, ((((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3f - mean) / std)
                        } else {
                            fb.put(o++, (((p shr 16) and 0xFF) - mean) / std)
                            fb.put(o++, (((p shr 8) and 0xFF) - mean) / std)
                            fb.put(o++, ((p and 0xFF) - mean) / std)
                            if (channels == 4) fb.put(o++, 0f)
                        }
                    }
                }
            }
            else -> {
                val shift = if (spec.dtype == "int8") 128 else 0
                if (img.nchw) {
                    for (i in 0 until plane) {
                        val p = pixels[i]
                        input.put(i, (((p shr 16) and 0xFF) - shift).toByte())
                        if (channels > 1) {
                            input.put(plane + i, (((p shr 8) and 0xFF) - shift).toByte())
                            input.put(2 * plane + i, ((p and 0xFF) - shift).toByte())
                        }
                    }
                } else {
                    var o = 0
                    for (i in 0 until plane) {
                        val p = pixels[i]
                        if (channels == 1) {
                            input.put(o++, ((((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3 - shift).toByte())
                        } else {
                            input.put(o++, (((p shr 16) and 0xFF) - shift).toByte())
                            input.put(o++, (((p shr 8) and 0xFF) - shift).toByte())
                            input.put(o++, ((p and 0xFF) - shift).toByte())
                            if (channels == 4) input.put(o++, 0.toByte())
                        }
                    }
                }
            }
        }
        input.rewind()
        return input
    }

    /** The current upright frame (valid until the next frame; pipeline thread only). */
    fun lastFrame(): Bitmap? = upright ?: raw

    /** Copy of the current upright frame (the profiler keeps a few as its fixed workload). */
    fun snapshot(): Bitmap? = (upright ?: raw)?.let { it.copy(Bitmap.Config.ARGB_8888, false) }

    private fun upright(image: ImageProxy): Bitmap {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val src = if (plane.rowStride == w * 4 && plane.pixelStride == 4) {
            val b = raw?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { raw = it }
            plane.buffer.rewind()
            b.copyPixelsFromBuffer(plane.buffer)
            b
        } else {
            image.toBitmap().also { raw = it }
        }
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) {
            upright = null
            return src
        }
        val outW = if (rotation % 180 == 0) w else h
        val outH = if (rotation % 180 == 0) h else w
        val out = upright?.takeIf { it.width == outW && it.height == outH }
            ?: Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also { upright = it }
        matrix.reset()
        matrix.postRotate(rotation.toFloat())
        when (rotation) {
            90 -> matrix.postTranslate(h.toFloat(), 0f)
            180 -> matrix.postTranslate(w.toFloat(), h.toFloat())
            270 -> matrix.postTranslate(0f, w.toFloat())
        }
        Canvas(out).drawBitmap(src, matrix, paint)
        return out
    }
}
