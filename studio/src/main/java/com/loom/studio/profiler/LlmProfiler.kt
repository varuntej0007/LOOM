package com.loom.studio.profiler

import com.loom.studio.core.Level
import com.loom.studio.pipeline.LlmPipeline
import com.loom.studio.telemetry.DeviceSignals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.abs

/**
 * LLM profiler (brief §7): the same fixed prompt, greedy decoding, for each thread profile
 * (6 / 4 / 2). Measures tokens/s, prefill time, battery current, heat slope; quality = token
 * match rate of each profile's reply vs HIGH's (greedy, so normally 1.0 — threads change speed,
 * not the answer; a lower number would expose a numerical difference).
 */
class LlmProfiler(
    private val pipeline: LlmPipeline,
    private val signals: () -> DeviceSignals?,
    private val deviceName: String,
) {
    private val _state = MutableStateFlow(ProfilerState())
    val state: StateFlow<ProfilerState> = _state

    private fun step(p: Float, msg: String) {
        val s = _state.value
        _state.value = s.copy(running = true, progress = p, step = msg, log = (s.log + msg).takeLast(30))
    }

    suspend fun run(outFile: File): ProfileTable? {
        _state.value = ProfilerState(running = true, step = "Starting…")
        try {
            while (!pipeline.ready) delay(200)
            step(0.03f, "Idle baseline (3 s)")
            val idle = sampleCurrent(3000)
            val charging = signals()?.charging == true
            val rows = mutableListOf<MeasuredProfile>()
            val profiles = pipeline.threads.profiles
            val original = pipeline.threads.current
            var refWords: List<String>? = null
            for ((i, p) in profiles.withIndex()) {
                val base = 0.08f + i * (0.85f / profiles.size)
                step(base, "Inference ${p.label}: generating the fixed prompt")
                pipeline.threads.requestLevel(p.level, "profiler")
                delay(300)
                val cur = mutableListOf<Long>()
                val h0 = signals()?.thermalHeadroom ?: Float.NaN
                var lastCur = 0L
                val text = StringBuilder()
                val stats = pipeline.benchmark(PROMPT, 96) { piece ->
                    text.append(piece)
                    val now = System.nanoTime()
                    if (now - lastCur > 500_000_000L) {
                        lastCur = now
                        signals()?.batteryCurrentRaw?.takeIf { it != Long.MIN_VALUE }?.let { cur += it }
                    }
                } ?: error("model not loaded")
                val h1 = signals()?.thermalHeadroom ?: Float.NaN
                val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val q = refWords?.let { r -> matchRate(r, words) } ?: run { refWords = words; 1.0 }
                val secs = (stats.genMs + stats.promptMs) / 1000.0
                val ma = cur.takeIf { it.isNotEmpty() }?.map { abs(it) / 1000.0 }?.average()
                rows += MeasuredProfile("inference", p.label, p.level.name,
                    if (stats.genTokens > 0) stats.genMs / stats.genTokens else null, null, stats.tokensPerSec, ma,
                    if (!h0.isNaN() && !h1.isNaN() && secs > 0) (h1 - h0) / (secs / 60.0) else null, q,
                    "prefill ${"%.0f".format(stats.promptMs)} ms, ${stats.genTokens} tokens")
                step(base + 0.85f / profiles.size, "Inference ${p.label}: ${"%.1f".format(stats.tokensPerSec)} tok/s, ${ma?.let { "%.0f mA".format(it) } ?: "— mA"}")
            }
            pipeline.threads.requestLevel(original.level, "profiler done")
            pipeline.contextLen?.profiles?.forEach { p ->
                rows += MeasuredProfile("context", p.label, p.level.name, null, null, null, null, null, null, "not measured: memory size, rebuilt between replies")
            }
            pipeline.sampler.profiles.forEach { p ->
                rows += MeasuredProfile("rate", p.label, p.level.name, null, null, (p.knobs["cap"] as? Double)?.takeIf { it > 0 }, null, null, null, "rate cap (estimated power)")
            }
            val table = ProfileTable(pipeline.card.id, pipeline.card.displayName, deviceName, System.currentTimeMillis(), charging, idle, rows,
                profiles.map { it.label })
            table.save(outFile)
            pipeline.threads.measuredQuality = rows.filter { it.chiplet == "inference" && it.quality != null }.associate { it.label to it.quality!! }
            _state.value = _state.value.copy(running = false, progress = 1f, result = table, step = if (charging)
                "Done — but the phone was charging: mA values are not workload power. Unplug and re-profile." else "Done")
            return table
        } catch (t: Throwable) {
            _state.value = _state.value.copy(running = false, error = t.message ?: t.javaClass.simpleName, step = "Failed: ${t.message}")
            return null
        }
    }

    private suspend fun sampleCurrent(ms: Long): Double? {
        val v = mutableListOf<Long>()
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            signals()?.batteryCurrentRaw?.takeIf { it != Long.MIN_VALUE }?.let { v += it }
            delay(500)
        }
        return v.takeIf { it.isNotEmpty() }?.map { abs(it) / 1000.0 }?.average()
    }

    companion object {
        const val PROMPT = "In three short sentences, explain why a phone gets warm when it runs an AI model."

        /** Position-wise word agreement (HIGH = reference). */
        fun matchRate(ref: List<String>, test: List<String>): Double {
            val n = maxOf(ref.size, test.size)
            if (n == 0) return 1.0
            var same = 0
            for (i in 0 until minOf(ref.size, test.size)) if (ref[i] == test[i]) same++
            return same.toDouble() / n
        }
    }
}

@Suppress("unused") private val keep = Level.HIGH
