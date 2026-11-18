package com.subtitleedit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

internal val STATS_HEATMAP_CELL_SIZE = 13.dp
internal val STATS_HEATMAP_CELL_GAP = 3.dp
private val HeatmapCellShape = RoundedCornerShape(3.dp)

private data class HeatmapCellPosition(val column: Int, val row: Int)

// Modifiers describe immutable styles, so cells can share them while keeping separate draw,
// pointer and semantics nodes (and hence independent ripples and clicks).
private class HeatmapCellStyles(colors: ColorScheme) {
    private val fills = List(5) { level ->
        Modifier.clip(HeatmapCellShape).background(heatmapColor(colors, level))
    }
    private val empty = fills[0].border(0.5.dp, colors.outlineVariant.copy(alpha = 0.5f), HeatmapCellShape)
    private val selected = fills.map { it.border(1.5.dp, colors.primary, HeatmapCellShape) }

    fun forDay(level: Int, isSelected: Boolean): Modifier {
        val index = if (level in fills.indices) level else fills.lastIndex
        return if (isSelected) selected[index] else if (index == 0) empty else fills[index]
    }
}

/** One layout positions visible days directly; every day keeps its clickable semantics node. */
@Composable
internal fun StatsHeatmapGrid(
    columns: List<List<StatsHeatmapDay?>>,
    visibleColumns: IntRange,
    selectedDate: String,
    onDayClick: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val styles = remember(colors) { HeatmapCellStyles(colors) }
    val cells = remember(columns, visibleColumns) {
        buildList {
            for (column in visibleColumns) {
                columns[column].forEachIndexed { row, day ->
                    if (day != null) add(HeatmapCellPosition(column, row))
                }
            }
        }
    }
    val measurePolicy = remember(cells, columns.size) {
        MeasurePolicy { measurables, constraints ->
            val cellSize = STATS_HEATMAP_CELL_SIZE.roundToPx()
            val stride = cellSize + STATS_HEATMAP_CELL_GAP.roundToPx()
            val height = constraints.constrainHeight((STATS_HEATMAP_CELL_SIZE * 7 + STATS_HEATMAP_CELL_GAP * 6).roundToPx())
            val width = constraints.constrainWidth((columns.size * stride - STATS_HEATMAP_CELL_GAP.roundToPx()).coerceAtLeast(0))
            val placeables = measurables.mapIndexed { index, measurable ->
                // Match the original fixed-height week Column when fractional density rounding
                // leaves less than a full cell for its final day.
                val remainingHeight = (height - cells[index].row * stride).coerceIn(0, cellSize)
                measurable.measure(Constraints.fixed(cellSize, remainingHeight))
            }
            layout(width, height) {
                placeables.forEachIndexed { index, placeable ->
                    val cell = cells[index]
                    placeable.placeRelative(cell.column * stride, cell.row * stride)
                }
            }
        }
    }
    Layout(content = {
        for (column in visibleColumns) {
            // Week identity stays stable as the viewport moves; its seven row slots retain
            // the original calendar positions without a keyed group for every single day.
            key(column) {
                columns[column].forEach { day ->
                    if (day != null) StatsHeatmapCell(day.date, styles.forDay(day.level, day.date == selectedDate), onDayClick)
                }
            }
        }
    }, measurePolicy = measurePolicy)
}

@Composable
private fun StatsHeatmapCell(date: String, appearance: Modifier, onDayClick: (String) -> Unit) {
    // The grid already supplies exact pixel constraints, including clipped final rows.
    Box(
        Modifier.testTag("stats-heatmap-day:$date")
            .then(appearance)
            .clickable { onDayClick(date) }
    )
}

@Composable
internal fun heatmapColor(level: Int): Color = heatmapColor(MaterialTheme.colorScheme, level)

private fun heatmapColor(colors: ColorScheme, level: Int): Color = when (level) {
    0 -> colors.surfaceContainerHighest
    1 -> colors.primary.copy(alpha = 0.28f)
    2 -> colors.primary.copy(alpha = 0.50f)
    3 -> colors.primary.copy(alpha = 0.74f)
    else -> colors.primary
}
