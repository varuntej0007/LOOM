package com.loom.studio.telemetry

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Device-level signals sampled once per second off the frame thread.
 *
 * - Thermal status is an Android CATEGORY, not a temperature. Never present it as °C.
 * - Thermal headroom: 1.0 ≈ the point where throttling is expected. Returns NaN on the first
 *   call or when polled too often; we keep the last valid value and show "—" until we have one.
 * - Battery current: BATTERY_PROPERTY_CURRENT_NOW, logged RAW. Sign and units (µA vs mA)
 *   vary by OEM — calibrate on the iQOO 15 before charting energy.
 */
class DeviceSignals(private val context: Context) {

    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val sampler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "loom-signals") }

    @Volatile var thermalStatus: Int = power.currentThermalStatus
        private set
    @Volatile var thermalHeadroom: Float = Float.NaN
        private set
    /** Android's own forecast of headroom 10 s from now (last valid value). */
    @Volatile var thermalForecast: Float = Float.NaN
        private set
    private var tickNo = 0L
    @Volatile var batteryCurrentRaw: Long = Long.MIN_VALUE
        private set
    @Volatile var airplaneMode: Boolean = false
        private set
    @Volatile var batteryPct: Int = -1
        private set
    @Volatile var charging: Boolean = false
        private set

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { thermalStatus = it }

    fun start() {
        power.addThermalStatusListener(context.mainExecutor, thermalListener)
        sampler.scheduleWithFixedDelay(::sample, 0, 1, TimeUnit.SECONDS)
    }

    private fun sample() {
        // getThermalHeadroom() returns NaN if polled too often, so alternate the two queries:
        // current headroom on even seconds, the 10-second forecast on odd seconds.
        try {
            if (tickNo++ % 2 == 0L) {
                val h = power.getThermalHeadroom(0)
                if (!h.isNaN()) thermalHeadroom = h
            } else {
                val f = power.getThermalHeadroom(FORECAST_SECONDS)
                if (!f.isNaN()) thermalForecast = f
            }
        } catch (_: Throwable) { }
        try {
            batteryCurrentRaw = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            batteryPct = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            charging = battery.isCharging
        } catch (_: Throwable) { }
        airplaneMode = Settings.Global.getInt(
            context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0
        ) == 1
    }

    fun stop() {
        power.removeThermalStatusListener(thermalListener)
        sampler.shutdownNow()
    }

    companion object {
        const val FORECAST_SECONDS = 10

        fun thermalName(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN($status)"
        }
    }
}
