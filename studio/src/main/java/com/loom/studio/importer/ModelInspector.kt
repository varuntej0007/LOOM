package com.loom.studio.importer

import android.app.ActivityManager
import android.content.Context
import com.loom.studio.core.TaskKind
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * Builds a [ModelCard] for an imported file. The file is untrusted: formats are checked by
 * their magic bytes first, nothing in it is ever executed, and every read is bounded.
 */
class ModelInspector(private val context: Context) {

    class InspectException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    fun inspect(id: String, displayName: String, format: String, file: File): ModelCard {
        val size = file.length()
        checkMagic(format, file)
        var inputs: List<TensorSpec> = emptyList()
        var outputs: List<TensorSpec> = emptyList()
        var labels: List<String>? = null
        var llm: LlmInfo? = null
        var hint: String? = null
        val notes = mutableListOf<String>()
        when (format) {
            "tflite" -> {
                val io = readTfliteTensors(file)
                inputs = io.first
                outputs = io.second
                labels = readEmbeddedLabels(file)
                if (labels != null) notes += "${labels.size} labels found inside the model"
            }
            "gguf" -> llm = file.inputStream().use { GgufReader.read(it, size) }
            "task" -> {
                hint = taskHint(file)
                labels = readEmbeddedLabels(file)
                // A non-LLM MediaPipe bundle is a zip of .tflite models: run its main model with LiteRT.
                val inner = if (Classifier.classify("task", emptyList(), emptyList(), hint).kind == TaskKind.LLM) null else extractMainTflite(file)
                if (inner != null) {
                    val io = readTfliteTensors(inner)
                    inputs = io.first
                    outputs = io.second
                    labels = labels ?: readEmbeddedLabels(inner)
                    notes += "MediaPipe bundle: its main model ${inner.name} runs with LiteRT"
                    val r = Classifier.classify("tflite", inputs, outputs)
                    return build(id, displayName, format, file, size, r, inputs, outputs, labels, null, notes, innerFile = inner.name)
                }
            }
            "onnx" -> {
                val io = try {
                    com.loom.studio.runtime.OnnxBackend.inspect(file)
                } catch (t: Throwable) {
                    throw InspectException("ONNX Runtime could not open this model: ${t.message ?: t.javaClass.simpleName}", t)
                }
                inputs = io.first
                outputs = io.second
                if (inputs.any { it.shape.isEmpty() }) notes += "some input sizes are dynamic; 1 is assumed for unknown dimensions"
            }
        }
        val r = Classifier.classify(format, inputs, outputs, hint)
        return build(id, displayName, format, file, size, r, inputs, outputs, labels, llm, notes)
    }

    private fun build(
        id: String, displayName: String, format: String, file: File, size: Long, r: Classifier.Result,
        inputs: List<TensorSpec>, outputs: List<TensorSpec>, labels: List<String>?, llm: LlmInfo?,
        notes: List<String>, innerFile: String? = null,
    ): ModelCard {
        val in0 = inputs.firstOrNull()
        val isFloatInput = in0?.dtype == "float32" || in0?.dtype == "float16"
        return ModelCard(
            id = id,
            displayName = displayName,
            format = format,
            fileName = file.name,
            sizeBytes = size,
            kind = r.kind,
            head = r.head,
            inputs = inputs,
            outputs = outputs,
            labels = labels,
            llm = llm,
            confidence = r.confidence,
            reasons = r.reasons + notes +
                (if (isFloatInput) listOf("float input: pixels normalised as (x − 127.5) / 127.5 (override in loom.json)") else emptyList()),
            normMean = if (isFloatInput) 127.5f else 0f,
            normStd = if (isFloatInput) 127.5f else 1f,
            ramWarning = ramWarning(r.kind, size),
            importedAt = System.currentTimeMillis(),
            innerFile = innerFile,
        )
    }

    /** Largest .tflite inside a MediaPipe bundle, extracted next to it (bounded size). */
    private fun extractMainTflite(file: File): File? = try {
        ZipFile(file).use { zip ->
            val e = zip.entries().toList().filter { !it.isDirectory && it.name.endsWith(".tflite", true) && it.size in 1..(2L shl 30) }
                .maxByOrNull { it.size } ?: return null
            val safe = e.name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            val out = File(file.parentFile, "inner_$safe")
            zip.getInputStream(e).use { ins -> out.outputStream().use { ins.copyTo(it) } }
            out
        }
    } catch (_: Throwable) {
        null
    }

