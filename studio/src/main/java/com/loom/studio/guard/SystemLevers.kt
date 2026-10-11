package com.loom.studio.guard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Display

/**
 * The device-wide levers LOOM Guard pulls. Every original value is saved before the first
 * change (in prefs, so a crash or reboot can still restore it) and written back on release.
 *
 *  - BRIGHTNESS needs "Modify system settings" (WRITE_SETTINGS app-op: user toggle, or adb).
 *  - REFRESH and SAVER need WRITE_SECURE_SETTINGS, granted once over USB by build_studio.bat:
 *      adb shell pm grant com.loom.studio android.permission.WRITE_SECURE_SETTINGS
 *    It is a development permission, which is why there's no root and no system image change.
 *
 * Each apply() reports whether the phone really took it (e.g. a vendor ROM may ignore the
 * AOSP refresh-rate key), so the dashboard shows what happened rather than what was attempted.
 */
class SystemLevers(private val ctx: Context, private val log: (String) -> Unit) {

    private val cr = ctx.contentResolver
    private val prefs = ctx.getSharedPreferences("loom_guard", Context.MODE_PRIVATE)

    val canWriteSettings: Boolean get() = Settings.System.canWrite(ctx)
    val hasSecure: Boolean get() =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun available(l: GuardCore.Lever): Boolean = when (l) {
        GuardCore.Lever.BRIGHTNESS -> canWriteSettings || hasSecure
        GuardCore.Lever.REFRESH, GuardCore.Lever.SAVER -> hasSecure
    }

    /** Lever names currently applied by Guard (persisted, for crash recovery). */
    val applied: Set<String> get() = prefs.getStringSet("applied", emptySet()) ?: emptySet()
    private fun mark(l: GuardCore.Lever, on: Boolean) {
        val s = applied.toMutableSet()
        if (on) s += l.name else s -= l.name
        prefs.edit().putStringSet("applied", s).apply()
    }

    /** Turn a lever on. Returns a short result for the event log. */
    fun apply(l: GuardCore.Lever): String {
        if (!available(l)) return "${l.label}: skipped (permission not granted)"
        return try {
            when (l) {
                GuardCore.Lever.BRIGHTNESS -> {
                    if (l.name !in applied) {
                        prefs.edit()
                            .putInt("bri_mode", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0))
                            .putInt("bri", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128))
                            .apply()
                    }
                    val orig = prefs.getInt("bri", 128)
                    val target = (orig * 0.6f).toInt().coerceAtLeast(MIN_BRIGHTNESS)
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
                    mark(l, true)
                    "brightness $orig → $target"
                }
                GuardCore.Lever.REFRESH -> {
                    if (l.name !in applied) {
                        prefs.edit()
                            .putString("peak", Settings.System.getString(cr, KEY_PEAK) ?: NONE)
                            .putString("min", Settings.System.getString(cr, KEY_MIN) ?: NONE)
                            .apply()
                    }
                    Settings.System.putString(cr, KEY_PEAK, "60.0")
                    val min = prefs.getString("min", NONE)
                    if (min != NONE && (min?.toFloatOrNull() ?: 0f) > 60f) Settings.System.putString(cr, KEY_MIN, "60.0")
                    mark(l, true)
                    "peak refresh → 60 Hz (was ${prefs.getString("peak", NONE).let { if (it == NONE) "default" else it }})"
                }
                GuardCore.Lever.SAVER -> {
                    if (l.name !in applied) prefs.edit().putInt("saver", Settings.Global.getInt(cr, KEY_SAVER, 0)).apply()
                    Settings.Global.putInt(cr, KEY_SAVER, 1)
                    mark(l, true)
                    "battery saver on"
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "apply $l", t)
            "${l.label}: failed (${t.javaClass.simpleName}: ${t.message})"
        }
    }

    /** Turn a lever off, restoring the saved original value. */
    fun release(l: GuardCore.Lever): String {
        if (l.name !in applied) return "${l.label}: nothing to restore"
        return try {
            when (l) {
                GuardCore.Lever.BRIGHTNESS -> {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, prefs.getInt("bri", 128))
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, prefs.getInt("bri_mode", 0))
                    "brightness restored"
                }
                GuardCore.Lever.REFRESH -> {
                    restoreSystem(KEY_PEAK, prefs.getString("peak", NONE))
                    restoreSystem(KEY_MIN, prefs.getString("min", NONE))
                    "refresh rate restored"
                }
                GuardCore.Lever.SAVER -> {
                    Settings.Global.putInt(cr, KEY_SAVER, prefs.getInt("saver", 0))
                    "battery saver restored"
                }
            }.also { mark(l, false) }
        } catch (t: Throwable) {
            Log.w(TAG, "release $l", t)
            "${l.label}: restore failed (${t.message})"
        }
    }

    /** Undo everything Guard left on (service stop, Guard off, or recovery after a crash). */
    fun releaseAll(): List<String> =
        GuardCore.Lever.entries.reversed().filter { it.name in applied }.map { release(it) }

    private fun restoreSystem(key: String, v: String?) {
        if (v == null || v == NONE) {
            // The key did not exist before: put back the panel's top rate (the phone's default).
            val top = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                ?.supportedModes?.maxOfOrNull { it.refreshRate } ?: return
            if (key == KEY_PEAK) Settings.System.putString(cr, key, top.toString())
        } else {
            Settings.System.putString(cr, key, v)
        }
    }

    /** What the phone is really doing right now (for "did it take?" checks). */
    fun observedRefreshHz(): Float =
        ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: Float.NaN

    fun saverOn(): Boolean = ctx.getSystemService(PowerManager::class.java).isPowerSaveMode

    companion object {
        private const val TAG = "LOOM"
        private const val NONE = "<none>"
        const val KEY_PEAK = "peak_refresh_rate"
        const val KEY_MIN = "min_refresh_rate"
        const val KEY_SAVER = "low_power"
        private const val MIN_BRIGHTNESS = 20
    }
}
