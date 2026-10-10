package com.loom.studio.pipeline

import com.loom.studio.chiplets.CameraSource
import com.loom.studio.chiplets.Display
import com.loom.studio.chiplets.InferenceChiplet
import com.loom.studio.chiplets.StudioChiplet
import com.loom.studio.chiplets.UnitResult
import com.loom.studio.core.TaskKind
import com.loom.studio.importer.ModelCard
import com.loom.studio.runtime.Session
import com.loom.studio.weaver.Adpf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * A running model: an ordered chain of chiplets built from the model's [TaskKind]
 * (see [PipelineFactory]). The Weaver reads its counters and turns its chiplets.
 * "Unit" = what the goal metric counts: a frame, a run, or a generated token.
 */
abstract class Pipeline(
    val card: ModelCard,
    protected val onEvent: (String) -> Unit,
) : AutoCloseable {
    abstract val chiplets: List<StudioChiplet>
    /** The chiplet that produces units (camera / input timer / prompt). */
    abstract val source: StudioChiplet
    abstract val display: Display?
    /** The main model chiplet (labels on the Run screen). */
    abstract val main: StudioChiplet
    /** Model loaded and able to produce units. */
    abstract val ready: Boolean
    /** False while nothing is being produced on purpose (camera closed, chat idle). */
    open val sourceActive: Boolean get() = true
    val kind: TaskKind get() = card.kind

    /** Goal metric for telemetry: "fps" (units/s) or "tokens_per_s". */
    open val metricName: String = "fps"
    open val unitName: String = "frames"
    /** Short unit for the pill, e.g. "fps", "runs", "tok". */
    open val unitShort: String = "fps"

    val processed = AtomicLong(0)
    @Volatile var detections = 0
    @Volatile var meanConf = 0.0
    @Volatile var p50Ms = 0.0
        protected set
    @Volatile var p90Ms = 0.0
        protected set
    val stageMs = java.util.concurrent.ConcurrentHashMap<String, Double>()
    @Volatile var lastSwap: String = "—"
    /** Live quality estimate (0..1) from the measured quality of the current profiles. */
    open val quality: Double? get() = chiplets.mapNotNull { it.quality() }.takeIf { it.isNotEmpty() }?.fold(1.0) { a, q -> a * q }

    /** True while the profiler owns the model. */
    @Volatile var paused = false

    protected val _last = MutableStateFlow<UnitResult?>(null)
    val last: StateFlow<UnitResult?> = _last

    val adpf = Adpf()

    private val totals = ArrayDeque<Double>()
    private var lastPct = 0L

    protected fun record(totalMs: Double, r: UnitResult?) {
        processed.incrementAndGet()
        if (r != null) {
            detections = r.count
            meanConf = r.meanScore
            _last.value = r
        }
        synchronized(totals) {
            totals.addLast(totalMs)
            if (totals.size > 120) totals.removeFirst()
            val now = System.currentTimeMillis()
            if (now - lastPct > 250) {
                lastPct = now
                val s = totals.sorted()
                p50Ms = s[(s.size - 1) / 2]
                p90Ms = s[((s.size - 1) * 0.9).toInt()]
            }
        }
    }

    /** Units/s the pipeline could do without pacing (1000 / p50). */
    open val capacity: Double get() = if (p50Ms > 0) 1000.0 / p50Ms else 0.0

    abstract suspend fun start()
    open fun setPace(unitsPerSec: Double?) {}

    override fun close() {
        adpf.close()
        display?.clear()
    }

    fun camera(): CameraSource? = source as? CameraSource
}

/** Pipelines whose model is a tensor-in / tensor-out [InferenceChiplet] (LiteRT, ONNX). */
abstract class TensorPipeline(card: ModelCard, onEvent: (String) -> Unit) : Pipeline(card, onEvent) {
    abstract val inference: InferenceChiplet
    override val main: StudioChiplet get() = inference
    override val ready: Boolean get() = inference.isReady

    protected fun wireInference() {
        inference.onSessionLive = { s: Session -> adpf.attach(s.tid, targetMs = 1000.0 / 15.0) }
    }

    protected fun recordUnit(totalMs: Double, r: UnitResult) {
        record(totalMs, r)
        inference.swapGapMs?.let { g ->
            lastSwap = "${inference.current.label} swap ${"%.3f".format(java.util.Locale.US, g)} ms"
            inference.swapGapMs = null
        }
    }

    override fun close() {
        super.close()
        inference.close()
    }
}
