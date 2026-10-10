package com.loom.studio.importer

import com.loom.studio.core.Level
import com.loom.studio.core.TaskKind
import org.json.JSONArray
import org.json.JSONObject

/** A validated loom.json (brief §10). Declarative data only: nothing in it is ever executed. */
data class ProjectSpec(
    val name: String,
    val task: TaskKind,
    val goalMetric: String?,
    val goalValue: Double?,
    val sourceType: String?,
    val fpsProfiles: List<Int>?,
    val models: List<Model>,
    val resize: Pair<Int, Int>?,
    val mean: List<Float>?,
    val std: List<Float>?,
    val postType: String?,
    val scoreThreshold: Float?,
    val labelsFile: String?,
    val overlay: Boolean,
    val speak: Boolean,
    val cutOrder: List<String>?,
    val heatLimit: Float?,
    val stages: List<String>?,
) {
    data class Model(val id: String, val file: String, val role: String, val level: Level?)

    /** The model a card is built from: the HIGH inference variant, else the first inference model. */
    val primary: Model get() = models.filter { it.role == "inference" }.let { inf -> inf.firstOrNull { it.level == Level.HIGH } ?: inf.first() }
    val classifier: Model? get() = stages?.drop(1)?.firstNotNullOfOrNull { id -> models.firstOrNull { it.id == id && it.role == "classifier" } }
        ?: models.firstOrNull { it.role == "classifier" }

    /** Every file the project references (copied at import, nothing else). */
    val files: List<String> get() = (models.map { it.file } + listOfNotNull(labelsFile)).distinct()
}

/**
 * Parses and validates loom.json. Strict: unknown keys are errors, every field is type- and
 * range-checked, paths must stay inside the project folder. Pure Kotlin + org.json (unit-tested).
 */
object ProjectParser {

    class Invalid(val errors: List<String>) : Exception(errors.joinToString("\n"))

    private val TOP = setOf("loom_project", "name", "task", "goal", "source", "models", "preprocess",
        "postprocess", "output", "cut_order", "heat_limit", "stages", "description")
    private val CUT_KEYS = setOf("display", "inference", "source", "voice", "stage2", "ui", "rate", "context")
    private val MODEL_EXT = setOf("tflite", "onnx", "task", "gguf")
    private val ID = Regex("^[A-Za-z0-9_-]{1,32}$")

    /** Relative path inside the project folder: no "..", no absolute paths, no drive letters. */
    fun safePath(p: String): Boolean {
        if (p.isEmpty() || p.length > 200) return false
        if (p.startsWith("/") || p.contains('\\') || p.contains(':') || p.contains('\u0000')) return false
        val parts = p.split('/')
        return parts.none { it.isEmpty() || it == "." || it == ".." }
    }

