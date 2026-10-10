package com.loom.studio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.loom.studio.MainActivity
import com.loom.studio.R
import com.loom.studio.StudioApp
import com.loom.studio.StudioRuntime
import com.loom.studio.telemetry.DeviceSignals
import com.loom.studio.telemetry.TelemetryBus
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service that hosts LOOM Studio's long-running work so Android does not kill it.
 *
 * M0: owns [DeviceSignals] and emits one LOOM_LIVE line per second (heat, forecast, thermal
 * status, battery current / % / charging), with or without the UI on screen and with the
 * screen off (a partial wake lock keeps the 1 Hz loop running). From M2 it also hosts the
 * pipeline and the Weaver, and its 1 Hz tick becomes the Weaver's sense step.
 */
class RunService : Service() {

    private var signals: DeviceSignals? = null
    private var loop: ScheduledExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var ticks = 0L
    private var control: BroadcastReceiver? = null

    /**
     * Laptop dashboard control over USB (same commands as app/, Studio's own action):
     *   adb shell am broadcast -a com.loom.studio.CONTROL -p com.loom.studio --es cmd stress --es value gpu
     * Only demo settings are reachable; nothing here reads or sends data.
     */
    private fun registerControl() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val cmd = intent.getStringExtra("cmd") ?: return
                StudioApp.instance.runtime.control(cmd, intent.getStringExtra("value") ?: "")
            }
        }
        ContextCompat.registerReceiver(this, r, IntentFilter(StudioRuntime.ACTION_CONTROL), ContextCompat.RECEIVER_EXPORTED)
        control = r
    }

    private val telemetry: TelemetryBus get() = StudioApp.instance.telemetry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop != null) return START_STICKY // already running

        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification("Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (t: Throwable) {
            // E.g. ForegroundServiceStartNotAllowedException if started from the background.
            Log.e(TAG, "startForeground failed", t)
            stopSelf()
            return START_NOT_STICKY
        }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LOOM:studio-run")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MAX_MS)
            }

        // Debug builds only: keep the Wi-Fi radio awake with the screen off, so adb over Wi-Fi
        // (the laptop dashboard) does not drop. Costs a little battery, so never in release.
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try {
                @Suppress("DEPRECATION")
                wifiLock = getSystemService(android.net.wifi.WifiManager::class.java)
                    ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LOOM:studio-adb")
                    ?.apply { setReferenceCounted(false); acquire() }
            } catch (t: Throwable) {
                Log.w(TAG, "wifi lock", t)
            }
        }

        val s = DeviceSignals(applicationContext).also { it.start() }
        signals = s
        StudioApp.instance.runtime.signals = s
        registerControl()
        loop = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "loom-studio-tick") }.also {
            it.scheduleWithFixedDelay(::tick, 1, 1, TimeUnit.SECONDS)
        }
        StudioApp.serviceRunningFlow.value = true
        telemetry.event("Studio run service started (telemetry 1 Hz)")
        Log.i(TAG, "run service started")
        return START_STICKY
    }

    private fun tick() {
        val s = signals ?: return
        try {
            StudioApp.instance.runtime.tick(s)
        } catch (t: Throwable) {
            Log.e(TAG, "tick", t)
        }
        // Refresh the notification text every 5 s (cheap, and readable on the lock screen).
        if (ticks++ % 5 == 0L) {
            try {
                val l = telemetry.live.value
                val heat = if (l.heat.isNaN()) "—" else String.format(Locale.US, "%.2f", l.heat)
                val ma = l.batteryUa?.let { String.format(Locale.US, "%d mA", it / 1000) } ?: "— mA"
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification("Heat $heat · $ma · ${l.thermalStatus} · ${l.run.status}"))
            } catch (_: Throwable) { }
        }
    }

    override fun onDestroy() {
        loop?.shutdownNow()
        loop = null
        control?.let { try { unregisterReceiver(it) } catch (_: Throwable) { } }
        control = null
        StudioApp.instance.runtime.signals = null
        signals?.stop()
        signals = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        StudioApp.serviceRunningFlow.value = false
        telemetry.event("Studio run service stopped")
        Log.i(TAG, "run service stopped")
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "LOOM Studio runs", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while LOOM Studio hosts a model or streams telemetry"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RunService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("LOOM Studio")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "LOOM"
        private const val CHANNEL_ID = "loom_studio_run"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_STOP = "com.loom.studio.action.STOP"
        /** Safety cap on the wake lock; a run longer than this re-acquires on next start. */
        private const val WAKE_LOCK_MAX_MS = 4L * 60 * 60 * 1000

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RunService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RunService::class.java))
        }
    }
}
