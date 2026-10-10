@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.loom.runtime.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.loom.runtime.core.FrameResult
import com.loom.runtime.core.Level
import com.loom.runtime.core.Mode
import com.loom.runtime.core.StressMode
import com.loom.runtime.telemetry.DashboardState
import com.loom.runtime.weaver.WeaverState
import kotlin.math.max
import kotlin.math.roundToInt

private val Teal = Color(0xFF2EC4B6)
private val Amber = Color(0xFFFFB020)
private val Red = Color(0xFFFF5A5F)
private val Panel = Color(0xD90B0F14)
private val Dim = Color(0xFF9AA4AE)

/** Everything the screen can ask the runtime to do. */
class LoomControls(
    val onMode: (Mode) -> Unit,
    val onStress: (StressMode) -> Unit,
    val onTarget: (Double) -> Unit,
    val onHeat: (Float?) -> Unit,
    val onPacing: (Boolean) -> Unit,
    val onRun: () -> Unit,
    val onVoice: (Boolean) -> Unit,
    val onInference: (Level) -> Unit,
    val onCapture: (Level) -> Unit,
    val onNarrator: (Level) -> Unit,
)

@Composable
fun LoomScreen(
    state: DashboardState,
    weaver: WeaverState,
    frame: FrameResult?,
    sentence: String,
    voiceOn: Boolean,
    levels: Triple<Level, Level, Level>, // inference, capture, narrator (targets)
    controls: LoomControls,
    showEngineering: Boolean,
    onToggleEngineering: () -> Unit,
    onPreviewReady: (PreviewView) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also {
                    it.scaleType = PreviewView.ScaleType.FILL_CENTER
                    onPreviewReady(it)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        DetectionOverlay(frame, Modifier.fillMaxSize())
        // Invisible full-screen touch layer: long-press toggles the engineering view.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onLongPress = { onToggleEngineering() })
            }
        )
        // Default: camera + boxes only. LOOM runs itself; the laptop dashboard shows its brain.
        if (showEngineering) {
            Column(Modifier.align(Alignment.TopCenter).statusBarsPadding()) {
                Header(state, weaver)
                Caption(sentence)
            }
            Dashboard(
                state, weaver, voiceOn, levels, controls,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        }
    }
}

/**
 * Boxes are in upright-frame coordinates. Preview and analysis share a 4:3 aspect ratio and
 * PreviewView uses FILL_CENTER, so the same scale-and-crop maps one onto the other.
 */
@Composable
private fun DetectionOverlay(frame: FrameResult?, modifier: Modifier) {
    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 38f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
    }
    val bgPaint = remember { Paint().apply { color = 0xCC2EC4B6.toInt() } }

    Canvas(modifier) {
        val f = frame ?: return@Canvas
        if (f.imageWidth == 0 || f.imageHeight == 0) return@Canvas
        val scale = max(size.width / f.imageWidth, size.height / f.imageHeight)
        val dx = (size.width - f.imageWidth * scale) / 2f
        val dy = (size.height - f.imageHeight * scale) / 2f
        for (b in f.boxes) {
            val l = b.left * scale + dx
            val t = b.top * scale + dy
            val r = b.right * scale + dx
            val bt = b.bottom * scale + dy
            drawRect(Teal, Offset(l, t), Size(r - l, bt - t), style = Stroke(width = 5f))
            val label = "${b.label} ${(b.score * 100).roundToInt()}%"
            val w = textPaint.measureText(label)
            drawContext.canvas.nativeCanvas.apply {
                drawRect(l, t - 48f, l + w + 16f, t, bgPaint)
                drawText(label, l + 8f, t - 12f, textPaint)
            }
        }
    }
}

@Composable
private fun Header(state: DashboardState, weaver: WeaverState) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mono("LOOM", Teal, bold = true, size = 14)
        Pill(weaver.mode.label.uppercase(), if (weaver.mode == Mode.ADAPTIVE) Teal else Amber)
        if (weaver.stress != StressMode.OFF) Pill("STRESS ${weaver.stress.label}", Red)
        Mono("#${state.frameNo}", Color.White)
        Mono(
            if (state.airplaneMode) "✈ ON" else "✈ off",
            if (state.airplaneMode) Teal else Amber,
            bold = true,
        )
    }
    weaver.runLabel?.let {
        Row(Modifier.fillMaxWidth().background(Red.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 3.dp)) {
            Mono("● $it", Color.White, bold = true)
        }
    }
}

