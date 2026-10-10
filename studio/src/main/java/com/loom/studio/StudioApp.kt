package com.loom.studio

import android.app.Application
import com.loom.studio.telemetry.TelemetryBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide singletons. Single-activity app; the foreground [service.RunService] owns the
 * device sensors and the 1 Hz loop, the UI only observes these flows.
 */
class StudioApp : Application() {

    lateinit var telemetry: TelemetryBus
        private set
    lateinit var runtime: StudioRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        telemetry = TelemetryBus(this)
        runtime = StudioRuntime(this, telemetry)
    }

    companion object {
        lateinit var instance: StudioApp
            private set

        internal val serviceRunningFlow = MutableStateFlow(false)
        /** True while the foreground run service is alive (drives the UI switch and pill). */
        val serviceRunning: StateFlow<Boolean> = serviceRunningFlow
    }
}
