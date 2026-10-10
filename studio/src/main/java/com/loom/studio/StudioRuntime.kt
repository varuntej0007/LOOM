package com.loom.studio

import android.content.Context
import android.net.Uri
import android.util.Log
import com.loom.studio.core.Mode
import com.loom.studio.core.TaskKind
import com.loom.studio.importer.Head
import com.loom.studio.importer.ImportManager
import com.loom.studio.importer.ModelCard
import com.loom.studio.importer.ModelSettings
import com.loom.studio.importer.ModelStore
import com.loom.studio.pipeline.Pipeline
import com.loom.studio.pipeline.PipelineFactory
import com.loom.studio.profiler.ProfileTable
import com.loom.studio.profiler.Profiler
import com.loom.studio.telemetry.DeviceSignals
import com.loom.studio.telemetry.RunContext
import com.loom.studio.telemetry.TelemetryBus
import com.loom.studio.weaver.StressLoad
import com.loom.studio.weaver.StressMode
import com.loom.studio.weaver.Weaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The app's control centre: model library, the one running model (pipeline + Weaver), the
 * profiler, and dashboard commands. The run service calls [tick] once per second.
 */
class StudioRuntime(private val app: Context, private val telemetry: TelemetryBus) {

    val store = ModelStore(app)
    val importer = ImportManager(app, store)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _models = MutableStateFlow(store.list())
    val models: StateFlow<List<ModelCard>> = _models

    /** Set by the run service while it is alive. */
    @Volatile var signals: DeviceSignals? = null

    class Active(val card: ModelCard, val pipeline: Pipeline, val weaver: Weaver)

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active

    private val _profiler = MutableStateFlow<Profiler?>(null)
    val profiler: StateFlow<Profiler?> = _profiler

    private val stress = StressLoad { telemetry.event(it) }
    private var profileJob: kotlinx.coroutines.Job? = null

    fun refresh() {
        _models.value = store.list()
    }

    suspend fun import(uri: Uri, progress: (Float) -> Unit): ImportManager.Result {
        val r = importer.import(uri, progress)
        if (r is ImportManager.Result.Ok) {
            telemetry.event("Imported ${r.card.displayName} (${r.card.format}, ${r.card.sizeText}) → ${r.card.kindLabel}, confidence ${"%.2f".format(r.card.confidence)}")
        } else if (r is ImportManager.Result.Failed) {
            telemetry.event("Import failed: ${r.message}")
        }
        refresh()
        return r
    }

    /** The user overrode the detected type on the Inspect screen. */
    fun override(card: ModelCard, kind: TaskKind, head: Head): ModelCard {
        val c = card.copy(kind = kind, head = if (kind == TaskKind.VISION) head else Head.NONE, overridden = true,
            reasons = card.reasons + "type set by the user: ${kind.name}${if (kind == TaskKind.VISION) " · " + head.name.lowercase() else ""}")
        store.saveCard(c)
        refresh()
        return c
    }

    fun delete(card: ModelCard) {
        if (_active.value?.card?.id == card.id) closeActive()
        store.delete(card.id)
        refresh()
    }

    fun card(id: String): ModelCard? = _models.value.firstOrNull { it.id == id } ?: store.loadCard(id)
    fun settings(card: ModelCard): ModelSettings = store.loadSettings(card)
    fun profiles(id: String): ProfileTable? = ProfileTable.load(store.profilesFile(id))

    fun saveSettings(card: ModelCard, s: ModelSettings) {
        store.saveSettings(card.id, s)
        _active.value?.takeIf { it.card.id == card.id }?.weaver?.let { w ->
            if (w.core.goal != s.goalFps) w.setGoal(s.goalFps)
            if (w.core.heatLimit != s.heatLimit) w.setHeatLimit(s.heatLimit)
            if (w.core.cutOrder != s.cutOrder) w.setCutOrder(s.cutOrder)
        }
    }

