package com.subtitleedit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** One layout positions visible days directly; every day keeps its clickable semantics node. */
@Composable
internal fun StatsHeatmapGrid(
    columns: List<List<StatsHeatmapDay?>>,
    visibleColumns: IntRange,
    selectedDate: String,
    onDayClick: (String) -> Unit,
) {
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
                    if (day != null) StatsHeatmapCell(day.date, day.level, day.date == selectedDate, onDayClick)
                }
            }
        }
    }, measurePolicy = measurePolicy)
}

@Composable
private fun StatsHeatmapCell(date: String, level: Int, selected: Boolean, onDayClick: (String) -> Unit) {
    Box(
        Modifier.size(STATS_HEATMAP_CELL_SIZE)
            .testTag("stats-heatmap-day:$date")
            .clip(HeatmapCellShape)
            .background(heatmapColor(level))
            .then(
                if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, HeatmapCellShape)
                else if (level == 0) Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), HeatmapCellShape)
                else Modifier
            )
            .clickable { onDayClick(date) }
    )
}

@Composable
internal fun heatmapColor(level: Int): Color {
    val base = MaterialTheme.colorScheme.primary
    return when (level) {
        0 -> MaterialTheme.colorScheme.surfaceContainerHighest
        1 -> base.copy(alpha = 0.28f)
        2 -> base.copy(alpha = 0.50f)
        3 -> base.copy(alpha = 0.74f)
        else -> base
    }
}
