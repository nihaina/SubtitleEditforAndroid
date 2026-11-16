package com.subtitleedit.player

import androidx.compose.ui.geometry.Rect

/** One coordinate system for touch tracking and both ends of the media transition. */
internal object PlayerSheetMotion {
    fun progressAfterDrag(progress: Float, deltaY: Float, travel: Float): Float =
        (progress - deltaY / travel.coerceAtLeast(1f)).coerceIn(0f, 1f)

    fun settleExpanded(progress: Float, velocityY: Float, flingThreshold: Float): Boolean = when {
        velocityY <= -flingThreshold -> true
        velocityY >= flingThreshold -> false
        else -> progress >= 0.5f
    }

    fun mediaBounds(collapsed: Rect, expanded: Rect, progress: Float): Rect {
        val p = progress.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * p
        return Rect(mix(collapsed.left, expanded.left), mix(collapsed.top, expanded.top),
            mix(collapsed.right, expanded.right), mix(collapsed.bottom, expanded.bottom))
    }
}
