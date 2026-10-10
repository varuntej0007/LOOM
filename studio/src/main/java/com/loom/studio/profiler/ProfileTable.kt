package com.loom.studio.profiler

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One measured chiplet profile (a row of profiles.json). */
data class MeasuredProfile(
    val chiplet: String,          // key: source | inference | display | …
    val label: String,
    val level: String,            // HIGH | BALANCED | ECO
    val p50Ms: Double?,
    val p90Ms: Double?,
    val throughput: Double?,      // units/s while measured
    val ma: Double?,              // average battery current magnitude, mA
    val heatSlopePerMin: Double?, // headroom change per minute while measured
    val quality: Double?,         // vs HIGH = 1.0
    val note: String? = null,
)

/**
 * profiles.json: what every chiplet profile costs ON THIS PHONE, measured at import.
 * Drives the Weaver (which lower profile is really faster / cooler) and is the input the
 * quantum planner exports (brief §9).
 */
data class ProfileTable(
    val modelId: String,
    val modelName: String,
    val device: String,
    val createdAt: Long,
    val chargingDuring: Boolean,
    val idleMa: Double?,
    val rows: List<MeasuredProfile>,
    /** Inference profiles chosen after measuring (HIGH → ECO). */
    val order: List<String> = emptyList(),
) {
    fun rows(key: String) = rows.filter { it.chiplet == key }
    fun row(key: String, label: String) = rows.firstOrNull { it.chiplet == key && it.label == label }

    fun toJson(): JSONObject = JSONObject()
        .put("loom_profiles", 1)
        .put("model_id", modelId)
        .put("model", modelName)
        .put("device", device)
        .put("created_at", createdAt)
        .put("charging_during", chargingDuring)
        .put("idle_mA", idleMa ?: JSONObject.NULL)
        .put("order", JSONArray(order))
        .put("chiplets", JSONObject().also { o ->
            rows.groupBy { it.chiplet }.forEach { (k, rs) ->
                o.put(k, JSONArray(rs.map { r ->
                    JSONObject()
                        .put("label", r.label).put("level", r.level)
                        .put("p50_ms", r.p50Ms ?: JSONObject.NULL).put("p90_ms", r.p90Ms ?: JSONObject.NULL)
                        .put("throughput", r.throughput ?: JSONObject.NULL)
                        .put("mA", r.ma ?: JSONObject.NULL)
                        .put("heat_slope_per_min", r.heatSlopePerMin ?: JSONObject.NULL)
                        .put("quality", r.quality ?: JSONObject.NULL)
                        .put("note", r.note ?: JSONObject.NULL)
                }))
            }
        })

    fun save(f: File) = com.loom.studio.importer.ModelStore.atomicWrite(f, toJson().toString(2))

    companion object {
        private fun JSONObject.d(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }

        fun load(f: File): ProfileTable? = try {
            if (!f.exists()) null else {
                val j = JSONObject(f.readText())
                val rows = mutableListOf<MeasuredProfile>()
                val cs = j.getJSONObject("chiplets")
                for (k in cs.keys()) {
                    val a = cs.getJSONArray(k)
                    for (i in 0 until a.length()) {
                        val r = a.getJSONObject(i)
                        rows += MeasuredProfile(k, r.getString("label"), r.optString("level", "HIGH"),
                            r.d("p50_ms"), r.d("p90_ms"), r.d("throughput"), r.d("mA"), r.d("heat_slope_per_min"),
                            r.d("quality"), if (r.isNull("note")) null else r.optString("note"))
                    }
                }
                val order = j.optJSONArray("order")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                ProfileTable(j.optString("model_id"), j.optString("model"), j.optString("device"),
                    j.optLong("created_at"), j.optBoolean("charging_during"), j.d("idle_mA"), rows, order)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