@Composable
private fun Caption(sentence: String) {
    if (sentence == "—") return
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(
            "“$sentence”",
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier
                .background(Color(0xB3000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun Dashboard(
    s: DashboardState,
    w: WeaverState,
    voiceOn: Boolean,
    levels: Triple<Level, Level, Level>,
    c: LoomControls,
    modifier: Modifier,
) {
    var showControls by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .background(Panel)
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        // ---- Weaver: goal vs measured ----
        val ok = w.fps >= w.effectiveTarget
        val targetText = if (w.effectiveTarget < w.target - 0.05)
            "${f0(w.effectiveTarget)} (camera-limited)" else f0(w.target)
        Mono(
            "FPS ${f1(w.fps)} / goal ≥ $targetText",
            if (ok) Teal else Amber, bold = true, size = 15,
        )
        val paceTxt = if (w.pacing && w.mode == Mode.ADAPTIVE) " · pacing on" else ""
        Mono("camera ${f0(w.cameraFps)} · capacity ${f0(w.capacityFps)} FPS$paceTxt", Dim)
        val heatTxt = if (w.heat.isNaN()) "—" else "%.2f".format(w.heat)
        val ceilTxt = w.heatCeiling?.let { "%.2f".format(it) } ?: "off"
        val heatHot = !w.heat.isNaN() && w.heatCeiling != null && w.heat > w.heatCeiling
        Mono("HEAT $heatTxt / limit $ceilTxt", if (heatHot) Red else Teal, bold = true)
        Mono("p50 ${f1(s.p50Ms)} · p90 ${f1(s.p90Ms)} ms   ${s.detections} obj · conf ${"%.2f".format(s.meanConf)}", Dim)
        Spacer(Modifier.height(4.dp))
        Mono("WEAVER · ${w.status} · ${w.decisions} decisions", Teal, bold = true)
        Mono(w.lastDecision, Color.White, lines = 3)
        Spacer(Modifier.height(6.dp))

        // ---- Chiplets ----
        ChipletRow("Capture", s.profileCapture, s.captureMs, "skip ${s.framesSkipped + s.framesPaced}")
        ChipletRow("Preprocess", "rotate", s.preprocessMs)
        ChipletRow("Inference", s.profileInference, s.inferenceMs)
        ChipletRow("Narrator", s.profileNarrator, s.narratorMs, if (voiceOn) "voice" else "muted")
        ChipletRow("Display", "boxes", s.displayMs)

        Spacer(Modifier.height(4.dp))
        val headroom = if (s.thermalHeadroom.isNaN()) "—" else "%.2f".format(s.thermalHeadroom)
        val forecast = if (s.thermalForecast.isNaN()) "—" else "%.2f".format(s.thermalForecast)
        val current = if (s.batteryCurrentRaw == Long.MIN_VALUE) "n/a" else "${s.batteryCurrentRaw}"
        Mono("Thermal ${s.thermalStatus} · headroom $headroom → $forecast in 10 s · battery $current (raw)", Dim)
        Mono("Swap: ${s.lastSwap}", Dim, lines = 2)
        Mono("log: ${s.csvName}", Dim, size = 10)

        // ---- Controls ----
        Spacer(Modifier.height(6.dp))
        Mono(
            if (showControls) "▾ CONTROLS" else "▸ CONTROLS",
            Teal, bold = true,
            modifier = Modifier.clickable { showControls = !showControls }.padding(vertical = 4.dp),
        )
        if (showControls) {
            ChipRow("Mode", Mode.entries.map { it.label to (w.mode == it) }) { idx -> c.onMode(Mode.entries[idx]) }
            ChipRow("Stress", StressMode.entries.map { it.label to (w.stress == it) }) { idx -> c.onStress(StressMode.entries[idx]) }
            val goals = listOf(10.0, 15.0, 20.0)
            ChipRow("Goal", goals.map { "≥${f0(it)}" to (w.target == it) }) { idx -> c.onTarget(goals[idx]) }
            val heats = listOf<Float?>(null, 0.75f, 0.80f, 0.85f)
            ChipRow("Heat", heats.map { (it?.let { v -> "≤%.2f".format(v) } ?: "Off") to (w.heatCeiling == it) }) { idx -> c.onHeat(heats[idx]) }
            ChipRow("Pacing", listOf("On" to w.pacing, "Off" to !w.pacing)) { idx -> c.onPacing(idx == 0) }
            ChipRow("Run", listOf((if (w.runLabel == null) "Start 15-min run" else "Stop run") to (w.runLabel != null))) { c.onRun() }
            ChipRow("Voice", listOf("On" to voiceOn, "Off" to !voiceOn)) { idx -> c.onVoice(idx == 0) }
            Spacer(Modifier.height(4.dp))
            Mono("Manual profiles (switches mode to Manual)", Dim, size = 10)
            LevelRow("Inference", levels.first, listOf("Lite2·GPU", "Lite0·GPU", "Lite0·CPU"), c.onInference)
            LevelRow("Capture", levels.second, listOf("1/1", "1/2", "1/3"), c.onCapture)
            LevelRow("Narrator", levels.third, listOf("3 s", "6 s", "pause"), c.onNarrator)
        }
    }
}

@Composable
private fun ChipletRow(name: String, profile: String, ms: Double?, extra: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Mono("●", Teal)
        Spacer(Modifier.width(6.dp))
        Mono(name, Color.White, bold = true, modifier = Modifier.width(88.dp))
        Mono(profile, Teal, modifier = Modifier.width(100.dp))
        Mono(if (ms == null) "—" else "${f1(ms)} ms", Color.White, modifier = Modifier.width(72.dp))
        Mono(extra, Dim)
    }
}

@Composable
private fun ChipRow(title: String, items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mono(title, Color.White, modifier = Modifier.width(72.dp))
        items.forEachIndexed { idx, (label, selected) ->
            FilterChip(
                selected = selected,
                onClick = { onPick(idx) },
                label = { Text(label, fontSize = 11.sp) },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

@Composable
private fun LevelRow(title: String, current: Level, labels: List<String>, onPick: (Level) -> Unit) {
    ChipRow(title, Level.entries.mapIndexed { i, l -> labels[i] to (l == current) }) { idx ->
        onPick(Level.entries[idx])
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text,
        color = Color.Black,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun f1(v: Double) = "%.1f".format(v)
private fun f0(v: Double) = "%.0f".format(v)

@Composable
private fun Mono(
    text: String,
    color: Color,
    bold: Boolean = false,
    size: Int = 12,
    lines: Int = 2,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontSize = size.sp,
        maxLines = lines,
        modifier = modifier,
    )
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Mono("LOOM needs the camera to see the scene.", Color.White, size = 16)
        Spacer(Modifier.height(12.dp))
        FilterChip(selected = false, onClick = onRequest, label = { Text("Grant camera access") })
    }
}
