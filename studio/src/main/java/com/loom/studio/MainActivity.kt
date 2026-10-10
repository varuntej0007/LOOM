package com.loom.studio

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.loom.studio.importer.ImportManager
import com.loom.studio.pipeline.PipelineFactory
import com.loom.studio.service.RunService
import com.loom.studio.ui.InspectScreen
import com.loom.studio.ui.LibraryScreen
import com.loom.studio.ui.Loom
import com.loom.studio.ui.LoomIntro
import com.loom.studio.ui.ModelDetailScreen
import com.loom.studio.ui.RunScreen
import com.loom.studio.ui.StudioTheme
import kotlinx.coroutines.launch

/** Navigation targets of the single activity. */
sealed class Screen {
    data object Library : Screen()
    data class Inspect(val id: String) : Screen()
    data class Detail(val id: String) : Screen()
    data class Run(val id: String, val profile: Boolean) : Screen()
    data object Settings : Screen()
}

/** Single activity. Screens are Compose; long work lives in [RunService] + [StudioRuntime]. */
class MainActivity : ComponentActivity() {

    private var screen by mutableStateOf<Screen>(Screen.Library)
    private var importProgress by mutableStateOf<Float?>(null)
    private var message by mutableStateOf<String?>(null)
    private var pendingRun: Screen.Run? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val r = pendingRun
            pendingRun = null
            if (granted && r != null) openRun(r) else if (!granted) message = "Camera permission is needed to run vision models."
        }

    private val pickModel =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importModel(uri)
        }

    private val pickProject =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) importProject(uri)
        }

    private val pickPolicy =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) message = runtime.importPolicy(uri)
        }

    private val runtime get() = StudioApp.instance.runtime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setBars(dark = true)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) RunService.start(this)

        setContent {
            StudioTheme {
                var intro by rememberSaveable { mutableStateOf(savedInstanceState == null) }
                val models by runtime.models.collectAsState()
                val active by runtime.active.collectAsState()
                Box(Modifier.fillMaxSize().background(Loom.Surface)) {
                    when (val s = screen) {
                        Screen.Library -> LibraryScreen(
                            models = models,
                            importProgress = importProgress,
                            message = message,
                            onDismissMessage = { message = null },
                            onImportModel = { pickModel.launch(arrayOf("*/*")) },
                            onImportProject = { pickProject.launch(null) },
                            onSettings = { screen = Screen.Settings },
                            onOpen = { c -> screen = Screen.Detail(c.id) },
                            onStartService = { RunService.start(this@MainActivity) },
                            onStopService = { RunService.stop(this@MainActivity) },
                        )
                        is Screen.Inspect -> {
                            val card = runtime.card(s.id)
                            if (card == null) screen = Screen.Library else InspectScreen(
                                card = card,
                                onBack = { screen = Screen.Library },
                                onOverride = { k, h -> runtime.override(card, k, h) },
                                onBuild = { screen = Screen.Detail(card.id) },
                                onDelete = { runtime.delete(card); screen = Screen.Library },
                            )
                        }
                        is Screen.Detail -> {
                            val card = runtime.card(s.id)
                            if (card == null) screen = Screen.Library else ModelDetailScreen(
                                card = card,
                                settings = runtime.settings(card),
                                table = runtime.profiles(card.id),
                                onBack = { screen = Screen.Library },
                                onSave = { st -> runtime.saveSettings(card, st) },
                                onRun = { requestRun(Screen.Run(card.id, profile = false)) },
                                onProfile = { requestRun(Screen.Run(card.id, profile = true)) },
                                onInspect = { screen = Screen.Inspect(card.id) },
                                policySource = runtime.policy(card.id)?.sourceLabel,
                                lastCompare = runtime.lastCompare(card),
                            )
                        }
                        Screen.Settings -> com.loom.studio.ui.SettingsScreen(onBack = { screen = Screen.Library })
                        is Screen.Run -> {
                            val a = active
                            if (a != null && a.card.id == s.id) {
                                RunScreen(a, s.profile, onBack = { leaveRun(s.id) },
                                    onImportPolicy = { pickPolicy.launch(arrayOf("application/json", "*/*")) })
                                message?.let { m ->
                                    androidx.compose.material3.AlertDialog(
                                        onDismissRequest = { message = null },
                                        confirmButton = { androidx.compose.material3.TextButton(onClick = { message = null }) { androidx.compose.material3.Text("OK") } },
                                        text = { androidx.compose.material3.Text(m) },
                                    )
                                }
                            }
                        }
                    }
                    if (intro) LoomIntro(onDone = { intro = false })
                }
                LaunchedEffect(intro) { if (!intro) setBars(dark = false) }
                BackHandler(enabled = screen != Screen.Library) {
                    when (val s = screen) {
                        is Screen.Run -> leaveRun(s.id)
                        is Screen.Detail, is Screen.Inspect, Screen.Settings -> screen = Screen.Library
                        Screen.Library -> Unit
                    }
                }
            }
        }
    }

    private fun importModel(uri: Uri) {
        importProgress = 0f
        message = null
        lifecycleScope.launch {
            when (val r = runtime.import(uri) { p -> runOnUiThread { importProgress = p } }) {
                is ImportManager.Result.Ok -> screen = Screen.Inspect(r.card.id)
                is ImportManager.Result.Failed -> message = r.message
            }
            importProgress = null
        }
    }

    private fun importProject(uri: Uri) {
        importProgress = 0f
        message = null
        lifecycleScope.launch {
            when (val r = runtime.importProject(uri) { p -> runOnUiThread { importProgress = p } }) {
                is ImportManager.Result.Ok -> screen = Screen.Inspect(r.card.id)
                is ImportManager.Result.Failed -> message = r.message
            }
            importProgress = null
        }
    }

    private fun requestRun(r: Screen.Run) {
        val card = runtime.card(r.id) ?: return
        if (PipelineFactory.usesCamera(card) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingRun = r
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        openRun(r)
    }

    private fun openRun(r: Screen.Run) {
        val card = runtime.card(r.id) ?: return
        try {
            runtime.open(card)
            screen = r
            setBars(dark = true)
        } catch (e: PipelineFactory.Unsupported) {
            message = e.message
            screen = Screen.Library
        } catch (t: Throwable) {
            message = "Could not start the model: ${t.message}"
            screen = Screen.Library
        }
    }

    private fun leaveRun(id: String) {
        runtime.closeActive()
        screen = Screen.Detail(id)
        setBars(dark = false)
    }

    /** Black bars during the splash and on the camera, light bars with dark icons elsewhere. */
    private fun setBars(dark: Boolean) {
        if (dark) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            )
        } else {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            )
        }
    }
}
