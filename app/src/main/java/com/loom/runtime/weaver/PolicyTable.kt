package com.loom.runtime.weaver

import android.content.Context
import android.util.Log
import com.loom.runtime.core.Level
import org.json.JSONObject

/**
 * The quantum policy table: for each FPS goal and heat band, the profile set chosen offline by
 * QAOA (Qiskit) on LOOM's QUBO and checked by brute force (quantum/solve_policy.py).
 * Loaded once from assets/loom_policy.json. The Weaver steers toward it; its reflexes stay on
 * as the safety net.
 */
class PolicyTable private constructor(
    val plans: List<Plan>,
    val method: String,
    val verified: Boolean,
) {
    data class Plan(
        val goal: Double,
        val bucket: String,
        val heatLo: Float,
        val heatHi: Float,
        val levels: Map<String, Level>,
        val labels: Map<String, String>,
        val predictedMa: Int,
        val predictedFps: Double,
        val limitReachable: Boolean,
        val pOptimal: Double,
    ) {
        fun levelOf(chiplet: String): Level? = levels[chiplet]
        fun describe(): String = CHIPLETS.joinToString(" · ") { labels[it] ?: "?" }
    }

    /** Plan for this goal (nearest solved goal at or below it, else the lowest) and heat. */
    fun lookup(goal: Double, heat: Float): Plan? {
        if (heat.isNaN()) return null
        val goals = plans.map { it.goal }.distinct().sorted()
        val g = goals.lastOrNull { it <= goal + 1e-6 } ?: goals.firstOrNull() ?: return null
        return plans.firstOrNull { it.goal == g && heat >= it.heatLo && heat < it.heatHi }
    }

    companion object {
        val CHIPLETS = listOf("Display", "Inference", "Capture", "Narrator")
        const val ASSET = "loom_policy.json"

        fun load(context: Context): PolicyTable? = try {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            parse(text)
        } catch (t: Throwable) {
            Log.w("LOOM", "no quantum policy table — reflexes only", t)
            null
        }

        fun parse(text: String): PolicyTable {
            val root = JSONObject(text)
            val arr = root.getJSONArray("plans")
            val plans = (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                val lv = p.getJSONObject("levels")
                val lb = p.getJSONObject("labels")
                Plan(
                    goal = p.getDouble("goal"),
                    bucket = p.getString("bucket"),
                    heatLo = p.getDouble("heat_lo").toFloat(),
                    heatHi = p.getDouble("heat_hi").toFloat(),
                    levels = CHIPLETS.associateWith { Level.valueOf(lv.getString(it)) },
                    labels = CHIPLETS.associateWith { lb.getString(it) },
                    predictedMa = p.getInt("predicted_mA"),
                    predictedFps = p.getDouble("predicted_fps"),
                    limitReachable = p.getBoolean("limit_reachable"),
                    pOptimal = p.optDouble("p_optimal", Double.NaN),
                )
            }
            return PolicyTable(plans, root.optString("method", "quantum"), root.optBoolean("verified", false))
        }
    }
}
