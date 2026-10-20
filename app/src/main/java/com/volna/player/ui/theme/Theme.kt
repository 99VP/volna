package com.volna.player.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.volna.player.data.SettingsStore
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Палитры повторяют темы сайта «Волна». */
data class VolnaPalette(
    val id: String,
    val name: String,
    val bg: Long,
    val bg2: Long,
    val bg3: Long,
    val text: Long,
    val muted: Long,
    val accent: Long,
    val light: Boolean = false,
)

val VolnaPalettes: List<VolnaPalette> = listOf(
    // Тёмные (5)
    VolnaPalette("graphite", "Графитово-синяя", 0xFF0F1115, 0xFF141821, 0xFF1C2230, 0xFFE6E9EF, 0xFF8A93A6, 0xFF5B8CFF),
    VolnaPalette("dark", "Тёмно-серо-красная", 0xFF111113, 0xFF161618, 0xFF1E1E22, 0xFFE7E7E9, 0xFF8C8C95, 0xFFC15A5F),
    VolnaPalette("olive", "Оливково-серая", 0xFF111310, 0xFF161A14, 0xFF1E241B, 0xFFE7EAE2, 0xFF8F9782, 0xFF9BB85A),
    VolnaPalette("rose", "Розово-графитовая", 0xFF131013, 0xFF1A151A, 0xFF241C23, 0xFFEFE7EE, 0xFFA08FA0, 0xFFE07A9A),
    VolnaPalette("lilac", "Лилово-серая", 0xFF121018, 0xFF181522, 0xFF211C2E, 0xFFECE7F4, 0xFF9A90AB, 0xFFA98BE0),
    // Светлые (5) — под свою тёмную пару
    VolnaPalette("light-blue", "Светло-холодная", 0xFFE9EEF6, 0xFFF4F7FB, 0xFFDCE4F0, 0xFF1C2130, 0xFF5F6779, 0xFF3F6FC2, light = true),
    VolnaPalette("light", "Светло-тёплая", 0xFFF2F1EE, 0xFFFBFAF8, 0xFFE9E7E2, 0xFF20201E, 0xFF6D6A63, 0xFFC2603F, light = true),
    VolnaPalette("light-green", "Светло-зелёная", 0xFFE9F1E9, 0xFFF4F8F3, 0xFFDDE9DC, 0xFF1E241E, 0xFF5F6B5F, 0xFF3F8F5A, light = true),
    VolnaPalette("light-rose", "Светло-розовая", 0xFFF6EBEF, 0xFFFCF5F7, 0xFFEFDDE3, 0xFF2A1E24, 0xFF755A64, 0xFFD2678C, light = true),
    VolnaPalette("light-lilac", "Светло-лиловая", 0xFFF0ECF7, 0xFFF9F6FC, 0xFFE4DDF0, 0xFF231E2E, 0xFF6A6379, 0xFF9A7BD0, light = true),
)

/** Точка и номер волны при смене темы (доли экрана 0..1). */
object ThemeReveal {
    var x by mutableFloatStateOf(0.5f)
    var y by mutableFloatStateOf(0.5f)
    var version by mutableIntStateOf(0)

    fun trigger(px: Float, py: Float) {
        x = px.coerceIn(0f, 1f)
        y = py.coerceIn(0f, 1f)
        version++
    }
}

private data class ThemeWave(
    val startNanos: Long,
    val to: VolnaPalette,
    val x: Float,
    val y: Float,
)

private const val WAVE_MS = 1250L
private val WAVE_NANOS = WAVE_MS * 1_000_000L

