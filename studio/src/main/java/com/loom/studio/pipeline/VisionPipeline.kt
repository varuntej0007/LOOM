package com.loom.studio.pipeline

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.loom.studio.chiplets.CameraSource
import com.loom.studio.chiplets.Display
import com.loom.studio.chiplets.InferenceChiplet
import com.loom.studio.chiplets.Postprocess
import com.loom.studio.chiplets.Preprocess
import com.loom.studio.chiplets.StudioChiplet
import com.loom.studio.core.Clock
import com.loom.studio.importer.ModelCard
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * VISION template (brief §6): CameraSource → Preprocess → Inference → Postprocess → Display.
 * It is the CameraX analyzer; the top of each analyze() call is the safe swap boundary.
 */
class VisionPipeline(
    card: ModelCard,
    file: File,
    scope: CoroutineScope,
    onEvent: (String) -> Unit,
    profiles: List<com.loom.studio.chiplets.Profile>? = null,
    private val project: com.loom.studio.importer.ProjectSpec? = null,
    appContext: android.content.Context? = null,
    projectDir: java.io.File? = null,
) : TensorPipeline(card, onEvent), ImageAnalysis.Analyzer {

    val camera = CameraSource(onEvent, project?.fpsProfiles ?: listOf(30, 15, 10))
    val preprocess = Preprocess(card)
    override val inference = InferenceChiplet(card, file, InferenceChiplet.backendFor(card), profiles ?: InferenceChiplet.profilesFor(card), scope, onEvent)
    val postprocess = Postprocess(card, project?.scoreThreshold ?: 0.4f)
    override val display = Display(onEvent).also { it.overlayOn = project?.overlay ?: true }
    /** loom.json chain: detector → classifier on the top box crops (brief §10). */
    val stage2: Stage2Classifier? = project?.classifier?.let { m ->
        Stage2Classifier(java.io.File(projectDir ?: file.parentFile, m.file), onEvent)
    }
    /** output.speak: the top labels are spoken (Voice chiplet). */
    val voice: com.loom.studio.chiplets.Voice? = if (project?.speak == true && appContext != null) com.loom.studio.chiplets.Voice(appContext, onEvent, vision = true) else null
    override val source: StudioChiplet get() = camera
    override val chiplets: List<StudioChiplet> = listOfNotNull(camera, preprocess, inference, postprocess, stage2, display, voice)

    /** Profiler hook: called with the Preprocess chiplet after each processed frame. */
    @Volatile var frameTap: ((Preprocess) -> Unit)? = null

    private var unitNo = 0L

    /** Shape Preprocess/Postprocess to [s]'s tensors (no-op when they already match). */
    fun adapt(s: com.loom.studio.runtime.Session?) {
        s ?: return
        s.inputSpecs?.firstOrNull()?.let { if (preprocess.retarget(it)) onEvent("Preprocess → ${it.shapeText} ${it.dtype} for ${s.label}") }
        postprocess.retarget(s.inputSpecs, s.outputSpecs)
    }

    init {
        wireInference()
    }

    override suspend fun start() {
        inference.start()
        stage2?.start()
    }

    override fun close() {
        super.close()
        stage2?.close()
        voice?.shutdown()
    }

    override fun setPace(unitsPerSec: Double?) {
        camera.paceFps = unitsPerSec
    }

    override fun analyze(image: ImageProxy) {
        val t0 = Clock.nowNs()
        inference.swap() // a pending profile goes live between two frames
        if (!camera.shouldProcess(t0) || (paused && frameTap == null) || !inference.isReady) {
            image.close()
            return
        }
        if (!paused) adapt(inference.session)
        val tCap = Clock.nowNs()
        val input = try {
            preprocess.process(image)
        } catch (t: Throwable) {
            Log.e("LOOM", "preprocess failed", t)
            image.close()
            return
        } finally {
            image.close()
        }
        val tPre = Clock.nowNs()
        frameTap?.invoke(preprocess)
        if (paused) return
        val out = try {
            inference.run(mapOf("0" to input))
        } catch (t: Throwable) {
            Log.e("LOOM", "inference failed", t)
            onEvent("Inference error: ${t.message}")
            null
        } ?: return
        val tInf = Clock.nowNs()
        adpf.report(Clock.ms(tPre, tInf))
        var r = postprocess.process(unitNo++, out)
        stage2?.let { s2 -> r = s2.refine(r, preprocess) }
        voice?.speakLabels(r.boxes.map { it.label } + r.labels.map { it.first })
        val tPost = Clock.nowNs()
        display.publish(r, preprocess.frameW, preprocess.frameH)
        val tDsp = Clock.nowNs()
        stageMs["source"] = Clock.ms(t0, tCap)
        stageMs["preprocess"] = Clock.ms(tCap, tPre)
        stageMs["inference"] = Clock.ms(tPre, tInf)
        stageMs["postprocess"] = Clock.ms(tInf, tPost)
        stageMs["display"] = Clock.ms(tPost, tDsp)
        recordUnit(Clock.ms(t0, tDsp), r)
    }
}
