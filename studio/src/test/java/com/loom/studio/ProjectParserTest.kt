package com.loom.studio

import com.loom.studio.core.Level
import com.loom.studio.core.TaskKind
import com.loom.studio.importer.ProjectParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** loom.json validator: good files, bad files, path traversal (brief §14). */
class ProjectParserTest {
    private val example = """
    {
      "loom_project": 1,
      "name": "Street sign reader",
      "task": "vision",
      "goal": { "metric": "fps", "value": 15 },
      "source": { "type": "camera", "fps_profiles": [30, 15, 10] },
      "models": [
        { "id": "det_hi", "file": "detector_large.tflite", "role": "inference", "level": "HIGH" },
        { "id": "det_lo", "file": "detector_small.tflite", "role": "inference", "level": "BALANCED" }
      ],
      "preprocess": { "resize": [320, 320], "normalize": { "mean": [127.5], "std": [127.5] } },
      "postprocess": { "type": "detection", "score_threshold": 0.4, "labels": "labels.txt" },
      "output": { "overlay": true, "speak": false },
      "cut_order": ["display", "inference", "source"],
      "heat_limit": 0.80
    }"""

    private fun errors(json: String): List<String> = try { ProjectParser.parse(json); emptyList() } catch (e: ProjectParser.Invalid) { e.errors }

    @Test fun briefExampleParses() {
        val p = ProjectParser.parse(example)
        assertEquals("Street sign reader", p.name)
        assertEquals(TaskKind.VISION, p.task)
        assertEquals(listOf(30, 15, 10), p.fpsProfiles)
        assertEquals("det_hi", p.primary.id)
        assertEquals(Level.BALANCED, p.models[1].level)
        assertEquals(listOf("detector_large.tflite", "detector_small.tflite", "labels.txt"), p.files)
        assertEquals(0.8f, p.heatLimit!!, 1e-6f)
    }

    @Test fun rejectsUnknownKeys() {
        val e = errors(example.replace("\"heat_limit\"", "\"script\": \"rm -rf /\", \"heat_limit\""))
        assertTrue(e.toString(), e.any { "unknown key 'script'" in it })
    }

    @Test fun rejectsPathTraversal() {
        for (bad in listOf("../evil.tflite", "/sdcard/x.tflite", "a/../../b.tflite", "C:\\\\x.tflite", "dir//x.tflite")) {
            val e = errors(example.replace("detector_large.tflite", bad))
            assertTrue("$bad → $e", e.any { "models[0].file" in it })
        }
        assertTrue(errors(example.replace("labels.txt", "../labels.txt")).any { "postprocess.labels" in it })
    }

    @Test fun rejectsBadValues() {
        assertTrue(errors(example.replace("\"loom_project\": 1", "\"loom_project\": 2")).any { "loom_project" in it })
        assertTrue(errors(example.replace("\"heat_limit\": 0.80", "\"heat_limit\": 5")).any { "heat_limit" in it })
        assertTrue(errors(example.replace("[30, 15, 10]", "[10, 30]")).any { "fps_profiles" in it })
        assertTrue(errors(example.replace("\"level\": \"BALANCED\"", "\"level\": \"HIGH\"")).any { "same level" in it })
        assertTrue(errors(example.replace("detector_small.tflite", "detector_small.exe")).any { "models[1].file" in it })
        assertTrue(errors("not json").any { "not valid JSON" in it })
    }

    @Test fun chainNeedsClassifierSecond() {
        val chain = example.replace("\"heat_limit\": 0.80", "\"heat_limit\": 0.80, \"stages\": [\"det_hi\", \"det_lo\"]")
        assertTrue(errors(chain).any { "second stage must be a classifier" in it })
        val ok = chain.replace("\"role\": \"inference\", \"level\": \"BALANCED\"", "\"role\": \"classifier\"")
        assertEquals("det_lo", ProjectParser.parse(ok).classifier!!.id)
    }
}
