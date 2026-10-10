package com.loom.runtime.chiplets

import android.content.Context
import android.speech.tts.TextToSpeech
import com.loom.runtime.core.AdaptiveChiplet
import com.loom.runtime.core.Box
import com.loom.runtime.core.Clock
import com.loom.runtime.core.Level
import com.loom.runtime.core.Profiles
import com.loom.runtime.core.Transitions
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Narrator VC. Every N seconds it turns the latest detections into one short, prioritised
 * sentence (largest = nearest first) and speaks it with the phone's offline text-to-speech.
 *
 * Phase 2a: template sentences. Phase 2b swaps the template for the on-device LLM; the template
 * stays as the ECO / fallback path so the user is never left without guidance (blueprint §7).
 *
 * Runs on its own thread; the analyzer only hands it the latest detections.
 */
class NarratorChiplet(
    context: Context,
    private val onEvent: (String) -> Unit,
) : AdaptiveChiplet {

    override val name = "Narrator"
    val appContext: Context = context.applicationContext

    /**
     * True only when narration costs real compute (the on-device LLM, later). The template
     * narrator costs well under a millisecond, so lowering it can't raise FPS — and the voice
     * is the most valuable output for a visually impaired user — so the Weaver leaves it alone
     * unless it is actually heavy.
     */
    @Volatile var heavy: Boolean = false

    private class Scene(val boxes: List<Box>, val width: Int, val height: Int, val atNs: Long)

    private val latest = AtomicReference<Scene?>(null)
    private val pending = AtomicReference<Pair<Double, String>?>(null)
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "loom-narrator") }

    @Volatile override var level: Level = Level.HIGH
        private set
    @Volatile private var intervalMs: Long? = Profiles.narratorIntervalMs.getValue(Level.HIGH)
    @Volatile var voiceOn: Boolean = true
    @Volatile var lastSentence: String = "—"
        private set

    @Volatile private var ttsReady = false
    private var lastSpokeNs = 0L
    private val window = ArrayDeque<Pair<Long, List<Obs>>>()
    private var lastSignature = ""
    private var lastSaidNs = 0L

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setSpeechRate(1.1f)
                ttsReady = true
            } else {
                onEvent("Narrator: text-to-speech unavailable, showing text only")
            }
        }
    }

    override val profileLabel: String
        get() = intervalMs?.let { "every ${it / 1000} s" } ?: "paused"

    fun start() {
        worker.scheduleWithFixedDelay(::tick, 500, 250, TimeUnit.MILLISECONDS)
    }

    override fun requestLevel(level: Level, reason: String) {
        if (level == this.level) return
        val old = profileLabel
        this.level = level
        intervalMs = Profiles.narratorIntervalMs.getValue(level)
        if (intervalMs == null) tts.stop()
        Transitions.emit(Transitions.nextId(), name, "applied", old, profileLabel, reason)
        onEvent("$reason → Narrator: $old → $profileLabel")
    }

    /** Analyzer thread: hand over the newest detections (cheap, no work done here). */
    fun update(boxes: List<Box>, width: Int, height: Int) {
        val now = Clock.nowNs()
        latest.set(Scene(boxes, width, height, now))
        if (width == 0 || height == 0) return
        val frameArea = width.toFloat() * height
        val obs = boxes.filter { it.score >= MIN_SCORE }.map { b ->
            val cx = (b.left + b.right) / 2f / width
            Obs(b.label, cx, (b.right - b.left) * (b.bottom - b.top) / frameArea)
        }
        synchronized(window) {
            window.addLast(now to obs)
            while (window.isNotEmpty() && (now - window.first().first) / 1_000_000 > WINDOW_MS) window.removeFirst()
        }
    }

    /** Analyzer thread: (generation ms, sentence) of the newest sentence, reported once. */
    fun takeGenerated(): Pair<Double, String>? = pending.getAndSet(null)

    /**
     * Speak only what is STABLE and only when it CHANGES:
     *  1. An object counts only if it is seen in ≥ 60% of the frames of the last 1.5 s,
     *     so a detection that flickers for a few frames is never spoken.
     *  2. Safety-relevant objects (people, vehicles, animals, obstacles) come first; anything
     *     else is mentioned only if it is close. At most two items per sentence.
     *  3. If the scene is the same as last time, stay quiet (a reminder every 15 s).
     *     "Path looks clear" is said once, when the last object disappears.
     */
    private fun tick() {
        val interval = intervalMs ?: return
        val now = Clock.nowNs()
        if ((now - lastSpokeNs) / 1_000_000 < interval) return
        lastSpokeNs = now

        val t0 = Clock.nowNs()
        val items = stableItems() ?: return // not enough frames yet
        val signature = items.joinToString("|") { "${it.label}:${it.zone}:${it.near}:${it.count}" }
        val sentence = if (items.isEmpty()) "Path looks clear." else items.joinToString(" ") { it.say() }
        pending.set(Clock.ms(t0, Clock.nowNs()) to sentence)

        val changed = signature != lastSignature
        val remind = (now - lastSaidNs) / 1_000_000 >= REMIND_MS && items.isNotEmpty()
        if (!changed && !remind) return
        if (items.isEmpty() && lastSignature.isEmpty()) return // was already clear
        lastSignature = signature
        lastSaidNs = now
        lastSentence = sentence
        if (voiceOn && ttsReady) tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, "loom")
    }

    private fun stableItems(): List<Item>? {
        val frames = synchronized(window) { window.toList() }
        if (frames.size < MIN_FRAMES) return null
        val needed = (frames.size * STABLE_SHARE).toInt().coerceAtLeast(2)
        // Stability per object TYPE: a person drifting across a zone boundary stays one person.
        val seenIn = HashMap<String, Int>()
        for ((_, obs) in frames) obs.map { it.label }.toSet().forEach { seenIn[it] = (seenIn[it] ?: 0) + 1 }
        val newest = frames.last().second
        return seenIn.filter { it.value >= needed }.keys.mapNotNull { label ->
            if (newest.none { it.label == label }) return@mapNotNull null // gone right now
            // Position and size of the largest instance, averaged over the window (no flapping).
            val largest = frames.mapNotNull { (_, obs) -> obs.filter { it.label == label }.maxByOrNull { it.share } }
            val cx = largest.map { it.cx }.average().toFloat()
            val share = largest.map { it.share }.average().toFloat()
            val zone = zoneWithHysteresis(label, cx)
            val near = when {
                share > 0.20f -> ", close"
                share < 0.03f -> ", far"
                else -> ""
            }
            val count = newest.count { it.label == label }
            Item(label, zone, near, count, rank(label), share)
        }
            .filter { it.rank < OTHER_RANK || it.share > 0.10f } // non-priority objects only when close
            .sortedWith(compareBy<Item> { it.rank }.thenByDescending { it.share })
            .take(MAX_ITEMS)
    }

    /** Last zone said per object type, so a person standing on a boundary doesn't flip-flop. */
    private val lastZone = HashMap<String, String>()

    private fun zoneWithHysteresis(label: String, cx: Float): String {
        val m = ZONE_HYSTERESIS
        val zone = when (lastZone[label]) {
            "on your left" -> if (cx < 0.33f + m) "on your left" else if (cx > 0.67f) "on your right" else "ahead"
            "on your right" -> if (cx > 0.67f - m) "on your right" else if (cx < 0.33f) "on your left" else "ahead"
            "ahead" -> if (cx < 0.33f - m) "on your left" else if (cx > 0.67f + m) "on your right" else "ahead"
            else -> if (cx < 0.33f) "on your left" else if (cx > 0.67f) "on your right" else "ahead"
        }
        lastZone[label] = zone
        return zone
    }

    private fun rank(label: String) = when (label) {
        "person" -> 0
        "bicycle", "car", "motorcycle", "bus", "truck", "train" -> 1
        "dog", "cat", "horse", "cow" -> 2
        "chair", "couch", "bench", "dining table", "bed", "potted plant", "suitcase",
        "fire hydrant", "stop sign", "traffic light", "toilet" -> 3
        else -> OTHER_RANK
    }

    private class Obs(val label: String, val cx: Float, val share: Float)

    private class Item(
        val label: String, val zone: String, val near: String,
        val count: Int, val rank: Int, val share: Float,
    ) {
        fun say(): String {
            val noun = when {
                count == 1 -> label
                label == "person" -> "$count people"
                else -> "$count ${label}s"
            }
            return noun.replaceFirstChar { it.uppercase() } + " " + zone + near + "."
        }
    }

    fun close() {
        worker.shutdownNow()
        tts.stop()
        tts.shutdown()
    }

    companion object {
        const val WINDOW_MS = 1_500L      // stability window
        const val STABLE_SHARE = 0.6      // seen in ≥ 60% of frames in the window
        const val MIN_FRAMES = 5
        const val MIN_SCORE = 0.5f        // ignore low-confidence detections for speech
        const val MAX_ITEMS = 2
        const val OTHER_RANK = 9
        const val REMIND_MS = 15_000L     // repeat an unchanged scene at most this often
        const val ZONE_HYSTERESIS = 0.06f // an object must move this far past a boundary to change zone
    }
}
