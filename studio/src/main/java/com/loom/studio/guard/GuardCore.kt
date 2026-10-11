package com.loom.studio.guard

/**
 * LOOM Guard decision core: cools the WHOLE phone, whatever app is in front (games, Camera,
 * video), by stepping through device-wide levers Android lets an app with the right
 * permissions change. There's no root and nothing touches the other app itself:
 *
 *   level 1  BRIGHTNESS  screen brightness down to 60 % of what it was (the panel is a big heat source)
 *   level 2  REFRESH     peak refresh rate capped at 60 Hz (games and UI render up to half as many frames)
 *   level 3  SAVER       Android battery saver (the OS lowers CPU/GPU clocks and background work)
 *
 * Same style as the Weaver: one step at a time, with hysteresis and cooldowns so it never
 * flaps. Pure Kotlin, so it is unit-tested on the laptop.
 */
class GuardCore(var limit: Float = 0.75f) {

    enum class Lever(val label: String) { BRIGHTNESS("brightness"), REFRESH("60 Hz"), SAVER("battery saver") }

    data class Change(val level: Int, val up: Boolean, val lever: Lever, val reason: String)

    var level = 0
        private set
    private var lastChangeMs = Long.MIN_VALUE / 2
    private var coolSinceMs: Long? = null

    /** Levers that are on at the current level, in order. */
    val activeLevers: List<Lever> get() = Lever.entries.take(level)

    /**
     * One 1 Hz step. [heat] is the combined thermal headroom (1.0 = throttling point),
     * [thermalStatus] Android's PowerManager status (2 MODERATE, 3 SEVERE). Returns a change
     * to apply, or null.
     */
    fun step(nowMs: Long, heat: Float, thermalStatus: Int): Change? {
        val severe = thermalStatus >= SEVERE
        val hot = (!heat.isNaN() && heat >= limit) || severe
        val cool = (heat.isNaN() || heat < limit - RELEASE_MARGIN) && thermalStatus < MODERATE

        if (hot) {
            coolSinceMs = null
            val wait = if (severe || (!heat.isNaN() && heat >= limit + URGENT_MARGIN)) URGENT_STEP_MS else STEP_MS
            if (level < MAX_LEVEL && nowMs - lastChangeMs >= wait) {
                level++
                lastChangeMs = nowMs
                val lever = Lever.entries[level - 1]
                return Change(level, true, lever, "heat ${f(heat)} ≥ ${f(limit)}${if (severe) " · Android SEVERE" else ""} → ${lever.label} on")
            }
            return null
        }
        if (cool && level > 0) {
            val since = coolSinceMs ?: nowMs.also { coolSinceMs = it }
            if (nowMs - since >= RELEASE_HOLD_MS && nowMs - lastChangeMs >= RELEASE_HOLD_MS) {
                val lever = Lever.entries[level - 1]
                level--
                lastChangeMs = nowMs
                coolSinceMs = nowMs
                return Change(level, false, lever, "heat ${f(heat)} < ${f(limit - RELEASE_MARGIN)} for ${RELEASE_HOLD_MS / 1000} s → ${lever.label} off")
            }
            return null
        }
        coolSinceMs = null // in the band between: hold
        return null
    }

    /** Back to level 0 (Guard switched off): the caller undoes every lever. */
    fun reset() {
        level = 0
        coolSinceMs = null
        lastChangeMs = Long.MIN_VALUE / 2
    }

    companion object {
        const val MAX_LEVEL = 3
        const val MODERATE = 2
        const val SEVERE = 3
        const val STEP_MS = 10_000L
        const val URGENT_STEP_MS = 3_000L
        const val URGENT_MARGIN = 0.15f
        const val RELEASE_MARGIN = 0.08f
        const val RELEASE_HOLD_MS = 30_000L
        private fun f(v: Float) = if (v.isNaN()) "—" else String.format(java.util.Locale.US, "%.2f", v)
    }
}
