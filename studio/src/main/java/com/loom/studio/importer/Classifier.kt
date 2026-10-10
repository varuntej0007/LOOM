package com.loom.studio.importer

import com.loom.studio.core.TaskKind

/**
 * Pure rules that decide what kind of AI a model is from its tensors (brief §5).
 * No Android dependencies, so it is unit-tested on the laptop.
 *
 *  - .gguf                                     → LLM
 *  - .task                                     → by the MediaPipe task type in the bundle
 *  - image input [1,H,W,3] / [1,3,H,W]         → VISION, then by outputs:
 *        boxes [1,N,4] + scores/classes [1,N]  → detector (SSD style)
 *        one [1,4+C,N] / [1,N,4+C] tensor      → detector (YOLO style)
 *        [1,1,K,3] / [1,K,3] with K 5..33      → pose
 *        [1,h,w,C] with C ≥ 2                  → segmentation
 *        [1,N] / [N]                           → classifier
 *  - long float vector / spectrogram           → AUDIO
 *  - int32/int64 token ids [1,seq]             → TEXT
 *  - anything else                             → GENERIC (still benchmarked and managed)
 */
object Classifier {

    data class Result(val kind: TaskKind, val head: Head, val confidence: Float, val reasons: List<String>)

    data class Image(val h: Int, val w: Int, val c: Int, val nchw: Boolean)

    fun imageLayout(t: TensorSpec): Image? {
        val s = t.shape
        if (s.size != 4 || s[0] > 1) return null
        if (t.dtype !in setOf("float32", "float16", "uint8", "int8")) return null
        // NHWC
        if ((s[3] == 3 || s[3] == 1 || s[3] == 4) && s[1] >= 16 && s[2] >= 16) return Image(s[1], s[2], s[3], false)
        // NCHW
        if ((s[1] == 3 || s[1] == 1) && s[2] >= 16 && s[3] >= 16) return Image(s[2], s[3], s[1], true)
        return null
    }

    fun classify(format: String, inputs: List<TensorSpec>, outputs: List<TensorSpec>, taskHint: String? = null): Result {
        when (format) {
            "gguf" -> return Result(TaskKind.LLM, Head.NONE, 0.99f, listOf(".gguf files are llama.cpp language models"))
            "task" -> return classifyTask(taskHint)
        }
        if (inputs.isEmpty()) return Result(TaskKind.GENERIC, Head.NONE, 0.3f, listOf("no input tensors could be read"))
        val in0 = inputs[0]
        val img = imageLayout(in0)
        if (img != null) {
            val why = mutableListOf("input ${in0.shapeText} ${in0.dtype} looks like an image " +
                "(${img.w}×${img.h}×${img.c}, ${if (img.nchw) "NCHW" else "NHWC"})")
            val (head, conf, hwhy) = visionHead(outputs, img)
            why += hwhy
            return Result(TaskKind.VISION, head, conf, why)
        }
        val s = in0.shape
        if ((in0.dtype == "int32" || in0.dtype == "int64") && s.size == 2 && s[0] <= 1) {
            return Result(TaskKind.TEXT, Head.NONE, 0.8f, listOf("input ${in0.shapeText} ${in0.dtype} looks like token ids"))
        }
        if (in0.dtype == "float32" || in0.dtype == "int16") {
            val n = in0.elements
            if ((s.size == 1 || (s.size == 2 && s[0] <= 1)) && n >= 8000) {
                return Result(TaskKind.AUDIO, Head.NONE, 0.7f, listOf("input ${in0.shapeText} is a long 1-D signal (audio samples)"))
            }
            if (s.size == 3 && s[0] <= 1 && s[2] in 32..128 && s[1] >= 32) {
                return Result(TaskKind.AUDIO, Head.NONE, 0.55f, listOf("input ${in0.shapeText} looks like a spectrogram (time × mel bins)"))
            }
        }
        return Result(TaskKind.GENERIC, Head.NONE, 0.5f, listOf("input ${in0.shapeText} ${in0.dtype} matches no known pattern — raw tensors in, numbers out"))
    }

