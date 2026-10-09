package com.volna.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.sin

/** Простое праздничное конфетти на победу. */
@Composable
fun Confetti(visible: Boolean, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(1800))
        } else {
            progress.snapTo(0f)
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val secondary = MaterialTheme.colorScheme.secondary
    val colors = listOf(
        primary, error, secondary,
        Color(0xFFFFC107), Color(0xFF4CAF50), Color(0xFF29B6F6),
    )
    Canvas(modifier.fillMaxSize()) {
        val p = progress.value
        if (p <= 0f) return@Canvas
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = kotlin.math.hypot(size.width.toDouble(), size.height.toDouble()).toFloat() * 0.6f
        for (i in 0 until 130) {
            val rnd = java.util.Random(i.toLong() * 131L + 7L)
            val angle = (i / 130f) * (2.0 * Math.PI).toFloat() + rnd.nextFloat() * 0.3f
            val speed = 0.6f + rnd.nextFloat() * 0.7f
            val delay = rnd.nextFloat() * 0.25f
            val t = ((p - delay) / (1f - delay)).coerceIn(0f, 1f)
            val dist = t * maxR * speed
            val x = cx + kotlin.math.cos(angle.toDouble()).toFloat() * dist
            val y = cy + kotlin.math.sin(angle.toDouble()).toFloat() * dist
            val c = colors[i % colors.size].copy(alpha = (1f - t).coerceIn(0f, 1f))
            val s = 10f + (i % 4) * 4f
            drawRect(color = c, topLeft = Offset(x, y), size = Size(s, s * 0.6f))
        }
    }
}
