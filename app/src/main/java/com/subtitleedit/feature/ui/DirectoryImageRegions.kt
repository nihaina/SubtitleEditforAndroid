package com.subtitleedit.feature.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor

internal data class DirectoryImageRegion(val bounds: IntRect, val sample: Int)
internal data class DirectoryImageTile(val region: DirectoryImageRegion, val image: ImageBitmap)

internal fun directoryImageOverview(size: IntSize, targetEdge: Int): DirectoryImageRegion {
    var sample = 1
    val sourceEdge = maxOf(size.width, size.height)
    while ((sourceEdge - 1) / sample >= targetEdge) sample *= 2
    return DirectoryImageRegion(IntRect(0, 0, size.width, size.height), sample)
}

internal fun directoryImageDetail(
    size: IntSize,
    viewport: IntSize,
    scale: Float,
    translation: Offset,
    quarterTurns: Int,
    mirrored: Boolean,
    overviewSample: Int
): DirectoryImageRegion? {
    if (overviewSample == 1) return null
    var sample = 1
    while (sample < overviewSample && sample * 2 * scale <= 1f) sample *= 2
    if (sample >= overviewSample) return null

    val halfViewport = Offset(viewport.width / 2f, viewport.height / 2f)
    val first = sourcePoint((-halfViewport - translation) / scale, size, quarterTurns, mirrored)
    val last = sourcePoint((halfViewport - translation) / scale, size, quarterTurns, mirrored)
    val left = minOf(first.x, last.x).coerceIn(0f, size.width.toFloat())
    val top = minOf(first.y, last.y).coerceIn(0f, size.height.toFloat())
    val right = maxOf(first.x, last.x).coerceIn(0f, size.width.toFloat())
    val bottom = maxOf(first.y, last.y).coerceIn(0f, size.height.toFloat())
    if (left >= right || top >= bottom) return null

    while (sample < overviewSample) {
        val grid = sample.toLong() * 256
        val bounds = IntRect(
            (floor(left / grid.toDouble()).toLong() * grid).toInt(),
            (floor(top / grid.toDouble()).toLong() * grid).toInt(),
            (ceil(right / grid.toDouble()).toLong() * grid).coerceAtMost(size.width.toLong()).toInt(),
            (ceil(bottom / grid.toDouble()).toLong() * grid).coerceAtMost(size.height.toLong()).toInt()
        )
        val decodedWidth = (bounds.width - 1L) / sample + 1
        val decodedHeight = (bounds.height - 1L) / sample + 1
        if (maxOf(decodedWidth, decodedHeight) <= 4096 && decodedWidth * decodedHeight <= 8_000_000) {
            return DirectoryImageRegion(bounds, sample)
        }
        sample *= 2
    }
    return null
}

private fun sourcePoint(point: Offset, size: IntSize, quarterTurns: Int, mirrored: Boolean): Offset {
    val rotated = when (quarterTurns % 4) {
        1 -> Offset(point.y, -point.x)
        2 -> -point
        3 -> Offset(-point.y, point.x)
        else -> point
    }
    return Offset(size.width / 2f + if (mirrored) -rotated.x else rotated.x, size.height / 2f + rotated.y)
}
