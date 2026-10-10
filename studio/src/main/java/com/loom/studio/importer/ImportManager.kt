package com.loom.studio.importer

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Imports a model chosen with the Storage Access Framework (ACTION_OPEN_DOCUMENT) into
 * app-private storage, then inspects it. Imported files are untrusted data:
 *  - only known extensions, checked again by magic bytes in [ModelInspector]
 *  - size capped at 8 GB, free storage checked before copying
 *  - a failure anywhere deletes the partial copy and returns a readable error, never a crash
 */
class ImportManager(private val context: Context, private val store: ModelStore) {

    sealed class Result {
        data class Ok(val card: ModelCard) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): Result = withContext(Dispatchers.IO) {
        val (name, declaredSize) = queryNameAndSize(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED) {
            return@withContext Result.Failed(
                if (ext == "litertlm") "LiteRT-LM (.litertlm) bundles are not supported yet — use a .task or .gguf LLM."
                else "“$name” is not a supported model. Supported: .tflite, .task, .gguf, .onnx"
            )
        }
        if (declaredSize > MAX_IMPORT_BYTES) {
            return@withContext Result.Failed("“$name” is ${formatBytes(declaredSize)}; the import limit is ${formatBytes(MAX_IMPORT_BYTES)}.")
        }
        val free = StatFs(store.root.absolutePath).availableBytes
        if (declaredSize > 0 && declaredSize + HEADROOM_BYTES > free) {
            return@withContext Result.Failed("Not enough free storage: need ${formatBytes(declaredSize + HEADROOM_BYTES)}, have ${formatBytes(free)}.")
        }