    private fun checkMagic(format: String, file: File) {
        val head = ByteArray(8)
        val n = RandomAccessFile(file, "r").use { it.read(head) }
        if (n < 8) throw InspectException("File is too small to be a model")
        val ok = when (format) {
            // FlatBuffer file identifier at bytes 4..7.
            "tflite" -> String(head, 4, 4, Charsets.US_ASCII) == "TFL3"
            "gguf" -> String(head, 0, 4, Charsets.US_ASCII) == "GGUF"
            "task" -> head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            // ONNX is a protobuf ModelProto; field 1 (ir_version, varint) is tag 0x08.
            "onnx" -> head[0] == 0x08.toByte()
            else -> false
        }
        if (!ok) throw InspectException("This does not look like a valid .$format file (wrong header bytes). It may be corrupted or renamed.")
    }

    private fun readTfliteTensors(file: File): Pair<List<TensorSpec>, List<TensorSpec>> {
        val interpreter = try {
            Interpreter(file, Interpreter.Options().setNumThreads(1))
        } catch (t: Throwable) {
            throw InspectException("LiteRT could not open this model: ${t.message ?: t.javaClass.simpleName}", t)
        }
        interpreter.use { ip ->
            val ins = (0 until ip.inputTensorCount).map { i ->
                val t = ip.getInputTensor(i)
                TensorSpec(t.name() ?: "input$i", t.shape(), dtype(t.dataType()),
                    t.quantizationParams().scale, t.quantizationParams().zeroPoint)
            }
            val outs = (0 until ip.outputTensorCount).map { i ->
                val t = ip.getOutputTensor(i)
                TensorSpec(t.name() ?: "output$i", t.shape(), dtype(t.dataType()),
                    t.quantizationParams().scale, t.quantizationParams().zeroPoint)
            }
            return ins to outs
        }
    }

    /**
     * TFLite models with metadata carry their label file as a zip appended to the model.
     * Read only small .txt entries, at most 20 000 lines of ≤200 chars.
     */
    private fun readEmbeddedLabels(file: File): List<String>? = try {
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory && it.size in 1..(2L shl 20) && it.name.endsWith(".txt", true) }
            val pick = entries.firstOrNull { "label" in it.name.lowercase() } ?: entries.firstOrNull()
            pick?.let { e ->
                zip.getInputStream(e).bufferedReader().useLines { lines ->
                    lines.take(20_000).map { it.trim().take(200) }.filter { it.isNotEmpty() }.toList()
                }
            }?.takeIf { it.isNotEmpty() }
        }
    } catch (_: Throwable) {
        null // no zip part: the model simply has no embedded labels
    }

    private fun taskHint(file: File): String? = try {
        ZipFile(file).use { zip ->
            zip.entries().toList().joinToString(" ") { it.name }.take(2000)
        }
    } catch (_: Throwable) {
        null
    }

    private fun ramWarning(kind: TaskKind, size: Long): String? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = mi.totalMem
        return when {
            size > total * 0.6 -> "Model is ${formatBytes(size)} but the phone has ${formatBytes(total)} RAM — it will very likely not load."
            size > total * 0.35 -> "Model is ${formatBytes(size)} of ${formatBytes(total)} RAM — it may load, but will be slow and hot."
            kind == TaskKind.LLM && size > (3L shl 30) -> "Large LLM (${formatBytes(size)}): expect low tokens/s and fast heating. 0.5–3 B at 4-bit is the sweet spot."
            else -> null
        }
    }

    companion object {
        fun dtype(t: DataType): String = when (t) {
            DataType.FLOAT32 -> "float32"
            DataType.UINT8 -> "uint8"
            DataType.INT8 -> "int8"
            DataType.INT32 -> "int32"
            DataType.INT64 -> "int64"
            DataType.BOOL -> "bool"
            DataType.STRING -> "string"
            else -> t.name.lowercase()
        }
    }
}
