package com.loom.studio.pipeline

import android.content.Context
import android.os.Process
import android.util.Log
import com.loom.studio.chiplets.Display
import com.loom.studio.chiplets.LlmContext
import com.loom.studio.chiplets.LlmSampler
import com.loom.studio.chiplets.LlmThreads
import com.loom.studio.chiplets.Profile
import com.loom.studio.chiplets.PromptInput
import com.loom.studio.chiplets.StudioChiplet
import com.loom.studio.chiplets.TextStream
import com.loom.studio.chiplets.Tokenizer
import com.loom.studio.chiplets.Voice
import com.loom.studio.core.Clock
import com.loom.studio.core.Level
import com.loom.studio.core.Transitions
import com.loom.studio.importer.ModelCard
import com.loom.studio.runtime.ChatMsg
import com.loom.studio.runtime.GenParams
import com.loom.studio.runtime.GenStats
import com.loom.studio.runtime.LlamaModel
import com.loom.studio.runtime.LlmLoader
import com.loom.studio.runtime.LlmSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * LLM (chat) template (brief §6): PromptInput → Tokenizer → Inference → Sampler → TextStream
 * (+ Voice). Goal metric = tokens/s while a reply is being generated.
 * Cut order default: Voice → TextStream → Sampler (rate cap) → Inference (threads) → Context.
 */
