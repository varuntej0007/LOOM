package com.loom.runtime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Brand colours sampled from the LOOM logo. */
private val Cyan = Color(0xFF3DD6FF)
private val Blue = Color(0xFF1A5CFF)
private val Sky = Color(0xFF17BDF8)
private val Glow = Color(0xFF1E6BFF)
private val Silver = Color(0xFFE6EAF0)

/**
 * Launch animation, about 2.6 s: the infinity ribbon draws itself from the crossing, "L" and "M"
 * slide in to spell L∞M, the mark pulses once, and the whole screen fades into the camera.
 * It runs over the camera while the camera and detector are already starting underneath.
 */
@Composable
fun LoomIntro(onDone: () -> Unit) {
    val draw = remember { Animatable(0f) }
    val letters = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        launch { draw.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
        delay(850)
        launch { letters.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) }
        delay(550)
        pulse.animateTo(1f, tween(320, easing = LinearEasing))
        pulse.animateTo(0f, tween(480, easing = FastOutSlowInEasing))
        delay(150)
        fade.animateTo(0f, tween(550, easing = FastOutSlowInEasing))
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "L",
                    color = Silver,
                    fontSize = 68.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier.graphicsLayer {
                        alpha = letters.value
                        translationX = (1f - letters.value) * -80f
                    },
                )
                InfinityMark(
                    progress = draw.value,
                    pulse = pulse.value,
                    modifier = Modifier
                        .size(width = 150.dp, height = 78.dp)
                        .graphicsLayer {
                            val s = 1f + 0.05f * pulse.value
                            scaleX = s
                            scaleY = s
                        },
                )
                Text(
                    text = "M",
                    color = Silver,
                    fontSize = 68.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier.graphicsLayer {
                        alpha = letters.value
                        translationX = (1f - letters.value) * 80f
                    },
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "virtual chiplets · on-device AI",
                color = Color(0xFF8A94A6),
                fontSize = 13.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.graphicsLayer { alpha = letters.value },
            )
        }
    }
}

/**
 * The infinity ribbon. [progress] 0..1 draws it along its length starting at the crossing;
 * [pulse] 0..1 brightens the glow. Same curve as the app icon (res/drawable/ic_launcher_foreground).
 */
@Composable
fun InfinityMark(progress: Float, pulse: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val sw = size.height * 0.16f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val hw = size.width / 2f - sw
        val hh = size.height / 2f - sw * 0.9f
        val full = infinityPath(cx, cy, hw, hh)
        val measure = PathMeasure()
        measure.setPath(full, false)
        val length = measure.length
        val drawn = length * progress.coerceIn(0f, 1f)
        if (drawn <= 0f) return@Canvas
        val seg = Path()
        measure.getSegment(0f, drawn, seg, true)

        val brush = Brush.linearGradient(
            colors = listOf(Cyan, Blue, Sky),
            start = Offset(cx - hw, cy - hh),
            end = Offset(cx + hw, cy + hh),
        )
        // Glow: two wide, faint passes under the ribbon.
        drawPath(seg, color = Glow.copy(alpha = 0.14f + 0.22f * pulse),
            style = Stroke(width = sw * 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(seg, color = Glow.copy(alpha = 0.22f + 0.25f * pulse),
            style = Stroke(width = sw * 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Ribbon and a thin highlight.
        drawPath(seg, brush = brush,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(seg, color = Color.White.copy(alpha = 0.22f + 0.2f * pulse),
            style = Stroke(width = sw * 0.25f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // A bright spark at the drawing head while the ribbon is being drawn.
        if (progress < 1f) {
            val head = measure.getPosition(drawn)
            drawCircle(color = Color.White.copy(alpha = 0.9f), radius = sw * 0.42f, center = head)
            drawCircle(color = Cyan.copy(alpha = 0.35f), radius = sw * 0.95f, center = head)
        }
    }
}

/** Figure-eight made of 8 cubic Béziers, starting at the centre crossing (matches the icon). */
private fun infinityPath(cx: Float, cy: Float, hw: Float, hh: Float): Path {
    val k = floatArrayOf(
        0.1875f, -0.571f, 0.375f, -1f, 0.594f, -1f,
        0.844f, -1f, 1f, -0.536f, 1f, 0f,
        1f, 0.536f, 0.844f, 1f, 0.594f, 1f,
        0.375f, 1f, 0.1875f, 0.571f, 0f, 0f,
    )
    val p = Path()
    p.moveTo(cx, cy)
    for (side in floatArrayOf(1f, -1f)) {
        var i = 0
        while (i < k.size) {
            p.cubicTo(
                cx + side * k[i] * hw, cy + k[i + 1] * hh,
                cx + side * k[i + 2] * hw, cy + k[i + 3] * hh,
                cx + side * k[i + 4] * hw, cy + k[i + 5] * hh,
            )
            i += 6
        }
    }
    return p
}
