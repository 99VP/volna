package com.volna.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.volna.player.data.SettingsStore
import kotlin.math.hypot

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
    // Светлые (5) — под своей тёмной парой
    VolnaPalette("light-blue", "Светло-холодная", 0xFFE9EEF6, 0xFFF4F7FB, 0xFFDCE4F0, 0xFF1C2130, 0xFF5F6779, 0xFF3F6FC2, light = true),
    VolnaPalette("light", "Светло-тёплая", 0xFFF2F1EE, 0xFFFBFAF8, 0xFFE9E7E2, 0xFF20201E, 0xFF6D6A63, 0xFFC2603F, light = true),
    VolnaPalette("light-green", "Светло-зелёная", 0xFFE9F1E9, 0xFFF4F8F3, 0xFFDDE9DC, 0xFF1E241E, 0xFF5F6B5F, 0xFF3F8F5A, light = true),
    VolnaPalette("light-rose", "Светло-розовая", 0xFFF6EBEF, 0xFFFCF5F7, 0xFFEFDDE3, 0xFF2A1E24, 0xFF755A64, 0xFFD2678C, light = true),
    VolnaPalette("light-lilac", "Светло-лиловая", 0xFFF0ECF7, 0xFFF9F6FC, 0xFFE4DDF0, 0xFF231E2E, 0xFF6A6379, 0xFF9A7BD0, light = true),
)

/** Точка, из которой расходится волна при смене темы (доли экрана 0..1). */
object ThemeReveal {
    var x: Float = 0.5f
    var y: Float = 0.5f
}

@Composable
fun VolnaTheme(content: @Composable () -> Unit) {
    val themeId by SettingsStore.theme.collectAsState()
    val fontScale by SettingsStore.fontScale.collectAsState()
    val targetP = VolnaPalettes.firstOrNull { it.id == themeId } ?: VolnaPalettes.first()
    val appliedId = remember { androidx.compose.runtime.mutableStateOf(themeId) }
    // «Откуда» переходим: предыдущая тема (для фона-волны).
    val fromPalette = remember { androidx.compose.runtime.mutableStateOf(targetP) }

    // Цвета интерфейса едут параллельно волне — тогда нет рывка ни в начале, ни в конце.
    val bg2 by animateColorAsState(Color(targetP.bg2), tween(1700, easing = FastOutSlowInEasing), label = "bg2")
    val bg3 by animateColorAsState(Color(targetP.bg3), tween(1700, easing = FastOutSlowInEasing), label = "bg3")
    val text by animateColorAsState(Color(targetP.text), tween(1700, easing = FastOutSlowInEasing), label = "text")
    val muted by animateColorAsState(Color(targetP.muted), tween(1700, easing = FastOutSlowInEasing), label = "muted")
    val accent by animateColorAsState(Color(targetP.accent), tween(1700, easing = FastOutSlowInEasing), label = "accent")

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

    val density = LocalDensity.current
    val wave = remember { Animatable(1f) }
    val firstTheme = remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(themeId) {
        if (firstTheme.value) {
            firstTheme.value = false
            appliedId.value = themeId
            fromPalette.value = targetP
            return@LaunchedEffect
        }
        if (themeId == appliedId.value) return@LaunchedEffect
        // Запоминаем предыдущую тему и сразу помечаем текущую применённой,
        // чтобы в конце не было кадра с «подменой» фона.
        fromPalette.value = VolnaPalettes.firstOrNull { it.id == appliedId.value } ?: targetP
        appliedId.value = themeId
        wave.snapTo(0f)
        wave.animateTo(1f, tween(1700, easing = FastOutSlowInEasing))
    }
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontScale),
    ) {
        Box(Modifier.fillMaxSize()) {
            val oldBg = if (themeId != appliedId.value) {
                // Первый кадр перехода: берём фон текущей применённой темы.
                Color((VolnaPalettes.firstOrNull { it.id == appliedId.value } ?: targetP).bg)
            } else {
                Color(fromPalette.value.bg)
            }
            val newBg = Color(targetP.bg)
            // Волна — это ФОН ПОД интерфейсом: старый фон + круг нового из точки.
            Canvas(Modifier.fillMaxSize()) {
                val t = if (wave.value >= 1f && themeId != appliedId.value) 0f else wave.value
                val cx = ThemeReveal.x * size.width
                val cy = ThemeReveal.y * size.height
                val maxR = hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
                if (targetP.light) {
                    // На светлую — светлый фон ВЫХОДИТ из точки.
                    drawRect(color = oldBg)
                    drawCircle(
                        color = newBg,
                        radius = (t * maxR).coerceAtLeast(1f),
                        center = Offset(cx, cy),
                    )
                } else {
                    // На тёмную — старый фон ВХОДИТ в точку, открывая тёмный.
                    drawRect(color = newBg)
                    drawCircle(
                        color = oldBg,
                        radius = ((1f - t) * maxR).coerceAtLeast(1f),
                        center = Offset(cx, cy),
                    )
                }
            }
            MaterialTheme(
                colorScheme = colors,
                content = content,
            )
        }
    }
}
