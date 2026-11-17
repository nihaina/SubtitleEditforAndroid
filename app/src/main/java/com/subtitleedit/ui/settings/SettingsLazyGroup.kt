package com.subtitleedit.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

/** Independent lazy rows retain one continuous group silhouette and the original row spacing. */
internal fun LazyListScope.settingsGroupItems(
    key: String,
    count: Int,
    material: Boolean,
    content: @Composable (Int) -> Unit,
) {
    items(count, key = { "$key:$it" }, contentType = { "settings-group-row" }) { index ->
        SettingsGroupRow(material, first = index == 0, last = index == count - 1) {
            content(index)
        }
    }
}

@Composable
private fun SettingsGroupRow(material: Boolean, first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    val groupShape = if (material) MaterialTheme.shapes.extraLarge else MaterialTheme.shapes.large
    val square = CornerSize(0.dp)
    val rowShape = groupShape.copy(
        topStart = if (first) groupShape.topStart else square,
        topEnd = if (first) groupShape.topEnd else square,
        bottomStart = if (last) groupShape.bottomStart else square,
        bottomEnd = if (last) groupShape.bottomEnd else square,
    )
    val background = if (material) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
    val border = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    Box(
        Modifier.fillMaxWidth().clip(rowShape)
            .then(if (material) Modifier else Modifier.drawWithCache {
                // Extend the outline past interior joins, so only the group's two outer ends
                // have horizontal borders. Do not draw a separate border around each row.
                val extension = size.width
                val above = if (first) 0f else extension
                val below = if (last) 0f else extension
                val outline = groupShape.createOutline(Size(size.width, size.height + above + below), layoutDirection, this)
                val stroke = 1.dp.toPx()
                val inset = groupShape.createOutline(
                    Size((size.width - stroke).coerceAtLeast(0f), (size.height + above + below - stroke).coerceAtLeast(0f)),
                    layoutDirection, this,
                )
                onDrawWithContent {
                    clipRect {
                        withTransform({ translate(top = -above) }) { drawOutline(outline, background) }
                    }
                    drawContent()
                    clipRect {
                        withTransform({ translate(left = stroke / 2, top = stroke / 2 - above) }) {
                            drawOutline(inset, border, style = Stroke(stroke))
                        }
                    }
                }
            })
            .padding(
                top = if (!material && first) 4.dp else 0.dp,
                bottom = if (material && !last) 2.dp else if (!material && last) 4.dp else 0.dp,
            ),
    ) { content() }
}
