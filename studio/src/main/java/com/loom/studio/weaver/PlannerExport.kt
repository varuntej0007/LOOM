package com.loom.studio.weaver

import com.loom.studio.chiplets.CameraSource
import com.loom.studio.core.TaskKind
import com.loom.studio.pipeline.IntervalSource
import com.loom.studio.pipeline.LlmPipeline
import com.loom.studio.pipeline.Pipeline
import com.loom.studio.profiler.ProfileTable
import kotlin.math.roundToInt

/** Per-second samples of a run (heat headroom, battery mA) for the thermal-model fit. */
class RunHistory(private val cap: Int = 7200) {
    data class S(val t: Double, val h: Double, val ma: Double)
    private val buf = ArrayDeque<S>()
    @Synchronized fun add(s: S) { buf.addLast(s); if (buf.size > cap) buf.removeFirst() }
    @Synchronized fun all(): List<S> = buf.toList()

    /**
     * Least-squares fit of dh/dt = a·P − b·(h − h_idle) on 60 s windows (same model as
     * quantum/calibrate.py). Null when there is not enough data or the fit is not physical.
     */
    fun fit(): PlannerModel.Thermal? {
        val s = all().filter { !it.h.isNaN() && it.ma > 0 }
        if (s.size < 600) return null
        val rows = s.chunked(60).filter { it.size >= 50 }.map { w ->
            val dt = w.last().t - w.first().t
            Triple((w.last().h - w.first().h) / dt, w.map { it.ma }.average(), w.map { it.h }.average())
        }
        if (rows.size < 10) return null
        // y = a*P + (-b)*h + c  → normal equations
        val x = rows.map { doubleArrayOf(it.second, it.third, 1.0) }
        val y = rows.map { it.first }
        val a = Array(3) { DoubleArray(3) }
        val v = DoubleArray(3)
        for (i in x.indices) for (r in 0..2) { v[r] += x[i][r] * y[i]; for (c in 0..2) a[r][c] += x[i][r] * x[i][c] }
        val sol = solve3(a, v) ?: return null
        val aP = sol[0]; val b = -sol[1]; val c = sol[2]
        if (aP <= 0 || b <= 0) return null
        val hIdle = c / b
        if (hIdle !in 0.2..1.2) return null
        return PlannerModel.Thermal(aP, b, hIdle, "fitted on this phone: ${rows.size} one-minute windows of Studio runs")
    }

    private fun solve3(m: Array<DoubleArray>, v: DoubleArray): DoubleArray? {
        val a = Array(3) { r -> DoubleArray(4) { c -> if (c < 3) m[r][c] else v[r] } }
        for (col in 0..2) {
            val piv = (col..2).maxBy { kotlin.math.abs(a[it][col]) }
            if (kotlin.math.abs(a[piv][col]) < 1e-18) return null
            val t = a[col]; a[col] = a[piv]; a[piv] = t
            for (r in 0..2) if (r != col) {
                val f = a[r][col] / a[col][col]
                for (c in col..3) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(3) { a[it][3] / a[it][it] }
    }
}

/** Builds the planning problem for one model from its measured profiles.json. */
object PlannerExport {
    private fun r10(v: Double) = (v / 10.0).roundToInt() * 10

    fun build(pipeline: Pipeline, table: ProfileTable, history: RunHistory?, device: String, heatLimit: Float, goal: Double): PlannerModel {
        val chiplets = LinkedHashMap<String, Map<String, PlannerModel.PLevel>>()
        val keys = LinkedHashMap<String, String>()
        val inf = table.rows("inference").filter { it.ma != null }
        val main = pipeline.main
        val highRow = inf.firstOrNull { it.label == main.profiles.first().label } ?: inf.maxByOrNull { it.ma!! }
        val base = highRow?.ma ?: 1000.0
        val idle = table.idleMa ?: (base * 0.6)
        val highRate = highRow?.throughput ?: 0.0

        for (c in pipeline.chiplets.filter { it.adaptive }) {
            val levels = LinkedHashMap<String, PlannerModel.PLevel>()
            c.profiles.forEachIndexed { i, p ->
                val lv = p.level.name
                val pl: PlannerModel.PLevel = when {
                    c.key == "inference" -> {
                        val row = inf.firstOrNull { it.label == p.label }
                        PlannerModel.PLevel(p.label, r10((row?.ma ?: base) - base), ((1 - (row?.quality ?: 1.0)) * 20).coerceAtLeast(0.0), row?.throughput)
                    }
                    c is CameraSource -> {
                        val rows = table.rows("source")
                        val row = rows.firstOrNull { it.label == p.label }
                        val top = rows.firstOrNull()?.ma
                        val fps = (p.knobs["fps"] as? Int)?.toDouble()
                        PlannerModel.PLevel(p.label, if (row?.ma != null && top != null) r10(row.ma - top) else 0, listOf(0.0, 3.0, 6.0)[i], row?.throughput ?: fps)
                    }
                    c is IntervalSource -> {
                        val iv = (p.knobs["interval_ms"] as? Long) ?: 0L
                        PlannerModel.PLevel(p.label, 0, listOf(0.0, 2.0, 5.0)[i], if (iv > 0) 1000.0 / iv else null)
                    }
                    c.key == "rate" -> {
                        val cap = (p.knobs["cap"] as? Double)?.takeIf { it > 0 }
                        // Estimated: inference power scales with the share of time spent generating.
                        val est = if (cap != null && highRate > 0) r10((idle + (base - idle) * minOf(1.0, cap / highRate)) - base) else 0
                        PlannerModel.PLevel(p.label, est, listOf(0.0, 2.0, 4.0)[i], cap)
                    }
                    c.key == "display" -> PlannerModel.PLevel(p.label, listOf(0, -10, -30)[i], listOf(0.0, 1.0, 2.0)[i], null)
                    c.key == "voice" -> PlannerModel.PLevel(p.label, listOf(0, -20, -40)[i], listOf(0.0, 3.0, 8.0)[i], null)
                    c.key == "context" -> PlannerModel.PLevel(p.label, listOf(0, -10, -20)[i], listOf(0.0, 0.5, 2.0)[i], null)
                    c.key == "ui" -> PlannerModel.PLevel(p.label, listOf(0, -10, -20)[i], listOf(0.0, 0.5, 1.0)[i], null)
                    c.key == "stage2" -> PlannerModel.PLevel(p.label, listOf(0, -40, -80)[i], listOf(0.0, 2.0, 5.0)[i], null)
                    else -> PlannerModel.PLevel(p.label, 0, i.toDouble(), null)
                }
                levels[lv] = pl
            }
            chiplets[c.name] = levels
            keys[c.name] = c.key
        }
        val throughput = when {
            pipeline is LlmPipeline -> listOf("Inference", "Sampler")
            else -> listOf("Inference", "Capture")
        }.filter { it in chiplets }
        val goals = (when (pipeline.kind) {
            TaskKind.VISION -> listOf(10.0, 15.0, 20.0)
            TaskKind.LLM -> listOf(4.0, 8.0, 12.0)
            else -> listOf(2.0, 5.0, 10.0)
        } + goal).distinct().sorted()
        return PlannerModel(
            device = device, model = pipeline.card.displayName, heatLimit = heatLimit.toDouble(), goals = goals,
            thermal = history?.fit() ?: PlannerModel.DEFAULT_THERMAL, basePowerMa = base,
            chiplets = chiplets, throughput = throughput, keys = keys,
        )
    }
}
