package com.loom.studio.ui

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.loom.studio.chiplets.CameraSource
import com.loom.studio.pipeline.VisionPipeline
import java.util.concurrent.Executors

/**
 * Camera preview + analysis for a [VisionPipeline], bound to the screen's lifecycle.
 * Copied from app/: 4:3 preview ≤1280×960, analysis ~640×480 RGBA with KEEP_ONLY_LATEST,
 * a 30 FPS lock, a sensor-frame counter, and the real Camera2 frame-rate lever for CameraSource.
 */
@Composable
fun CameraHost(pipeline: VisionPipeline, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor { r -> Thread(r, "loom-analyzer") } }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(pipeline) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            try {
                bind(p, owner, previewView, pipeline, executor, lockFps = true)
            } catch (t: Throwable) {
                Log.w("LOOM", "30 FPS lock rejected, using auto frame rate", t)
                try {
                    bind(p, owner, previewView, pipeline, executor, lockFps = false)
                } catch (t2: Throwable) {
                    Log.e("LOOM", "camera bind failed", t2)
                }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            try { provider?.unbindAll() } catch (_: Throwable) { }
        }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(factory = { previewView }, modifier = modifier)
}

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
private fun bind(
    provider: ProcessCameraProvider,
    owner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    pipeline: VisionPipeline,
    executor: java.util.concurrent.Executor,
    lockFps: Boolean,
) {
    val aspect = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
    val previewBuilder = Preview.Builder().setResolutionSelector(
        ResolutionSelector.Builder().setAspectRatioStrategy(aspect)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
    )
    val pext = Camera2Interop.Extender(previewBuilder)
    if (lockFps) pext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
    pext.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            pipeline.camera.sensorFrames.incrementAndGet()
        }
    })
    val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

    val analysisBuilder = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder().setAspectRatioStrategy(aspect)
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    if (lockFps) Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
    val analysis = analysisBuilder.build()
    analysis.setAnalyzer(executor, pipeline)

    provider.unbindAll()
    val camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    if (lockFps) attachRate(camera, pipeline.camera, ContextCompat.getMainExecutor(previewView.context))
}

/** Give CameraSource the real sensor frame-rate lever (no rebind; the preview keeps running). */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
private fun attachRate(camera: Camera, source: CameraSource, main: java.util.concurrent.Executor) {
    val full = CameraSource.FULL_RATE
    val ranges: List<Range<Int>> = try {
        Camera2CameraInfo.from(camera.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }
    val byTop = ranges.filter { it.upper in 5..full }.groupBy { it.upper }.mapValues { (_, rs) -> rs.maxBy { it.lower } }
    val usable = byTop.filterKeys { it < full }
    if (usable.isEmpty()) return
    val control = Camera2CameraControl.from(camera.cameraControl)
    source.attachHardware(object : CameraSource.HardwareRate {
        override val rates: List<Int> = (usable.keys + full).sorted()
        override fun request(fps: Int, done: (Boolean) -> Unit) {
            val range = if (fps >= full) Range(full, full) else usable[fps]
            if (range == null) { done(false); return }
            main.execute {
                try {
                    val f = control.setCaptureRequestOptions(
                        CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range).build()
                    )
                    f.addListener({
                        val ok = try { f.get(); true } catch (t: Throwable) { false }
                        done(ok)
                    }, main)
                } catch (t: Throwable) {
                    done(false)
                }
            }
        }
    })
}
