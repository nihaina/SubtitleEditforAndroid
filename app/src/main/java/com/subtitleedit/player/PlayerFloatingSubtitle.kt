package com.subtitleedit.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

private val floatingSubtitleShape = RoundedCornerShape(16.dp)

/** Preview of the system caption window in the appearance settings. */
@Composable
internal fun PlayerFloatingSubtitle(
    text: String,
    style: PlayerFloatingSubtitleStyle,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    Box(
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth()
            .testTag("player-floating-subtitle")
            .background(Color(style.backgroundColorArgb), floatingSubtitleShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment { size, space, _ ->
            IntOffset(floatingSubtitleLeftOffset(space.width - size.width, style.leftMarginPercent), 0)
        },
    ) {
        val textStyle = playerAudioSubtitleTextStyle(style.fontSize, lineSpacing = 4, alignment = style.multilineAlignment)
        val weight = FontWeight(style.weight.value)
        Box {
            if (style.outlineColorArgb ushr 24 != 0) {
                Text(
                    text = text,
                    modifier = Modifier.clearAndSetSemantics {},
                    color = Color(style.outlineColorArgb),
                    fontWeight = weight,
                    style = textStyle.copy(drawStyle = Stroke(with(LocalDensity.current) { 2.dp.toPx() }, join = StrokeJoin.Round)),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = text,
                modifier = Modifier.testTag("player-floating-subtitle-text"),
                color = Color(style.colorArgb),
                fontWeight = weight,
                style = textStyle,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
