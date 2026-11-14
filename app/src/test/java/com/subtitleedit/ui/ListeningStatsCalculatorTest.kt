package com.subtitleedit.ui

import com.subtitleedit.library.ListeningDayStat
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ListeningStatsCalculatorTest {
    @Test fun weekStartsOnMondayIncludingSunday() {
        assertEquals("2026-11-09" to "2026-11-15", StatsCalculator.currentRange("2026-11-15", StatsGranularity.WEEK))
        assertEquals("2026-11-16" to "2026-11-16", StatsCalculator.currentRange("2026-11-16", StatsGranularity.WEEK))
    }

    @Test fun monthComparisonUsesMatchingElapsedDaysAndClampsShortMonths() {
        val range = requireNotNull(StatsCalculator.referenceRange("2026-03-31", null, StatsGranularity.MONTH))
        assertEquals("2026-02-01", range.start)
        assertEquals("2026-02-28", range.end)
        val leap = requireNotNull(StatsCalculator.referenceRange("2024-03-31", null, StatsGranularity.MONTH))
        assertEquals("2024-02-29", leap.end)
    }

    @Test fun yearComparisonHandlesLeapDay() {
        val range = requireNotNull(StatsCalculator.referenceRange("2024-02-29", null, StatsGranularity.YEAR))
        assertEquals("2023-01-01", range.start)
        assertEquals("2023-02-28", range.end)
    }

    @Test fun selectedPastDayIsAReferenceButFutureDayIsNot() {
        assertEquals("11/1", StatsCalculator.referenceRange("2026-11-14", "2026-11-01", StatsGranularity.DAY)?.label)
        assertEquals("2026-11-13", StatsCalculator.referenceRange("2026-11-14", "2026-12-01", StatsGranularity.DAY)?.start)
    }

    @Test fun summaryUsesCurrentPeriodAndOnlyCountsActiveDays() {
        val stats = listOf(
            ListeningDayStat("2026-11-14", 120_000L, 2, 3),
            ListeningDayStat("2026-11-13", 60_000L, 1, 1),
            ListeningDayStat("2026-11-01", 0L, 0, 0),
            ListeningDayStat("2026-10-14", 30_000L, 1, 1)
        )
        val result = StatsCalculator.buildSummary(stats, "2026-11-14", null, StatsGranularity.MONTH)
        assertEquals(180_000L, result.totalDurationMs)
        assertEquals(3, result.totalTrackCount)
        assertEquals(4, result.totalPlayCount)
        assertEquals(2, result.activeDayCount)
        assertEquals(150_000L, result.deltaDurationMs)
    }

    @Test fun yearViewStopsAtTodayAndPastYearIncludesDecember() {
        assertEquals("2026-01-01" to "2026-11-14", StatsCalculator.yearRange(2026, "2026-11-14"))
        assertEquals("2024-01-01" to "2024-12-31", StatsCalculator.yearRange(2024, "2026-11-14"))
        assertEquals("2026-01-01" to "2026-11-14", StatsCalculator.yearRange(2027, "2026-11-14"))
    }

    @Test fun heatmapColumnsKeepAllDaysAndSundayRowAlignment() {
        val start = LocalDate.of(2026, 1, 1)
        val days = (0..364).map { StatsHeatmapDay(start.plusDays(it.toLong()).toString(), 0L, 0) }
        val columns = StatsCalculator.buildHeatmapColumns(days)
        assertEquals(4, columns.first().takeWhile { it == null }.size)
        assertEquals(days, columns.flatten().filterNotNull())
        assertTrue(columns.all { it.size == 7 })
        assertEquals(columns.size, StatsCalculator.buildMonthLabels(columns).sumOf { it.second })
    }

    @Test fun heatLevelsHandleBoundaryAndLargeDurationsWithoutOverflow() {
        assertEquals(0, StatsCalculator.levelFor(0L))
        assertEquals(1, StatsCalculator.levelFor(9 * 60_000L))
        assertEquals(2, StatsCalculator.levelFor(9 * 60_000L + 1))
        assertEquals(3, StatsCalculator.levelFor(120 * 60_000L))
        assertEquals(4, StatsCalculator.levelFor(Long.MAX_VALUE))
    }

    @Test fun invalidDatesDoNotExpandTheYearSelector() {
        assertEquals(listOf(2026, 2025, 2024), StatsCalculator.availableYears(listOf("bad", "2024-02-29", "2028-01-01"), 2026))
        assertNull(StatsCalculator.currentRange("2026-02-30", StatsGranularity.MONTH))
    }
}
