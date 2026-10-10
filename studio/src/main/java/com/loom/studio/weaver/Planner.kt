package com.loom.studio.weaver

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.floor

/**
 * The per-model planning problem (brief §9), in the SAME schema as quantum/loom_model.json so
 * quantum/solve_policy.py --profiles can solve it with QAOA, and [ExactPlanner] can solve it
 * on the phone by exhaustive search. Power deltas are relative to all-HIGH, in mA.
 */
data class PlannerModel(
    val device: String,
    val model: String,
    val heatLimit: Double,
    val horizonS: Double = 180.0,
    val powerUnit: Int = 10,
    val goals: List<Double>,
    val buckets: List<Bucket> = DEFAULT_BUCKETS,
    val thermal: Thermal,
    val basePowerMa: Double,
    /** chiplet NAME → levels (HIGH/BALANCED/ECO subset) */
    val chiplets: LinkedHashMap<String, Map<String, PLevel>>,
    /** Chiplets whose level caps the unit rate (≤ 2: the shortfall term stays quadratic). */
    val throughput: List<String>,
    val shortfallPenalty: Double = 3.0,
    val keys: Map<String, String>,   // chiplet name → key (for the Weaver)
) {
    data class Bucket(val name: String, val lo: Double, val hi: Double, val planAt: Double)
    data class Thermal(val a: Double, val b: Double, val hIdle: Double, val source: String)
    data class PLevel(val label: String, val powerMa: Int, val qualityLoss: Double, val maxRate: Double?)

    fun power(cfg: Map<String, String>) = basePowerMa + cfg.entries.sumOf { (c, l) -> chiplets[c]!![l]!!.powerMa }
    fun rate(cfg: Map<String, String>): Double =
        throughput.mapNotNull { c -> chiplets[c]?.get(cfg[c])?.maxRate }.minOrNull() ?: Double.MAX_VALUE
    fun objective(cfg: Map<String, String>, goal: Double): Double =
        cfg.entries.sumOf { (c, l) -> chiplets[c]!![l]!!.qualityLoss } + shortfallPenalty * maxOf(0.0, goal - rate(cfg))

    fun predictedHeat(h: Double, p: Double): Double {
        val eq = thermal.hIdle + thermal.a * p / thermal.b
        val e = exp(-thermal.b * horizonS)
        return eq + (h - eq) * e
    }

    fun heatBudgetMa(h: Double): Double {
        val e = exp(-thermal.b * horizonS)
        val eqMax = (heatLimit - h * e) / (1 - e)
        return (eqMax - thermal.hIdle) * thermal.b / thermal.a
    }

    fun configs(): List<Map<String, String>> {
        var out = listOf(emptyMap<String, String>())
        for ((c, levels) in chiplets) out = out.flatMap { base -> levels.keys.map { l -> base + (c to l) } }
        return out
    }

    fun toJson(): JSONObject = JSONObject()
        .put("studio", 1)
        .put("about", "LOOM Studio export: profiles measured on this phone. Same schema as quantum/loom_model.json.")
        .put("device", device).put("model", model)
        .put("heat_limit", heatLimit).put("plan_horizon_s", horizonS).put("power_unit_mA", powerUnit)
        .put("goals_fps", JSONArray(goals))
        .put("heat_buckets", JSONArray(buckets.map { JSONObject().put("name", it.name).put("lo", it.lo).put("hi", it.hi).put("plan_at", it.planAt) }))
        .put("thermal", JSONObject().put("model", "dh/dt = a*P - b*(h - h_idle)").put("a_per_mA_s", thermal.a)
            .put("b_per_s", thermal.b).put("h_idle", thermal.hIdle).put("source", thermal.source))
        .put("base_power_mA", JSONObject().put("value", basePowerMa).put("source", "measured: all chiplets at HIGH"))
        .put("roles", JSONObject().put("throughput", JSONArray(throughput)))
        .put("keys", JSONObject(keys as Map<*, *>))
        .put("fps_shortfall_penalty_per_fps", shortfallPenalty)
        .put("chiplets", JSONObject().also { o ->
            chiplets.forEach { (c, levels) ->
                o.put(c, JSONObject().put("levels", JSONObject().also { lo ->
                    levels.forEach { (l, v) ->
                        lo.put(l, JSONObject().put("label", v.label).put("power_mA", v.powerMa).put("quality_loss", v.qualityLoss)
                            .put("max_fps", v.maxRate ?: JSONObject.NULL))
                    }
                }))
            }
        })

    companion object {
        val DEFAULT_BUCKETS = listOf(
            Bucket("cool", 0.0, 0.65, 0.6), Bucket("mild", 0.65, 0.7, 0.675), Bucket("warm", 0.7, 0.75, 0.725),
            Bucket("near", 0.75, 0.8, 0.775), Bucket("over", 0.8, 9.99, 0.825),
        )
        /**
         * Read quantum/loom_model.json or a Studio export. For app/'s model, levels the planner
         * may not choose (Narrator "paused") are excluded exactly as quantum/loom_qubo.ALLOWED does.
         */
        fun fromJson(j: JSONObject): PlannerModel {
            val ch = j.getJSONObject("chiplets")
            val chiplets = LinkedHashMap<String, Map<String, PLevel>>()
            val studio = j.optInt("studio", 0) == 1
            for (c in ch.keys()) {
                val lv = ch.getJSONObject(c).getJSONObject("levels")
                val m = LinkedHashMap<String, PLevel>()
                for (l in listOf("HIGH", "BALANCED", "ECO")) {
                    if (!lv.has(l)) continue
                    if (!studio && c == "Narrator" && l == "ECO") continue
                    val o = lv.getJSONObject(l)
                    val rate = when {
                        o.has("max_fps") && !o.isNull("max_fps") -> o.getDouble("max_fps")
                        o.has("camera_fps") -> o.getDouble("camera_fps")
                        else -> null
                    }
                    m[l] = PLevel(o.optString("label", l), o.getInt("power_mA"), o.getDouble("quality_loss"), rate)
                }
                chiplets[c] = m
            }
            val t = j.getJSONObject("thermal")
            val throughput = j.optJSONObject("roles")?.optJSONArray("throughput")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?: listOf("Inference", "Capture")
            val goals = j.getJSONArray("goals_fps").let { a -> (0 until a.length()).map { a.getDouble(it) } }
            val buckets = j.getJSONArray("heat_buckets").let { a -> (0 until a.length()).map { i ->
                val b = a.getJSONObject(i); Bucket(b.getString("name"), b.getDouble("lo"), b.getDouble("hi"), b.getDouble("plan_at")) } }
            val keys = j.optJSONObject("keys")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } } ?: emptyMap()
            return PlannerModel(
                device = j.optString("device"), model = j.optString("model", "LOOM camera app"),
                heatLimit = j.getDouble("heat_limit"), horizonS = j.getDouble("plan_horizon_s"), powerUnit = j.getInt("power_unit_mA"),
                goals = goals, buckets = buckets,
                thermal = Thermal(t.getDouble("a_per_mA_s"), t.getDouble("b_per_s"), t.getDouble("h_idle"), t.optString("source")),
                basePowerMa = j.getJSONObject("base_power_mA").getDouble("value"),
                chiplets = chiplets, throughput = throughput,
                shortfallPenalty = j.getDouble("fps_shortfall_penalty_per_fps"), keys = keys,
            )
        }

        /** app/'s fit on the iQOO 15 (quantum/calibrate.py), used until Studio has its own data. */
        val DEFAULT_THERMAL = Thermal(1.294e-6, 0.0044, 0.669, "default: app/ calibration on the iQOO 15 (117 windows, R² 0.82)")
    }
}

