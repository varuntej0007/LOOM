package com.loom.studio.pipeline

import com.loom.studio.core.TaskKind
import com.loom.studio.importer.Classifier
import com.loom.studio.importer.ModelCard
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** TaskKind → chiplet pipeline template (brief §6). */
object PipelineFactory {

    class Unsupported(msg: String) : Exception(msg)

    fun build(card: ModelCard, file: File, scope: CoroutineScope, onEvent: (String) -> Unit): Pipeline {
        if (card.format != "tflite") {
            throw Unsupported(
                when (card.format) {
                    "gguf" -> "GGUF chat models run in drop 2 (llama.cpp)."
                    "task" -> "MediaPipe .task models run in drop 3."
                    "onnx" -> "ONNX models run in drop 3."
                    else -> "Unsupported format ${card.format}"
                }
            )
        }
        val visionInput = card.inputs.firstOrNull()?.let { Classifier.imageLayout(it) } != null
        return if (card.kind == TaskKind.VISION && visionInput) VisionPipeline(card, file, scope, onEvent)
        else GenericPipeline(card, file, scope, onEvent)
    }

    data class ChipletInfo(val key: String, val name: String, val profiles: List<String>, val role: String)

    /** The chiplet chain this model gets, without loading it (Model detail screen). */
    fun describe(card: ModelCard): List<ChipletInfo> {
        val inf = com.loom.studio.chiplets.InferenceChiplet.liteRtProfiles().map { it.label }
        val noop: (String) -> Unit = {}
        return if (usesCamera(card)) listOf(
            ChipletInfo("source", "Capture", com.loom.studio.chiplets.CameraSource(noop).profiles.map { it.label }, "camera frames (real Camera2 rate)"),
            ChipletInfo("preprocess", "Preprocess", listOf("rotate + resize"), "frame → ${card.inputs.firstOrNull()?.shapeText} ${card.inputs.firstOrNull()?.dtype}"),
            ChipletInfo("inference", "Inference", inf, "LiteRT · GPU delegate / XNNPACK CPU"),
            ChipletInfo("postprocess", "Postprocess", listOf(com.loom.studio.chiplets.Postprocess.headLabel(card.head)), card.kindLabel),
            ChipletInfo("display", "Display", com.loom.studio.chiplets.Display(noop).profiles.map { it.label }, "overlay + screen brightness"),
        ) else listOf(
            ChipletInfo("source", "Capture", com.loom.studio.pipeline.IntervalSource(noop).profiles.map { it.label }, "synthetic input tensors"),
            ChipletInfo("inference", "Inference", inf, "LiteRT · GPU delegate / XNNPACK CPU"),
            ChipletInfo("postprocess", "Postprocess", listOf(com.loom.studio.chiplets.Postprocess.headLabel(card.head)), "raw outputs"),
        )
    }

    fun usesCamera(card: ModelCard): Boolean =
        card.format == "tflite" && card.kind == TaskKind.VISION && card.inputs.firstOrNull()?.let { Classifier.imageLayout(it) } != null
}
