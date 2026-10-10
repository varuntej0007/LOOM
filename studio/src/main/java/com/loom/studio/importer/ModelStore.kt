package com.loom.studio.importer

import android.content.Context
import com.loom.studio.core.TaskKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-model user settings (goal, cut order, heat limit), saved as settings.json.
 * Cut order = which chiplet the Weaver lowers first when the phone is hot (brief §6).
 */
data class ModelSettings(
    val goalFps: Double = 15.0,
    val cutOrder: List<String> = DEFAULT_VISION_CUT,
    val heatLimit: Float = 0.80f,
) {
    companion object {
        /** Cut what the user notices least first: screen, then model quality, then frames. */
        val DEFAULT_VISION_CUT = listOf("display", "inference", "source")
        val DEFAULT_GENERIC_CUT = listOf("inference", "source")
        /** LLM: Voice → UI refresh → token-rate cap → threads → context length (brief §6). */
        val DEFAULT_LLM_CUT = listOf("voice", "ui", "rate", "inference", "context")
    }
}

/** App-private model library: files/models/<id>/{model.<ext>, card.json, settings.json, profiles.json}. */
class ModelStore(context: Context) {
    val root: File = File(context.filesDir, "models").apply { mkdirs() }

    fun dir(id: String) = File(root, id)
    fun modelFile(card: ModelCard) = File(dir(card.id), card.fileName)
    /** The file a tensor runtime loads (inner .tflite of a MediaPipe vision bundle, else the model). */
    fun runFile(card: ModelCard) = File(dir(card.id), card.runFile)
    fun profilesFile(id: String) = File(dir(id), "profiles.json")

    fun list(): List<ModelCard> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { d -> loadCard(d.name) }
            .sortedByDescending { it.importedAt }

    fun loadCard(id: String): ModelCard? = try {
        val f = File(dir(id), "card.json")
        if (f.exists()) cardFromJson(JSONObject(f.readText())) else null
    } catch (_: Throwable) {
        null
    }

    fun saveCard(card: ModelCard) {
        val d = dir(card.id).apply { mkdirs() }
        atomicWrite(File(d, "card.json"), cardToJson(card).toString(2))
    }

    fun loadSettings(card: ModelCard): ModelSettings {
        val default = ModelSettings(
            cutOrder = when (card.kind) {
                TaskKind.VISION -> ModelSettings.DEFAULT_VISION_CUT
                TaskKind.LLM -> ModelSettings.DEFAULT_LLM_CUT
                else -> ModelSettings.DEFAULT_GENERIC_CUT
            },
            goalFps = when (card.kind) { TaskKind.VISION -> 15.0; TaskKind.LLM -> 8.0; else -> 10.0 },
        )
        return try {
            val f = File(dir(card.id), "settings.json")
            if (!f.exists()) return default
            val j = JSONObject(f.readText())
            ModelSettings(
                goalFps = j.optDouble("goal_fps", default.goalFps).coerceIn(1.0, 60.0),
                cutOrder = j.optJSONArray("cut_order")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?.let { saved -> saved + (default.cutOrder - saved.toSet()) } ?: default.cutOrder,
                heatLimit = j.optDouble("heat_limit", default.heatLimit.toDouble()).toFloat().coerceIn(0.3f, 1.2f),
            )
        } catch (_: Throwable) {
            default
        }
    }

    fun saveSettings(id: String, s: ModelSettings) {
        val j = JSONObject()
            .put("goal_fps", s.goalFps)
            .put("cut_order", JSONArray(s.cutOrder))
            .put("heat_limit", s.heatLimit.toDouble())
        atomicWrite(File(dir(id), "settings.json"), j.toString(2))
    }