/** One plan: the profile set for a goal and heat band. */
data class Plan(
    val goal: Double,
    val bucket: String,
    val heatLo: Double,
    val heatHi: Double,
    val levels: Map<String, String>,   // chiplet NAME → level
    val labels: Map<String, String>,
    val predictedMa: Double,
    val predictedRate: Double,
    val reachable: Boolean,
)

/** loom_policy.json (quantum/solve_policy.py output, or the on-device exact solver). */
data class PolicyTable(val source: String, val verified: Boolean, val generated: String, val plans: List<Plan>) {

    /** Nearest goal at or above [goal] (else the highest), bucket containing [heat]. */
    fun lookup(goal: Double, heat: Float): Plan? {
        if (plans.isEmpty() || heat.isNaN()) return null
        val goals = plans.map { it.goal }.distinct().sorted()
        val g = goals.firstOrNull { it >= goal - 1e-6 } ?: goals.last()
        return plans.filter { it.goal == g }.firstOrNull { heat >= it.heatLo && heat < it.heatHi }
    }

    val sourceLabel: String get() = when {
        source.contains("QAOA", true) && verified -> "quantum (QAOA), verified"
        source.contains("QAOA", true) -> "quantum (QAOA), NOT verified"
        else -> "classical exact"
    }

    fun toJson(): JSONObject = JSONObject().put("version", 1).put("generated", generated).put("method", source)
        .put("verified", verified)
        .put("plans", JSONArray(plans.map { p ->
            JSONObject().put("goal", p.goal).put("bucket", p.bucket).put("heat_lo", p.heatLo).put("heat_hi", p.heatHi)
                .put("levels", JSONObject(p.levels as Map<*, *>)).put("labels", JSONObject(p.labels as Map<*, *>))
                .put("predicted_mA", p.predictedMa).put("predicted_fps", p.predictedRate).put("limit_reachable", p.reachable)
        }))

