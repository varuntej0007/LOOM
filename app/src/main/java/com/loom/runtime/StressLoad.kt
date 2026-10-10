package com.loom.runtime

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.loom.runtime.core.StressMode
import java.util.concurrent.atomic.AtomicLong

/**
 * Controlled, real extra load (blueprint §3: "a controlled stress toggle that adds real load").
 *
 * - GPU: a second EfficientDet-Lite2 detector on the GPU, running back-to-back on the latest
 *   camera frame. Real GPU contention, the same kind the on-device LLM will create.
 * - CPU: busy threads on half the cores, competing with preprocessing and CPU inference.
 *
 * It never fakes a metric: it only makes the phone busier, and the Weaver reacts to what the
 * telemetry then measures.
 */
class StressLoad(private val context: Context, private val onEvent: (String) -> Unit) {

    @Volatile var mode: StressMode = StressMode.OFF
        private set

    /** Shadow inferences completed (GPU mode), for the dashboard. */
    val shadowRuns = AtomicLong(0)

    @Volatile private var latestFrame: Bitmap? = null
    @Volatile private var running = false
    private val threads = mutableListOf<Thread>()

    /** Analyzer thread: share the newest upright frame (read-only). */
    fun offer(frame: Bitmap) {
        if (mode == StressMode.GPU) latestFrame = frame
    }

    @Synchronized
    fun set(newMode: StressMode) {
        if (newMode == mode) return
        stopThreads()
        mode = newMode
        when (newMode) {
            StressMode.OFF -> Unit
            StressMode.GPU -> startGpu()
            StressMode.CPU -> startCpu()
        }
        onEvent("Stress: ${newMode.label}")
    }

    private fun startGpu() {
        running = true
        threads += Thread({
            val detector = try {
                ObjectDetector.createFromOptions(
                    context,
                    ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(
                            BaseOptions.builder()
                                .setModelAssetPath("efficientdet_lite2.tflite")
                                .setDelegate(Delegate.GPU)
                                .build()
                        )
                        .setRunningMode(RunningMode.IMAGE)
                        .setMaxResults(8)
                        .build()
                )
            } catch (t: Throwable) {
                onEvent("Stress GPU unavailable: ${t.message}")
                return@Thread
            }
            try {
                while (running) {
                    val frame = latestFrame
                    if (frame == null || frame.isRecycled) {
                        Thread.sleep(20)
                        continue
                    }
                    detector.detect(BitmapImageBuilder(frame).build())
                    shadowRuns.incrementAndGet()
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                Log.e("LOOM", "stress gpu", t)
            } finally {
                detector.close()
            }
        }, "loom-stress-gpu").also { it.start() }
    }

    private fun startCpu() {
        running = true
        val n = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        repeat(n) { i ->
            threads += Thread({
                var x = 1.0001
                while (running) {
                    for (k in 0 until 200_000) x = x * 1.0000001 + 0.0000001
                    if (x > 1e9) x = 1.0001
                }
            }, "loom-stress-cpu-$i").also { it.start() }
        }
    }

    private fun stopThreads() {
        running = false
        threads.forEach { it.interrupt() }
        threads.forEach { it.join(1_000) }
        threads.clear()
        latestFrame = null
    }

    @Synchronized
    fun close() {
        stopThreads()
        mode = StressMode.OFF
    }
}