    fun project(card: ModelCard): ProjectSpec? = card.projectFile?.let { f ->
        try { ProjectParser.parse(File(dir(card.id), f).readText()) } catch (_: Throwable) { null }
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    companion object {
        fun atomicWrite(f: File, text: String) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }

        private fun tensorToJson(t: TensorSpec) = JSONObject()
            .put("name", t.name).put("shape", JSONArray(t.shape.toList())).put("dtype", t.dtype)
            .put("scale", t.scale.toDouble()).put("zero_point", t.zeroPoint)

        private fun tensorFromJson(j: JSONObject): TensorSpec {
            val a = j.getJSONArray("shape")
            return TensorSpec(j.getString("name"), IntArray(a.length()) { a.getInt(it) }, j.getString("dtype"),
                j.optDouble("scale", 0.0).toFloat(), j.optInt("zero_point", 0))
        }

        fun cardToJson(c: ModelCard): JSONObject = JSONObject()
            .put("id", c.id).put("display_name", c.displayName).put("format", c.format)
            .put("file", c.fileName).put("size_bytes", c.sizeBytes)
            .put("kind", c.kind.name).put("head", c.head.name)
            .put("inputs", JSONArray(c.inputs.map(::tensorToJson)))
            .put("outputs", JSONArray(c.outputs.map(::tensorToJson)))
            .put("labels", c.labels?.let { JSONArray(it) } ?: JSONObject.NULL)
            .put("llm", c.llm?.let { l ->
                JSONObject().put("architecture", l.architecture ?: JSONObject.NULL).put("name", l.name ?: JSONObject.NULL)
                    .put("context_length", l.contextLength ?: JSONObject.NULL).put("quant", l.quant ?: JSONObject.NULL)
                    .put("params_b", l.paramsBillions ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
            .put("confidence", c.confidence.toDouble())
            .put("reasons", JSONArray(c.reasons))
            .put("norm_mean", c.normMean.toDouble()).put("norm_std", c.normStd.toDouble())
            .put("ram_warning", c.ramWarning ?: JSONObject.NULL)
            .put("imported_at", c.importedAt)
            .put("overridden", c.overridden)
            .put("inner_file", c.innerFile ?: JSONObject.NULL)
            .put("project_file", c.projectFile ?: JSONObject.NULL)

        fun cardFromJson(j: JSONObject): ModelCard {
            fun tensors(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { tensorFromJson(a.getJSONObject(it)) } } ?: emptyList()
            fun strs(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.getString(i) } }
            val llm = j.optJSONObject("llm")?.let { l ->
                LlmInfo(
                    l.optString("architecture").takeIf { !l.isNull("architecture") },
                    l.optString("name").takeIf { !l.isNull("name") },
                    if (l.isNull("context_length")) null else l.optLong("context_length"),
                    l.optString("quant").takeIf { !l.isNull("quant") },
                    if (l.isNull("params_b")) null else l.optDouble("params_b"),
                )
            }
            return ModelCard(
                id = j.getString("id"),
                displayName = j.getString("display_name"),
                format = j.getString("format"),
                fileName = j.getString("file"),
                sizeBytes = j.getLong("size_bytes"),
                kind = TaskKind.valueOf(j.getString("kind")),
                head = Head.valueOf(j.optString("head", "NONE")),
                inputs = tensors("inputs"),
                outputs = tensors("outputs"),
                labels = if (j.isNull("labels")) null else strs(j.optJSONArray("labels")),
                llm = llm,
                confidence = j.optDouble("confidence", 0.5).toFloat(),
                reasons = strs(j.optJSONArray("reasons")) ?: emptyList(),
                normMean = j.optDouble("norm_mean", 0.0).toFloat(),
                normStd = j.optDouble("norm_std", 1.0).toFloat(),
                ramWarning = if (j.isNull("ram_warning")) null else j.optString("ram_warning"),
                importedAt = j.optLong("imported_at", 0L),
                overridden = j.optBoolean("overridden", false),
                innerFile = if (j.isNull("inner_file")) null else j.optString("inner_file").ifEmpty { null },
                projectFile = if (j.isNull("project_file")) null else j.optString("project_file").ifEmpty { null },
            )
        }
    }
}
