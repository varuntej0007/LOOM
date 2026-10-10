package com.loom.studio

import com.loom.studio.weaver.ExactPlanner
import com.loom.studio.weaver.PlannerModel
import com.loom.studio.weaver.PolicyTable
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Exact planner == brute force, and == app/'s QAOA-verified policy on the same model (brief §14). */
class PlannerTest {
    private fun repo(path: String): File = listOf(File(path), File("../$path"), File("../../$path")).first { it.exists() }

    @Test fun reproducesTheVerifiedQaoaPolicyOfTheCameraApp() {
        val m = PlannerModel.fromJson(JSONObject(repo("quantum/loom_model.json").readText()))
        val mine = ExactPlanner.solve(m, "test")
        val theirs = PolicyTable.parse(repo("app/src/main/assets/loom_policy.json").readText())
        assertEquals(theirs.plans.size, mine.plans.size)
        for (t in theirs.plans) {
            val p = mine.plans.first { it.goal == t.goal && it.bucket == t.bucket }
            assertEquals("goal ${t.goal} ${t.bucket}", t.levels, p.levels)
            assertEquals(t.predictedMa, p.predictedMa, 1.0)
        }
    }

    @Test fun exactEqualsIndependentBruteForce() {
        val m = PlannerModel.fromJson(JSONObject(repo("quantum/loom_model.json").readText()))
        val plans = ExactPlanner.solve(m, "test").plans
        for (p in plans) {
            val b = m.buckets.first { it.name == p.bucket }
            val budget = maxOf(m.heatBudgetMa(b.planAt), m.configs().minOf { m.power(it) })
            val best = m.configs().filter { m.power(it) <= budget + m.powerUnit }.minOf { m.objective(it, p.goal) }
            assertTrue("${p.goal}/${p.bucket}", m.objective(p.levels, p.goal) <= best + 1e-9)
        }
    }

    @Test fun lookupPicksGoalAndHeatBand() {
        val t = PolicyTable.parse(repo("app/src/main/assets/loom_policy.json").readText())
        val p = t.lookup(15.0, 0.72f)!!
        assertEquals(15.0, p.goal, 1e-9)
        assertEquals("warm", p.bucket)
        assertEquals("quantum (QAOA), verified", t.sourceLabel)
    }
}
