package com.loom.studio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loom.studio.StudioApp
import com.loom.studio.importer.ModelCard
import com.loom.studio.telemetry.LiveSnapshot
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Screen 1 (brief §11): the model Library — imported models as cards, Import FAB,
 * and the live LOOM pill fed by the run service.
 */
@Composable
fun LibraryScreen(
    models: List<ModelCard>,
    importProgress: Float?,
    message: String?,
    onDismissMessage: () -> Unit,
    onImportModel: () -> Unit,
    onImportProject: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (ModelCard) -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
    val live by StudioApp.instance.telemetry.live.collectAsStateWithLifecycle()
    val running by StudioApp.serviceRunning.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }

    fun soon(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    Scaffold(
        containerColor = Loom.Surface,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { menuOpen = true },
                    containerColor = Loom.Blue,
                    contentColor = Color.White,
                ) { Text("+  Import", fontWeight = FontWeight.SemiBold) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Import model (.tflite .task .gguf .onnx)") },
                        onClick = { menuOpen = false; onImportModel() },
                    )
                    DropdownMenuItem(
                        text = { Text("Import LOOM project (loom.json)") },
                        onClick = { menuOpen = false; onImportProject() },
                    )
                }
            }
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = inner.calculateTopPadding() + 12.dp,
                bottom = inner.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Header(onSettings) }
            item {
                LoomPill(
                    live = live,
                    running = running,
                    onToggleService = { on -> if (on) onStartService() else onStopService() },
                )
            }
            item { GuardCard(full = false, onStartService = onStartService) }
            if (importProgress != null) {
                item {
                    LoomCard {
                        Text("Importing… ${(importProgress * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { importProgress }, modifier = Modifier.fillMaxWidth())
                        Text("Copying into app storage, then inspecting the model.", color = Loom.Muted, fontSize = 12.sp)
                    }
                }
            }
            if (message != null) {
                item {
                    LoomCard {
                        Text(message, color = Loom.Ink, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton("OK", onClick = onDismissMessage)
                    }
                }
            }
            if (models.isEmpty() && importProgress == null) item { EmptyLibrary() }
            items(models.size) { i -> ModelRow(models[i]) { onOpen(models[i]) } }
        }
    }
}

@Composable
private fun ModelRow(card: ModelCard, onClick: () -> Unit) {
    val profiled = remember(card.id) { StudioApp.instance.runtime.profiles(card.id) }
    val compared = remember(card.id) { StudioApp.instance.runtime.lastCompare(card) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Loom.Card)
            .border(1.dp, Loom.Line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Loom.gradient),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when (card.kind) {
                    com.loom.studio.core.TaskKind.VISION -> "👁"
                    com.loom.studio.core.TaskKind.LLM -> "💬"
                    com.loom.studio.core.TaskKind.AUDIO -> "🎙"
                    com.loom.studio.core.TaskKind.TEXT -> "Aa"
                    com.loom.studio.core.TaskKind.GENERIC -> "∑"
                },
                color = Color.White, fontSize = 18.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(card.displayName, fontWeight = FontWeight.SemiBold, color = Loom.Ink, maxLines = 1)
            Text("${card.kindLabel} · .${card.format} · ${card.sizeText}", color = Loom.Muted, fontSize = 12.sp)
            Text(
                when {
                    !card.runnable -> "no runtime for this file type"
                    compared != null -> compared
                    profiled != null -> "profiled on this phone · ${profiled.rows.size} profiles"
                    else -> "not profiled yet"
                },
                color = if (profiled != null) Loom.Green else Loom.Muted, fontSize = 12.sp,
            )
        }
        Text("›", color = Loom.Muted, fontSize = 22.sp)
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InfinityMark(progress = 1f, pulse = 0f, modifier = Modifier.size(width = 46.dp, height = 24.dp))
            Spacer(Modifier.width(10.dp))
            Text("LOOM Studio", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Loom.Ink, modifier = Modifier.weight(1f))
            Text("⚙", fontSize = 24.sp, color = Loom.Muted, modifier = Modifier.clickable(onClick = onSettings).padding(8.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Import any on-device model. LOOM builds its virtual chiplets and keeps it cool.",
            color = Loom.Muted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Loom.gradient)
        )
    }
}

