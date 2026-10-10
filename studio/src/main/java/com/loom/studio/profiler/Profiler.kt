package com.loom.studio.profiler

import android.graphics.Bitmap
import com.loom.studio.chiplets.UnitResult
import com.loom.studio.core.Level
import com.loom.studio.pipeline.GenericPipeline
import com.loom.studio.chiplets.InferenceChiplet
import com.loom.studio.chiplets.Profile
import com.loom.studio.pipeline.TensorPipeline
import com.loom.studio.pipeline.VisionPipeline
import com.loom.studio.telemetry.DeviceSignals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

data class ProfilerState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val step: String = "",
    val log: List<String> = emptyList(),
    val result: ProfileTable? = null,
    val error: String? = null,
)

/**
 * Measures every chiplet profile ON THIS PHONE (brief §7), ~1–2 minutes:
 *  1. capture a fixed workload (20 camera frames, or synthetic tensors for generic models)
 *  2. for each Inference profile: build it, warm up, run the workload for 8 s →
 *     p50/p90 latency, throughput, battery current (mA), heat slope, quality vs HIGH
 *  3. for each camera profile: 6 s at that sensor rate → real fps and battery current
 *  4. save profiles.json next to the model
 * Battery current is only honest on battery power; charging is detected and flagged.
 */