    /** Build (or reuse) the running pipeline for [card]. */
    fun open(card: ModelCard): Active {
        _active.value?.let { if (it.card.id == card.id) return it }
        closeActive()
        val file = store.modelFile(card)
        val pipeline = PipelineFactory.build(card, file, scope) { msg -> telemetry.event(msg) }
        val table = profiles(card.id)
        table?.let { t ->
            pipeline.inference.measuredQuality = t.rows("inference").filter { it.quality != null }.associate { it.label to it.quality!! }
            if (!t.chargingDuring) pipeline.inference.reorder(t.rows("inference").filter { it.ma != null }.associate { it.label to it.ma!! })
        }
        val weaver = Weaver(pipeline, settings(card), table, stress, file)
        val a = Active(card, pipeline, weaver)
        _active.value = a
        telemetry.event("Run: ${card.displayName} — ${card.kindLabel}, chiplets ${pipeline.chiplets.joinToString(" → ") { it.name }}")
        scope.launch {
            try {
                pipeline.start()
            } catch (t: Throwable) {
                Log.e("LOOM", "pipeline start", t)
                telemetry.event("Pipeline failed to start: ${t.message}")
            }
        }
        return a
    }

    fun closeActive() {
        val a = _active.value ?: return
        _active.value = null
        profileJob?.cancel()
        profileJob = null
        _profiler.value = null
        a.weaver.close()
        scope.launch(Dispatchers.IO) {
            try { a.pipeline.close() } catch (t: Throwable) { Log.w("LOOM", "close", t) }
        }
        telemetry.run = RunContext()
        telemetry.event("Run stopped: ${a.card.displayName}")
    }

    fun startProfiling() {
        val a = _active.value ?: return
        if (_profiler.value?.state?.value?.running == true) return
        val p = Profiler(a.pipeline, store.modelFile(a.card), { signals }, telemetry.deviceName)
        _profiler.value = p
        val prevMode = a.weaver.core.mode
        a.weaver.setMode(Mode.MANUAL)
        telemetry.event("Profiler started for ${a.card.displayName}")
        profileJob = scope.launch {
            val t = p.run(store.profilesFile(a.card.id))
            if (t != null) {
                a.weaver.profiles = t
                telemetry.event("Profiler done: ${t.rows.size} profiles measured" + if (t.chargingDuring) " (charging — mA not valid)" else "")
            }
            a.weaver.setMode(prevMode)
            refresh()
        }
    }

    /** Once per second from the run service. */
    fun tick(s: DeviceSignals) {
        val a = _active.value
        if (a == null) {
            telemetry.run = RunContext(stress = stress.mode.name)
        } else {
            a.weaver.tick(s, telemetry)
        }
        telemetry.emitLive(s)
    }

    /**
     * Dashboard / adb control (same command names as app/):
     *   adb shell am broadcast -a com.loom.studio.CONTROL -p com.loom.studio --es cmd stress --es value gpu
     * Commands: stress off|gpu|cpu · mode adaptive|fixed|manual · goal <n> · heat off|<0.xx>
     */
    fun control(cmd: String, value: String) {
        val w = _active.value?.weaver
        when (cmd) {
            "stress" -> StressMode.entries.firstOrNull { it.name.equals(value, true) }?.let { m ->
                if (w != null) w.setStress(m) else stress.set(m, null, null)
            }
            "mode" -> when (value.lowercase()) {
                "adaptive" -> w?.setMode(Mode.ADAPTIVE)
                "fixed" -> w?.setMode(Mode.FIXED)
                "manual" -> w?.setMode(Mode.MANUAL)
            }
            "goal" -> value.toDoubleOrNull()?.takeIf { it in 1.0..120.0 }?.let { w?.setGoal(it) }
            "heat" -> if (value.equals("off", true)) w?.setHeatLimit(null)
                else value.toFloatOrNull()?.takeIf { it in 0.3f..1.2f }?.let { w?.setHeatLimit(it) }
        }
    }

    companion object {
        const val ACTION_CONTROL = "com.loom.studio.CONTROL"
    }
}
