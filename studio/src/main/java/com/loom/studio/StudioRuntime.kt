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
import com.loom.studio.pipeline.LlmPipeline
import com.loom.studio.pipeline.Pipeline
import com.loom.studio.pipeline.PipelineFactory
import com.loom.studio.pipeline.TensorPipeline
import com.loom.studio.profiler.LlmProfiler
import com.loom.studio.profiler.ProfileTable
import com.loom.studio.profiler.Profiler
import com.loom.studio.profiler.ProfilerState
import com.loom.studio.telemetry.DeviceSignals
import com.loom.studio.telemetry.RunContext
import com.loom.studio.telemetry.TelemetryBus
import com.loom.studio.weaver.ExactPlanner
import com.loom.studio.weaver.PlannerExport
import com.loom.studio.weaver.PolicyTable
import com.loom.studio.weaver.StressLoad
import com.loom.studio.weaver.StressMode
import com.loom.studio.weaver.Weaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's control centre: model library, the one running model (pipeline + Weaver), the
 * profiler, planner, Compare runs and dashboard commands. The run service calls [tick] at 1 Hz.
 */
class StudioRuntime(private val app: Context, private val telemetry: TelemetryBus) {

    val store = ModelStore(app)
    val importer = ImportManager(app, store)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val reportsDir: File get() = File(app.filesDir, "reports")
    private val prefs = app.getSharedPreferences("loom_studio", Context.MODE_PRIVATE)

    private val _models = MutableStateFlow(store.list())
    val models: StateFlow<List<ModelCard>> = _models

    @Volatile var signals: DeviceSignals? = null

    class Active(val card: ModelCard, val pipeline: Pipeline, val weaver: Weaver)

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active

    /** State of the running profiler (tensor or LLM), null when none. */
    private val _profilerState = MutableStateFlow<StateFlow<ProfilerState>?>(null)
    val profilerState: StateFlow<StateFlow<ProfilerState>?> = _profilerState

    private val _compare = MutableStateFlow<CompareRunner?>(null)
    val compare: StateFlow<CompareRunner?> = _compare

    private val stress = StressLoad { telemetry.event(it) }
    private var profileJob: Job? = null

    // ---------------- settings (Settings screen) ----------------
    var defaultHeatLimit: Float
        get() = prefs.getFloat("heat_limit", 0.80f)
        set(v) { prefs.edit().putFloat("heat_limit", v).apply() }
    var telemetryOn: Boolean
        get() = prefs.getBoolean("telemetry", true)
        set(v) { prefs.edit().putBoolean("telemetry", v).apply(); telemetry.enabled = v }

    init {
        telemetry.enabled = telemetryOn
    }

    fun refresh() {
        _models.value = store.list()
    }

    private fun announce(r: ImportManager.Result) {
        when (r) {
            is ImportManager.Result.Ok -> telemetry.event("Imported ${r.card.displayName} (${r.card.format}, ${r.card.sizeText}) → ${r.card.kindLabel}, confidence ${"%.2f".format(r.card.confidence)}")
            is ImportManager.Result.Failed -> telemetry.event("Import failed: ${r.message}")
        }
        refresh()
    }

    suspend fun import(uri: Uri, progress: (Float) -> Unit): ImportManager.Result = importer.import(uri, progress).also(::announce)

