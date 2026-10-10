package com.loom.studio.pipeline

import com.loom.studio.core.TaskKind
import com.loom.studio.importer.Classifier
import com.loom.studio.importer.ModelCard
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** TaskKind → chiplet pipeline template (brief §6). */
object PipelineFactory {

    class Unsupported(msg: String) : Exception(msg)

    fun build(card: ModelCard, store: com.loom.studio.importer.ModelStore, scope: CoroutineScope,
              onEvent: (String) -> Unit, context: android.content.Context): Pipeline {
        if (!card.runnable) throw Unsupported("No runtime for this model (${card.format}, ${card.kindLabel}).")
        val file = store.modelFile(card)
        if (card.kind == TaskKind.LLM) return LlmPipeline(card, file, context, scope, onEvent)
        val project = store.project(card)
        val projectDir = File(store.dir(card.id), "project")
        val profiles = project?.let { ProjectProfiles.inference(it, projectDir) }
        val runFile = store.runFile(card)
        return if (usesCamera(card)) VisionPipeline(card, runFile, scope, onEvent, profiles, project, context, projectDir)
        else GenericPipeline(card, runFile, scope, onEvent, profiles)
    }

    data class ChipletInfo(val key: String, val name: String, val profiles: List<String>, val role: String)

    /** The chiplet chain this model gets, without loading it (Model detail screen). */
    fun describe(card: ModelCard): List<ChipletInfo> {
        val inf = com.loom.studio.chiplets.InferenceChiplet.profilesFor(card).map { it.label }
        val noop: (String) -> Unit = {}
        if (card.kind == TaskKind.LLM) {
            val mp = card.format == "task"
            return listOfNotNull(
                ChipletInfo("prompt", "Prompt", listOf("chat history + new message"), "your message"),
                ChipletInfo("tokenizer", "Tokenizer", listOf("chat template + prefill"), "model's chat template"),
                ChipletInfo("inference", "Inference", if (mp) listOf("MediaPipe GPU", "MediaPipe CPU") else listOf("CPU ×6", "CPU ×4", "CPU ×2"),
                    if (mp) "MediaPipe LLM Inference" else "llama.cpp (threads change live)"),
                if (mp) null else ChipletInfo("context", "Context", listOf("context 4096", "context 2048", "context 1024"), "KV cache size, rebuilt between replies"),
                ChipletInfo("rate", "Sampler", listOf("uncapped · 512 tokens", "≤ 12 tok/s · 384 tokens", "≤ 6 tok/s · 256 tokens"), "token-rate cap + reply length"),
                ChipletInfo("ui", "TextStream", listOf("every token", "10 updates/s", "4 updates/s"), "screen refresh while streaming"),
                ChipletInfo("voice", "Voice", listOf("speak replies", "first sentence", "silent"), "optional text-to-speech"),
            )
        }
        return if (usesCamera(card)) listOf(
            ChipletInfo("source", "Capture", com.loom.studio.chiplets.CameraSource(noop).profiles.map { it.label }, "camera frames (real Camera2 rate)"),
            ChipletInfo("preprocess", "Preprocess", listOf("rotate + resize"), "frame → ${card.inputs.firstOrNull()?.shapeText} ${card.inputs.firstOrNull()?.dtype}"),
            ChipletInfo("inference", "Inference", inf, if (card.runFormat == "onnx") "ONNX Runtime · QNN NPU / CPU" else "LiteRT · GPU delegate / XNNPACK CPU / NNAPI"),
            ChipletInfo("postprocess", "Postprocess", listOf(com.loom.studio.chiplets.Postprocess.headLabel(card.head)), card.kindLabel),
            ChipletInfo("display", "Display", com.loom.studio.chiplets.Display(noop).profiles.map { it.label }, "overlay + screen brightness"),
        ) else listOf(
            ChipletInfo("source", "Capture", com.loom.studio.pipeline.IntervalSource(noop).profiles.map { it.label }, "synthetic input tensors"),
            ChipletInfo("inference", "Inference", inf, if (card.runFormat == "onnx") "ONNX Runtime · QNN NPU / CPU" else "LiteRT · GPU delegate / XNNPACK CPU"),
            ChipletInfo("postprocess", "Postprocess", listOf(com.loom.studio.chiplets.Postprocess.headLabel(card.head)), "raw outputs"),
        )
    }

    fun usesCamera(card: ModelCard): Boolean =
        card.runFormat in setOf("tflite", "onnx") && card.kind == TaskKind.VISION && card.inputs.firstOrNull()?.let { Classifier.imageLayout(it) } != null
}
