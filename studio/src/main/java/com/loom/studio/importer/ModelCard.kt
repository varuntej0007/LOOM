package com.loom.studio.importer

import com.loom.studio.core.TaskKind

/** What a vision model's outputs mean (decides the Postprocess chiplet). */
enum class Head { DETECTOR, CLASSIFIER, SEGMENTER, POSE, NONE }

data class TensorSpec(
    val name: String,
    val shape: IntArray,
    /** float32 | float16 | uint8 | int8 | int32 | int64 | bool | string | unknown */
    val dtype: String,
    val scale: Float = 0f,
    val zeroPoint: Int = 0,
) {
    val shapeText: String get() = shape.joinToString(prefix = "[", postfix = "]")
    val elements: Long get() = shape.fold(1L) { a, d -> a * d.coerceAtLeast(1) }

    override fun equals(other: Any?): Boolean =
        other is TensorSpec && name == other.name && shape.contentEquals(other.shape) &&
            dtype == other.dtype && scale == other.scale && zeroPoint == other.zeroPoint

    override fun hashCode(): Int = (name.hashCode() * 31 + shape.contentHashCode()) * 31 + dtype.hashCode()
}

/** GGUF facts read from the file header (M5 uses them; shown on the Inspect screen now). */
data class LlmInfo(
    val architecture: String?,
    val name: String?,
    val contextLength: Long?,
    val quant: String?,
    val paramsBillions: Double?,
)

/**
 * Everything Studio knows about an imported model. Built at import by [ModelInspector] and
 * saved as card.json next to the model. The user can override [kind] / [head].
 */
data class ModelCard(
    val id: String,
    val displayName: String,
    /** tflite | task | gguf | onnx */
    val format: String,
    val fileName: String,
    val sizeBytes: Long,
    val kind: TaskKind,
    val head: Head,
    val inputs: List<TensorSpec>,
    val outputs: List<TensorSpec>,
    val labels: List<String>?,
    val llm: LlmInfo?,
    /** How sure the auto-detect is (0..1). */
    val confidence: Float,
    /** Why the inspector decided what it decided (shown on the Inspect screen). */
    val reasons: List<String>,
    /** Input normalisation for float models: (x - mean) / std on 0..255 pixels. */
    val normMean: Float,
    val normStd: Float,
    val ramWarning: String?,
    val importedAt: Long,
    val overridden: Boolean = false,
) {
    /** "VISION · detector" — the line the M1 done-check looks for. */
    val kindLabel: String
        get() = if (kind == TaskKind.VISION && head != Head.NONE) "VISION · ${head.name.lowercase()}" else kind.name

    val sizeText: String get() = formatBytes(sizeBytes)

    /** True when Studio can run it in this build (other runtimes arrive in later drops). */
    val runnable: Boolean get() = format == "tflite"
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", b / (1L shl 10).toDouble())
    else -> "$b B"
}