    private fun classifyTask(hint: String?): Result {
        val h = hint?.lowercase() ?: ""
        return when {
            "llm" in h || "genai" in h || "gemma" in h || "tokenizer" in h ->
                Result(TaskKind.LLM, Head.NONE, 0.85f, listOf("MediaPipe bundle contains an LLM ($hint)"))
            "detector" in h || "detection" in h -> Result(TaskKind.VISION, Head.DETECTOR, 0.8f, listOf("MediaPipe vision bundle ($hint)"))
            "classifier" in h || "classification" in h -> Result(TaskKind.VISION, Head.CLASSIFIER, 0.7f, listOf("MediaPipe bundle ($hint)"))
            "segment" in h -> Result(TaskKind.VISION, Head.SEGMENTER, 0.75f, listOf("MediaPipe bundle ($hint)"))
            "pose" in h || "landmark" in h -> Result(TaskKind.VISION, Head.POSE, 0.75f, listOf("MediaPipe bundle ($hint)"))
            "audio" in h -> Result(TaskKind.AUDIO, Head.NONE, 0.7f, listOf("MediaPipe audio bundle ($hint)"))
            "text" in h -> Result(TaskKind.TEXT, Head.NONE, 0.7f, listOf("MediaPipe text bundle ($hint)"))
            else -> Result(TaskKind.GENERIC, Head.NONE, 0.3f, listOf("MediaPipe bundle of unknown type"))
        }
    }

    private fun visionHead(outputs: List<TensorSpec>, img: Image): Triple<Head, Float, String> {
        if (outputs.isEmpty()) return Triple(Head.NONE, 0.5f, "no outputs readable")
        val boxes = outputs.firstOrNull { o -> o.shape.size == 3 && o.shape[2] == 4 && o.shape[1] >= 1 }
        if (boxes != null) {
            val n = boxes.shape[1]
            val perBox = outputs.count { o -> o !== boxes && o.shape.size == 2 && o.shape[1] == n }
            if (perBox >= 2 || (perBox >= 1 && outputs.size >= 3)) {
                return Triple(Head.DETECTOR, 0.95f, "outputs: boxes ${boxes.shapeText} + scores/classes [1,$n] → SSD-style detector")
            }
        }
        if (outputs.size == 1) {
            val o = outputs[0]
            val s = o.shape
            // Pose: [1,1,K,3] (MoveNet) or [1,K,3]
            val k = when {
                s.size == 4 && s[0] == 1 && s[1] == 1 && s[3] == 3 -> s[2]
                s.size == 3 && s[0] == 1 && s[2] == 3 -> s[1]
                else -> -1
            }
            if (k in 5..33) return Triple(Head.POSE, 0.85f, "output ${o.shapeText} = $k keypoints × (y, x, score) → pose")
            // YOLO style: [1, 4+C, N] or [1, N, 4+C], N large
            if (s.size == 3 && s[0] == 1) {
                val a = s[1]
                val b = s[2]
                if ((a in 5..400 && b >= 500) || (b in 5..400 && a >= 500)) {
                    return Triple(Head.DETECTOR, 0.75f, "output ${o.shapeText} = candidate boxes × (4 + classes) → YOLO-style detector")
                }
            }
            // Segmentation: [1,h,w,C] spatial output
            if (s.size == 4 && s[0] == 1 && s[1] >= img.h / 16 && s[2] >= img.w / 16 && s[1] >= 8 && s[3] >= 2) {
                return Triple(Head.SEGMENTER, 0.8f, "output ${o.shapeText} is a per-pixel class map → segmentation")
            }
            if (s.size == 4 && s[0] == 1 && s[1] >= 2 && s[2] >= img.h / 16 && s[3] >= img.w / 16 && s[2] >= 8) {
                return Triple(Head.SEGMENTER, 0.7f, "output ${o.shapeText} is a per-pixel class map (NCHW) → segmentation")
            }
            // Classifier: [1,N] or [N]
            if ((s.size == 2 && s[0] == 1 && s[1] >= 2) || (s.size == 1 && s[0] >= 2)) {
                return Triple(Head.CLASSIFIER, 0.9f, "output ${o.shapeText} = one score per class → classifier")
            }
        }
        val cls = outputs.firstOrNull { o -> o.shape.size == 2 && o.shape[0] == 1 && o.shape[1] >= 2 }
        if (cls != null && outputs.size <= 2) {
            return Triple(Head.CLASSIFIER, 0.6f, "output ${cls.shapeText} looks like class scores → classifier")
        }
        return Triple(Head.NONE, 0.55f, "outputs ${outputs.joinToString { it.shapeText }} match no known vision head — raw outputs shown")
    }
}
