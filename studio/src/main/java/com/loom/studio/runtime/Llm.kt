package com.loom.studio.runtime

import android.content.Context
import com.loom.studio.importer.ModelCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicInteger

data class ChatMsg(val role: String, val text: String)

data class GenStats(
    val promptTokens: Int,
    val promptMs: Double,
    val genTokens: Int,
    val genMs: Double,
    val stop: String,
) {
    val tokensPerSec: Double get() = if (genMs > 0) genTokens * 1000.0 / genMs else 0.0
}

/** A loaded chat model. One generation at a time. */
interface LlmSession : AutoCloseable {
    val label: String
    val info: String
    /** Live thread knob (llama.cpp); no-op where the runtime cannot change it. */
    fun setThreads(n: Int) {}
    /**
     * Generate a reply to the last user message of [history]. [onToken] gets each new piece of
     * text and returns false to stop. Blocking; call from a worker thread.
     */
    fun generate(history: List<ChatMsg>, params: GenParams, onToken: (String) -> Boolean): GenStats
    fun resetConversation() {}
}

/**
 * llama.cpp (GGUF). The model is memory-mapped once; contexts can be rebuilt (context-length
 * profile) while the model stays loaded. The KV cache is reused across turns: only the new part
 * of the formatted conversation is evaluated.
 */
class LlamaModel private constructor(val ptr: Long, val info: String) : AutoCloseable {
    private val refs = AtomicInteger(1)
    fun retain(): LlamaModel { refs.incrementAndGet(); return this }
    override fun close() { if (refs.decrementAndGet() == 0) LlamaNative.freeModel(ptr) }

    companion object {
        fun load(file: File): LlamaModel {
            check(LlamaNative.loaded) { "llama.cpp native library missing from this build" }
            val p = LlamaNative.loadModel(file.absolutePath, true)
            if (p == 0L) error(LlamaNative.lastError())
            return LlamaModel(p, LlamaNative.modelInfo(p))
        }
    }
}

class LlamaSession(private val model: LlamaModel, val nCtx: Int, threads: Int) : LlmSession {
    private val ctx: Long = LlamaNative.newContext(model.ptr, nCtx, threads).also { if (it == 0L) error(LlamaNative.lastError()) }
    @Volatile private var threads = threads
    override val label: String get() = "CPU ×$threads · ctx $nCtx"
    override val info: String get() = model.info
    /** Formatted conversation text already in the KV cache. */
    private var evaluated = ""

    override fun setThreads(n: Int) {
        threads = n
        LlamaNative.setThreads(ctx, n)
    }

    override fun resetConversation() {
        LlamaNative.reset(ctx)
        evaluated = ""
    }

    private fun format(history: List<ChatMsg>): String {
        val bytes = LlamaNative.applyTemplate(model.ptr, history.map { it.role }.toTypedArray(),
            history.map { it.text.toByteArray(Charsets.UTF_8) }.toTypedArray(), true)
        return String(bytes, Charsets.UTF_8)
    }

