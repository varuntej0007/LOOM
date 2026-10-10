package com.loom.studio.pipeline

import android.util.Log
import com.loom.studio.chiplets.CostSample
import com.loom.studio.chiplets.Display
import com.loom.studio.chiplets.InferenceChiplet
import com.loom.studio.chiplets.KnobChiplet
import com.loom.studio.chiplets.Postprocess
import com.loom.studio.chiplets.Profile
import com.loom.studio.chiplets.StudioChiplet
import com.loom.studio.core.Clock
import com.loom.studio.core.Level
import com.loom.studio.importer.ModelCard
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Input chiplet for GENERIC / TEXT / AUDIO models: how often a unit runs (the power lever). */
class IntervalSource(onEvent: (String) -> Unit) : KnobChiplet(
    name = "Capture",
    key = "source",
    profiles = listOf(
        Profile(Level.HIGH, "run continuously", mapOf("interval_ms" to 0L)),
        Profile(Level.BALANCED, "run every 100 ms", mapOf("interval_ms" to 100L)),
        Profile(Level.ECO, "run every 500 ms", mapOf("interval_ms" to 500L)),
    ),
    onEvent = onEvent,
) {
    @Volatile var intervalMs = 0L
        private set
    @Volatile var paceMs = 0L

    override fun apply(p: Profile, reason: String) {
        intervalMs = (p.knobs["interval_ms"] as? Long) ?: 0L
    }

    override fun cost() = CostSample(0.0, if (intervalMs > 0) 1000.0 / intervalMs else 0.0)
    override fun quality(): Double = when (level) { Level.HIGH -> 1.0; Level.BALANCED -> 0.8; Level.ECO -> 0.5 }
}

/**
 * GENERIC / TEXT / AUDIO template for this drop: Input (synthetic tensors) → Inference → Output.
 * Real inputs (mic, text) arrive with their backends in later drops; the model is still
 * benchmarked, measured and managed by the Weaver exactly like a vision model.
 */
class GenericPipeline(
    card: ModelCard,
    file: File,
    scope: CoroutineScope,
    onEvent: (String) -> Unit,
    profiles: List<com.loom.studio.chiplets.Profile>? = null,
) : TensorPipeline(card, onEvent) {

    val input = IntervalSource(onEvent)
    override val inference = InferenceChiplet(card, file, InferenceChiplet.backendFor(card), profiles ?: InferenceChiplet.profilesFor(card), scope, onEvent)
    val output = Postprocess(card)
    override val display: Display? = null
    override val source: StudioChiplet get() = input
    override val chiplets: List<StudioChiplet> = listOf(input, inference, output)
    override val unitName = "runs"
    override val unitShort = "runs"

    private val buffers: Map<String, ByteBuffer> = card.inputs.mapIndexed { i, t ->
        val bytes = (t.elements * when (t.dtype) { "float32", "int32" -> 4; "int64" -> 8; "float16", "int16" -> 2; else -> 1 }).toInt()
        val b = ByteBuffer.allocateDirect(bytes.coerceIn(4, 64 shl 20)).order(ByteOrder.nativeOrder())
        val rnd = Random(i)
        when (t.dtype) {
            "float32" -> { val fb = b.asFloatBuffer(); for (k in 0 until fb.capacity()) fb.put(k, rnd.nextFloat() * 2 - 1) }
            "int32" -> { val ib = b.asIntBuffer(); for (k in 0 until ib.capacity()) ib.put(k, rnd.nextInt(0, 1000)) }
            "int64" -> { val lb = b.asLongBuffer(); for (k in 0 until lb.capacity()) lb.put(k, rnd.nextLong(0, 1000)) }
            else -> for (k in 0 until b.capacity()) b.put(k, rnd.nextInt(256).toByte())
        }
        i.toString() to b
    }.toMap()

    /** The fixed synthetic workload (also used by the profiler). */
    fun syntheticInputs(): Map<String, Any> = buffers

    @Volatile private var running = false
    private var thread: Thread? = null
    private var unitNo = 0L

    init {
        wireInference()
    }

    override suspend fun start() {
        inference.start()
        running = true
        thread = Thread({ loop() }, "loom-generic").also { it.start() }
    }

    override fun setPace(unitsPerSec: Double?) {
        input.paceMs = unitsPerSec?.let { (1000.0 / it).toLong() } ?: 0L
    }

    private fun loop() {
        while (running) {
            try {
                val t0 = Clock.nowNs()
                inference.swap()
                if (paused || !inference.isReady) { Thread.sleep(50); continue }
                val out = inference.run(buffers) ?: continue
                val tInf = Clock.nowNs()
                adpf.report(Clock.ms(t0, tInf))
                val r = output.process(unitNo++, out)
                val tOut = Clock.nowNs()
                stageMs["inference"] = Clock.ms(t0, tInf)
                stageMs["postprocess"] = Clock.ms(tInf, tOut)
                recordUnit(Clock.ms(t0, tOut), r)
                val wait = maxOf(input.intervalMs, input.paceMs) - Clock.ms(t0, Clock.nowNs()).toLong()
                if (wait > 0) Thread.sleep(wait)
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                Log.e("LOOM", "generic loop", t)
                onEvent("Inference error: ${t.message}")
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }
    }

    override fun close() {
        running = false
        thread?.interrupt()
        thread?.join(2000)
        super.close()
    }
}
