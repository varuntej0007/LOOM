package com.loom.runtime.chiplets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageProxy
import com.loom.runtime.core.Chiplet

/**
 * Preprocess VC. Copies the RGBA camera frame into a bitmap and rotates it upright, so the
 * detector and the overlay share one coordinate system.
 *
 * Heat: the two bitmaps are allocated once and reused every frame — no 1–2 MB allocation per
 * frame, so no garbage-collector churn on the CPU.
 *
 * We do NOT downscale here: MediaPipe resizes to the model's own input size internally,
 * so shrinking the frame would only cost accuracy, not compute (blueprint §5).
 *
 * Note: the returned bitmap is reused on the next frame. It is only valid until then.
 */
class PreprocessChiplet : Chiplet {
    override val name = "Preprocess"
    override val profileLabel = "rotate (reused buffers)"

    private var raw: Bitmap? = null
    private var upright: Bitmap? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun process(image: ImageProxy): Bitmap {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]

        // 1. Camera RGBA → reused bitmap (falls back to CameraX's copy if rows are padded).
        val src = if (plane.rowStride == w * 4 && plane.pixelStride == 4) {
            val b = raw?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { raw = it }
            plane.buffer.rewind()
            b.copyPixelsFromBuffer(plane.buffer)
            b
        } else {
            image.toBitmap()
        }

        // 2. Rotate upright into a second reused bitmap.
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return src
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