        val id = UUID.randomUUID().toString().substring(0, 8)
        val dir = store.dir(id).apply { mkdirs() }
        val part = File(dir, "model.$ext.part")
        val target = File(dir, "model.$ext")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext fail(dir, "Could not open the selected file.")
            var copied = 0L
            input.use { ins ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied > MAX_IMPORT_BYTES) return@withContext fail(dir, "File is larger than ${formatBytes(MAX_IMPORT_BYTES)}.")
                        if (declaredSize > 0) onProgress((copied.toDouble() / declaredSize).toFloat().coerceIn(0f, 1f))
                    }
                }
            }
            if (copied == 0L) return@withContext fail(dir, "The selected file is empty.")
            if (!part.renameTo(target)) return@withContext fail(dir, "Could not store the model file.")
            onProgress(1f)
            val display = name.substringBeforeLast('.').take(60)
            val card = ModelInspector(context).inspect(id, display, ext, target)
            store.saveCard(card)
            Result.Ok(card)
        } catch (e: ModelInspector.InspectException) {
            fail(dir, e.message ?: "Could not read the model.")
        } catch (e: GgufReader.GgufException) {
            fail(dir, "Could not read the GGUF header: ${e.message}")
        } catch (e: java.io.IOException) {
            fail(dir, if (e.message?.contains("ENOSPC") == true) "The phone ran out of storage during import."
                      else "Import failed while copying: ${e.message}")
        } catch (t: Throwable) {
            fail(dir, "Import failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * Import a LOOM project folder (picked with ACTION_OPEN_DOCUMENT_TREE): loom.json is parsed
     * and validated FIRST; only the files it names are copied (nothing else in the folder is read).
     */
    suspend fun importProject(tree: Uri, onProgress: (Float) -> Unit): Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rootId = android.provider.DocumentsContract.getTreeDocumentId(tree)

        fun children(parentId: String): Map<String, Triple<String, String, Long>> {
            val out = HashMap<String, Triple<String, String, Long>>()
            val uri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            resolver.query(uri, arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                android.provider.DocumentsContract.Document.COLUMN_SIZE,
            ), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    out[name] = Triple(c.getString(0), c.getString(2) ?: "", if (c.isNull(3)) -1L else c.getLong(3))
                }
            }
            return out
        }

        /** Resolve "a/b/model.tflite" by walking the folder tree; null if absent. */
        fun resolve(path: String): Triple<String, String, Long>? {
            var parent = rootId
            val parts = path.split('/')
            for ((i, part) in parts.withIndex()) {
                val kids = children(parent)
                val hit = kids[part] ?: return null
                if (i == parts.size - 1) return hit
                if (hit.second != android.provider.DocumentsContract.Document.MIME_TYPE_DIR) return null
                parent = hit.first
            }
            return null
        }

        val json = resolve("loom.json") ?: return@withContext Result.Failed("This folder has no loom.json.")
        if (json.third > 256 * 1024) return@withContext Result.Failed("loom.json is larger than 256 KB.")
        val text = resolver.openInputStream(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, json.first))
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@withContext Result.Failed("Could not read loom.json.")
        val spec = try {
            ProjectParser.parse(text)
        } catch (e: ProjectParser.Invalid) {
            return@withContext Result.Failed("loom.json is not valid:\n• " + e.errors.joinToString("\n• "))
        }
        val found = spec.files.associateWith { resolve(it) }
        val missing = found.filterValues { it == null }.keys
        if (missing.isNotEmpty()) return@withContext Result.Failed("loom.json names files that are not in the folder: ${missing.joinToString()}")
        val total = found.values.sumOf { it!!.third.coerceAtLeast(0) }
        if (total > MAX_IMPORT_BYTES) return@withContext Result.Failed("Project files total ${formatBytes(total)}; the limit is ${formatBytes(MAX_IMPORT_BYTES)}.")
        val free = StatFs(store.root.absolutePath).availableBytes
        if (total + HEADROOM_BYTES > free) return@withContext Result.Failed("Not enough free storage: need ${formatBytes(total + HEADROOM_BYTES)}, have ${formatBytes(free)}.")

        val id = UUID.randomUUID().toString().substring(0, 8)
        val dir = store.dir(id).apply { mkdirs() }
        val pdir = File(dir, "project").apply { mkdirs() }
        try {
            File(pdir, "loom.json").writeText(text)
            var copied = 0L
            for ((path, doc) in found) {
                val out = File(pdir, path)
                if (!out.canonicalPath.startsWith(pdir.canonicalPath + File.separator)) return@withContext fail(dir, "Unsafe path $path")
                out.parentFile?.mkdirs()
                resolver.openInputStream(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, doc!!.first))?.use { ins ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break
                            o.write(buf, 0, n); copied += n
                            if (copied > MAX_IMPORT_BYTES) return@withContext fail(dir, "Project is larger than ${formatBytes(MAX_IMPORT_BYTES)}.")
                            if (total > 0) onProgress((copied.toDouble() / total).toFloat().coerceIn(0f, 1f))
                        }
                    }
                } ?: return@withContext fail(dir, "Could not read $path")
            }
            val primary = File(pdir, spec.primary.file)
            val ext = spec.primary.file.substringAfterLast('.').lowercase()
            val base = ModelInspector(context).inspect(id, spec.name.take(60), ext, primary)
            val labels = spec.labelsFile?.let { f -> File(pdir, f).readLines().map { it.trim().take(200) }.filter { it.isNotEmpty() }.take(20_000) }
            val head = when (spec.postType) {
                "detection" -> Head.DETECTOR; "classification" -> Head.CLASSIFIER; "segmentation" -> Head.SEGMENTER
                "pose" -> Head.POSE; "raw" -> Head.NONE; else -> base.head
            }
            val card = base.copy(
                fileName = "project/" + spec.primary.file,
                kind = spec.task,
                head = if (spec.task == com.loom.studio.core.TaskKind.VISION) head else Head.NONE,
                labels = labels ?: base.labels,
                normMean = spec.mean?.first() ?: base.normMean,
                normStd = spec.std?.first() ?: base.normStd,
                reasons = base.reasons + "LOOM project “${spec.name}”: ${spec.models.size} model(s)" +
                    (spec.stages?.let { " · chain ${it.joinToString(" → ")}" } ?: "") + " · task set by loom.json",
                confidence = 1f,
                overridden = true,
                projectFile = "project/loom.json",
                innerFile = base.innerFile?.let { "project/" + spec.primary.file.substringBeforeLast('/', "").let { d -> if (d.isEmpty()) it else "$d/$it" } },
            )
            store.saveCard(card)
            store.saveSettings(id, store.loadSettings(card).let { st ->
                st.copy(
                    goalFps = spec.goalValue ?: st.goalFps,
                    cutOrder = spec.cutOrder ?: st.cutOrder,
                    heatLimit = spec.heatLimit ?: st.heatLimit,
                )
            })
            onProgress(1f)
            Result.Ok(card)
        } catch (e: ModelInspector.InspectException) {
            fail(dir, e.message ?: "Could not read the project's model.")
        } catch (t: Throwable) {
            fail(dir, "Project import failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun fail(dir: File, msg: String): Result {
        dir.deleteRecursively()
        return Result.Failed(msg)
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "model"
        var size = -1L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Throwable) { }
        // Never let a provider-supplied name escape the model folder.
        name = name.replace('/', '_').replace('\\', '_').take(120)
        return name to size
    }

    companion object {
        val SUPPORTED = setOf("tflite", "task", "gguf", "onnx")
        const val MAX_IMPORT_BYTES = 8L shl 30
        const val HEADROOM_BYTES = 256L shl 20
    }
}
