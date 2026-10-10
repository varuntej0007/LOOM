package com.loom.studio.runtime

import com.loom.studio.importer.ModelCard
import java.io.File

/** Generation settings for LLM sessions (llama.cpp / MediaPipe, drops 2–3). */
data class GenParams(
    val maxTokens: Int = 256,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val tokenRateCap: Double? = null,
)

/** A runtime that can load an imported model with a set of knobs (one chiplet profile). */
interface Backend {
    suspend fun load(card: ModelCard, file: File, knobs: Map<String, Any>): Session
}

/**
 * One loaded model instance. Vision / audio / generic use [run]; LLMs use [generate].
 * Inputs and outputs are keyed by tensor index as a string ("0", "1", …); values are
 * direct ByteBuffers in native order.
 */
interface Session : AutoCloseable {
    val label: String
    /** Kernel thread id that runs inference (for ADPF CPU hints), or -1. */
    val tid: Int
    fun run(inputs: Map<String, Any>): Map<String, Any>
    fun generate(prompt: String, params: GenParams, onToken: (String) -> Unit) {
        throw UnsupportedOperationException("$label does not generate text")
    }
}