/** The small LOOM pill (heat · mA · thermal); tap to expand into the telemetry panel. */
@Composable
fun LoomPill(live: LiveSnapshot, running: Boolean, onToggleService: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val heatColor = when {
        !running || live.heat.isNaN() -> Loom.Muted
        live.heat < 0.60f -> Loom.Green
        live.heat < 0.80f -> Loom.Amber
        else -> Loom.Red
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Loom.Card)
            .border(1.dp, Loom.Line, RoundedCornerShape(18.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(heatColor))
            Spacer(Modifier.width(8.dp))
            Text("LOOM", fontWeight = FontWeight.Bold, color = Loom.Blue, fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                if (running) "heat ${fmt2(live.heat)} · ${maL(live.batteryUa)} · ${live.thermalStatus}"
                else "telemetry off",
                fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Loom.Ink,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "▲" else "▼", color = Loom.Muted, fontSize = 12.sp)
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 10.dp)) {
                HorizontalDivider(color = Loom.Line)
                Spacer(Modifier.height(8.dp))
                Stat("Thermal headroom", fmt3(live.headroom))
                Stat("10 s forecast", fmt3(live.forecast))
                Stat("Heat (Weaver signal)", fmt3(live.heat) + "  limit 0.80")
                Stat("Thermal status", live.thermalStatus)
                Stat("Battery current (raw/1000)", maL(live.batteryUa))
                Stat("Battery", if (live.batteryPct < 0) "—" else "${live.batteryPct}%" + if (live.charging) " · charging" else "")
                Stat("Weaver", live.run.status)
                Stat("LOOM_LIVE lines", live.lines.toString())
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Run service + telemetry", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "Foreground service; streams LOOM_LIVE at 1 Hz, also with the screen off.",
                            color = Loom.Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = running, onCheckedChange = onToggleService)
                }
                if (live.charging && running) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Charging: battery current reflects the charger, not the workload. Unplug for power numbers.",
                        color = Loom.Amber, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Loom.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Loom.Ink)
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Loom.Card)
            .border(1.dp, Loom.Line, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Text("No models yet", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Loom.Ink)
        Spacer(Modifier.height(4.dp))
        Text("Try efficientdet_lite0.tflite from the LOOM repo (app/src/main/assets) — copy it to the phone first.",
            color = Loom.Muted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap Import to add a model. Studio inspects it, builds a chiplet pipeline, measures every " +
                "profile on this phone, then runs it under the Weaver.",
            color = Loom.Muted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(14.dp))
        Format(".tflite", "LiteRT · vision, audio, classifiers")
        Format(".task", "MediaPipe bundles")
        Format(".gguf", "llama.cpp · local chat LLMs")
        Format(".onnx", "ONNX Runtime")
        Format("loom.json", "LOOM project · multi-model pipelines")
        Spacer(Modifier.height(14.dp))
        Text("Size guide", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Loom.Ink)
        Spacer(Modifier.height(4.dp))
        Text(
            "Vision / audio models: a few MB. LLMs: 0.5–3 B parameters at 4-bit (≈0.4–2 GB) suit a " +
                "flagship phone; 7–8 B at 4-bit (≈4–5 GB) may load but will be slow and hot. " +
                "Studio measures on this phone instead of promising numbers.",
            color = Loom.Muted, fontSize = 13.sp,
        )
    }
}

@Composable
private fun Format(ext: String, what: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            ext,
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Loom.Blue,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(what, fontSize = 13.sp, color = Loom.Ink)
    }
}

private fun fmt2(v: Float) = if (v.isNaN()) "—" else String.format(Locale.US, "%.2f", v)
private fun fmt3(v: Float) = if (v.isNaN()) "—" else String.format(Locale.US, "%.3f", v)
/** Battery current is logged raw (µA on most phones; sign is OEM-specific). Shown /1000. */
private fun maL(ua: Long?) = ua?.let { String.format(Locale.US, "%d mA", it / 1000) } ?: "— mA"
