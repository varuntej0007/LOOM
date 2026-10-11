package com.loom.studio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loom.studio.StudioApp
import java.util.Locale

/** Screen 6 (brief §11): heat limit, telemetry on/off, export logs, about. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val rt = StudioApp.instance.runtime
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var heat by remember { mutableStateOf(rt.defaultHeatLimit) }
    var tele by remember { mutableStateOf(rt.telemetryOn) }
    var msg by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackRow("Settings", onBack) }
        item { GuardCard(full = true, onStartService = { com.loom.studio.service.RunService.start(ctx) }) }
        item {
            LoomCard {
                SectionTitle("Default heat limit", "Used for newly opened models (each model can override it)")
                Spacer(Modifier.height(6.dp))
                val limits = listOf(0.70f, 0.75f, 0.80f, 0.85f)
                ChipRow(limits.map { String.format(Locale.US, "%.2f", it) }, limits.indexOf(heat)) { heat = limits[it]; rt.defaultHeatLimit = heat }
            }
        }
        item {
            LoomCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text("Telemetry to logcat", fontWeight = FontWeight.SemiBold)
                        Text("LOOM_LIVE / LOOM_EVT for the laptop dashboard over USB or Wi-Fi debugging. Nothing leaves the phone otherwise.",
                            color = Loom.Muted, fontSize = 12.sp)
                    }
                    Switch(checked = tele, onCheckedChange = { tele = it; rt.telemetryOn = it })
                }
            }
        }
        item {
            LoomCard {
                SectionTitle("Export logs", "This app's log, Compare reports and profiles.json files → Download/LOOM")
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Export") { msg = rt.exportLogs() }
                msg?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 13.sp) }
            }
        }
        item {
            LoomCard {
                SectionTitle("About")
                Text("LOOM Studio (Plan B) runs imported on-device models inside LOOM's virtual-chiplet runtime and keeps them " +
                    "within a heat limit. LOOM Guard also keeps the phone cool while other apps run, using phone-wide settings " +
                    "(brightness, refresh rate, battery saver). It cannot change another app's code or the CPU clocks directly. " +
                    "Fully offline: no internet permission.", fontSize = 13.sp, color = Loom.Ink)
            }
        }
    }
}
