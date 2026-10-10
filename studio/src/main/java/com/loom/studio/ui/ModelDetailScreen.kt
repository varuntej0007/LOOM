package com.loom.studio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loom.studio.importer.ModelCard
import com.loom.studio.importer.ModelSettings
import com.loom.studio.pipeline.PipelineFactory
import com.loom.studio.profiler.ProfileTable
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen 3 (brief §11): the generated chiplet pipeline, each profile's measured ms / mA /
 * quality, goal picker, cut order, heat limit, policy source, Run and (Re-)profile.
 */
@Composable
fun ModelDetailScreen(
    card: ModelCard,
    settings: ModelSettings,
    table: ProfileTable?,
    onBack: () -> Unit,
    onSave: (ModelSettings) -> Unit,
    onRun: () -> Unit,
    onProfile: () -> Unit,
    onInspect: () -> Unit,
) {
    var s by remember(card.id, settings) { mutableStateOf(settings) }
    val chain = remember(card.id) { PipelineFactory.describe(card) }
    val goals = if (PipelineFactory.usesCamera(card)) listOf(10.0, 15.0, 20.0, 24.0) else listOf(2.0, 5.0, 10.0, 20.0)
    val unit = if (PipelineFactory.usesCamera(card)) "FPS" else "runs/s"
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackRow(card.displayName, onBack) }
        item {
            LoomCard {
                Text(card.kindLabel, fontWeight = FontWeight.Bold, color = Loom.Blue, fontSize = 18.sp)
                Text(".${card.format} · ${card.sizeText} · ${card.inputs.firstOrNull()?.shapeText ?: ""}", color = Loom.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (table == null) "Policy: reflex only — not profiled on this phone yet"
                    else "Policy: measured profiles + reflex · profiled ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(table.createdAt))}" +
                        if (table.chargingDuring) " (charging — mA not valid)" else "",
                    color = if (table == null) Loom.Amber else Loom.Green, fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    PrimaryButton("▶  Run", onClick = onRun)
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton(if (table == null) "Profile" else "Re-profile", onClick = onProfile)
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton("Inspect", onClick = onInspect)
                }
            }
        }
        item {
            SectionTitle("Virtual chiplets", "Built automatically for ${card.kind.name.lowercase()} models")
        }
        chain.forEach { c ->
            item {
                LoomCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, fontWeight = FontWeight.Bold, color = Loom.Ink, modifier = Modifier.weight(1f))
                        Text(if (c.profiles.size > 1) "adaptive" else "fixed", color = Loom.Muted, fontSize = 12.sp)
                    }
                    Text(c.role, color = Loom.Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    val rows = table?.rows(c.key)?.takeIf { it.isNotEmpty() }
                    if (rows != null) {
                        rows.forEach { r ->
                            Text(
                                "${r.level.take(4).padEnd(5)} ${r.label.padEnd(18).take(18)} " +
                                    listOfNotNull(
                                        r.p50Ms?.let { String.format(Locale.US, "%.1f ms", it) },
                                        r.throughput?.let { String.format(Locale.US, "%.1f/s", it) },
                                        r.ma?.let { String.format(Locale.US, "%.0f mA", it) },
                                        r.quality?.let { String.format(Locale.US, "q %.2f", it) },
                                    ).joinToString(" · ").ifEmpty { r.note ?: "" },
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Loom.Ink,
                            )
                        }
                    } else {
                        c.profiles.forEach { Text("• $it", fontSize = 12.sp, color = Loom.Ink) }
                    }
                }
            }
        }
        item {
            LoomCard {
                SectionTitle("Goal", "What the Weaver defends")
                Spacer(Modifier.height(6.dp))
                ChipRow(goals.map { "≥ ${it.toInt()} $unit" }, goals.indexOf(s.goalFps)) {
                    s = s.copy(goalFps = goals[it]); onSave(s)
                }
                Spacer(Modifier.height(12.dp))
                SectionTitle("Heat limit", "Thermal headroom the Weaver keeps below (1.0 ≈ throttling)")
                Spacer(Modifier.height(6.dp))
                val limits = listOf(0.70f, 0.75f, 0.80f, 0.85f)
                ChipRow(limits.map { String.format(Locale.US, "%.2f", it) }, limits.indexOf(s.heatLimit)) {
                    s = s.copy(heatLimit = limits[it]); onSave(s)
                }
            }
        }
        item {
            LoomCard {
                SectionTitle("Cut order", "When hot, the Weaver lowers the first one first — put what you notice least on top")
                Spacer(Modifier.height(6.dp))
                s.cutOrder.forEachIndexed { i, key ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text("${i + 1}.", color = Loom.Muted, modifier = Modifier.width(24.dp))
                        Text(chain.firstOrNull { it.key == key }?.name ?: key, color = Loom.Ink, modifier = Modifier.weight(1f))
                        Text("▲", color = if (i > 0) Loom.Blue else Loom.Line, fontSize = 18.sp,
                            modifier = Modifier.clickable(enabled = i > 0) {
                                val l = s.cutOrder.toMutableList(); l.add(i - 1, l.removeAt(i)); s = s.copy(cutOrder = l); onSave(s)
                            }.padding(horizontal = 10.dp))
                        Text("▼", color = if (i < s.cutOrder.size - 1) Loom.Blue else Loom.Line, fontSize = 18.sp,
                            modifier = Modifier.clickable(enabled = i < s.cutOrder.size - 1) {
                                val l = s.cutOrder.toMutableList(); l.add(i + 1, l.removeAt(i)); s = s.copy(cutOrder = l); onSave(s)
                            }.padding(horizontal = 10.dp))
                    }
                }
            }
        }
        item { Column { Spacer(Modifier.height(40.dp)) } }
    }
}
