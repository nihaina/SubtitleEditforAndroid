package com.subtitleedit.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.library.ListeningDay

/** The main navigation owns this snapshot, so returning to statistics does not render empty data. */
internal class ListeningStatsPageState(currentDate: String = ListeningDay.currentDate()) {
    var granularity by mutableStateOf(StatsGranularity.DAY)
    var selectedYear by mutableIntStateOf(currentDate.take(4).toInt())
    var selectedDate by mutableStateOf(currentDate)
    var previousCurrentDate by mutableStateOf(currentDate)
    var content by mutableStateOf<ListeningStatsContent?>(null)
    val listState = LazyListState()
}

internal typealias ListeningStatsLoader = suspend (
    currentDate: String,
    selectedYear: Int,
    granularity: StatsGranularity,
    selectedDate: String
) -> ListeningStatsContent
