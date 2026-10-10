package com.loom.studio

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.loom.studio.service.RunService
import com.loom.studio.ui.LibraryScreen
import com.loom.studio.ui.LoomIntro
import com.loom.studio.ui.StudioTheme

/** Single activity. Screens are Compose; long work lives in [RunService]. */
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Granted or not, the service runs; without the permission its notification is just
            // hidden from the shade (Android still lists it under active apps).
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setBars(dark = true)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // M0: telemetry streams as soon as Studio opens, so the laptop dashboard lights up.
        if (savedInstanceState == null) RunService.start(this)

        setContent {
            StudioTheme {
                var intro by rememberSaveable { mutableStateOf(savedInstanceState == null) }
                Box(Modifier.fillMaxSize()) {
                    LibraryScreen(
                        onStartService = { RunService.start(this@MainActivity) },
                        onStopService = { RunService.stop(this@MainActivity) },
                    )
                    if (intro) {
                        LoomIntro(onDone = { intro = false })
                    }
                }
                LaunchedEffect(intro) { if (!intro) setBars(dark = false) }
            }
        }
    }

    /** Black bars during the splash, light bars with dark icons inside the app. */
    private fun setBars(dark: Boolean) {
        if (dark) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.BLACK),
                navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
            )
        } else {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            )
        }
    }
}
