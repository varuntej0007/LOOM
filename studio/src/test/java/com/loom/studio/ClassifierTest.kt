package com.loom.studio

import com.loom.studio.core.TaskKind
import com.loom.studio.importer.Classifier
import com.loom.studio.importer.Head
import com.loom.studio.importer.TensorSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inspector classification on 12 real-world tensor signatures (brief §14). */
class ClassifierTest {
    private fun t(vararg shape: Int, dtype: String = "float32", name: String = "t") = TensorSpec(name, shape, dtype)
    private fun cls(ins: List<TensorSpec>, outs: List<TensorSpec>, format: String = "tflite") = Classifier.classify(format, ins, outs)

    @Test fun efficientDetLite0_isVisionDetector() {
        val r = cls(listOf(t(1, 320, 320, 3, dtype = "uint8")),
            listOf(t(1, 25, 4), t(1, 25), t(1, 25), t(1)))
        assertEquals(TaskKind.VISION, r.kind); assertEquals(Head.DETECTOR, r.head)
        assertTrue(r.confidence >= 0.9f)
    }

    /** The real efficientdet_lite0.tflite LOOM ships: raw head, no NMS inside the model. */
    @Test fun efficientDetLite0Raw_isAnchorDetector() {
        val outs = listOf(t(1, 19206, 90), t(1, 19206, 4))
        val r = cls(listOf(t(1, 320, 320, 3)), outs)
        assertEquals(TaskKind.VISION, r.kind); assertEquals(Head.DETECTOR, r.head)
        assertTrue(Classifier.isAnchorDetector(outs))
    }

    @Test fun ssdMobileNet_isDetector() {
        val r = cls(listOf(t(1, 300, 300, 3, dtype = "uint8")), listOf(t(1, 10, 4), t(1, 10), t(1, 10), t(1)))
        assertEquals(Head.DETECTOR, r.head)
    }

    @Test fun yolov8_isDetector() {
        val r = cls(listOf(t(1, 640, 640, 3)), listOf(t(1, 84, 8400)))
        assertEquals(TaskKind.VISION, r.kind); assertEquals(Head.DETECTOR, r.head)
    }

    @Test fun mobilenetClassifier() {
        val r = cls(listOf(t(1, 224, 224, 3, dtype = "uint8")), listOf(t(1, 1001, dtype = "uint8")))
        assertEquals(Head.CLASSIFIER, r.head)
    }

    @Test fun efficientNetNchwClassifier() {
        val r = cls(listOf(t(1, 3, 224, 224)), listOf(t(1, 1000)), format = "onnx")
        assertEquals(TaskKind.VISION, r.kind); assertEquals(Head.CLASSIFIER, r.head)
    }

    @Test fun deeplabSegmentation() {
        val r = cls(listOf(t(1, 257, 257, 3)), listOf(t(1, 257, 257, 21)))
        assertEquals(Head.SEGMENTER, r.head)
    }

    @Test fun moveNetPose() {
        val r = cls(listOf(t(1, 192, 192, 3, dtype = "uint8")), listOf(t(1, 1, 17, 3)))
        assertEquals(Head.POSE, r.head)
    }

    @Test fun yamnetAudio() {
        val r = cls(listOf(t(15600)), listOf(t(1, 521)))
        assertEquals(TaskKind.AUDIO, r.kind)
    }

    @Test fun spectrogramAudio() {
        val r = cls(listOf(t(1, 96, 64)), listOf(t(1, 521)))
        assertEquals(TaskKind.AUDIO, r.kind)
    }

    @Test fun bertText() {
        val r = cls(listOf(t(1, 128, dtype = "int32"), t(1, 128, dtype = "int32")), listOf(t(1, 2)))
        assertEquals(TaskKind.TEXT, r.kind)
    }

    @Test fun tabularGeneric() {
        val r = cls(listOf(t(1, 12)), listOf(t(1, 1)))
        assertEquals(TaskKind.GENERIC, r.kind)
    }

    @Test fun ggufIsLlm() {
        assertEquals(TaskKind.LLM, cls(emptyList(), emptyList(), format = "gguf").kind)
    }

    @Test fun mediapipeLlmTask() {
        assertEquals(TaskKind.LLM, Classifier.classify("task", emptyList(), emptyList(), "TF_LITE_PREFILL_DECODE TOKENIZER_MODEL").kind)
    }
}
