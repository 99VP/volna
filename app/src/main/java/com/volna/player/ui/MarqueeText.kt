package com.volna.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Название песни, если оно не влезает в строку: стоит, затем плавно едет
 * справа налево, в конце снова пауза и повтор. Короткие названия стоят.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeTitle(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Center,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        textAlign = textAlign,
        modifier = modifier
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = 1600,
                repeatDelayMillis = 1400,
                velocity = 28.dp,
            ),
    )
}