    suspend fun importProject(tree: Uri, progress: (Float) -> Unit): ImportManager.Result = importer.importProject(tree, progress).also(::announce)

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
    fun settings(card: ModelCard): ModelSettings = store.loadSettings(card).let { s ->
        if (File(store.dir(card.id), "settings.json").exists()) s else s.copy(heatLimit = defaultHeatLimit)
    }
    fun profiles(id: String): ProfileTable? = ProfileTable.load(store.profilesFile(id))
    fun policyFile(id: String) = File(store.dir(id), "policy.json")
    fun policy(id: String): PolicyTable? = PolicyTable.load(policyFile(id))
    fun lastCompare(card: ModelCard): String? = CompareRunner.lastResult(reportsDir, card.displayName)

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
        val pipeline = PipelineFactory.build(card, store, scope, { msg -> telemetry.event(msg) }, app)
        val table = profiles(card.id)
        if (table != null && pipeline is TensorPipeline) {
            pipeline.inference.measuredQuality = table.rows("inference").filter { it.quality != null }.associate { it.label to it.quality!! }
            if (!table.chargingDuring && table.order.isNotEmpty()) {
                val all = pipeline.inference.profiles + com.loom.studio.chiplets.InferenceChiplet.liteRtExtraProfiles()
                pipeline.inference.applyOrder(table.order, all)
            }
        }
        if (table != null && pipeline is LlmPipeline) {
            pipeline.threads.measuredQuality = table.rows("inference").filter { it.quality != null }.associate { it.label to it.quality!! }
        }
        val weaver = Weaver(pipeline, settings(card), table, stress, store.modelFile(card), policy(card.id))
        val a = Active(card, pipeline, weaver)
        _active.value = a
        telemetry.event("Run: ${card.displayName} — ${card.kindLabel}, chiplets ${pipeline.chiplets.joinToString(" → ") { it.name }}" +
            (weaver.policy?.let { " · policy: ${it.sourceLabel}" } ?: ""))
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
        _compare.value?.cancel()
        _compare.value = null
        _active.value = null
        profileJob?.cancel()
        profileJob = null
        _profilerState.value = null
        a.weaver.close()
        scope.launch(Dispatchers.IO) {
            try { a.pipeline.close() } catch (t: Throwable) { Log.w("LOOM", "close", t) }
        }
        telemetry.run = RunContext()
        telemetry.event("Run stopped: ${a.card.displayName}")
    }

    fun startProfiling() {
        val a = _active.value ?: return
        if (_profilerState.value?.value?.running == true) return
        val prevMode = a.weaver.core.mode
        a.weaver.setMode(Mode.MANUAL)
        telemetry.event("Profiler started for ${a.card.displayName}")
        val out = store.profilesFile(a.card.id)
        profileJob = when (val p = a.pipeline) {
            is TensorPipeline -> {
                val prof = Profiler(p, store.modelFile(a.card), { signals }, telemetry.deviceName)
                _profilerState.value = prof.state
                scope.launch { finishProfile(a, prof.run(out), prevMode) }
            }
            is LlmPipeline -> {
                val prof = LlmProfiler(p, { signals }, telemetry.deviceName)
                _profilerState.value = prof.state
                scope.launch { finishProfile(a, prof.run(out), prevMode) }
            }
            else -> null
        }
    }

    private fun finishProfile(a: Active, t: ProfileTable?, prevMode: Mode) {
        if (t != null) {
            a.weaver.profiles = t
            telemetry.event("Profiler done: ${t.rows.size} profiles measured" + if (t.chargingDuring) " (charging — mA not valid)" else "")
        }
        a.weaver.setMode(prevMode)
        refresh()
    }

    // ---------------- planner (M8) ----------------

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())

    /** On-device exact search → policy.json; the running Weaver follows it immediately. */
    fun planExact(): String {
        val a = _active.value ?: return "Open the model (Run) first: the planner needs its live chiplets."
        val t = profiles(a.card.id) ?: return "Profile the model first: the planner needs measured profiles."
        val m = PlannerExport.build(a.pipeline, t, a.weaver.history, telemetry.deviceName, a.weaver.core.heatLimit ?: 0.8f, a.weaver.core.goal)
        val pol = ExactPlanner.solve(m, stamp())
        pol.save(policyFile(a.card.id))
        a.weaver.policy = pol
        telemetry.event("Policy: classical exact, ${pol.plans.size} plans (${m.chiplets.size} chiplets, thermal ${m.thermal.source})")
        return "Exact plan ready: ${pol.plans.size} plans for ${m.goals.size} goals × ${m.buckets.size} heat bands."
    }

    /** studio_export.json for quantum/solve_policy.py --profiles, copied to Download/LOOM. */
    fun exportForQuantum(): String {
        val a = _active.value ?: return "Open the model (Run) first."
        val t = profiles(a.card.id) ?: return "Profile the model first."
        val m = PlannerExport.build(a.pipeline, t, a.weaver.history, telemetry.deviceName, a.weaver.core.heatLimit ?: 0.8f, a.weaver.core.goal)
        val f = File(store.dir(a.card.id), "studio_export.json")
        ModelStore.atomicWrite(f, m.toJson().toString(2))
        val where = Exporter.toDownloads(app, f, "application/json")
        return if (where != null) "Saved $where — run: python quantum/solve_policy.py --profiles studio_export.json" else "Export failed"
    }

    /** Import loom_policy.json from the QAOA solver (validated; untrusted file). */
    fun importPolicy(uri: Uri): String {
        val a = _active.value
        return try {
            val text = app.contentResolver.openInputStream(uri)?.use { s -> s.readBytes().takeIf { it.size < 1_000_000 }?.toString(Charsets.UTF_8) }
                ?: return "Could not read the file (or larger than 1 MB)."
            val pol = PolicyTable.parse(text)
            val card = a?.card ?: return "Open the model first, then import its policy."
            val names = a.pipeline.chiplets.map { it.name }.toSet()
            val unknown = pol.plans.flatMap { it.levels.keys }.distinct().filter { it !in names }
            if (unknown.isNotEmpty()) return "This policy is for other chiplets (${unknown.joinToString()}); not imported."
            pol.save(policyFile(card.id))
            a.weaver.policy = pol
            telemetry.event("Policy imported: ${pol.sourceLabel}, ${pol.plans.size} plans")
            "Policy imported: ${pol.sourceLabel}"
        } catch (t: Throwable) {
            "Not a valid policy file: ${t.message}"
        }
    }

    // ---------------- Compare (M9) ----------------

    fun startCompare(minutes: Int) {
        val a = _active.value ?: return
        if (_compare.value?.state?.value?.phase?.let { it == CompareRunner.Phase.DONE || it == CompareRunner.Phase.CANCELLED } == false) return
        _compare.value = CompareRunner(a.weaver, minutes, reportsDir)
        telemetry.event("Compare started: $minutes min Fixed HIGH, cool-down, $minutes min Adaptive")
    }

    fun cancelCompare() {
        _compare.value?.cancel()
    }

    fun exportCompare(): String {
        val path = _compare.value?.state?.value?.reportPath ?: return "No report yet."
        val a = Exporter.toDownloads(app, File("$path.csv"), "text/csv")
        val b = Exporter.toDownloads(app, File("$path.json"), "application/json")
        return if (a != null && b != null) "Saved to Download/LOOM" else "Export failed"
    }

    /** Settings → Export logs: this app's logcat + reports + profiles into Download/LOOM. */
    fun exportLogs(): String = try {
        val f = File(app.cacheDir, "loom_studio_logcat_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt")
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "20000"))
        f.outputStream().use { o -> p.inputStream.use { it.copyTo(o) } }
        var n = if (Exporter.toDownloads(app, f, "text/plain") != null) 1 else 0
        reportsDir.listFiles()?.forEach { r -> if (Exporter.toDownloads(app, r, if (r.name.endsWith(".csv")) "text/csv" else "application/json") != null) n++ }
        store.root.listFiles()?.forEach { d ->
            File(d, "profiles.json").takeIf { it.exists() }?.let { pf ->
                val copy = File(app.cacheDir, "profiles_${d.name}.json"); pf.copyTo(copy, true)
                if (Exporter.toDownloads(app, copy, "application/json") != null) n++
            }
        }
        "Exported $n files to Download/LOOM"
    } catch (t: Throwable) {
        "Export failed: ${t.message}"
    }

    /** Once per second from the run service. */
    fun tick(s: DeviceSignals) {
        val a = _active.value
        if (a == null) {
            telemetry.run = RunContext(stress = stress.mode.name)
        } else {
            a.weaver.tick(s, telemetry)
            val c = _compare.value
            if (c != null && c.tick(s)) telemetry.run = telemetry.run.copy(compare = c.label)
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
            "run" -> if (_compare.value == null) startCompare(15) else cancelCompare()
        }
    }

    companion object {
        const val ACTION_CONTROL = "com.loom.studio.CONTROL"
    }
}
