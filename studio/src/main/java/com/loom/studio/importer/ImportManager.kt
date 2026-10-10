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
