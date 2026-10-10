package com.loom.studio.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loom.studio.StudioApp
import com.loom.studio.StudioRuntime
import com.loom.studio.chiplets.UnitResult
import com.loom.studio.pipeline.VisionPipeline
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max

/**
 * Screen 4 (brief §11): the task UI full-screen with the LOOM pill / Weaver panel.
 * Vision: camera preview + overlay. Generic: live numbers. [profile] = run the profiler.
 */
@Composable
fun RunScreen(active: StudioRuntime.Active, profile: Boolean, onBack: () -> Unit, onImportPolicy: () -> Unit) {
    val rt = StudioApp.instance.runtime
    val pipeline = active.pipeline
    val w by active.weaver.state.collectAsState()
    val result by pipeline.last.collectAsState()
    val profFlow by rt.profilerState.collectAsState()
    val pstate = profFlow?.collectAsState()?.value
    val compareRunner by rt.compare.collectAsState()
    val cstate = compareRunner?.state?.collectAsState()?.value
    var toast by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val activity = LocalContext.current as? Activity

    // Keep the screen on while a model runs; the Display chiplet sets real screen brightness.
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.window?.let { win -> win.attributes = win.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }
    pipeline.display?.let { d ->
        val b by d.brightness.collectAsState()
        LaunchedEffect(b) {
            activity?.window?.let { win -> win.attributes = win.attributes.apply { screenBrightness = b ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }
    if (profile) {
        LaunchedEffect(active) {
            while (!pipeline.ready) delay(200)
            delay(1500)
            rt.startProfiling()
        }
    }

    val chips = pipeline.chiplets.filter { it.adaptive }.map { c -> Triple(c.key, c.name, c.profiles.map { it.level }) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val vp = pipeline as? VisionPipeline
        val lp = pipeline as? com.loom.studio.pipeline.LlmPipeline
        when {
            vp != null -> {
                CameraHost(vp, Modifier.fillMaxSize())
                val disp by vp.display.result.collectAsState()
                Overlay(disp, vp.display.frameW, vp.display.frameH, Modifier.fillMaxSize())
            }
            lp != null -> ChatBody(lp, Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(top = 150.dp, start = 12.dp, end = 12.dp, bottom = 8.dp))
            else -> GenericPanel(result, Modifier.fillMaxSize().statusBarsPadding().padding(top = 150.dp, start = 16.dp, end = 16.dp))
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, fontSize = 32.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
                Column(Modifier.weight(1f)) {
                    Text(active.card.displayName, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("${active.card.kindLabel} · ${pipeline.main.profileLabel} · policy: ${w.policySource}", color = Color(0xFFB6C2D6), fontSize = 12.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            WeaverPill(w, pipeline.unitShort, chips,
                onMode = active.weaver::setMode, onManual = active.weaver::manual,
                extra = {
                    Text(w.planText?.let { "Plan: $it" } ?: "No plan yet — profile, then Plan", color = Color(0xFFB6C2D6), fontSize = 11.sp)
                    androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChipRow(listOf("Profile", "Plan (exact)", "Export for QAOA", "Import policy", "Compare 15 min", "Compare 2 min"), -1) { i ->
                            when (i) {
                                0 -> rt.startProfiling()
                                1 -> toast = rt.planExact()
                                2 -> toast = rt.exportForQuantum()
                                3 -> onImportPolicy()
                                4 -> rt.startCompare(15)
                                5 -> rt.startCompare(2)
                            }
                        }
                    }
                })
            toast?.let { t ->
                Spacer(Modifier.height(6.dp))
                Text(t, color = Color.White, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE61A5CFF)).clickable { toast = null }.padding(10.dp))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
            if (pstate != null && (pstate.running || pstate.result != null || pstate.error != null)) {
                ProfilerCard(pstate)
                Spacer(Modifier.height(8.dp))
            }
            if (cstate != null) {
                CompareCard(cstate, onCancel = rt::cancelCompare, onExport = { toast = rt.exportCompare() })
                Spacer(Modifier.height(8.dp))
            }
            result?.let { r -> if (r.labels.isNotEmpty() && vp != null) ResultStrip(r) }
        }
    }
}

@Composable
private fun ProfilerCard(p: com.loom.studio.profiler.ProfilerState) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xE60B1220)).padding(14.dp)
    ) {
        Text(if (p.running) "Profiling on this phone…" else if (p.error != null) "Profiling failed" else "Profiling done",
            color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth(), color = Loom.Cyan)
        Spacer(Modifier.height(6.dp))
        Text(p.error ?: p.step, color = Color(0xFFB6C2D6), fontSize = 12.sp)
        if (p.running) Text("Unplug the charger for honest mA numbers. Keep the camera on a normal scene.", color = Color(0xFF8A94A6), fontSize = 11.sp)
        p.result?.let { t ->
            Spacer(Modifier.height(6.dp))
            t.rows.filter { it.chiplet == "inference" || it.chiplet == "source" }.forEach { r ->
                Text(
                    "${r.chiplet.take(5)} ${r.label.padEnd(12).take(12)} " + listOfNotNull(
                        r.p50Ms?.let { String.format(Locale.US, "%.1fms", it) },
                        r.throughput?.let { String.format(Locale.US, "%.1f/s", it) },
                        r.ma?.let { String.format(Locale.US, "%.0fmA", it) },
                        r.quality?.let { String.format(Locale.US, "q%.2f", it) },
                    ).joinToString(" ").ifEmpty { r.note ?: "" },
                    color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun CompareCard(c: com.loom.studio.CompareRunner.State, onCancel: () -> Unit, onExport: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xE60B1220)).padding(14.dp)) {
        Text("Compare · ${c.phase.name}" + if (c.secondsLeft > 0) " · ${c.secondsLeft / 60}:${"%02d".format(c.secondsLeft % 60)} left" else "",
            color = Color.White, fontWeight = FontWeight.Bold)
        Text(c.note, color = Color(0xFFB6C2D6), fontSize = 12.sp)
        fun line(s: com.loom.studio.CompareRunner.Summary?) = s?.let {
            String.format(Locale.US, "%-8s %s · heat avg %.2f max %.2f · %s · %.1f/s · goal %.0f%% · q %s",
                it.phase, it.avgMa?.let { m -> String.format(Locale.US, "%.0f mA", m) } ?: "— mA", it.avgHeat, it.maxHeat, it.maxThermal,
                it.avgMetric, it.goalMetPct, it.avgQuality?.let { q -> String.format(Locale.US, "%.2f", q) } ?: "—")
        }
        line(c.fixed)?.let { Text(it, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        line(c.adaptive)?.let { Text(it, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.phase == com.loom.studio.CompareRunner.Phase.DONE) ChipRow(listOf("Save to Download/LOOM"), -1) { onExport() }
            else if (c.phase != com.loom.studio.CompareRunner.Phase.CANCELLED) ChipRow(listOf("Cancel"), -1) { onCancel() }
        }
    }
}

@Composable
private fun ResultStrip(r: UnitResult) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xCC0B1220)).padding(12.dp)) {
        r.labels.take(5).forEach { (l, s) ->
            Text("${l.take(30)}  ${String.format(Locale.US, "%.0f%%", s * 100)}", color = Color.White, fontSize = 14.sp)
        }
    }
}

