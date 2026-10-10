package com.loom.runtime

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.loom.runtime.chiplets.CaptureChiplet
import com.loom.runtime.chiplets.DisplayChiplet
import com.loom.runtime.chiplets.InferenceChiplet
import com.loom.runtime.chiplets.NarratorChiplet
import com.loom.runtime.chiplets.PreprocessChiplet
import com.loom.runtime.core.Box
import com.loom.runtime.core.Clock
import com.loom.runtime.core.FrameResult
import com.loom.runtime.core.FrameSample
import com.loom.runtime.core.Level
import com.loom.runtime.telemetry.TelemetryBus
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The data plane: Capture → Preprocess → Inference → Narrator → Display.
 *
 * Runs on a single-threaded analyzer executor, so the top of analyze() is a safe processing
 * boundary by construction — that is where profile changes are applied.
 */
class LoomPipeline(
    context: Context,
    private val telemetry: TelemetryBus,
    private val stress: StressLoad,
) : ImageAnalysis.Analyzer {

    private val events = ConcurrentLinkedQueue<String>()
    val postEvent: (String) -> Unit = { events.add(it) }

    val capture = CaptureChiplet(postEvent)
    val preprocess = PreprocessChiplet()
    val inference = InferenceChiplet(context.applicationContext, postEvent)
    val narrator = NarratorChiplet(context.applicationContext, postEvent)
    val display = DisplayChiplet(postEvent)

    // Analyzer thread only. Never resets for the lifetime of the pipeline.
    private var frameNo = 0L
    private var unloggedSwapGapMs: Double? = null

    fun start() {
        // Baseline: best quality everywhere; the Weaver steps down only when it must.
        inference.requestLevel(Level.HIGH, "initial")
        capture.requestLevel(Level.HIGH, "initial")
        narrator.start()
    }

    override fun analyze(image: ImageProxy) {
        val tStart = Clock.nowNs()
        telemetry.onFrameArrived()

        // ---- Safe boundary: nothing is in flight here. ----
        inference.swapIfReady()?.let { swap ->
            events.add(swap.describe())
            unloggedSwapGapMs = swap.gapMs
        }

        // ---- Capture VC ----
        if (!capture.shouldProcess(tStart) || !inference.isReady) {
            image.close()
            return
        }
        val tCapture = Clock.nowNs()

        // ---- Preprocess VC ----
        val bitmap = try {
            preprocess.process(image)
        } catch (t: Throwable) {
            Log.e(TAG, "preprocess failed", t)
            return
        } finally {
            image.close() // release the camera buffer as early as possible (double close is safe)
        }
        val mpImage = BitmapImageBuilder(bitmap).build()
        val tPreprocess = Clock.nowNs()

        // ---- Inference VC ----
        val result = try {
            inference.detect(mpImage)
        } catch (t: Throwable) {
            Log.e(TAG, "inference failed", t)
            events.add("Inference error: ${t.message}")
            null
        }
        val tInference = Clock.nowNs()

        // Keep only what matters for walking guidance (filtered here, not inside MediaPipe).
        val boxes = result?.detections()?.mapNotNull { d ->
            val top = d.categories().firstOrNull() ?: return@mapNotNull null
            if (top.categoryName() !in InferenceChiplet.NAVIGATION_CLASSES) return@mapNotNull null
            val r = d.boundingBox()
            Box(top.categoryName(), top.score(), r.left, r.top, r.right, r.bottom)
        } ?: emptyList()

        // ---- Narrator VC: hand over detections; it speaks on its own thread and schedule ----
        narrator.update(boxes, bitmap.width, bitmap.height)
        val narration = narrator.takeGenerated()
        stress.offer(bitmap)

        // ---- Display VC ----
        val thisFrame = frameNo++
        display.publish(FrameResult(thisFrame, bitmap.width, bitmap.height, boxes))
        val tDisplay = Clock.nowNs()

        telemetry.record(
            FrameSample(
                frameNo = thisFrame,
                captureMs = Clock.ms(tStart, tCapture),
                preprocessMs = Clock.ms(tCapture, tPreprocess),
                inferenceMs = Clock.ms(tPreprocess, tInference),
                narratorMs = narration?.first,
                displayMs = Clock.ms(tInference, tDisplay),
                totalMs = Clock.ms(tStart, tDisplay),
                detections = boxes.size,
                meanConf = if (boxes.isEmpty()) 0.0 else boxes.map { it.score.toDouble() }.average(),
                profileCapture = capture.profileLabel,
                profileInference = inference.profileLabel,
                profileNarrator = narrator.profileLabel,
                event = drainEvents(),
                swapGapMs = unloggedSwapGapMs,
                narration = narration?.second,
            ),
            framesSkipped = capture.skipped,
            framesPaced = capture.paced,
        )
        unloggedSwapGapMs = null
    }

    private fun drainEvents(): String? {
        if (events.isEmpty()) return null
        val parts = mutableListOf<String>()
        while (true) parts.add(events.poll() ?: break)
        return parts.joinToString(" | ")
    }

    /** Analyzer thread only. */
    fun close() {
        narrator.close()
        inference.close()
    }

    companion object {
        private const val TAG = "LOOM"
    }
}