class Profiler(
    private val pipeline: TensorPipeline,
    private val modelFile: File,
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
        var charging = false
        try {
            val rows = mutableListOf<MeasuredProfile>()
            pipeline.paused = true

            // ---- 1. workload ----
            val vision = pipeline as? VisionPipeline
            val frames = mutableListOf<Bitmap>()
            if (vision != null) {
                step(0.02f, "Point the camera at a typical scene — capturing 20 frames")
                var lastGrab = 0L
                vision.frameTap = { pre ->
                    val now = System.currentTimeMillis()
                    if (frames.size < 20 && now - lastGrab > 250) {
                        pre.snapshot()?.let { synchronized(frames) { frames += it } }
                        lastGrab = now
                    }
                }
                val t0 = System.currentTimeMillis()
                while (synchronized(frames) { frames.size } < 20 && System.currentTimeMillis() - t0 < 30_000) delay(100)
                vision.frameTap = null
                if (frames.isEmpty()) error("the camera delivered no frames — keep the Profile screen open")
                step(0.12f, "Captured ${frames.size} frames")
            }
            val sampleCount = if (vision != null) frames.size else 8
            fun inputFor(i: Int): Map<String, Any> = if (vision != null) {
                mapOf("0" to vision.preprocess.fill(frames[i]))
            } else {
                (pipeline as GenericPipeline).let { g -> genericInputs(g) }
            }

            // ---- idle baseline ----
            step(0.14f, "Idle baseline (3 s)")
            val idle = sampleCurrent(3_000).first
            charging = signals()?.charging == true

            // ---- 2. inference profiles ----
            val base = pipeline.inference.profiles
            val extras = if (pipeline.card.runFormat == "tflite" && !base.any { it.label.startsWith("NPU") })
                InferenceChiplet.liteRtExtraProfiles() else emptyList()
            val infProfiles: List<Profile> = base + extras.filter { e -> base.none { it.label == e.label } }
            val backend = InferenceChiplet.backendFor(pipeline.card)
            var reference: List<UnitResult>? = null
            val share = 0.6f / infProfiles.size
            for ((pi, p) in infProfiles.withIndex()) {
                val base = 0.16f + pi * share
                step(base, "Inference ${p.label}: building")
                val session = try {
                    backend.load(pipeline.card, pipeline.inference.fileFor(p), p.knobs)
                } catch (t: Throwable) {
                    rows += MeasuredProfile("inference", p.label, p.level.name, null, null, null, null, null, null, "failed: ${t.message}")
                    step(base + share, "Inference ${p.label}: failed (${t.message})")
                    continue
                }
                try {
                    repeat(3) { session.run(inputFor(it % sampleCount)) }  // warm-up
                    val outs = mutableListOf<UnitResult>()
                    val lat = mutableListOf<Double>()
                    val cur = mutableListOf<Long>()
                    val h0 = heatNow()
                    val start = System.nanoTime()
                    var lastCur = 0L
                    var i = 0
                    while ((System.nanoTime() - start) < 8_000_000_000L) {
                        val idx = i % sampleCount
                        val inp = inputFor(idx)
                        val t = System.nanoTime()
                        val out = session.run(inp)
                        lat += (System.nanoTime() - t) / 1e6
                        if (i < sampleCount) outs += postprocess(out, i.toLong())
                        i++
                        val now = System.nanoTime()
                        if (now - lastCur > 500_000_000L) {
                            lastCur = now
                            signals()?.batteryCurrentRaw?.takeIf { it != Long.MIN_VALUE }?.let { cur += it }
                            step(base + share * ((now - start) / 8e9f).coerceIn(0f, 1f), "Inference ${p.label}: measuring (${i} runs)")
                            delay(1)
                        }
                    }
                    val secs = (System.nanoTime() - start) / 1e9
                    val h1 = heatNow()
                    val sorted = lat.sorted()
                    val q = if (reference == null) { reference = outs; 1.0 } else QualityProxy.agreement(reference!!, outs)
                    val ma = cur.takeIf { it.isNotEmpty() }?.map { abs(it) / 1000.0 }?.average()
                    rows += MeasuredProfile(
                        "inference", p.label, p.level.name,
                        sorted[(sorted.size - 1) / 2], sorted[((sorted.size - 1) * 0.9).toInt()],
                        i / secs, ma,
                        if (!h0.isNaN() && !h1.isNaN()) (h1 - h0) / (secs / 60.0) else null,
                        q.takeIf { !it.isNaN() },
                    )
                    step(base + share, "Inference ${p.label}: p50 ${"%.1f".format(sorted[(sorted.size - 1) / 2])} ms, ${ma?.let { "%.0f mA".format(it) } ?: "— mA"}")
                } finally {
                    session.close()
                }
            }

            // ---- 3. source profiles ----
            val cam = pipeline.camera()
            if (cam != null) {
                val levels = cam.profiles
                val cshare = 0.2f / levels.size
                for ((ci, p) in levels.withIndex()) {
                    val base = 0.76f + ci * cshare
                    step(base, "Camera ${p.label}: settling")
                    cam.requestLevel(p.level, "profiler")
                    delay(2_000)
                    val f0 = cam.sensorFrames.get()
                    val t0 = System.nanoTime()
                    val (ma, _) = sampleCurrent(5_000)
                    val fps = (cam.sensorFrames.get() - f0) / ((System.nanoTime() - t0) / 1e9)
                    rows += MeasuredProfile("source", p.label, p.level.name, null, null, fps, ma, null,
                        (fps / cam.fullRateFps).coerceAtMost(1.0), "camera only (no inference)")
                    step(base + cshare, "Camera ${p.label}: ${"%.1f".format(fps)} fps, ${ma?.let { "%.0f mA".format(it) } ?: "— mA"}")
                }
                cam.requestLevel(Level.HIGH, "profiler done")
            } else {
                pipeline.source.profiles.forEach { p ->
                    rows += MeasuredProfile("source", p.label, p.level.name, null, null, null, null, null, p.knobs["interval_ms"]?.let { iv ->
                        if ((iv as Long) == 0L) 1.0 else null
                    }, "not measured: run-rate knob")
                }
            }
            pipeline.display?.profiles?.forEach { p ->
                rows += MeasuredProfile("display", p.label, p.level.name, null, null, null, null, null, null, "not measured: screen brightness lever")
            }

            if (!charging) {
                pipeline.inference.reorder(infProfiles.mapNotNull { p -> rows.firstOrNull { it.chiplet == "inference" && it.label == p.label }?.let { p to it } })
            }
            val table = ProfileTable(pipeline.card.id, pipeline.card.displayName, deviceName, System.currentTimeMillis(), charging, idle, rows,
                pipeline.inference.profiles.map { it.label })
            table.save(outFile)
            pipeline.inference.measuredQuality = rows.filter { it.chiplet == "inference" && it.quality != null }.associate { it.label to it.quality!! }
            _state.value = _state.value.copy(running = false, progress = 1f, step = if (charging)
                "Done — but the phone was charging: mA values are not workload power. Unplug and re-profile." else "Done", result = table)
            return table
        } catch (t: Throwable) {
            _state.value = _state.value.copy(running = false, error = t.message ?: t.javaClass.simpleName, step = "Failed: ${t.message}")
            return null
        } finally {
            (pipeline as? VisionPipeline)?.frameTap = null
            pipeline.paused = false
        }
    }

    private fun postprocess(out: Map<String, Any>, unit: Long): UnitResult = when (val p = pipeline) {
        is VisionPipeline -> p.postprocess.process(unit, out)
        is GenericPipeline -> p.output.process(unit, out)
        else -> UnitResult(unit)
    }

    private fun genericInputs(g: GenericPipeline): Map<String, Any> = g.syntheticInputs()

    private fun heatNow(): Float = signals()?.thermalHeadroom ?: Float.NaN

    /** Average |battery current| in mA over [ms], plus the number of readings. */
    private suspend fun sampleCurrent(ms: Long): Pair<Double?, Int> {
        val vals = mutableListOf<Long>()
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            signals()?.batteryCurrentRaw?.takeIf { it != Long.MIN_VALUE }?.let { vals += it }
            delay(500)
        }
        return (vals.takeIf { it.isNotEmpty() }?.map { abs(it) / 1000.0 }?.average()) to vals.size
    }

    @Suppress("unused")
    private fun ByteBuffer.copy(): ByteBuffer = ByteBuffer.allocateDirect(capacity()).also { it.put(this.duplicate().apply { rewind() }); it.rewind() }
}