@Composable
private fun GenericPanel(r: UnitResult?, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Raw outputs", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("This model has no camera/text/audio UI yet; it runs on fixed synthetic input so it can be measured and managed.",
            color = Color(0xFFB6C2D6), fontSize = 13.sp)
        if (r == null) Text("waiting for the first run…", color = Color(0xFF8A94A6))
        r?.labels?.forEach { (l, s) -> Text("$l  ${String.format(Locale.US, "%.3f", s)}", color = Color.White, fontFamily = FontFamily.Monospace) }
        r?.numbers?.let { if (it.isNotEmpty()) Text(it.joinToString { v -> String.format(Locale.US, "%.4f", v) }, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
        r?.let { Text("unit #${it.unit}", color = Color(0xFF8A94A6), fontSize = 12.sp) }
    }
}

/**
 * Draws boxes / keypoints / a coarse segmentation map over the preview. PreviewView uses
 * FILL_CENTER, so the same scale-and-crop maps normalised frame coordinates onto the screen.
 */
@Composable
private fun Overlay(r: UnitResult?, frameW: Int, frameH: Int, modifier: Modifier) {
    val paint = androidx.compose.runtime.remember {
        android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 38f; isAntiAlias = true }
    }
    val bg = androidx.compose.runtime.remember { android.graphics.Paint().apply { color = 0xCC1A5CFF.toInt() } }
    Canvas(modifier) {
        if (r == null || frameW == 0 || frameH == 0) return@Canvas
        val scale = max(size.width / frameW, size.height / frameH)
        val dx = (size.width - frameW * scale) / 2f
        val dy = (size.height - frameH * scale) / 2f
        fun x(n: Float) = n * frameW * scale + dx
        fun y(n: Float) = n * frameH * scale + dy
        r.mask?.let { m ->
            val cw = frameW * scale / r.maskW
            val ch = frameH * scale / r.maskH
            for (gy in 0 until r.maskH) for (gx in 0 until r.maskW) {
                val k = m[gy * r.maskW + gx]
                if (k == 0) continue
                val hue = (k * 47) % 360
                drawRect(Color.hsv(hue.toFloat(), 0.8f, 1f, 0.35f), Offset(dx + gx * cw, dy + gy * ch), Size(cw + 1, ch + 1))
            }
        }
        r.boxes.forEach { b ->
            val l = x(b.left); val t = y(b.top); val rr = x(b.right); val bt = y(b.bottom)
            drawRect(Loom.Cyan, Offset(l, t), Size(rr - l, bt - t), style = Stroke(width = 5f))
            val text = "${b.label} ${(b.score * 100).toInt()}%"
            val tw = paint.measureText(text)
            drawContext.canvas.nativeCanvas.apply {
                drawRect(l, t - 46f, l + tw + 16f, t, bg)
                drawText(text, l + 8f, t - 12f, paint)
            }
        }
        r.keypoints.forEach { k ->
            if (k.score > 0.3f) drawCircle(Loom.Cyan, radius = 9f, center = Offset(x(k.x), y(k.y)))
        }
    }
}
