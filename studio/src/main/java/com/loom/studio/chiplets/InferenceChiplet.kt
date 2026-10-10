package com.loom.studio.chiplets

import android.util.Log
import com.loom.studio.core.Clock
import com.loom.studio.core.Level
import com.loom.studio.core.Transitions
import com.loom.studio.importer.ModelCard
import com.loom.studio.runtime.Backend
import com.loom.studio.runtime.LiteRtBackend
import com.loom.studio.runtime.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Inference chiplet, generalised from app/'s InferenceChiplet to any [Backend].
 *
 * Hot swap: a new profile is BUILT in the background (own thread) while the old session keeps
 * serving; the pipeline calls [swap] between two units and the sessions trade places in
 * microseconds. LOOM_TRX: requested → built → swapped, or failed (old one stays live).
 */
class InferenceChiplet(
    private val card: ModelCard,
    private val file: File,
    private val backend: Backend,
    initialProfiles: List<Profile>,
    private val scope: CoroutineScope,
    private val onEvent: (String) -> Unit,
) : StudioChiplet {
    override val name = "Inference"
    override val key = "inference"

    @Volatile override var profiles: List<Profile> = initialProfiles
        private set
    @Volatile override var current: Profile = initialProfiles.first()
        private set
    override val level: Level get() = current.level
    override val profileLabel: String get() = current.label + if (session == null) " (loading)" else ""

    @Volatile var session: Session? = null
        private set
    private val pending = AtomicReference<Pending?>(null)
    private class Pending(val profile: Profile, val session: Session, val id: Long, val reason: String, val from: String)
    private var buildJob: Job? = null
    /** Profiles that failed to build on this phone (e.g. GPU delegate rejected). */
    private val failed = mutableSetOf<String>()
    /** Measured quality per profile label (profiles.json); HIGH = 1.0. */
    @Volatile var measuredQuality: Map<String, Double> = emptyMap()
    /** Called with the inference thread id whenever a session goes live (ADPF hints). */
    @Volatile var onSessionLive: ((Session) -> Unit)? = null

    private val window = ArrayDeque<Double>()
    @Volatile private var lastMs = 0.0
    @Volatile var swapGapMs: Double? = null

    val isReady: Boolean get() = session != null

    override fun cost(): CostSample {
        val avg = synchronized(window) { if (window.isEmpty()) 0.0 else window.average() }
        return CostSample(avg, if (avg > 0) 1000.0 / avg else 0.0)
    }

    override fun quality(): Double? = measuredQuality[current.label]

    /** Inference ms of the last unit. */
    val lastUnitMs: Double get() = lastMs

    override fun lowerLevel(): Level? = profiles.filter { it.label !in failed && it.level.ordinal > level.ordinal }.minByOrNull { it.level.ordinal }?.level
    override fun higherLevel(): Level? = profiles.filter { it.label !in failed && it.level.ordinal < level.ordinal }.maxByOrNull { it.level.ordinal }?.level
    override fun canLower(): Boolean = lowerLevel() != null
    override fun canRaise(): Boolean = higherLevel() != null

    /** Load the first profile that works on this phone (HIGH, falling back down the list). */
    suspend fun start() {
        for (p in profiles) {
            try {
                val t0 = Clock.nowNs()
                val s = backend.load(card, file, p.knobs)
                current = p
                session = s
                onSessionLive?.invoke(s)
                onEvent("Inference ready: ${p.label} (built in ${"%.0f".format(Clock.ms(t0, Clock.nowNs()))} ms)")
                return
            } catch (t: Throwable) {
                failed += p.label
                Log.w("LOOM", "profile ${p.label} failed", t)
                Transitions.emit(Transitions.nextId(), name, "failed", "—", p.label, "build failed: ${t.message}")
                onEvent("Inference ${p.label} unavailable: ${t.message}")
            }
        }
        onEvent("No inference profile could be built for this model")
    }

    override fun requestLevel(level: Level, reason: String) {
        val p = profiles.firstOrNull { it.level == level && it.label !in failed } ?: return
        if (p == current && pending.get() == null) return
        buildJob?.cancel()
        val id = Transitions.nextId()
        val from = current.label
        Transitions.emit(id, name, "requested", from, p.label, reason)
        buildJob = scope.launch(Dispatchers.IO) { build(p, id, reason, from) }
    }

    override suspend fun prepare(p: Profile) {
        build(p, Transitions.nextId(), "prepare", current.label)
    }

    private suspend fun build(p: Profile, id: Long, reason: String, from: String) {
        val t0 = Clock.nowNs()
        val s = try {
            backend.load(card, file, p.knobs)
        } catch (t: Throwable) {
            failed += p.label
            Transitions.emit(id, name, "failed", from, p.label, "build failed: ${t.message}", Clock.ms(t0, Clock.nowNs()))
            onEvent("$reason → Inference ${p.label} failed (${t.message}); staying on $from")
            return
        }
        val buildMs = Clock.ms(t0, Clock.nowNs())
        Transitions.emit(id, name, "built", from, p.label, reason, buildMs)
        pending.getAndSet(Pending(p, s, id, reason, from))?.session?.close()
    }

    /** Pipeline thread, between two units. */
    override fun swap() {
        val next = pending.getAndSet(null) ?: return
        val t0 = Clock.nowNs()
        val old = session
        session = next.session
        current = next.profile
        val gap = Clock.ms(t0, Clock.nowNs())
        swapGapMs = gap
        synchronized(window) { window.clear() }
        onSessionLive?.invoke(next.session)
        Transitions.emit(next.id, name, "swapped", next.from, next.profile.label, next.reason, swapGapMs = gap)
        onEvent("${next.reason} → Inference: ${next.from} → ${next.profile.label}")
        if (old != null) scope.launch(Dispatchers.IO) { try { old.close() } catch (_: Throwable) { } }
    }

    /** Pipeline thread: one unit of inference. */
    fun run(inputs: Map<String, Any>): Map<String, Any>? {
        val s = session ?: return null
        val t0 = Clock.nowNs()
        val out = s.run(inputs)
        lastMs = Clock.ms(t0, Clock.nowNs())
        synchronized(window) {
            window.addLast(lastMs)
            if (window.size > 30) window.removeFirst()
        }
        return out
    }

    /**
     * After profiling: re-order profiles so HIGH is the most power-hungry working profile and
     * ECO the least, by measured battery current (brief §6: best quality → lowest power).
     */
    fun reorder(byMa: Map<String, Double>) {
        val working = profiles.filter { it.label !in failed && byMa[it.label] != null }
        if (working.size < 2) return
        val sorted = working.sortedByDescending { byMa[it.label] }
        val levels = Level.entries
        val re = sorted.take(levels.size).mapIndexed { i, p -> p.copy(level = levels[i]) }
        val currentLabel = current.label
        profiles = re
        current = re.firstOrNull { it.label == currentLabel } ?: current
    }

    fun close() {
        buildJob?.cancel()
        pending.getAndSet(null)?.session?.close()
        session?.close()
        session = null
    }

    companion object {
        /** Default profile set for a LiteRT model: GPU, CPU ×4, CPU ×2. */
        fun liteRtProfiles(): List<Profile> = buildList {
            add(Profile(Level.HIGH, "GPU", mapOf("delegate" to "gpu", "fp16" to true)))
            add(Profile(Level.BALANCED, "CPU ×4", mapOf("delegate" to "cpu", "threads" to 4)))
            add(Profile(Level.ECO, "CPU ×2", mapOf("delegate" to "cpu", "threads" to 2)))
        }

        val defaultBackend: Backend by lazy { LiteRtBackend() }
    }
}
