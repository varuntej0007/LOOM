package com.loom.studio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loom.studio.core.TaskKind
import com.loom.studio.importer.Head
import com.loom.studio.importer.ModelCard
import java.util.Locale

/**
 * Screen 2 (brief §11): Import → Inspect. Detected type + confidence + why, tensors, labels,
 * RAM warning, override, and "Build chiplets".
 */
@Composable
fun InspectScreen(
    card: ModelCard,
    onBack: () -> Unit,
    onOverride: (TaskKind, Head) -> Unit,
    onBuild: () -> Unit,
    onDelete: () -> Unit,
) {
    var kind by remember(card.id) { mutableStateOf(card.kind) }
    var head by remember(card.id) { mutableStateOf(card.head) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackRow("Inspect", onBack) }
        item {
            LoomCard {
                Text(card.displayName, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Loom.Ink)
                Text(".${card.format} · ${card.sizeText}", color = Loom.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Text(card.kindLabel, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Loom.Blue)
                Text("auto-detect confidence ${String.format(Locale.US, "%.0f%%", card.confidence * 100)}" +
                    if (card.overridden) " · set by you" else "", color = Loom.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                card.reasons.forEach { Text("• $it", fontSize = 13.sp, color = Loom.Ink) }
                card.ramWarning?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("⚠ $it", color = Loom.Amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (card.inputs.isNotEmpty() || card.outputs.isNotEmpty()) item {
            LoomCard {
                SectionTitle("Tensors")
                Spacer(Modifier.height(6.dp))
                card.inputs.forEach { KeyValue("in  ${it.name.take(28)}", "${it.shapeText} ${it.dtype}") }
                card.outputs.forEach { KeyValue("out ${it.name.take(28)}", "${it.shapeText} ${it.dtype}") }
            }
        }
        card.llm?.let { l ->
            item {
                LoomCard {
                    SectionTitle("Language model (GGUF header)")
                    KeyValue("architecture", l.architecture ?: "—")
                    KeyValue("name", l.name ?: "—")
                    KeyValue("parameters", l.paramsBillions?.let { String.format(Locale.US, "≈ %.2f B", it) } ?: "—")
                    KeyValue("quantisation", l.quant ?: "—")
                    KeyValue("context length", l.contextLength?.toString() ?: "—")
                }
            }
        }
        item {
            LoomCard {
                SectionTitle("Labels", if (card.labels == null) "none found in the model" else "${card.labels.size} labels")
                card.labels?.let { ls ->
                    Spacer(Modifier.height(6.dp))
                    Text(ls.take(40).joinToString(", ") + if (ls.size > 40) " …" else "",
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Loom.Ink)
                }
            }
        }
        item {
            LoomCard {
                SectionTitle("Override", "If the auto-detect is wrong, pick the type yourself")
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ChipRow(TaskKind.entries.map { it.name }, TaskKind.entries.indexOf(kind)) { kind = TaskKind.entries[it] }
                }
                if (kind == TaskKind.VISION) {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        ChipRow(Head.entries.map { it.name.lowercase() }, Head.entries.indexOf(head)) { head = Head.entries[it] }
                    }
                }
                if (kind != card.kind || (kind == TaskKind.VISION && head != card.head)) {
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Save override") { onOverride(kind, head) }
                }
            }
        }
        item {
            Row {
                PrimaryButton(if (card.runnable) "Build chiplets" else "Runtime arrives later", enabled = card.runnable, onClick = onBuild)
                Spacer(Modifier.width(12.dp))
                SecondaryButton("Delete", onClick = onDelete)
            }
        }
    }
}

@Composable
fun BackRow(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("‹", fontSize = 32.sp, color = Loom.Blue, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Loom.Ink)
    }
}
