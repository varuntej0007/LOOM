package com.loom.runtime.chiplets

import android.content.Context
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import com.loom.runtime.core.AdaptiveChiplet
import com.loom.runtime.core.Clock
import com.loom.runtime.core.Compute
import com.loom.runtime.core.InferenceProfile
import com.loom.runtime.core.Level
import com.loom.runtime.core.Profiles
import com.loom.runtime.core.Transitions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Inference VC with a hot-swappable detector (blueprint §6, safe-boundary migration).
 *
 * 1. requestLevel() posts a target profile.
 * 2. A background builder thread creates the new detector (GPU init can take hundreds of ms).
 * 3. Frames keep flowing through the old detector meanwhile.
 * 4. At the top of the next analyze() call — a safe boundary, because the analyzer executor
 *    is single-threaded and no frame is in flight — swapIfReady() swaps the reference.
 * 5. The old detector is closed only after the swap.
 * 6. The swap gap is measured and logged.
 */
class InferenceChiplet(
    private val context: Context,
    private val onEvent: (String) -> Unit,
) : AdaptiveChiplet {

    override val name = "Inference"

    data class Swap(
        val from: String,
        val to: String,
        val buildMs: Double,
        val gapMs: Double,
        val reason: String,
        val id: Long,
    ) {
        fun describe() = "$reason → Inference: $from → $to (build ${"%.0f".format(buildMs)} ms, swap ${"%.2f".format(gapMs)} ms)"
    }

    private class Pending(
        val profile: InferenceProfile,
        val detector: ObjectDetector,
        val buildMs: Double,
        val reason: String,
        val id: Long,
    )

    private val builder = Executors.newSingleThreadExecutor { r -> Thread(r, "loom-builder") }
    private val pending = AtomicReference<Pending?>(null)
    @Volatile private var requested: InferenceProfile? = null

    // Touched only on the analyzer thread.
    private var active: ObjectDetector? = null

    @Volatile var activeProfile: InferenceProfile? = null
        private set

    /** The level being moved to (if a build is pending) or the live one. The Weaver reasons on this. */
    override val level: Level get() = requested?.level ?: activeProfile?.level ?: Level.HIGH
    override val profileLabel: String get() = activeProfile?.label ?: "loading…"

    /** Analyzer thread only. */
    val isReady: Boolean get() = active != null

    /** Any thread: a detector is live and no swap is pending. */
    fun isReadyForControl(): Boolean = activeProfile != null && requested == activeProfile

    override fun requestLevel(level: Level, reason: String) {
        val target = Profiles.inference.getValue(level)
        if (target == requested) return
        requested = target
        val id = Transitions.nextId()
        Transitions.emit(id, name, "requested", activeProfile?.label ?: "none", target.label, reason)
        builder.execute { build(target, reason, id) }
    }

    private fun build(target: InferenceProfile, reason: String, id: Long) {
        if (target != requested) return // superseded before we started
        val t0 = Clock.nowNs()
        val detector = try {
            create(target)
        } catch (t: Throwable) {
            Transitions.emit(id, name, "failed", activeProfile?.label ?: "none", target.label, "build failed: ${t.message}")
            onEvent("Inference build FAILED for ${target.label}: ${t.message}")
            requested = activeProfile
            // If the GPU path is unavailable at startup, fall back to CPU rather than show nothing.
            if (activeProfile == null && target.compute == Compute.GPU) {
                requestLevel(Level.ECO, "fallback (GPU unavailable)")
            }
            return
        }
        val buildMs = Clock.ms(t0, Clock.nowNs())
        if (target != requested) { // a newer request arrived while we were building
            detector.close()
            Transitions.emit(id, name, "failed", activeProfile?.label ?: "none", target.label, "superseded by a newer request")
            return
        }
        Transitions.emit(id, name, "built", activeProfile?.label ?: "none", target.label, reason, buildMs = buildMs)
        // Replace any older, never-swapped-in pending detector.
        pending.getAndSet(Pending(target, detector, buildMs, reason, id))?.detector?.close()
    }

    private fun create(p: InferenceProfile): ObjectDetector {
        val base = BaseOptions.builder()
            .setModelAssetPath(p.modelAsset)
            .setDelegate(if (p.compute == Compute.GPU) Delegate.GPU else Delegate.CPU)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(MAX_RESULTS)
            .setScoreThreshold(SCORE_THRESHOLD)
            // NOTE: no setCategoryAllowlist() — MediaPipe 0.10.x aborts natively with a partial
            // allowlist on EfficientDet ("Only all classes >= class 0 or >= class 1"). LOOM filters
            // to NAVIGATION_CLASSES itself, after detection (LoomPipeline).
            .build()
        return ObjectDetector.createFromOptions(context, options)
    }

    /** Call at the top of analyze(): the safe boundary. Analyzer thread only. */
    fun swapIfReady(): Swap? {
        val next = pending.getAndSet(null) ?: return null
        val t0 = Clock.nowNs()
        val old = active
        val from = activeProfile?.label ?: "none"
        active = next.detector
        activeProfile = next.profile
        val gapMs = Clock.ms(t0, Clock.nowNs())
        // The frame thread no longer references the old detector, so it can be closed safely on
        // the builder thread. Closing a GPU model takes ~25 ms on the iQOO; doing it here would
        // stall the very next frame.
        if (old != null) builder.execute { old.close() }
        Transitions.emit(next.id, name, "swapped", from, next.profile.label, next.reason, next.buildMs, gapMs)
        return Swap(from, next.profile.label, next.buildMs, gapMs, next.reason, next.id)
    }

    /** Analyzer thread only. */
    fun detect(image: MPImage): ObjectDetectorResult =
        checkNotNull(active) { "detector not ready" }.detect(image)

    /** Analyzer thread only. */
    fun close() {
        builder.shutdown()
        builder.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) // let pending closes finish
        pending.getAndSet(null)?.detector?.close()
        active?.close()
        active = null
    }

    companion object {
        // Higher than we show, because non-navigation objects are filtered out afterwards.
        const val MAX_RESULTS = 12
        const val SCORE_THRESHOLD = 0.4f

        /** COCO classes relevant to moving around safely: people, traffic, animals, obstacles. */
        val NAVIGATION_CLASSES = setOf(
            "person",
            "bicycle", "car", "motorcycle", "bus", "truck", "train",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "dog", "cat", "horse", "cow",
            "chair", "couch", "bed", "dining table", "toilet", "potted plant",
            "suitcase", "backpack", "umbrella", "refrigerator",
        )
    }
}