    fun save(f: File) = com.loom.studio.importer.ModelStore.atomicWrite(f, toJson().toString(2))

    companion object {
        private val LEVEL_NAMES = setOf("HIGH", "BALANCED", "ECO")

        /** Parse + validate an imported policy (untrusted file): bounded, typed, known levels only. */
        fun parse(text: String): PolicyTable {
            require(text.length < 1_000_000) { "policy file too large" }
            val j = JSONObject(text)
            val arr = j.getJSONArray("plans")
            require(arr.length() in 1..500) { "policy must have 1–500 plans" }
            val plans = (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                val lv = p.getJSONObject("levels")
                val levels = lv.keys().asSequence().associateWith { k -> lv.getString(k).also { require(it in LEVEL_NAMES) { "bad level $it" } } }
                val lb = p.optJSONObject("labels")
                Plan(
                    goal = p.getDouble("goal"), bucket = p.optString("bucket", "?"),
                    heatLo = p.getDouble("heat_lo"), heatHi = p.getDouble("heat_hi"),
                    levels = levels,
                    labels = lb?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } } ?: emptyMap(),
                    predictedMa = p.optDouble("predicted_mA", 0.0), predictedRate = p.optDouble("predicted_fps", 0.0),
                    reachable = p.optBoolean("limit_reachable", true),
                )
            }
            return PolicyTable(j.optString("method", "imported"), j.optBoolean("verified", false), j.optString("generated", ""), plans)
        }

        fun load(f: File): PolicyTable? = try { if (f.exists()) parse(f.readText()) else null } catch (_: Throwable) { null }
    }
}

/**
 * Exact search on the phone (brief §9 fallback): every combination of chiplet levels
 * (e.g. 3^4 = 81, 3^6 = 729) for every goal × heat band, under the same heat budget and
 * objective as the QUBO. Identical to quantum/loom_qubo.brute_force.
 */
object ExactPlanner {
    fun solve(m: PlannerModel, stamp: String): PolicyTable {
        val cfgs = m.configs()
        val floorP = cfgs.minOf { m.power(it) }
        val plans = mutableListOf<Plan>()
        for (b in m.buckets) for (g in m.goals) {
            val budget = m.heatBudgetMa(b.planAt)
            val reachable = budget >= floorP
            val used = maxOf(budget, floorP)
            val rhs = floor((used - m.basePowerMa) / m.powerUnit + 1e-9) * m.powerUnit + m.basePowerMa
            val best = cfgs.filter { m.power(it) <= rhs + 1e-6 }
                .minWithOrNull(compareBy<Map<String, String>>({ m.objective(it, g) }, { m.power(it) }))
                ?: cfgs.minBy { m.power(it) }
            plans += Plan(
                g, b.name, b.lo, b.hi, best,
                best.mapValues { (c, l) -> m.chiplets[c]!![l]!!.label },
                m.power(best), m.rate(best).let { if (it == Double.MAX_VALUE) 0.0 else it }, reachable,
            )
        }
        return PolicyTable("classical exact search over ${cfgs.size} configurations per problem", true, stamp, plans)
    }
}
