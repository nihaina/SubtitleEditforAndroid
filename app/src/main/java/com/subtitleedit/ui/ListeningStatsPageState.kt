package com.subtitleedit.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.library.ListeningDay
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.PlaybackHistoryEntry

/** The main navigation owns this snapshot, so returning to statistics does not render empty data. */
internal class ListeningStatsPageState(currentDate: String = ListeningDay.currentDate()) {
    var granularity by mutableStateOf(StatsGranularity.DAY)
    var selectedYear by mutableIntStateOf(currentDate.take(4).toInt())
    var selectedDate by mutableStateOf(currentDate)
    var previousCurrentDate by mutableStateOf(currentDate)
    var content by mutableStateOf<ListeningStatsContent?>(null)
    internal var loadedQuery: ListeningStatsQuery? = null
    internal var mostPlayed by mutableStateOf<InsightSnapshot<PlaybackHistoryEntry>?>(null)
    internal var neverPlayed by mutableStateOf<InsightSnapshot<MediaLibraryTrack>?>(null)
    private val heatmapScrollStates = mutableMapOf<Int, ScrollState>()
    val listState = LazyListState()

    fun heatmapScrollState(year: Int): ScrollState =
        heatmapScrollStates.getOrPut(year) { ScrollState(Int.MAX_VALUE) }
}

internal data class ListeningStatsQuery(
    val revision: Long,
    val refreshVersion: Int,
    val currentDate: String,
    val selectedYear: Int,
    val granularity: StatsGranularity,
    val selectedDate: String
)

// Day/period selection changes the report, not the all-time insight rankings.
internal data class InsightVersion(val revision: Long, val refreshVersion: Int)

internal data class InsightSnapshot<T>(val version: InsightVersion, val entries: List<T>)

internal typealias ListeningStatsLoader = suspend (
    currentDate: String,
    selectedYear: Int,
    granularity: StatsGranularity,
    selectedDate: String
) -> ListeningStatsContent
