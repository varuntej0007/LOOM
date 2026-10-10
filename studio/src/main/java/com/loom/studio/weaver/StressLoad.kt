package com.loom.studio.weaver

import android.util.Log
import com.loom.studio.importer.ModelCard
import com.loom.studio.runtime.LiteRtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class StressMode { OFF, GPU, CPU }

/**
 * Controlled, REAL extra load (same idea as app/'s StressLoad), so a genuine Weaver reaction
 * can be demonstrated from the dashboard. It never fakes a metric; it only makes the phone busier.
 *  - GPU: a second copy of the imported model on the GPU delegate, back to back
 *  - CPU: busy threads on half the cores
 */
class StressLoad(private val onEvent: (String) -> Unit) {
    @Volatile var mode = StressMode.OFF
        private set
    @Volatile private var running = false
    private val threads = mutableListOf<Thread>()

    @Synchronized
    fun set(m: StressMode, card: ModelCard?, file: File?) {
        if (m == mode) return
        stop()
        mode = m
        when (m) {
            StressMode.OFF -> Unit
            StressMode.GPU -> if (card != null && file != null && card.format == "tflite") startGpu(card, file) else startCpu()
            StressMode.CPU -> startCpu()
        }
        onEvent("Stress: ${m.name}")
    }

    private fun startGpu(card: ModelCard, file: File) {
        running = true
        threads += Thread({
            val s = try {
                LiteRtSession.create(file, mapOf("delegate" to "gpu"))
            } catch (t: Throwable) {
                onEvent("Stress GPU unavailable (${t.message}) → CPU stress instead")
                startCpuLocked()
                return@Thread
            }
            try {
                val inputs = card.inputs.mapIndexed { i, t ->
                    i.toString() to ByteBuffer.allocateDirect((t.elements * 4).toInt().coerceIn(4, 64 shl 20)).order(ByteOrder.nativeOrder())
                }.toMap()
                while (running) s.run(inputs)
            } catch (t: Throwable) {
                if (running) Log.e("LOOM", "stress gpu", t)
            } finally {
                s.close()
            }
        }, "loom-stress-gpu").also { it.start() }
    }

    private fun startCpu() {
        running = true
        startCpuLocked()
    }

    private fun startCpuLocked() {
        val n = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        repeat(n) { i ->
            val t = Thread({
                var x = 1.0001
                while (running) {
                    for (k in 0 until 200_000) x = x * 1.0000001 + 0.0000001
                    if (x > 1e9) x = 1.0001
                }
            }, "loom-stress-cpu-$i")
            synchronized(threads) { threads += t }
            t.start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        val ts = synchronized(threads) { threads.toList().also { threads.clear() } }
        ts.forEach { it.interrupt() }
        ts.forEach { it.join(1500) }
        mode = StressMode.OFF
    }
}
