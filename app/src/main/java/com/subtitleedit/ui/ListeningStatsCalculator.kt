package com.subtitleedit.ui

import com.subtitleedit.R
import com.subtitleedit.library.ListeningDayStat
import com.subtitleedit.library.ListeningSessionRecord
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.PlaybackHistoryEntry
import com.subtitleedit.library.TopListeningItem
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.TreeMap

internal enum class StatsGranularity(val labelRes: Int, val titleRes: Int) {
    DAY(R.string.stats_granularity_day, R.string.stats_summary_today),
    WEEK(R.string.stats_granularity_week, R.string.stats_summary_week),
    MONTH(R.string.stats_granularity_month, R.string.stats_summary_month),
    YEAR(R.string.stats_granularity_year, R.string.stats_summary_year)
}

internal data class StatsHeatmapDay(
    val date: String,
    val durationMs: Long,
    val level: Int
)

internal data class StatsSummaryData(
    val totalDurationMs: Long = 0L,
    val totalTrackCount: Int = 0,
    val activeDayCount: Int = 0,
    val totalPlayCount: Int = 0,
    val referenceLabel: String = "",
    val referenceLabelRes: Int? = null,
    val deltaDurationMs: Long = 0L,
    val deltaTrackCount: Int = 0,
    val deltaActiveDays: Int = 0,
    val deltaPlayCount: Int = 0
)

internal data class ListeningStatsContent(
    val stats: ListeningStatsIndex = ListeningStatsIndex.Empty,
    val availableYears: List<Int> = emptyList(),
    val heatmapDays: List<StatsHeatmapDay> = emptyList(),
    val sessions: List<ListeningSessionRecord> = emptyList(),
    val topItems: List<TopListeningItem> = emptyList(),
    val recentHistory: List<PlaybackHistoryEntry> = emptyList(),
    val mostPlayed: List<PlaybackHistoryEntry> = emptyList(),
    val neverPlayed: List<MediaLibraryTrack> = emptyList(),
    val topItemsRange: Pair<String, String>? = null
)

/** Prepared with each stats snapshot; day/period taps never re-index the whole history. */
internal class ListeningStatsIndex(rows: List<ListeningDayStat>) {
    private val byDate = TreeMap<String, ListeningDayStat>().apply {
        rows.forEach { put(it.date, it) }
    }

    fun durationOn(date: String): Long = byDate[date]?.durationMs ?: 0L

    fun availableYears(currentYear: Int): List<Int> = StatsCalculator.availableYears(byDate.keys, currentYear)

    fun forEachInRange(start: String, end: String, consume: (ListeningDayStat) -> Unit) {
        if (start <= end) byDate.subMap(start, true, end, true).values.forEach(consume)
    }

    companion object {
        val Empty = ListeningStatsIndex(emptyList())
    }
}

/** Date ranges and arithmetic are independent of Android UI and local wall-clock offsets. */
internal object StatsCalculator {
    fun levelFor(durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        val minutes = (durationMs - 1L) / 60_000L + 1L
        return when {
            minutes <= 9L -> 1
            minutes <= 59L -> 2
            minutes <= 120L -> 3
            else -> 4
        }
    }

    fun availableYears(dates: Iterable<String>, currentYear: Int): List<Int> {
        val earliest = dates.asSequence().mapNotNull { parse(it)?.year }.filter { it <= currentYear }.minOrNull() ?: currentYear
        return (currentYear downTo earliest).toList()
    }

    fun yearRange(year: Int, currentDate: String): Pair<String, String> {
        val current = requireNotNull(parse(currentDate))
        val target = year.coerceIn(1, current.year)
        return LocalDate.of(target, 1, 1).toString() to
            (if (target == current.year) current else LocalDate.of(target, 12, 31)).toString()
    }

    fun buildHeatmapColumns(days: List<StatsHeatmapDay>): List<List<StatsHeatmapDay?>> {
        if (days.isEmpty()) return emptyList()
        val first = parse(days.first().date) ?: return emptyList()
        val cells = MutableList<StatsHeatmapDay?>(first.dayOfWeek.value % 7) { null }
        cells += days
        while (cells.size % 7 != 0) cells += null
        return cells.chunked(7)
    }

