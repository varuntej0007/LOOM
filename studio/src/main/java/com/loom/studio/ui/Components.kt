package com.loom.studio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loom.studio.core.Level
import com.loom.studio.core.Mode
import com.loom.studio.weaver.WeaverState
import java.util.Locale

@Composable
fun LoomCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Loom.Card)
            .border(1.dp, Loom.Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, sub: String? = null) {
    Column {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Loom.Ink)
        if (sub != null) Text(sub, color = Loom.Muted, fontSize = 13.sp)
    }
}

@Composable
fun KeyValue(label: String, value: String, mono: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Loom.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, fontSize = 13.sp, color = Loom.Ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Selectable chip row (no experimental APIs). */
@Composable
fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Text(
                o,
                fontSize = 12.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) Color.White else Loom.Ink,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Loom.Blue else Color(0xFFEAF1FA))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Loom.gradient else androidx.compose.ui.graphics.SolidColor(Color(0xFFCBD5E1)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Loom.Blue, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Loom.Blue, fontWeight = FontWeight.SemiBold) }
}

fun heatColor(h: Float, limit: Float? = 0.8f): Color = when {
    h.isNaN() -> Loom.Muted
    h < (limit ?: 0.8f) - 0.2f -> Loom.Green
    h < (limit ?: 0.8f) -> Loom.Amber
    else -> Loom.Red
}

fun fmt(v: Double, d: Int = 1) = if (v.isNaN()) "—" else String.format(Locale.US, "%.${d}f", v)
fun fmtF(v: Float, d: Int = 2) = if (v.isNaN()) "—" else String.format(Locale.US, "%.${d}f", v)
fun ma(ua: Long?) = ua?.let { String.format(Locale.US, "%d mA", it / 1000) } ?: "— mA"

/**
 * The LOOM pill on the Run screen (heat · mA · goal ✓) that expands into the Weaver panel:
 * mode, goal, status, last decision and per-chiplet manual levels.
 */
@Composable
fun WeaverPill(
    w: WeaverState,
    unit: String,
    chiplets: List<Triple<String, String, List<Level>>>,   // key, name, available levels
    onMode: (Mode) -> Unit,
    onManual: (String, Level) -> Unit,
    dark: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val bg = if (dark) Color(0xCC0B1220) else Loom.Card
    val fg = if (dark) Color.White else Loom.Ink
    val muted = if (dark) Color(0xFFB6C2D6) else Loom.Muted
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable { open = !open }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(heatColor(w.heat, w.heatLimit)))
            Spacer(Modifier.width(8.dp))
            Text("LOOM", fontWeight = FontWeight.Bold, color = Loom.Cyan, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "heat ${fmtF(w.heat)} · ${ma(w.batteryUa)} · ${fmt(w.metric)}/${fmt(w.goal, 0)} $unit/s " + if (w.goalMet) "✓" else "✗",
                color = fg, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            Text(if (open) "▲" else "▼", color = muted, fontSize = 11.sp)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 8.dp)) {
                HorizontalDivider(color = muted.copy(alpha = 0.3f))
                Spacer(Modifier.height(6.dp))
                Text("Weaver: ${w.status}", color = fg, fontSize = 13.sp)
                Text("Last: ${w.lastDecision}", color = muted, fontSize = 12.sp, maxLines = 3)
                Text("Thermal ${w.thermalStatus} · limit ${w.heatLimit?.let { fmtF(it) } ?: "off"} · ${w.decisions} decisions · stress ${w.stress.name}",
                    color = muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                ChipRow(Mode.entries.map { it.label }, Mode.entries.indexOf(w.mode)) { onMode(Mode.entries[it]) }
                Spacer(Modifier.height(8.dp))
                chiplets.forEach { (key, name, levels) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(name, color = fg, fontSize = 12.sp, modifier = Modifier.width(84.dp))
                        val cur = w.levels[key]
                        ChipRow(levels.map { it.name.take(4) }, levels.indexOf(cur)) { onManual(key, levels[it]) }
                    }
                }
            }
        }
    }
}
