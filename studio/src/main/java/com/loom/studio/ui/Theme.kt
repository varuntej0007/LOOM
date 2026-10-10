package com.loom.studio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** LOOM brand: cyan → blue gradient (#3DD6FF → #1A5CFF), light theme inside the app. */
object Loom {
    val Cyan = Color(0xFF3DD6FF)
    val Blue = Color(0xFF1A5CFF)
    val Ink = Color(0xFF0E1726)
    val Muted = Color(0xFF5B667A)
    val Surface = Color(0xFFF5F8FC)
    val Card = Color(0xFFFFFFFF)
    val Line = Color(0xFFE2E8F0)
    val Green = Color(0xFF16A34A)
    val Amber = Color(0xFFD97706)
    val Red = Color(0xFFDC2626)

    val gradient: Brush get() = Brush.horizontalGradient(listOf(Cyan, Blue))
}

private val StudioColors = lightColorScheme(
    primary = Loom.Blue,
    onPrimary = Color.White,
    secondary = Loom.Cyan,
    onSecondary = Loom.Ink,
    background = Loom.Surface,
    onBackground = Loom.Ink,
    surface = Loom.Card,
    onSurface = Loom.Ink,
    surfaceVariant = Color(0xFFEAF1FA),
    onSurfaceVariant = Loom.Muted,
    outline = Loom.Line,
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StudioColors, content = content)
}
