package com.loom.studio.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loom.studio.StudioApp
import java.util.Locale

/**
 * LOOM Guard card: phone-wide cooling while any app runs (games, Camera, video).
 * [full] adds the heat-limit chips and the permission checklist (Settings screen).
 */
@Composable
fun GuardCard(full: Boolean, onStartService: () -> Unit = {}) {
    val guard = StudioApp.instance.guard
    val st by guard.state.collectAsStateWithLifecycle()
    val running by StudioApp.serviceRunning.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(st.enabled, running) { if (st.enabled && !running) onStartService() }

    LoomCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LOOM Guard — cool any app", fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !st.enabled -> "Off. Keeps the phone under the heat limit while you game, film or watch, by stepping brightness → 60 Hz → battery saver."
                        st.levers.isEmpty() -> "Watching · limit ${fmtF(st.limit)} · nothing to cool" + (st.foreground?.let { " · $it" } ?: "")
                        else -> "Cooling: ${st.levers.joinToString(" · ")}" + (st.foreground?.let { " · $it" } ?: "")
                    },
                    color = if (st.levers.isNotEmpty()) Color(0xFFB45309) else Loom.Muted, fontSize = 12.sp,
                )
            }
            Switch(checked = st.enabled, onCheckedChange = { on -> guard.enabled = on; if (on) onStartService() })
        }
        if (st.enabled && !st.perms.secure && !full) {
            Spacer(Modifier.height(4.dp))
            Text("Only brightness works until build_studio.bat grants the 60 Hz / saver permission (Settings → LOOM Guard).",
                fontSize = 11.sp, color = Loom.Muted)
        }
        if (!full) return@LoomCard
        Spacer(Modifier.height(10.dp))
        Text("Heat limit", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val limits = listOf(0.65f, 0.70f, 0.75f, 0.80f)
        ChipRow(limits.map { String.format(Locale.US, "%.2f", it) }, limits.indexOfFirst { kotlin.math.abs(it - st.limit) < 0.001f }) { guard.limit = limits[it] }
        Spacer(Modifier.height(10.dp))
        Text("Permissions", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val pkg = Uri.parse("package:" + ctx.packageName)
        PermRow("Heat pill over other apps", st.perms.overlay) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        PermRow("Brightness (modify system settings)", st.perms.writeSettings || st.perms.secure) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        PermRow("Front app name (usage access)", st.perms.usage) {
            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        PermRow("60 Hz cap + battery saver", st.perms.secure, action = null)
        if (!st.perms.secure) {
            Text("Granted over USB by build_studio.bat, or run:\nadb shell pm grant com.loom.studio android.permission.WRITE_SECURE_SETTINGS",
                fontSize = 11.sp, color = Loom.Muted)
        }
        Spacer(Modifier.height(8.dp))
        Text("Last decision: ${st.last}", fontSize = 12.sp, color = Loom.Ink)
        Text("Guard can't slow another app's own code. It changes phone-wide settings and puts each one back once the phone cools down, or when Guard or the service stops.",
            fontSize = 11.sp, color = Loom.Muted)
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, action: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(if (ok) "✓" else "○", color = if (ok) Color(0xFF047857) else Loom.Muted, modifier = Modifier.padding(end = 8.dp))
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (!ok && action != null) TextButton(onClick = action) { Text("Allow") }
    }
}
