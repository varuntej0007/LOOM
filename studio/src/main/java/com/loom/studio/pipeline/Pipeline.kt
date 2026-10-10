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
 */
abstract class Pipeline(
    val card: ModelCard,
    protected val onEvent: (String) -> Unit,
) : AutoCloseable {
    abstract val chiplets: List<StudioChiplet>
    abstract val inference: InferenceChiplet
    /** The chiplet that produces units (camera or input timer). */
    abstract val source: StudioChiplet
    abstract val display: Display?
    val kind: TaskKind get() = card.kind

    /** Goal metric name for telemetry: processed units per second. */
    open val metricName: String = "fps"
    open val unitName: String = "frames"

    /** Units fully processed (through inference + postprocess). */
    val processed = AtomicLong(0)
    @Volatile var detections = 0
    @Volatile var meanConf = 0.0
    @Volatile var p50Ms = 0.0
        protected set
    @Volatile var p90Ms = 0.0
        protected set
    /** Latest stage times in ms, by chiplet key. */
    val stageMs = java.util.concurrent.ConcurrentHashMap<String, Double>()
    @Volatile var lastSwap: String = "—"

    /** True while the profiler owns the model: the live chain keeps sourcing but skips inference. */
    @Volatile var paused = false

    protected val _last = MutableStateFlow<UnitResult?>(null)
    val last: StateFlow<UnitResult?> = _last

    val adpf = Adpf()

    private val totals = ArrayDeque<Double>()
    private var lastPct = 0L

    init {
        // Hooked up by subclasses once `inference` exists.
    }

    protected fun wireInference() {
        inference.onSessionLive = { s: Session -> adpf.attach(s.tid, targetMs = 1000.0 / 15.0) }
    }

    /** Pipeline thread: book-keeping for one processed unit. */
    protected fun record(totalMs: Double, r: UnitResult) {
        processed.incrementAndGet()
        detections = r.count
        meanConf = r.meanScore
        _last.value = r
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
        inference.swapGapMs?.let { g ->
            lastSwap = "${inference.current.label} swap ${"%.3f".format(java.util.Locale.US, g)} ms"
            inference.swapGapMs = null
        }
    }

    /** Units/s the pipeline could do without pacing (1000 / p50). */
    val capacity: Double get() = if (p50Ms > 0) 1000.0 / p50Ms else 0.0

    abstract suspend fun start()
    open fun setPace(unitsPerSec: Double?) {}

    override fun close() {
        adpf.close()
        inference.close()
        display?.clear()
    }

    fun camera(): CameraSource? = source as? CameraSource
}
