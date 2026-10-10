package com.loom.studio.chiplets

import android.content.Context
import android.speech.tts.TextToSpeech
import com.loom.studio.core.Level
import java.util.Locale

/** PromptInput: the user's message (fixed stage; reports how long the prompt is). */
class PromptInput : FixedChiplet("Prompt", "prompt", "chat history + new message") {
    @Volatile var lastChars = 0
    override fun cost() = CostSample(0.0, 0.0)
}

/** Tokenizer + prefill (fixed stage; reports prefill time and prompt tokens). */
class Tokenizer : FixedChiplet("Tokenizer", "tokenizer", "chat template + prefill") {
    @Volatile var lastMs = 0.0
    @Volatile var lastTokens = 0
    override fun cost() = CostSample(lastMs, 0.0)
}

/**
 * LLM inference threads (llama.cpp): 6 / 4 / 2 CPU threads, applied LIVE between two tokens
 * (llama_set_n_threads — no reload). For MediaPipe .task models: GPU / CPU (rebuilt between replies).
 */
class LlmThreads(onEvent: (String) -> Unit, private val onApply: (Profile) -> Unit, mediaPipe: Boolean) : KnobChiplet(
    name = "Inference",
    key = "inference",
    profiles = if (mediaPipe) listOf(
        Profile(Level.HIGH, "MediaPipe GPU", mapOf("gpu" to true)),
        Profile(Level.ECO, "MediaPipe CPU", mapOf("gpu" to false)),
    ) else listOf(
        Profile(Level.HIGH, "CPU ×6", mapOf("threads" to 6)),
        Profile(Level.BALANCED, "CPU ×4", mapOf("threads" to 4)),
        Profile(Level.ECO, "CPU ×2", mapOf("threads" to 2)),
    ),
    onEvent = onEvent,
) {
    @Volatile var msPerToken = 0.0
    @Volatile var measuredQuality: Map<String, Double> = emptyMap()
    override fun cost() = CostSample(msPerToken, if (msPerToken > 0) 1000.0 / msPerToken else 0.0)
    override fun quality(): Double? = measuredQuality[current.label]
    override fun apply(p: Profile, reason: String) = onApply.invoke(p)
}

/** Context length: 4096 / 2048 / 1024 tokens. A new context is built between replies. */
class LlmContext(onEvent: (String) -> Unit, private val onApply: (Profile) -> Unit) : KnobChiplet(
    name = "Context",
    key = "context",
    profiles = listOf(
        Profile(Level.HIGH, "context 4096", mapOf("ctx" to 4096)),
        Profile(Level.BALANCED, "context 2048", mapOf("ctx" to 2048)),
        Profile(Level.ECO, "context 1024", mapOf("ctx" to 1024)),
    ),
    onEvent = onEvent,
) {
    override fun cost() = CostSample(0.0, 0.0)
    override fun quality(): Double = when (level) { Level.HIGH -> 1.0; Level.BALANCED -> 0.97; Level.ECO -> 0.9 }
    override fun apply(p: Profile, reason: String) = onApply.invoke(p)
}

/** Sampler: token-rate cap + max reply length (the cheapest LLM heat lever). */
class LlmSampler(onEvent: (String) -> Unit) : KnobChiplet(
    name = "Sampler",
    key = "rate",
    profiles = listOf(
        Profile(Level.HIGH, "uncapped · 512 tokens", mapOf("cap" to 0.0, "max" to 512)),
        Profile(Level.BALANCED, "≤ 12 tok/s · 384 tokens", mapOf("cap" to 12.0, "max" to 384)),
        Profile(Level.ECO, "≤ 6 tok/s · 256 tokens", mapOf("cap" to 6.0, "max" to 256)),
    ),
    onEvent = onEvent,
) {
    val cap: Double? get() = (current.knobs["cap"] as? Double)?.takeIf { it > 0 }
    val maxTokens: Int get() = (current.knobs["max"] as? Int) ?: 512
    override fun cost() = CostSample(0.0, cap ?: 0.0)
    override fun quality(): Double = when (level) { Level.HIGH -> 1.0; Level.BALANCED -> 0.92; Level.ECO -> 0.8 }
    override fun apply(p: Profile, reason: String) {}
}

/** TextStream: how often the reply on screen is refreshed (UI work per token). */
class TextStream(onEvent: (String) -> Unit) : KnobChiplet(
    name = "TextStream",
    key = "ui",
    profiles = listOf(
        Profile(Level.HIGH, "every token", mapOf("hz" to 1000)),
        Profile(Level.BALANCED, "10 updates/s", mapOf("hz" to 10)),
        Profile(Level.ECO, "4 updates/s", mapOf("hz" to 4)),
    ),
    onEvent = onEvent,
) {
    val minIntervalMs: Long get() = 1000L / ((current.knobs["hz"] as? Int) ?: 1000)
    override fun cost() = CostSample(0.0, 0.0)
    override fun quality(): Double = when (level) { Level.HIGH -> 1.0; Level.BALANCED -> 0.97; Level.ECO -> 0.92 }
    override fun apply(p: Profile, reason: String) {}
}

/**
 * Voice (optional TTS). LLM: speak whole replies / first sentence / off.
 * Vision projects (output.speak): speak the top labels every 3 s / 6 s / off.
 */
class Voice(context: Context, onEvent: (String) -> Unit, vision: Boolean = false) : KnobChiplet(
    name = "Voice",
    key = "voice",
    profiles = if (vision) listOf(
        Profile(Level.HIGH, "speak every 3 s", mapOf("every_ms" to 3000L)),
        Profile(Level.BALANCED, "speak every 6 s", mapOf("every_ms" to 6000L)),
        Profile(Level.ECO, "silent", mapOf("every_ms" to 0L)),
    ) else listOf(
        Profile(Level.HIGH, "speak replies", mapOf("mode" to "all")),
        Profile(Level.BALANCED, "first sentence", mapOf("mode" to "first")),
        Profile(Level.ECO, "silent", mapOf("mode" to "off")),
    ),
    onEvent = onEvent,
) {
    @Volatile var enabled = vision
    @Volatile private var ready = false
    private var lastSpoken = 0L
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) try { tts.language = Locale.getDefault() } catch (_: Throwable) { }
        }
    }

    override fun cost() = CostSample(0.0, 0.0)
    override fun quality(): Double = when (level) { Level.HIGH -> 1.0; Level.BALANCED -> 0.85; Level.ECO -> 0.6 }
    override fun apply(p: Profile, reason: String) {
        if (p.level == Level.ECO) tts.stop()
    }

    fun speakReply(text: String) {
        if (!enabled || !ready) return
        val t = when (current.knobs["mode"]) {
            "all" -> text
            "first" -> text.split(Regex("(?<=[.!?])\\s")).firstOrNull() ?: text
            else -> return
        }
        tts.speak(t.take(600), TextToSpeech.QUEUE_FLUSH, null, "loom-reply")
    }

    fun speakLabels(labels: List<String>) {
        val every = (current.knobs["every_ms"] as? Long) ?: 0L
        if (!enabled || !ready || every == 0L || labels.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastSpoken < every) return
        lastSpoken = now
        tts.speak(labels.distinct().take(3).joinToString(", "), TextToSpeech.QUEUE_FLUSH, null, "loom-labels")
    }

    fun shutdown() {
        try { tts.stop(); tts.shutdown() } catch (_: Throwable) { }
    }
}
