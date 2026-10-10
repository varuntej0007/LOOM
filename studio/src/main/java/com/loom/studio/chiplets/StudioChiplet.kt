package com.loom.studio.chiplets

import com.loom.studio.core.AdaptiveChiplet
import com.loom.studio.core.Level
import com.loom.studio.core.Transitions

/** One power/quality setting of a chiplet. Knobs are the real levers it turns. */
data class Profile(val level: Level, val label: String, val knobs: Map<String, Any> = emptyMap())

/** Live cost of a chiplet: time per unit, units per second, estimated share of battery current. */
data class CostSample(val msPerUnit: Double, val unitsPerSec: Double, val estMa: Double? = null)

/**
 * A Studio chiplet (brief §6): a pipeline stage with power profiles (best quality → lowest
 * power), live cost, live quality, and hot-swap. [key] is its id in cut orders and profiles.json;
 * [name] is what the dashboard shows (kept identical to app/: Capture, Preprocess, Inference, Display).
 */
interface StudioChiplet : AdaptiveChiplet {
    val key: String
    val profiles: List<Profile>
    val current: Profile
    fun cost(): CostSample
    fun quality(): Double?
    /** Build the profile in the background (e.g. a new interpreter) without disturbing the live one. */
    suspend fun prepare(p: Profile)
    /** Switch at the next unit boundary (frame / token / chunk). */
    fun swap()

    /** True when this chiplet has more than one profile, i.e. the Weaver can turn it. */
    val adaptive: Boolean get() = profiles.size > 1
    fun canLower(): Boolean = profiles.any { it.level.ordinal > level.ordinal }
    fun canRaise(): Boolean = profiles.any { it.level.ordinal < level.ordinal }
    fun profileFor(l: Level): Profile? = profiles.firstOrNull { it.level == l }
    /** The next existing profile below / above the current one. */
    fun lowerLevel(): Level? = profiles.map { it.level }.filter { it.ordinal > level.ordinal }.minByOrNull { it.ordinal }
    fun higherLevel(): Level? = profiles.map { it.level }.filter { it.ordinal < level.ordinal }.maxByOrNull { it.ordinal }
}

/**
 * Base for chiplets whose knobs take effect immediately at the next unit (camera rate, screen,
 * run interval): prepare() does nothing, swap() applies, LOOM_TRX phase "applied".
 */
abstract class KnobChiplet(
    final override val name: String,
    final override val key: String,
    final override val profiles: List<Profile>,
    private val onEvent: (String) -> Unit,
) : StudioChiplet {
    @Volatile final override var current: Profile = profiles.first()
        private set
    override val level: Level get() = current.level
    override val profileLabel: String get() = current.label
    override fun quality(): Double? = null
    override suspend fun prepare(p: Profile) {}
    override fun swap() {}

    /** Subclasses turn the real lever here. */
    protected abstract fun apply(p: Profile, reason: String)

    @Synchronized
    override fun requestLevel(level: Level, reason: String) {
        val p = profileFor(level) ?: return
        if (p == current) return
        val old = current
        current = p
        apply(p, reason)
        Transitions.emit(Transitions.nextId(), name, "applied", old.label, p.label, reason)
        onEvent("$reason → $name: ${old.label} → ${p.label}")
    }
}

/** A stage with a single fixed profile (e.g. Preprocess): reported, never turned. */
abstract class FixedChiplet(
    final override val name: String,
    final override val key: String,
    label: String,
) : StudioChiplet {
    final override val profiles = listOf(Profile(Level.HIGH, label))
    final override val current: Profile get() = profiles[0]
    override val level: Level = Level.HIGH
    override val profileLabel: String get() = current.label
    override fun quality(): Double? = null
    override suspend fun prepare(p: Profile) {}
    override fun swap() {}
    override fun requestLevel(level: Level, reason: String) {}
}
