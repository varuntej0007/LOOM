package com.loom.runtime

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Range
import android.util.Size
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import com.loom.runtime.chiplets.CaptureChiplet
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.loom.runtime.core.Mode
import com.loom.runtime.core.StressMode
import com.loom.runtime.telemetry.DeviceSignals
import com.loom.runtime.telemetry.TelemetryBus
import com.loom.runtime.ui.LoomControls
import com.loom.runtime.ui.LoomIntro
import com.loom.runtime.ui.LoomScreen
import com.loom.runtime.ui.PermissionScreen
import com.loom.runtime.weaver.Weaver
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_CONTROL = "com.loom.runtime.CONTROL"
    }

    /** Single-threaded on purpose: the top of each analyze() call is LOOM's safe boundary. */
    private lateinit var analyzerExecutor: ExecutorService
    private lateinit var signals: DeviceSignals
    private lateinit var telemetry: TelemetryBus
    private lateinit var stress: StressLoad
    private lateinit var pipeline: LoomPipeline
    private lateinit var weaver: Weaver

    private var hasCamera by mutableStateOf(false)
    private var voiceOn by mutableStateOf(true)
    private var showEngineering by mutableStateOf(false)
    /** The L∞M launch animation, once per app start (not on returning from the background). */
    private var showIntro by mutableStateOf(true)
    private var controlReceiver: BroadcastReceiver? = null
    private var cameraBound = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCamera = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        analyzerExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "loom-analyzer") }
        signals = DeviceSignals(this).also { it.start() }
        telemetry = TelemetryBus(getExternalFilesDir(null) ?: filesDir, signals)
        lateinit var post: (String) -> Unit
        stress = StressLoad(this) { msg -> post(msg) }
        pipeline = LoomPipeline(this, telemetry, stress).also { it.start() }
        post = pipeline.postEvent
        weaver = Weaver(pipeline, telemetry, signals, stress).also { it.start() }

        val controls = LoomControls(
            onMode = weaver::setMode,
            onStress = weaver::setStress,
            onTarget = weaver::setTarget,
            onHeat = weaver::setHeatCeiling,
            onPacing = weaver::setPacing,
            onRun = weaver::toggleRun,
            onVoice = { on -> voiceOn = on; pipeline.narrator.voiceOn = on },
            onInference = { weaver.manual(pipeline.inference, it) },
            onCapture = { weaver.manual(pipeline.capture, it) },
            onNarrator = { weaver.manual(pipeline.narrator, it) },
        )

        registerLaptopControl()

        // Display chiplet → real screen brightness (a major power and heat draw).
        lifecycleScope.launch {
            pipeline.display.brightness.collect { b ->
                window.attributes = window.attributes.apply {
                    screenBrightness = b ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }

        hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    if (hasCamera) {
                        val state by telemetry.state.collectAsStateWithLifecycle()
                        val weaverState by weaver.state.collectAsStateWithLifecycle()
                        val frame by pipeline.display.frames.collectAsStateWithLifecycle()
                        LoomScreen(
                            state = state,
                            weaver = weaverState,
                            frame = frame,
                            sentence = pipeline.narrator.lastSentence,
                            voiceOn = voiceOn,
                            levels = Triple(pipeline.inference.level, pipeline.capture.level, pipeline.narrator.level),
                            controls = controls,
                            showEngineering = showEngineering,
                            onToggleEngineering = { showEngineering = !showEngineering },
                            onPreviewReady = ::bindCamera,
                        )
                    } else {
                        PermissionScreen { permissionLauncher.launch(Manifest.permission.CAMERA) }
                    }
                    // Camera and detector start underneath while the logo plays.
                    if (showIntro) LoomIntro(onDone = { showIntro = false })
                }
            }
        }
    }

    private fun bindCamera(previewView: PreviewView) {
        if (cameraBound) return
        cameraBound = true
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            try {
                bindUseCases(provider, previewView, lockFps = true)
            } catch (t: Throwable) {
                // Some camera configurations reject a fixed 30 FPS range; fall back to auto.
                Log.w("LOOM", "30 FPS lock rejected, using auto frame rate", t)
                try {
                    bindUseCases(provider, previewView, lockFps = false)
                } catch (t2: Throwable) {
                    Log.e("LOOM", "camera bind failed", t2)
                    cameraBound = false
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Indoors, auto-exposure lowers the camera to ~15 FPS, which caps processed FPS below the
     * 20 FPS goal no matter what the Weaver does. Ask the camera for a fixed 30 FPS range.
     */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun <T : Any> withFps(builder: T, lockFps: Boolean): T {
        when (builder) {
            is Preview.Builder -> {
                val ext = Camera2Interop.Extender(builder)
                if (lockFps) ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                // Count every frame the sensor produces: the true camera FPS.
                ext.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        telemetry.sensorFrames.incrementAndGet()
                    }
                })
            }
            is ImageAnalysis.Builder -> if (lockFps) {
                Camera2Interop.Extender(builder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
            }
        }
        return builder
    }

    private fun bindUseCases(provider: ProcessCameraProvider, previewView: PreviewView, lockFps: Boolean) {
        run {
            // Preview and analysis share 4:3 so overlay boxes line up with the preview.
            val aspect = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            // Preview capped at ~1280x960: the camera pipeline and screen composition do less work.
            val preview = withFps(Preview.Builder(), lockFps)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(aspect)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 960),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            )
                        )
                        .build()
                )
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = withFps(ImageAnalysis.Builder(), lockFps)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(aspect)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                // Backpressure: drop stale frames instead of queueing (blueprint §6).
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analyzerExecutor, pipeline)

            provider.unbindAll()
            val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            pipeline.postEvent(if (lockFps) "Camera: 30 FPS requested" else "Camera: auto frame rate")
            if (lockFps) attachCameraRate(camera)
        }
    }

    /**
     * Give the Capture chiplet a real hardware lever: the sensor frame rate. Lower rates are
     * requested on the running camera (no rebind, the preview keeps going), so frames LOOM does
     * not need are never captured at all — the sensor and image processor do less work.
     * Only fixed-ish ranges the camera itself lists are used; if there are none below 30 FPS,
     * frames are skipped in software, exactly as before.
     */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun attachCameraRate(camera: Camera) {
        val full = CaptureChiplet.FULL_RATE
        val ranges: List<Range<Int>> = try {
            Camera2CameraInfo.from(camera.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.toList() ?: emptyList()
        } catch (t: Throwable) {
            Log.w("LOOM", "cannot read camera frame-rate ranges", t)
            emptyList()
        }
        Log.i("LOOM", "camera fps ranges: " + ranges.joinToString { "[${it.lower},${it.upper}]" })
        // For each top rate, the most fixed range (highest lower bound): steady frame timing,
        // and auto-exposure can't wander far below it.
        val byTop: Map<Int, Range<Int>> = ranges
            .filter { it.upper in 5..full }
            .groupBy { it.upper }
            .mapValues { (_, rs) -> rs.maxBy { it.lower } }
        val usable = byTop.filterKeys { it < full }
        if (usable.isEmpty()) {
            pipeline.postEvent("Camera lists no frame rates below $full FPS → frames skipped in software")
            return
        }
        val control = Camera2CameraControl.from(camera.cameraControl)
        val main = ContextCompat.getMainExecutor(this)
        pipeline.capture.attachHardware(object : CaptureChiplet.HardwareRate {
            override val rates: List<Int> = (usable.keys + full).sorted()

            override fun request(fps: Int, done: (Boolean) -> Unit) {
                val range = if (fps >= full) Range(full, full) else usable[fps]
                if (range == null) {
                    done(false)
                    return
                }
                main.execute {
                    try {
                        val future = control.setCaptureRequestOptions(
                            CaptureRequestOptions.Builder()
                                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                                .build()
                        )
                        future.addListener({
                            val ok = try {
                                future.get(); true
                            } catch (t: Throwable) {
                                Log.w("LOOM", "camera rate $fps rejected", t); false
                            }
                            done(ok)
                        }, main)
                    } catch (t: Throwable) {
                        Log.w("LOOM", "camera rate $fps failed", t)
                        done(false)
                    }
                }
            }
        })
        pipeline.postEvent(
            "Camera frame-rate lever ready: " + usable.entries.sortedBy { it.key }
                .joinToString { (f, r) -> "$f FPS [${r.lower},${r.upper}]" }
        )
    }

    /**
     * Demo control from the laptop dashboard over USB, so nobody has to touch the phone:
     *   adb shell am broadcast -a com.loom.runtime.CONTROL -p com.loom.runtime --es cmd stress --es value gpu
     * Commands: stress off|gpu|cpu · mode adaptive|fixed · goal <fps> · heat off|<0.xx>
     *           pacing on|off · run toggle · voice on|off · overlay on|off|toggle
     * Only demo settings are reachable; nothing here reads or sends data.
     */
    private fun registerLaptopControl() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val cmd = intent.getStringExtra("cmd") ?: return
                val value = intent.getStringExtra("value") ?: ""
                when (cmd) {
                    "stress" -> StressMode.entries.firstOrNull { it.name.equals(value, true) }?.let(weaver::setStress)
                    "mode" -> when (value.lowercase()) {
                        "adaptive" -> weaver.setMode(Mode.ADAPTIVE)
                        "fixed" -> weaver.setMode(Mode.FIXED)
                    }
                    "goal" -> value.toDoubleOrNull()?.takeIf { it in 1.0..60.0 }?.let(weaver::setTarget)
                    "heat" -> if (value.equals("off", true)) weaver.setHeatCeiling(null)
                        else value.toFloatOrNull()?.takeIf { it in 0.3f..1.2f }?.let(weaver::setHeatCeiling)
                    "pacing" -> weaver.setPacing(value.equals("on", true))
                    "run" -> weaver.toggleRun()
                    "voice" -> { voiceOn = value.equals("on", true); pipeline.narrator.voiceOn = voiceOn }
                    "overlay" -> showEngineering = when (value.lowercase()) {
                        "on" -> true
                        "off" -> false
                        else -> !showEngineering
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(ACTION_CONTROL), ContextCompat.RECEIVER_EXPORTED,
        )
        controlReceiver = receiver
    }

    override fun onDestroy() {
        super.onDestroy()
        controlReceiver?.let { unregisterReceiver(it) }
        weaver.close()
        signals.stop()
        stress.close()
        // Close on the analyzer thread so no frame is mid-inference, then flush the CSV.
        analyzerExecutor.execute {
            pipeline.close()
            telemetry.close()
        }
        analyzerExecutor.shutdown()
    }
}
