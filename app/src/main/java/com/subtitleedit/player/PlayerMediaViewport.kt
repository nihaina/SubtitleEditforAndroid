package com.subtitleedit.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Clips input as well as drawing without resizing the native output's full-size surface. */
internal fun Modifier.playerMediaViewport(bounds: Rect): Modifier = graphicsLayer {
    shape = PlayerMediaViewportShape(bounds)
    clip = true
}

private data class PlayerMediaViewportShape(val bounds: Rect) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(bounds.intersect(Rect(0f, 0f, size.width, size.height)))
}