    override fun generate(history: List<ChatMsg>, params: GenParams, onToken: (String) -> Boolean): GenStats {
        var h = history
        var prompt = format(h)
        val t0 = System.nanoTime()
        var delta: String
        var n: Int
        while (true) {
            if (!prompt.startsWith(evaluated) || evaluated.isEmpty()) {
                LlamaNative.reset(ctx); evaluated = ""
            }
            delta = prompt.substring(evaluated.length)
            n = LlamaNative.prefill(ctx, delta.toByteArray(Charsets.UTF_8))
            if (n != -2) break
            // Context full: forget the oldest turns (keep a system message) and start over.
            LlamaNative.reset(ctx); evaluated = ""
            val sys = h.takeWhile { it.role == "system" }
            val rest = h.drop(sys.size)
            if (rest.size <= 1) return GenStats(0, 0.0, 0, 0.0, "prompt longer than the context")
            h = sys + rest.drop(2)
            prompt = format(h)
        }
        if (n < 0) return GenStats(0, 0.0, 0, 0.0, "prefill failed ($n)")
        evaluated = prompt
        val t1 = System.nanoTime()
        LlamaNative.setSampler(ctx, params.temperature, params.topK, 0.95f, params.seed)
        val reply = StringBuilder()
        var count = 0
        var stop = "end of reply"
        val minNsPerToken = params.tokenRateCap?.let { (1e9 / it).toLong() } ?: 0L
        var last = System.nanoTime()
        while (count < params.maxTokens) {
            val bytes = LlamaNative.nextToken(ctx) ?: break
            count++
            val piece = String(bytes, Charsets.UTF_8)
            reply.append(piece)
            if (!onToken(piece)) { stop = "stopped"; break }
            if (minNsPerToken > 0) {     // token-rate cap: the Weaver's cheapest LLM lever
                val wait = minNsPerToken - (System.nanoTime() - last)
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
            last = System.nanoTime()
        }
        if (count >= params.maxTokens) stop = "max reply length"
        evaluated += reply.toString()
        val t2 = System.nanoTime()
        return GenStats(n, (t1 - t0) / 1e6, count, (t2 - t1) / 1e6, stop)
    }

    override fun close() {
        LlamaNative.freeContext(ctx)
        model.close()
    }
}

/** MediaPipe LLM Inference (.task, e.g. Gemma). Backend knob: GPU / CPU (rebuild). */
class MediaPipeLlmSession(context: Context, file: File, useGpu: Boolean, maxTokens: Int) : LlmSession {
    private val llm: com.google.mediapipe.tasks.genai.llminference.LlmInference
    override val label = if (useGpu) "MediaPipe GPU" else "MediaPipe CPU"
    override val info = "MediaPipe LLM Inference · ${file.name}"

    init {
        val opts = com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(maxTokens)
            .setPreferredBackend(
                if (useGpu) com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend.GPU
                else com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend.CPU
            )
            .build()
        llm = com.google.mediapipe.tasks.genai.llminference.LlmInference.createFromOptions(context, opts)
    }

    /** Gemma-style turns (MediaPipe .task LLMs are mostly Gemma). */
    private fun format(history: List<ChatMsg>): String = buildString {
        history.forEach { m ->
            val role = if (m.role == "assistant") "model" else "user"
            append("<start_of_turn>").append(role).append('\n').append(m.text).append("<end_of_turn>\n")
        }
        append("<start_of_turn>model\n")
    }

    override fun generate(history: List<ChatMsg>, params: GenParams, onToken: (String) -> Boolean): GenStats {
        val prompt = format(history.takeLast(6))
        val t0 = System.nanoTime()
        var first = 0L
        var count = 0
        var stopped = false
        val minNs = params.tokenRateCap?.let { (1e9 / it).toLong() } ?: 0L
        var last = System.nanoTime()
        val done = java.util.concurrent.CountDownLatch(1)
        llm.generateResponseAsync(prompt) { partial, finished ->
            if (!stopped && !partial.isNullOrEmpty()) {
                if (first == 0L) first = System.nanoTime()
                count++
                if (count > params.maxTokens || !onToken(partial)) stopped = true
                if (minNs > 0) {
                    val wait = minNs - (System.nanoTime() - last)
                    if (wait > 0) Thread.sleep(wait / 1_000_000)
                }
                last = System.nanoTime()
            }
            if (finished) done.countDown()
        }
        done.await()
        val t2 = System.nanoTime()
        val f = if (first == 0L) t2 else first
        return GenStats(0, (f - t0) / 1e6, count, (t2 - f) / 1e6, if (stopped) "stopped" else "end of reply")
    }

    override fun close() = llm.close()
}

object LlmLoader {
    /** knobs: "threads", "ctx" (llama.cpp); "gpu" (MediaPipe). */
    suspend fun load(context: Context, card: ModelCard, file: File, knobs: Map<String, Any>, shared: LlamaModel?): Pair<LlmSession, LlamaModel?> =
        withContext(Dispatchers.IO) {
            if (card.format == "gguf") {
                val m = shared?.retain() ?: LlamaModel.load(file)
                try {
                    LlamaSession(m, (knobs["ctx"] as? Int) ?: 2048, (knobs["threads"] as? Int) ?: 4) to (shared ?: m.retain())
                } catch (t: Throwable) {
                    m.close(); throw t
                }
            } else {
                MediaPipeLlmSession(context, file, knobs["gpu"] as? Boolean ?: false, (knobs["ctx"] as? Int) ?: 1024) to null
            }
        }
}

@Suppress("unused")
private fun decodeUtf8(b: ByteBuffer): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPLACE).decode(b).let(CharBuffer::toString)
