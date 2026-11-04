package com.subtitleedit.view

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class SourceScrollHeightTableTest {
    @Test fun aMillionUniformRowsUseLongCumulativeOffsets() {
        val table = SourceScrollHeightTable(1_000_000, 10_000)

        assertEquals(1_000_000, table.count)
        assertEquals(10_000_000_000L, table.totalHeight)
        assertEquals(5_000_000_000L, table.start(500_000))
        assertEquals(800_000, table.positionAt(8_000_000_000L))
        assertEquals(999_999, table.positionAt(Long.MAX_VALUE))
    }

    @Test fun manyCorrectionsAndFenwickBoundariesKeepAccuratePrefixSums() {
        val table = SourceScrollHeightTable(100_000, 28)
        repeat(100_000) { table.setMeasuredHeight(it, 30) }
        assertEquals(3_000_000L, table.totalHeight)
        for (position in listOf(1, 127, 128, 256, 65_535, 65_536, 99_999)) {
            assertEquals(position * 30L, table.start(position))
            assertEquals(position, table.positionAt(position * 30L))
        }
        repeat(100_000) { table.setMeasuredHeight(it, 28) }
        assertEquals(2_800_000L, table.totalHeight)
        assertEquals(70_000, table.positionAt(70_000 * 28L))

        val largest = SourceScrollHeightTable(Int.MAX_VALUE, 1)
        largest.setMeasuredHeight(Int.MAX_VALUE - 1, 100)
        largest.setMeasuredHeight(0, 20)
        assertEquals(Int.MAX_VALUE.toLong() + 118, largest.totalHeight)
        assertEquals(1L + 19, largest.start(1))
        assertEquals(Int.MAX_VALUE - 1, largest.positionAt(Long.MAX_VALUE))
    }

    @Test fun oneTallVisibleRowSupportsOffsetsInsideTheSamePhysicalLine() {
        val table = SourceScrollHeightTable(1, 28)
        table.setMeasuredHeight(0, 1_000_000)

        assertEquals(1_000_000L, table.totalHeight)
        assertEquals(0L, table.start(0))
        assertEquals(1_000_000L, table.start(1))
        for (offset in listOf(0L, 250_000L, 500_000L, 999_999L)) {
            assertEquals(0, table.positionAt(offset))
        }
    }

    @Test fun exactMixedHeightsMapBoundariesAndLongRows() {
        val table = SourceScrollHeightTable.exact(longArrayOf(0, 28, 1_028, 1_044, 1_072))

        assertEquals(4, table.count)
        assertEquals(1_072L, table.totalHeight)
        assertEquals(0, table.positionAt(27))
        assertEquals(1, table.positionAt(28))
        assertEquals(1, table.positionAt(600))
        assertEquals(1, table.positionAt(1_027))
        assertEquals(2, table.positionAt(1_028))
        assertEquals(3, table.positionAt(1_044))
        assertEquals(3, table.positionAt(1_071))
    }

    @Test fun uniformCorrectionsReplaceAndReturnToTheBaseHeight() {
        val table = SourceScrollHeightTable(4, 20)
        table.setMeasuredHeight(1, 100)
        assertEquals(160L, table.totalHeight)
        assertEquals(120L, table.start(2))
        assertEquals(1, table.positionAt(119))
        assertEquals(2, table.positionAt(120))

        table.setMeasuredHeight(1, 60)
        assertEquals(120L, table.totalHeight)
        assertEquals(80L, table.start(2))

        table.setMeasuredHeight(1, 20)
        assertEquals(80L, table.totalHeight)
        assertEquals(40L, table.start(2))
        assertEquals(2, table.positionAt(40))
    }

    @Test fun exactCorrectionsCanGrowAndShrinkRowsWithoutChangingThePrefixArray() {
        val table = SourceScrollHeightTable.exact(longArrayOf(0, 10, 30, 60))
        table.setMeasuredHeight(1, 50)
        table.setMeasuredHeight(0, 5)

        assertEquals(85L, table.totalHeight)
        assertEquals(5L, table.start(1))
        assertEquals(55L, table.start(2))
        assertEquals(1, table.positionAt(54))
        assertEquals(2, table.positionAt(55))

        table.setMeasuredHeight(0, 10)
        table.setMeasuredHeight(1, 20)
        assertEquals(60L, table.totalHeight)
        assertEquals(30L, table.start(2))
    }

    @Test fun positionsAreClampedAndRemovedRowCorrectionsAreIgnored() {
        val table = SourceScrollHeightTable(3, 28)
        assertEquals(0L, table.start(Int.MIN_VALUE))
        assertEquals(84L, table.start(Int.MAX_VALUE))
        assertEquals(0, table.positionAt(Long.MIN_VALUE))
        assertEquals(2, table.positionAt(Long.MAX_VALUE))

        table.setMeasuredHeight(-1, 1_000)
        table.setMeasuredHeight(3, 1_000)
        assertEquals(84L, table.totalHeight)

        table.setMeasuredHeight(1, 0)
        assertEquals(57L, table.totalHeight)
        assertEquals(2, table.positionAt(29))
    }

    @Test fun emptyDocumentsHaveNoScrollableRows() {
        for (table in listOf(SourceScrollHeightTable(0, 28),
            SourceScrollHeightTable.exact(longArrayOf(0)))) {
            assertEquals(0L, table.totalHeight)
            assertEquals(0L, table.start(100))
            assertEquals(0, table.positionAt(Long.MAX_VALUE))
            table.setMeasuredHeight(0, 100)
            assertEquals(0L, table.totalHeight)
        }
    }

    @Test fun sparseUpdatesMatchAFullHeightList() {
        val random = Random(320)
        val heights = IntArray(100) { 28 }
        val table = SourceScrollHeightTable(heights.size, 28)
        repeat(150) {
            val position = random.nextInt(heights.size)
            heights[position] = random.nextInt(1, 5_000)
            table.setMeasuredHeight(position, heights[position])
            assertEquals(heights.sumOf { it.toLong() }, table.totalHeight)
            var start = 0L
            heights.indices.forEach { row ->
                assertEquals(start, table.start(row))
                assertEquals(row, table.positionAt(start))
                assertEquals(row, table.positionAt(start + heights[row] - 1))
                start += heights[row]
            }
        }
    }
}
