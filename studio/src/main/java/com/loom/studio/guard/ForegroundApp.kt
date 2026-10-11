package com.loom.studio.guard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Which app is on screen (for the log and dashboard only: "cooling while com.game runs").
 * Needs "Usage access" (GET_USAGE_STATS app-op: user toggle, or adb appops). Reads only the
 * package name of the last resumed activity; nothing else from the other app.
 */
class ForegroundApp(private val ctx: Context) {

    private val usm = ctx.getSystemService(UsageStatsManager::class.java)
    private var last: String? = null
    private var lastQuery = 0L

    val allowed: Boolean get() = try {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    /** Package in front, or null if unknown / not allowed. Queried at most every 2 s. */
    fun current(now: Long = System.currentTimeMillis()): String? {
        if (now - lastQuery < 2_000) return last
        lastQuery = now
        if (!allowed) return null.also { last = null }
        try {
            val ev = usm.queryEvents(now - 60_000, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
            }
        } catch (_: Throwable) { }
        return last
    }
}