    fun buildMonthLabels(columns: List<List<StatsHeatmapDay?>>): List<Pair<String, Int>> {
        val markers = ArrayList<Pair<Int, String>>()
        columns.forEachIndexed { index, column ->
            // ISO calendar dates end in the day of the month. Only possible markers need
            // full parsing; malformed candidates still do not create month labels.
            val monthStart = column.firstNotNullOfOrNull { day ->
                day?.date?.takeIf { it.endsWith("-01") }?.let(::parse)
            }
            val date = monthStart ?: if (index == 0) {
                column.firstOrNull { it != null }?.date?.let(::parse)
            } else null
            if (date != null) {
                val month = "${date.monthValue}月"
                if (markers.lastOrNull()?.second != month) markers += index to month
            }
        }
        return markers.mapIndexed { index, marker ->
            marker.second to ((markers.getOrNull(index + 1)?.first ?: columns.size) - marker.first).coerceAtLeast(1)
        }
    }

    fun currentRange(currentDate: String, granularity: StatsGranularity): Pair<String, String>? {
        val current = parse(currentDate) ?: return null
        val start = when (granularity) {
            StatsGranularity.DAY -> current
            StatsGranularity.WEEK -> current.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            StatsGranularity.MONTH -> current.withDayOfMonth(1)
            StatsGranularity.YEAR -> current.withDayOfYear(1)
        }
        return start.toString() to current.toString()
    }

    data class ReferenceRange(val start: String, val end: String, val labelRes: Int? = null, val label: String = "")

    fun referenceRange(currentDate: String, selectedDate: String?, granularity: StatsGranularity): ReferenceRange? {
        val current = parse(currentDate) ?: return null
        val (start, end, label) = when (granularity) {
            StatsGranularity.DAY -> {
                val yesterday = current.minusDays(1)
                val target = selectedDate?.let(::parse)?.takeIf { it < current } ?: yesterday
                return if (target == yesterday) ReferenceRange(target.toString(), target.toString(), R.string.stats_ref_yesterday)
                else ReferenceRange(target.toString(), target.toString(), label = "${target.monthValue}/${target.dayOfMonth}")
            }
            StatsGranularity.WEEK -> {
                val monday = current.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Triple(monday.minusWeeks(1), current.minusWeeks(1), R.string.stats_ref_last_week)
            }
            StatsGranularity.MONTH -> {
                val previous = current.minusMonths(1)
                Triple(previous.withDayOfMonth(1), previous, R.string.stats_ref_last_month)
            }
            StatsGranularity.YEAR -> Triple(current.withDayOfYear(1).minusYears(1), current.minusYears(1), R.string.stats_ref_last_year)
        }
        return ReferenceRange(start.toString(), end.toString(), label)
    }

    fun buildSummary(
        stats: ListeningStatsIndex,
        currentDate: String,
        selectedDate: String?,
        granularity: StatsGranularity
    ): StatsSummaryData {
        val currentRange = currentRange(currentDate, granularity) ?: return StatsSummaryData()
        val current = totals(stats, currentRange.first, currentRange.second)
        val reference = referenceRange(currentDate, selectedDate, granularity)
        val previous = reference?.let { totals(stats, it.start, it.end) } ?: Totals()
        return StatsSummaryData(
            totalDurationMs = current.duration,
            totalTrackCount = current.tracks,
            activeDayCount = current.days,
            totalPlayCount = current.plays,
            referenceLabel = reference?.label.orEmpty(),
            referenceLabelRes = reference?.labelRes,
            deltaDurationMs = current.duration - previous.duration,
            deltaTrackCount = current.tracks - previous.tracks,
            deltaActiveDays = current.days - previous.days,
            deltaPlayCount = current.plays - previous.plays
        )
    }

    private data class Totals(val duration: Long = 0L, val tracks: Int = 0, val days: Int = 0, val plays: Int = 0)

    private fun totals(stats: ListeningStatsIndex, start: String, end: String): Totals {
        var duration = 0L
        var tracks = 0
        var days = 0
        var plays = 0
        stats.forEachInRange(start, end) { row ->
            duration += row.durationMs.coerceAtLeast(0L)
            tracks += row.trackCount.coerceAtLeast(0)
            plays += row.playCount.coerceAtLeast(0)
            if (row.durationMs > 0L || row.trackCount > 0 || row.playCount > 0) days++
        }
        return Totals(duration, tracks, days, plays)
    }

    private fun parse(date: String): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()
}
