package com.volna.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun LineSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val start = valueRange.start
    val end = valueRange.endInclusive
    val span = (end - start).takeIf { it != 0f } ?: 1f
    val accent = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(start, end) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width).coerceIn(0f, 1f)
                    onValueChange(start + f * span)
                    onValueChangeFinished?.invoke()
                }
            }
            .pointerInput(start, end) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        onValueChange(start + f * span)
                    },
                    onHorizontalDrag = { change, _ ->
                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                        onValueChange(start + f * span)
                    },
                    onDragEnd = { onValueChangeFinished?.invoke() },
                    onDragCancel = { onValueChangeFinished?.invoke() },
                )
            },
    ) {
        val track = 8.dp.toPx()
        val y = (size.height - track) / 2f
        val radius = CornerRadius(track / 2f)
        drawRoundRect(
            color = inactive,
            topLeft = Offset(0f, y),
            size = Size(size.width, track),
            cornerRadius = radius,
        )
        val fraction = ((value - start) / span).coerceIn(0f, 1f)
        if (fraction > 0f) {
            drawRoundRect(
                color = accent,
                topLeft = Offset(0f, y),
                size = Size(size.width * fraction, track),
                cornerRadius = radius,
            )
        }
    }
}
