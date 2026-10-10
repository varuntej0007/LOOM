package com.loom.studio.runtime

/** JNI surface of libloomllama.so (studio/src/main/cpp/llama_jni.cpp). Text is UTF-8 bytes. */
object LlamaNative {
    val loaded: Boolean = try {
        System.loadLibrary("loomllama"); true
    } catch (t: Throwable) {
        android.util.Log.e("LOOM", "libloomllama not available", t); false
    }

    external fun lastError(): String
    external fun loadModel(path: String, useMmap: Boolean): Long
    external fun freeModel(model: Long)
    external fun modelInfo(model: Long): String
    external fun newContext(model: Long, nCtx: Int, nThreads: Int): Long
    external fun freeContext(ctx: Long)
    external fun setThreads(ctx: Long, n: Int)
    external fun nCtx(ctx: Long): Int
    external fun nCtxUsed(ctx: Long): Int
    external fun reset(ctx: Long)
    external fun applyTemplate(model: Long, roles: Array<String>, contents: Array<ByteArray>, addAssistant: Boolean): ByteArray
    external fun prefill(ctx: Long, text: ByteArray): Int
    external fun setSampler(ctx: Long, temp: Float, topK: Int, topP: Float, seed: Int)
    external fun nextToken(ctx: Long): ByteArray?
}