class LlmPipeline(
    card: ModelCard,
    private val file: File,
    private val appContext: Context,
    private val scope: CoroutineScope,
    onEvent: (String) -> Unit,
) : Pipeline(card, onEvent) {

    private val mediaPipe = card.format == "task"
    val prompt = PromptInput()
    val tokenizer = Tokenizer()
    val threads = LlmThreads(onEvent, ::onThreads, mediaPipe)
    val contextLen: LlmContext? = if (mediaPipe) null else LlmContext(onEvent, ::onContext)
    val sampler = LlmSampler(onEvent)
    val stream = TextStream(onEvent)
    val voice = Voice(appContext, onEvent)

    override val chiplets: List<StudioChiplet> = listOfNotNull(prompt, tokenizer, threads, contextLen, sampler, stream, voice)
    override val source: StudioChiplet get() = prompt
    override val display: Display? = null
    override val main: StudioChiplet get() = threads
    override val metricName = "tokens_per_s"
    override val unitName = "tokens"
    override val unitShort = "tok"

    @Volatile private var session: LlmSession? = null
    private var shared: LlamaModel? = null
    private val rebuild = AtomicReference<Pending?>(null)
    private class Pending(val knobs: Map<String, Any>, val id: Long, val from: String, val to: String, val reason: String)

    override val ready: Boolean get() = session != null
    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating
    override val sourceActive: Boolean get() = _generating.value

    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val messages: StateFlow<List<ChatMsg>> = _messages
    private val _streaming = MutableStateFlow("")
    val streaming: StateFlow<String> = _streaming
    private val _stats = MutableStateFlow<GenStats?>(null)
    val stats: StateFlow<GenStats?> = _stats
    private val _status = MutableStateFlow("loading model…")
    val status: StateFlow<String> = _status
    val sessionLabel: String get() = session?.label ?: "loading"
    val sessionInfo: String get() = session?.info ?: ""

    @Volatile private var stopRequested = false
    private var genThread: Thread? = null

    private fun knobs(): Map<String, Any> = HashMap<String, Any>().apply {
        putAll(threads.current.knobs)
        contextLen?.let { putAll(it.current.knobs) }
    }

    override suspend fun start() {
        contextLen?.requestLevel(Level.BALANCED, "start: 2048-token context")
        try {
            val t0 = Clock.nowNs()
            val (s, m) = LlmLoader.load(appContext, card, file, knobs(), null)
            session = s
            shared = m
            _status.value = "ready · ${s.info}"
            onEvent("LLM ready: ${s.label} — ${s.info} (loaded in ${"%.1f".format(Clock.ms(t0, Clock.nowNs()) / 1000)} s)")
        } catch (t: Throwable) {
            _status.value = "could not load: ${t.message}"
            onEvent("LLM load failed: ${t.message}")
        }
    }

    // ---- knobs ----
    private fun onThreads(p: Profile) {
        if (mediaPipe) schedule(p.knobs, p.label, "backend") else session?.setThreads((p.knobs["threads"] as? Int) ?: 4)
    }

    private fun onContext(p: Profile) = schedule(p.knobs, p.label, "context")

    /** Changes that need a new session are built at the next reply boundary (hot-swap between replies). */
    private fun schedule(k: Map<String, Any>, label: String, what: String) {
        if (session == null) return
        val id = Transitions.nextId()
        val from = session?.label ?: "—"
        Transitions.emit(id, if (what == "context") "Context" else "Inference", "requested", from, label, "$what change at the next reply")
        rebuild.set(Pending(knobs() + k, id, from, label, what))
    }

    private fun applyRebuild() {
        val p = rebuild.getAndSet(null) ?: return
        val t0 = Clock.nowNs()
        try {
            val (s, _) = runBlocking { LlmLoader.load(appContext, card, file, p.knobs, shared) }
            val buildMs = Clock.ms(t0, Clock.nowNs())
            val chip = if (p.reason == "context") "Context" else "Inference"
            Transitions.emit(p.id, chip, "built", p.from, s.label, p.reason, buildMs)
            val t1 = Clock.nowNs()
            val old = session
            session = s
            val gap = Clock.ms(t1, Clock.nowNs())
            Transitions.emit(p.id, chip, "swapped", p.from, s.label, p.reason, swapGapMs = gap)
            lastSwap = "${s.label} swap ${"%.3f".format(java.util.Locale.US, gap)} ms"
            old?.close()
        } catch (t: Throwable) {
            Transitions.emit(p.id, "Context", "failed", p.from, p.to, "build failed: ${t.message}")
            onEvent("LLM rebuild failed (${t.message}); keeping ${p.from}")
        }
    }

    // ---- chat ----
    fun send(text: String) {
        val msg = text.trim()
        if (msg.isEmpty() || _generating.value || session == null) return
        _messages.value = _messages.value + ChatMsg("user", msg)
        prompt.lastChars = msg.length
        stopRequested = false
        _generating.value = true
        _streaming.value = ""
        genThread = Thread({ generate() }, "loom-llm-gen").also { it.start() }
    }

    fun stop() { stopRequested = true }

    fun clear() {
        if (_generating.value) return
        _messages.value = emptyList()
        _streaming.value = ""
        session?.resetConversation()
    }

    private fun generate() {
        try {
            applyRebuild()
            val s = session ?: return
            adpf.attach(Process.myTid(), targetMs = 1000.0 / 8.0)
            val params = GenParams(maxTokens = sampler.maxTokens, temperature = 0.7f, topK = 40, tokenRateCap = sampler.cap)
            val reply = StringBuilder()
            var lastTok = Clock.nowNs()
            var lastUi = 0L
            var firstToken = true
            _status.value = "thinking…"
            val stats = s.generate(_messages.value, params) { piece ->
                val now = Clock.nowNs()
                if (firstToken) {
                    firstToken = false
                } else {
                    val ms = Clock.ms(lastTok, now)
                    threads.msPerToken = ms
                    adpf.report(ms)
                    record(ms, null)
                }
                lastTok = now
                reply.append(piece)
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastUi >= stream.minIntervalMs) {
                    lastUi = nowMs
                    _streaming.value = reply.toString()
                    _status.value = "generating…"
                }
                !stopRequested
            }
            tokenizer.lastMs = stats.promptMs
            tokenizer.lastTokens = stats.promptTokens
            stageMs["tokenizer"] = stats.promptMs
            stageMs["inference"] = threads.msPerToken
            val text = reply.toString().trim()
            _messages.value = _messages.value + ChatMsg("assistant", text.ifEmpty { "(no reply: ${stats.stop})" })
            _streaming.value = ""
            _stats.value = stats
            _status.value = String.format(java.util.Locale.US, "%d tokens · %.1f tok/s · prefill %.0f ms · %s",
                stats.genTokens, stats.tokensPerSec, stats.promptMs, stats.stop)
            voice.speakReply(text)
        } catch (t: Throwable) {
            Log.e("LOOM", "generation", t)
            _status.value = "error: ${t.message}"
            onEvent("LLM error: ${t.message}")
        } finally {
            _generating.value = false
        }
    }

    /** For the profiler: one fixed-prompt generation with the current session (greedy). */
    fun benchmark(promptText: String, maxTokens: Int, onToken: (String) -> Unit): GenStats? {
        val s = session ?: return null
        s.resetConversation()
        val st = s.generate(listOf(ChatMsg("user", promptText)), GenParams(maxTokens = maxTokens, temperature = 0f)) { onToken(it); true }
        s.resetConversation()
        return st
    }

    /** For the profiler: set threads directly (llama.cpp). */
    fun setThreadsNow(n: Int) = session?.setThreads(n)

    override fun close() {
        stopRequested = true
        genThread?.join(3000)
        voice.shutdown()
        session?.close()
        session = null
        shared?.close()
        shared = null
        super.close()
    }
}
