package com.loom.studio.runtime

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.os.Process
import com.loom.studio.importer.ModelCard
import com.loom.studio.importer.TensorSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ONNX Runtime backend (M6). Knobs: "delegate" = "cpu" | "qnn" | "nnapi", "threads".
 * "qnn" = Qualcomm QNN execution provider on the Hexagon NPU (HTP) — M10's NPU profile.
 * Inputs / outputs are the same direct ByteBuffers as LiteRT, so Preprocess / Postprocess
 * chiplets work unchanged.
 */
class OnnxBackend : Backend {
    override suspend fun load(card: ModelCard, file: File, knobs: Map<String, Any>): Session =
        withContext(Dispatchers.IO) { OnnxSession.create(card, file, knobs) }

    companion object {
        val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

        /** Read tensor specs at import (CPU session, closed right after). */
        fun inspect(file: File): Pair<List<TensorSpec>, List<TensorSpec>> {
            OrtSession.SessionOptions().use { o ->
                o.setIntraOpNumThreads(1)
                env.createSession(file.absolutePath, o).use { s ->
                    fun spec(name: String, info: ai.onnxruntime.NodeInfo): TensorSpec {
                        val ti = info.info as? TensorInfo
                        val shape = ti?.shape?.map { d -> if (d < 0) 1 else d.toInt() }?.toIntArray() ?: IntArray(0)
                        return TensorSpec(name, shape, dtype(ti?.type))
                    }
                    return s.inputInfo.map { (n, i) -> spec(n, i) } to s.outputInfo.map { (n, i) -> spec(n, i) }
                }
            }
        }

        fun dtype(t: OnnxJavaType?): String = when (t) {
            OnnxJavaType.FLOAT -> "float32"
            OnnxJavaType.UINT8 -> "uint8"
            OnnxJavaType.INT8 -> "int8"
            OnnxJavaType.INT32 -> "int32"
            OnnxJavaType.INT64 -> "int64"
            OnnxJavaType.FLOAT16 -> "float16"
            OnnxJavaType.BOOL -> "bool"
            else -> "unknown"
        }
    }
}

class OnnxSession private constructor(
    private val exec: ExecutorService,
    private val session: OrtSession,
    private val card: ModelCard,
    override val label: String,
    override val tid: Int,
) : Session {
    private val inNames = session.inputNames.toList()
    private val outNames = session.outputNames.toList()
    private val outBufs = HashMap<String, ByteBuffer>()
    @Volatile private var closed = false

    override fun run(inputs: Map<String, Any>): Map<String, Any> {
        check(!closed) { "session closed" }
        return exec.submit(Callable {
            val env = OnnxBackend.env
            val tensors = inNames.mapIndexed { i, name ->
                val buf = (inputs[i.toString()] as? ByteBuffer)?.duplicate()?.order(ByteOrder.nativeOrder()) ?: error("missing input $i")
                buf.rewind()
                val spec = card.inputs.getOrNull(i)
                val shape = spec?.shape?.map { it.toLong() }?.toLongArray() ?: longArrayOf(buf.remaining().toLong() / 4)
                val t = when (spec?.dtype) {
                    "float32", null -> OnnxTensor.createTensor(env, buf.asFloatBuffer(), shape)
                    "int64" -> OnnxTensor.createTensor(env, buf.asLongBuffer(), shape)
                    "int32" -> OnnxTensor.createTensor(env, buf.asIntBuffer(), shape)
                    else -> OnnxTensor.createTensor(env, buf, shape, OnnxJavaType.UINT8)
                }
                name to t
            }.toMap()
            try {
                session.run(tensors).use { res ->
                    val out = HashMap<String, Any>()
                    outNames.forEachIndexed { i, name ->
                        val v = res.get(name).orElse(null) as? OnnxTensor ?: return@forEachIndexed
                        val src: ByteBuffer = when (v.info.type) {
                            OnnxJavaType.FLOAT -> { val fb = v.floatBuffer; toBytes(name, fb.remaining() * 4) { b -> b.asFloatBuffer().put(fb) } }
                            OnnxJavaType.INT64 -> { val lb = v.longBuffer; toBytes(name, lb.remaining() * 8) { b -> b.asLongBuffer().put(lb) } }
                            OnnxJavaType.INT32 -> { val ib = v.intBuffer; toBytes(name, ib.remaining() * 4) { b -> b.asIntBuffer().put(ib) } }
                            else -> { val bb = v.byteBuffer; toBytes(name, bb.remaining()) { b -> b.put(bb) } }
                        }
                        out[i.toString()] = src
                    }
                    out
                }
            } finally {
                tensors.values.forEach { it.close() }
            }
        }).get()
    }

    private inline fun toBytes(name: String, n: Int, fill: (ByteBuffer) -> Unit): ByteBuffer {
        val b = outBufs[name]?.takeIf { it.capacity() >= n } ?: ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder()).also { outBufs[name] = it }
        b.clear()
        fill(b)
        b.rewind()
        b.limit(n)
        return b
    }

    override fun close() {
        if (closed) return
        closed = true
        exec.execute { try { session.close() } catch (_: Throwable) { } }
        exec.shutdown()
        exec.awaitTermination(2, TimeUnit.SECONDS)
    }

    companion object {
        fun create(card: ModelCard, file: File, knobs: Map<String, Any>): OnnxSession {
            val del = knobs["delegate"] as? String ?: "cpu"
            val threads = (knobs["threads"] as? Number)?.toInt() ?: 4
            val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "loom-ort-$del") }
            try {
                return exec.submit(Callable {
                    val tid = Process.myTid()
                    val o = OrtSession.SessionOptions()
                    o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    val label = when (del) {
                        "qnn" -> {
                            // Hexagon NPU via QNN HTP; nodes it cannot run fall back to CPU.
                            o.addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "sustained_high_performance"))
                            o.setIntraOpNumThreads(2)
                            "NPU (QNN HTP)"
                        }
                        "nnapi" -> { o.addNnapi(); "NPU (NNAPI)" }
                        else -> { o.setIntraOpNumThreads(threads); "CPU ×$threads" }
                    }
                    val s = OnnxBackend.env.createSession(file.absolutePath, o)
                    OnnxSession(exec, s, card, label, tid)
                }).get()
            } catch (t: Throwable) {
                exec.shutdownNow()
                throw (t as? java.util.concurrent.ExecutionException)?.cause ?: t
            }
        }
    }
}
