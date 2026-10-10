package com.loom.studio.runtime

import android.os.Process
import android.util.Log
import com.loom.studio.importer.ModelCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LiteRT (TensorFlow Lite Interpreter API) backend.
 *
 * Knobs: "delegate" = "gpu" | "cpu", "threads" = Int (CPU), "fp16" = Boolean (GPU precision loss allowed).
 *
 * Every session owns one thread: the GPU delegate must be created and used on the same thread,
 * and a dedicated thread lets a NEW session be built in the background while the old one keeps
 * serving frames (the hot-swap in InferenceChiplet).
 */
class LiteRtBackend : Backend {

    override suspend fun load(card: ModelCard, file: File, knobs: Map<String, Any>): Session =
        withContext(Dispatchers.IO) { LiteRtSession.create(file, knobs) }

    companion object {
        /** Whether this phone's GPU is on LiteRT's GPU-delegate allow list. Cached. */
        val gpuSupported: Boolean by lazy {
            try {
                val cl = CompatibilityList()
                try { cl.isDelegateSupportedOnThisDevice } finally { cl.close() }
            } catch (t: Throwable) {
                Log.w("LOOM", "GPU compatibility check failed", t)
                false
            }
        }
    }
}

class LiteRtSession private constructor(
    private val exec: ExecutorService,
    private val interpreter: Interpreter,
    private val delegate: Delegate?,
    override val label: String,
    override val tid: Int,
) : Session {

    private val outputs: Map<Int, ByteBuffer> = (0 until interpreter.outputTensorCount).associateWith { i ->
        ByteBuffer.allocateDirect(interpreter.getOutputTensor(i).numBytes().coerceAtLeast(4)).order(ByteOrder.nativeOrder())
    }
    @Volatile private var closed = false

    override fun run(inputs: Map<String, Any>): Map<String, Any> {
        check(!closed) { "session closed" }
        return exec.submit(Callable {
            val ins = Array<Any>(interpreter.inputTensorCount) { i ->
                (inputs[i.toString()] as? ByteBuffer)?.also { it.rewind() } ?: error("missing input $i")
            }
            outputs.values.forEach { it.rewind() }
            interpreter.runForMultipleInputsOutputs(ins, outputs)
            outputs.entries.associate { (k, v) -> k.toString() to v.also { it.rewind() } }
        }).get()
    }

    override fun close() {
        if (closed) return
        closed = true
        exec.execute {
            try { interpreter.close() } catch (_: Throwable) { }
            try { (delegate as? AutoCloseable)?.close() } catch (_: Throwable) { }
        }
        exec.shutdown()
        exec.awaitTermination(2, TimeUnit.SECONDS)
    }

    companion object {
        fun create(file: File, knobs: Map<String, Any>): LiteRtSession {
            val useGpu = knobs["delegate"] == "gpu"
            val threads = (knobs["threads"] as? Number)?.toInt() ?: 4
            val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "loom-infer-" + (if (useGpu) "gpu" else "cpu$threads")) }
            try {
                return exec.submit(Callable {
                    val tid = Process.myTid()
                    val opts = Interpreter.Options()
                    var delegate: Delegate? = null
                    val label: String
                    if (useGpu) {
                        // Newer GPUs are often missing from LiteRT's allow list although the
                        // delegate works; try it anyway and let a failure mark the profile failed.
                        val gpuOpts = if (LiteRtBackend.gpuSupported) {
                            val cl = CompatibilityList()
                            try { cl.bestOptionsForThisDevice } finally { cl.close() }
                        } else {
                            GpuDelegate.Options()
                        }
                        gpuOpts.setPrecisionLossAllowed(knobs["fp16"] as? Boolean ?: true)
                        delegate = GpuDelegate(gpuOpts)
                        opts.addDelegate(delegate)
                        label = "GPU"
                    } else {
                        opts.setNumThreads(threads)
                        opts.setUseXNNPACK(true)
                        label = "CPU ×$threads"
                    }
                    val ip = try {
                        Interpreter(file, opts)
                    } catch (t: Throwable) {
                        try { (delegate as? AutoCloseable)?.close() } catch (_: Throwable) { }
                        throw t
                    }
                    LiteRtSession(exec, ip, delegate, label, tid)
                }).get()
            } catch (t: Throwable) {
                exec.shutdownNow()
                throw (t as? java.util.concurrent.ExecutionException)?.cause ?: t
            }
        }
    }
}
