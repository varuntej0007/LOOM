package com.loom.studio.weaver

import android.os.Build
import android.os.PerformanceHintManager
import android.util.Log
import com.loom.studio.StudioApp

/**
 * Android Dynamic Performance Framework (API 31+): tells the CPU governor how long each
 * inference SHOULD take (target) and how long it DID take (actual). With a relaxed target the
 * governor can keep the inference thread on lower clocks — a cooling lever an ordinary app is
 * allowed to use. No-op below Android 12 or when the phone does not support hint sessions.
 */
class Adpf {
    @Volatile private var session: PerformanceHintManager.Session? = null
    @Volatile private var targetNs = 0L
    @Volatile var active = false
        private set

    @Synchronized
    fun attach(tid: Int, targetMs: Double) {
        close()
        if (tid <= 0 || Build.VERSION.SDK_INT < 31) return
        try {
            val phm = StudioApp.instance.getSystemService(PerformanceHintManager::class.java) ?: return
            if (phm.preferredUpdateRateNanos < 0) return
            targetNs = (targetMs * 1e6).toLong()
            session = phm.createHintSession(intArrayOf(tid), targetNs)
            active = session != null
        } catch (t: Throwable) {
            Log.w("LOOM", "ADPF unavailable", t)
            session = null
            active = false
        }
    }

    /** New per-unit time budget, e.g. 1000 / goal FPS. */
    fun setTarget(targetMs: Double) {
        val ns = (targetMs * 1e6).toLong().coerceAtLeast(1_000_000L)
        if (ns == targetNs) return
        targetNs = ns
        try { session?.updateTargetWorkDuration(ns) } catch (_: Throwable) { }
    }

    fun report(actualMs: Double) {
        val s = session ?: return
        try { s.reportActualWorkDuration((actualMs * 1e6).toLong().coerceAtLeast(1L)) } catch (_: Throwable) { }
    }

    @Synchronized
    fun close() {
        try { session?.close() } catch (_: Throwable) { }
        session = null
        active = false
    }
}