@Composable
fun VolnaTheme(content: @Composable () -> Unit) {
    val themeId by SettingsStore.theme.collectAsState()
    val revealVersion = ThemeReveal.version
    val targetP = VolnaPalettes.firstOrNull { it.id == themeId } ?: VolnaPalettes.first()

    // Палитра «под низом». Когда все волны дошли до края, она становится новой.
    var basePalette by remember { mutableStateOf(targetP) }
    val waves = remember { mutableStateListOf<ThemeWave>() }
    var frameNanos by remember { mutableLongStateOf(0L) }
    var firstTheme by remember { mutableStateOf(true) }
    var lastWaveTheme by remember { mutableStateOf(themeId) }

    // Цвета интерфейса едут вслед за волной; при быстром переключении
    // animateColorAsState плавно перехватывает текущее значение.
    val waveSpec = tween<androidx.compose.ui.graphics.Color>(1150, easing = FastOutSlowInEasing)
    val bg2 by animateColorAsState(Color(targetP.bg2), waveSpec, label = "bg2")
    val bg3 by animateColorAsState(Color(targetP.bg3), waveSpec, label = "bg3")
    val text by animateColorAsState(Color(targetP.text), waveSpec, label = "text")
    val muted by animateColorAsState(Color(targetP.muted), waveSpec, label = "muted")
    val accent by animateColorAsState(Color(targetP.accent), waveSpec, label = "accent")

    LaunchedEffect(themeId, revealVersion) {
        if (firstTheme) {
            firstTheme = false
            basePalette = targetP
            lastWaveTheme = themeId
            waves.clear()
            return@LaunchedEffect
        }
        // Повторное нажатие той же темы не запускает ещё одну волну.
        if (themeId == lastWaveTheme) return@LaunchedEffect
        lastWaveTheme = themeId
        if (waves.size >= 3) waves.removeAt(0)
        waves.add(
            ThemeWave(
                startNanos = System.nanoTime(),
                to = targetP,
                x = ThemeReveal.x,
                y = ThemeReveal.y,
            ),
        )
    }

    // Один общий кадровый цикл: старые волны продолжают идти, даже если
    // пользователь уже нажал следующую тему. Никакого «входа в круг».
    LaunchedEffect(waves.size) {
        if (waves.isEmpty()) return@LaunchedEffect
        while (true) {
            withFrameNanos { frameNanos = it }
            val now = System.nanoTime()
            if (waves.all { now - it.startNanos >= WAVE_NANOS }) {
                basePalette = waves.last().to
                waves.clear()
                break
            }
        }
    }

    val colors = if (targetP.light) {
        lightColorScheme(
            primary = accent,
            onPrimary = Color(0xFFFFFFFF),
            background = Color.Transparent,
            onBackground = text,
            surface = bg2,
            onSurface = text,
            surfaceVariant = bg3,
            onSurfaceVariant = muted,
            secondary = muted,
            error = Color(0xFFB3261E),
        )
    } else {
        darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = bg3,
            onPrimaryContainer = text,
            background = Color.Transparent,
            onBackground = text,
            surface = bg2,
            onSurface = text,
            surfaceVariant = bg3,
            onSurfaceVariant = muted,
            secondary = muted,
            error = Color(0xFFE57373),
        )
    }

    Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(basePalette.bg))
                val now = if (frameNanos > 0L) frameNanos else System.nanoTime()
                val maxR = hypot(size.width.toDouble(), size.height.toDouble()).toFloat() * 1.08f
                val wavePath = Path()
                for (wave in waves) {
                    val p = ((now - wave.startNanos).toFloat() / WAVE_NANOS)
                        .coerceIn(0f, 1f)
                    if (p <= 0f) continue
                    val cx = wave.x * size.width
                    val cy = wave.y * size.height
                    val r = (p * maxR).coerceAtLeast(1f)
                    // Неровный край — это и есть «волна», а не ровный круг.
                    val path = wavePath
                    path.reset()
                    val steps = 64
                    val phase = p * 10f
                    for (i in 0..steps) {
                        val a = i.toFloat() / steps * 2f * PI.toFloat()
                        val wobble = 1f + 0.022f * sin(a * 6f + phase)
                        val rr = r * wobble
                        val px = cx + cos(a) * rr
                        val py = cy + sin(a) * rr
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    drawPath(path, color = Color(wave.to.bg))
                    if (p < 0.995f) {
                        drawPath(
                            path = path,
                            color = Color(wave.to.accent).copy(alpha = 0.35f * (1f - p)),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
            MaterialTheme(
                colorScheme = colors,
                content = content,
            )
        }
}