    fun parse(text: String): ProjectSpec {
        val e = mutableListOf<String>()
        val j = try { JSONObject(text) } catch (t: Throwable) { throw Invalid(listOf("loom.json is not valid JSON: ${t.message}")) }

        fun unknown(o: JSONObject, allowed: Set<String>, where: String) {
            for (k in o.keys()) if (k !in allowed) e += "unknown key '$k' in $where"
        }
        fun obj(o: JSONObject, k: String, where: String): JSONObject? =
            if (!o.has(k) || o.isNull(k)) null else (o.opt(k) as? JSONObject ?: run { e += "$where.$k must be an object"; null })
        fun arr(o: JSONObject, k: String, where: String): JSONArray? =
            if (!o.has(k) || o.isNull(k)) null else (o.opt(k) as? JSONArray ?: run { e += "$where.$k must be a list"; null })
        fun num(o: JSONObject, k: String, where: String, lo: Double, hi: Double): Double? {
            if (!o.has(k) || o.isNull(k)) return null
            val v = o.opt(k) as? Number ?: run { e += "$where.$k must be a number"; return null }
            val d = v.toDouble()
            if (d.isNaN() || d < lo || d > hi) { e += "$where.$k = $d is outside $lo..$hi"; return null }
            return d
        }
        fun str(o: JSONObject, k: String, where: String): String? =
            if (!o.has(k) || o.isNull(k)) null else (o.opt(k) as? String ?: run { e += "$where.$k must be text"; null })
        fun bool(o: JSONObject, k: String, where: String, def: Boolean): Boolean =
            if (!o.has(k)) def else (o.opt(k) as? Boolean ?: run { e += "$where.$k must be true/false"; def })

        unknown(j, TOP, "loom.json")
        val version = j.opt("loom_project")
        if (version != 1) e += "loom_project must be 1 (got ${version ?: "nothing"})"
        val name = str(j, "name", "loom.json")?.trim().orEmpty()
        if (name.isEmpty() || name.length > 80) e += "name is required (1–80 characters)"
        val taskText = str(j, "task", "loom.json")
        val task = when (taskText) {
            "vision" -> TaskKind.VISION; "llm" -> TaskKind.LLM; "audio" -> TaskKind.AUDIO
            "text" -> TaskKind.TEXT; "generic" -> TaskKind.GENERIC
            else -> { e += "task must be one of vision, llm, audio, text, generic"; TaskKind.GENERIC }
        }

        var goalMetric: String? = null
        var goalValue: Double? = null
        obj(j, "goal", "loom.json")?.let { g ->
            unknown(g, setOf("metric", "value"), "goal")
            goalMetric = str(g, "metric", "goal")
            if (goalMetric !in setOf("fps", "tokens_per_s", "latency_ms")) e += "goal.metric must be fps, tokens_per_s or latency_ms"
            goalValue = num(g, "value", "goal", 0.1, 10000.0) ?: run { e += "goal.value is required"; null }
        }

        var sourceType: String? = null
        var fps: List<Int>? = null
        obj(j, "source", "loom.json")?.let { s ->
            unknown(s, setOf("type", "fps_profiles"), "source")
            sourceType = str(s, "type", "source")
            if (sourceType !in setOf("camera", "interval", "mic", "prompt")) e += "source.type must be camera, interval, mic or prompt"
            arr(s, "fps_profiles", "source")?.let { a ->
                val l = (0 until a.length()).mapNotNull { i -> (a.opt(i) as? Number)?.toInt()?.takeIf { it in 1..60 } }
                if (l.size != a.length() || l.isEmpty() || l.size > 3) e += "source.fps_profiles must be 1–3 numbers between 1 and 60"
                else if (l != l.sortedDescending() || l.distinct().size != l.size) e += "source.fps_profiles must go from highest to lowest"
                else fps = l
            }
        }

        val models = mutableListOf<ProjectSpec.Model>()
        val ma = arr(j, "models", "loom.json")
        if (ma == null || ma.length() == 0) e += "models must list at least one model"
        else if (ma.length() > 6) e += "at most 6 models per project"
        else for (i in 0 until ma.length()) {
            val where = "models[$i]"
            val m = ma.opt(i) as? JSONObject ?: run { e += "$where must be an object"; null } ?: continue
            unknown(m, setOf("id", "file", "role", "level"), where)
            val id = str(m, "id", where)
            if (id == null || !ID.matches(id)) e += "$where.id must be 1–32 letters, digits, - or _"
            val file = str(m, "file", where)
            if (file == null || !safePath(file)) e += "$where.file must be a relative path inside the project folder (no '..')"
            else if (file.substringAfterLast('.', "").lowercase() !in MODEL_EXT) e += "$where.file must be .tflite, .onnx, .task or .gguf"
            val role = str(m, "role", where) ?: "inference"
            if (role !in setOf("inference", "classifier")) e += "$where.role must be inference or classifier"
            val level = str(m, "level", where)?.let { lv ->
                Level.entries.firstOrNull { it.name == lv } ?: run { e += "$where.level must be HIGH, BALANCED or ECO"; null }
            }
            if (id != null && file != null) models += ProjectSpec.Model(id, file, role, level)
        }
        if (models.map { it.id }.distinct().size != models.size) e += "model ids must be unique"
        val inf = models.filter { it.role == "inference" }
        if (models.isNotEmpty() && inf.isEmpty()) e += "at least one model must have role inference"
        val lv = inf.mapNotNull { it.level }
        if (lv.distinct().size != lv.size) e += "two inference models share the same level"

        var resize: Pair<Int, Int>? = null
        var mean: List<Float>? = null
        var std: List<Float>? = null
        obj(j, "preprocess", "loom.json")?.let { p ->
            unknown(p, setOf("resize", "normalize"), "preprocess")
            arr(p, "resize", "preprocess")?.let { a ->
                val w = (a.opt(0) as? Number)?.toInt(); val h = (a.opt(1) as? Number)?.toInt()
                if (a.length() != 2 || w == null || h == null || w !in 8..4096 || h !in 8..4096) e += "preprocess.resize must be [width, height], 8–4096"
                else resize = w to h
            }
            obj(p, "normalize", "preprocess")?.let { n ->
                unknown(n, setOf("mean", "std"), "preprocess.normalize")
                fun floats(k: String): List<Float>? = arr(n, k, "preprocess.normalize")?.let { a ->
                    val l = (0 until a.length()).mapNotNull { (a.opt(it) as? Number)?.toFloat() }
                    if (l.size != a.length() || (l.size != 1 && l.size != 3)) { e += "preprocess.normalize.$k must have 1 or 3 numbers"; null } else l
                }
                mean = floats("mean")
                std = floats("std")
                if (std?.any { it <= 0f } == true) e += "preprocess.normalize.std must be > 0"
            }
        }

        var postType: String? = null
        var thr: Float? = null
        var labels: String? = null
        obj(j, "postprocess", "loom.json")?.let { p ->
            unknown(p, setOf("type", "score_threshold", "labels"), "postprocess")
            postType = str(p, "type", "postprocess")
            if (postType != null && postType !in setOf("detection", "classification", "segmentation", "pose", "raw")) {
                e += "postprocess.type must be detection, classification, segmentation, pose or raw"
            }
            thr = num(p, "score_threshold", "postprocess", 0.0, 1.0)?.toFloat()
            labels = str(p, "labels", "postprocess")
            if (labels != null && (!safePath(labels!!) || !labels!!.endsWith(".txt", true))) e += "postprocess.labels must be a .txt file inside the project folder"
        }

        var overlay = true
        var speak = false
        obj(j, "output", "loom.json")?.let { o ->
            unknown(o, setOf("overlay", "speak"), "output")
            overlay = bool(o, "overlay", "output", true)
            speak = bool(o, "speak", "output", false)
        }

        val cut = arr(j, "cut_order", "loom.json")?.let { a ->
            val l = (0 until a.length()).mapNotNull { a.opt(it) as? String }
            if (l.size != a.length() || l.any { it !in CUT_KEYS } || l.distinct().size != l.size) {
                e += "cut_order must list distinct chiplets from: ${CUT_KEYS.joinToString()}"; null
            } else l
        }
        val heat = num(j, "heat_limit", "loom.json", 0.3, 1.2)?.toFloat()
        val stages = arr(j, "stages", "loom.json")?.let { a ->
            val l = (0 until a.length()).mapNotNull { a.opt(it) as? String }
            if (l.size != a.length() || l.any { s -> models.none { it.id == s } }) { e += "stages must list model ids from models"; null }
            else if (l.size > 2) { e += "at most 2 stages (detector → classifier) are supported"; null }
            else if (l.size == 2 && models.first { it.id == l[1] }.role != "classifier") { e += "the second stage must be a classifier model"; null }
            else l
        }

        if (e.isNotEmpty()) throw Invalid(e)
        return ProjectSpec(name, task, goalMetric, goalValue, sourceType, fps, models, resize, mean, std,
            postType, thr, labels, overlay, speak, cut, heat, stages)
    }
}
