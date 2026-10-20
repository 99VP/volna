package com.volna.player.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Свайп от правого края, как системный жест «назад» на Xiaomi. */
fun Modifier.edgeSwipeBack(
    edgeWidth: Dp = 48.dp,
    onBack: () -> Unit,
): Modifier = pointerInput(edgeWidth) {
    var startX = 0f
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { startX = it.x; total = 0f },
        onHorizontalDrag = { _, drag -> total += drag },
        onDragEnd = {
            if (total > 60f && startX >= size.width.toFloat() - edgeWidth.toPx()) onBack()
        },
        onDragCancel = { total = 0f },
    )
}
