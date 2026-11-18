package com.subtitleedit.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Trace
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R
import com.subtitleedit.library.ListeningDay
import com.subtitleedit.library.ListeningSessionRecord
import com.subtitleedit.library.SharedMediaCache
import com.subtitleedit.util.SharedFileCache
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.PlaybackHistoryEntry
import com.subtitleedit.library.TopListeningItem
import com.subtitleedit.player.PlayerActivity
import com.subtitleedit.player.LocalPlayerLauncher
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.ui.theme.cardContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
internal fun ListeningStatsScreen(
    refreshVersion: Int,
    pageState: ListeningStatsPageState = remember { ListeningStatsPageState() },
    isActive: Boolean = true,
    libraryRepository: MediaLibraryRepository? = null,
    contentLoader: ListeningStatsLoader? = null
) {
    val context = LocalContext.current
    val playerLauncher = LocalPlayerLauncher.current
    val repository = libraryRepository ?: remember(context) { MediaLibraryRepository.getInstance(context) }
    val revision by repository.revision.collectRetainedPageState(isActive)

    val scope = rememberCoroutineScope()
    val currentDate = rememberListeningDate(refreshVersion, revision)
    val currentYear = currentDate.take(4).toInt()

    val granularity = pageState.granularity
    val selectedYear = pageState.selectedYear
    val selectedDate = pageState.selectedDate

    val content = pageState.content ?: ListeningStatsContent()
    val stats = content.stats
    val availableYears = content.availableYears.ifEmpty { listOf(currentYear) }
    val heatmapDays = remember(content.heatmapDays, currentDate, selectedYear) {
        content.heatmapDays.takeIf { it.firstOrNull()?.date?.take(4)?.toIntOrNull() == selectedYear }
            ?: StatsCalculator.yearRange(selectedYear, currentDate).let { (start, end) ->
                ListeningDay.datesBetween(start, end).map { StatsHeatmapDay(it, 0L, 0) }
            }
    }
    val sessions = content.sessions
    val topItems = content.topItems
    var insightTab by rememberSaveable { mutableStateOf(PlaybackInsightTab.RECENT) }
    var neverPlayedLimit by rememberSaveable { mutableIntStateOf(INSIGHT_PAGE_SIZE) }
    val historyTimeFormat = rememberStatsTimeFormat("MM-dd HH:mm")
    val sessionTimeFormat = rememberStatsTimeFormat("HH:mm")
    val summaryRange = StatsCalculator.currentRange(currentDate, granularity)
    // A retained snapshot can belong to the previous period while the new query is running.
    val topArtworkPath = topItems.firstOrNull()?.mediaPath.takeIf { content.topItemsRange == summaryRange }

    LaunchedEffect(currentDate) {
        if (pageState.selectedDate == pageState.previousCurrentDate) pageState.selectedDate = currentDate
        if (pageState.selectedYear == pageState.previousCurrentDate.take(4).toInt()) pageState.selectedYear = currentYear
        pageState.previousCurrentDate = currentDate
    }

    val query = ListeningStatsQuery(revision, refreshVersion, currentDate, selectedYear, granularity, selectedDate)
    LaunchedEffect(query, isActive) {
        if (!isActive) return@LaunchedEffect
        if (pageState.content != null && pageState.loadedQuery == query) return@LaunchedEffect
        val year = selectedYear
        val day = selectedDate
        val mode = granularity
        val range = StatsCalculator.currentRange(currentDate, mode)
        // Picking another day, year or period leaves the database untouched, so only the parts
        // that depend on that choice are queried again. Rebuilding the history lists (including
        // the full never-played scan) on every tap made the page stutter.
        val previous = pageState.content
        val previousQuery = pageState.loadedQuery?.takeIf {
            previous != null && contentLoader == null && it.revision == query.revision &&
                it.refreshVersion == query.refreshVersion && it.currentDate == query.currentDate
        }
        try {
            val loaded = withContext(Dispatchers.IO) {
                contentLoader?.let { return@withContext it(currentDate, year, mode, day) }
                if (previous != null && previousQuery != null) {
                    return@withContext previous.copy(
                        heatmapDays = if (previousQuery.selectedYear == year) previous.heatmapDays
                        else buildHeatmapDays(previous.stats, year, currentDate),
                        sessions = if (previousQuery.selectedDate == day) previous.sessions
                        else repository.getSessionsForDate(day),
                        topItems = if (previous.topItemsRange == range) previous.topItems
                        else range?.let { repository.getTopListeningItems(it.first, it.second, limit = 5) }.orEmpty()
                    )
                }
                val index = ListeningStatsIndex(repository.getAllDailyStats())
                ListeningStatsContent(
                    stats = index,
                    availableYears = index.availableYears(currentYear),
                    heatmapDays = buildHeatmapDays(index, year, currentDate),
                    sessions = repository.getSessionsForDate(day),
                    topItems = range?.let { repository.getTopListeningItems(it.first, it.second, limit = 5) }.orEmpty(),
                    recentHistory = repository.getPlaybackHistory(limit = 20)
                )
            }
            pageState.content = loaded.copy(topItemsRange = range)
            pageState.loadedQuery = query
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showLibraryError(context, error)
        }
    }

    // Keep the default report independent from the rankings. In particular, Never Played
    // can scan a large catalog, even though it returns at most 100 rows.
    val insightVersion = InsightVersion(revision, refreshVersion)
    var insightLoadFailed by remember(insightVersion, insightTab, isActive) { mutableStateOf(false) }
    val requestedInsight = insightTab
    if (contentLoader == null && requestedInsight != PlaybackInsightTab.RECENT) {
        LaunchedEffect(requestedInsight, insightVersion, isActive) {
            if (!isActive) return@LaunchedEffect
            try {
                when (requestedInsight) {
                    PlaybackInsightTab.MOST_PLAYED -> if (pageState.mostPlayed?.version != insightVersion) {
                        val entries = withContext(Dispatchers.IO) {
                            traceStats("Stats:insights-most:query") { repository.getMostPlayed(limit = 20) }
                        }
                        pageState.mostPlayed = InsightSnapshot(insightVersion, entries)
                    }
                    PlaybackInsightTab.NEVER_PLAYED -> if (pageState.neverPlayed?.version != insightVersion) {
                        val entries = withContext(Dispatchers.IO) {
                            traceStats("Stats:insights-never:query") { repository.getNeverPlayedTracks(limit = 100) }
                        }
                        pageState.neverPlayed = InsightSnapshot(insightVersion, entries)
                    }
                    PlaybackInsightTab.RECENT -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                insightLoadFailed = true
                showLibraryError(context, error)
            }
        }
    }

    val rawSummary = remember(stats, currentDate, selectedDate, granularity) {
        traceStats("Stats:summary:aggregate") { StatsCalculator.buildSummary(stats, currentDate, selectedDate, granularity) }
    }
    val summary = rawSummary.copy(referenceLabel = rawSummary.referenceLabelRes?.let { stringResource(it) }
        ?: rawSummary.referenceLabel)

    fun playFile(path: String) {
        scope.launch {
            try {
                val exists = withContext(Dispatchers.IO) { File(path).isFile }
                if (!exists) {
                    Toast.makeText(context, context.getString(R.string.media_lib_file_missing), Toast.LENGTH_SHORT).show()
                } else if (playerLauncher != null) {
                    playerLauncher(listOf(path), 0, null, null)
                } else {
                    context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                        putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, arrayListOf(path))
                        putExtra(PlayerActivity.EXTRA_START_INDEX, 0)
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showLibraryError(context, error)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(settingsPageBackground())
            .testTag("listening-stats-page")
            .traceStatsMeasure("Stats:list:measure"),
        state = pageState.listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        item(key = "summary") {
            traceStats("Stats:summary:compose") {
                Box(Modifier.traceStatsMeasure("Stats:summary:measure").padding(bottom = 16.dp)) {
                    SummarySection(
                        summary = summary,
                        granularity = granularity,
                        topArtworkPath = topArtworkPath,
                        artworkRefreshVersion = refreshVersion,
                        animateChanges = pageState.content != null,
                        onGranularitySelected = { pageState.granularity = it }
                    )
                }
            }
        }

        item(key = "heatmap") {
            traceStats("Stats:heatmap:compose") {
                Box(Modifier.traceStatsMeasure("Stats:heatmap:measure").padding(bottom = 16.dp)) {
                    HeatmapSection(
                        days = heatmapDays,
                        selectedDate = selectedDate,
                        availableYears = availableYears,
                        selectedYear = selectedYear,
                        scrollState = pageState.heatmapScrollState(selectedYear),
                        onYearSelected = { year ->
                            pageState.selectedYear = year
                            pageState.selectedDate = if (year == currentYear) currentDate else "%04d-12-31".format(Locale.US, year)
                        },
                        onDayClick = { pageState.selectedDate = it }
                    )
                }
            }
        }

        if (topItems.isNotEmpty()) {
            item(key = "top-works") {
                traceStats("Stats:top-works:compose") {
                    Box(Modifier.traceStatsMeasure("Stats:top-works:measure").padding(bottom = 16.dp)) {
                        TopWorksSection(
                            items = topItems,
                            onItemClick = { playFile(it.mediaPath) }
                        )
                    }
                }
            }
        }

        playbackInsightsItems(
            recentHistory = content.recentHistory,
            mostPlayed = if (contentLoader != null) pageState.content?.mostPlayed else pageState.mostPlayed?.entries,
            neverPlayed = if (contentLoader != null) pageState.content?.neverPlayed else pageState.neverPlayed?.entries,
            loadFailed = insightLoadFailed,
            selectedTab = insightTab,
            neverPlayedLimit = neverPlayedLimit,
            timeFormat = historyTimeFormat,
            onTabSelected = { insightTab = it },
            onShowMore = { neverPlayedLimit += INSIGHT_PAGE_SIZE },
            onTrackClick = { playFile(it.path) }
        )

        item(key = "timeline") {
            traceStats("Stats:timeline-header:compose") { DayTimelineHeader(selectedDate) }
        }
        if (sessions.isEmpty()) {
            item(key = "timeline-empty") {
                traceStats("Stats:timeline-empty:compose") { EmptyDayTimeline() }
            }
        } else {
            itemsIndexed(
                sessions,
                key = { _, session -> "session:${session.id}" },
                contentType = { _, _ -> "session" }
            ) { index, session ->
                traceStats("Stats:timeline-row:compose") {
                    Box(Modifier.traceStatsMeasure("Stats:timeline-row:measure")
                        .padding(bottom = if (index == sessions.lastIndex) 16.dp else 8.dp)) {
                        TimelineRow(
                            session = session,
                            isLast = index == sessions.lastIndex,
                            timeFormat = sessionTimeFormat,
                            onClick = { playFile(session.mediaPath) }
                        )
                    }
                }
            }
        }
    }
}

// Synchronous sections only: never keep a trace span open across a coroutine suspension.
private inline fun <T> traceStats(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}

private fun Modifier.traceStatsMeasure(name: String): Modifier = then(StatsMeasureTrace(name))

// Equality by name avoids invalidating measurement just because a traced item recomposed.
private data class StatsMeasureTrace(val name: String) : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val child = traceStats(name) { measurable.measure(constraints) }
        return layout(child.width, child.height) { child.placeRelative(0, 0) }
    }
}

private fun buildHeatmapDays(
    stats: ListeningStatsIndex,
    year: Int,
    currentDate: String
): List<StatsHeatmapDay> {
    val (startDate, endDate) = StatsCalculator.yearRange(year, currentDate)
    return ListeningDay.datesBetween(startDate, endDate).map { date ->
        val duration = stats.durationOn(date)
        StatsHeatmapDay(date, duration, StatsCalculator.levelFor(duration))
    }
}

private enum class PlaybackInsightTab(val labelRes: Int, val iconRes: Int) {
    RECENT(R.string.stats_section_recent, R.drawable.ic_history),
    MOST_PLAYED(R.string.stats_section_most_played, R.drawable.ic_whatshot),
    NEVER_PLAYED(R.string.stats_section_never_played, R.drawable.ic_block)
}

// Each row is a separate lazy item, including after expanding the never-played list.
// Adjacent surfaces retain the original single-card appearance without an eager inner Column.
private fun LazyListScope.playbackInsightsItems(
    recentHistory: List<PlaybackHistoryEntry>,
    mostPlayed: List<PlaybackHistoryEntry>?,
    neverPlayed: List<MediaLibraryTrack>?,
    loadFailed: Boolean,
    selectedTab: PlaybackInsightTab,
    neverPlayedLimit: Int,
    timeFormat: SimpleDateFormat,
    onTabSelected: (PlaybackInsightTab) -> Unit,
    onShowMore: () -> Unit,
    onTrackClick: (MediaLibraryTrack) -> Unit
) {
    item(key = "playback-insights", contentType = "insights-header") {
        InsightCardPart(first = true) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlaybackInsightTab.entries.forEach { tab ->
                    val selected = tab == selectedTab
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { onTabSelected(tab) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                painter = painterResource(tab.iconRes),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(tab.labelRes),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
    if ((selectedTab == PlaybackInsightTab.MOST_PLAYED && mostPlayed == null) ||
        (selectedTab == PlaybackInsightTab.NEVER_PLAYED && neverPlayed == null)
    ) {
        item(key = "insights-pending") {
            InsightCardPart(last = true) {
                InsightEmptyText(if (loadFailed) R.string.error else R.string.loading)
            }
        }
        return
    }
    val entries = when (selectedTab) {
        PlaybackInsightTab.RECENT -> recentHistory
        PlaybackInsightTab.MOST_PLAYED -> mostPlayed.orEmpty()
        PlaybackInsightTab.NEVER_PLAYED -> {
            val tracks = neverPlayed.orEmpty()
            val shown = tracks.take(neverPlayedLimit)
            val hasMore = shown.size < tracks.size
            if (shown.isEmpty()) {
                item(key = "insights-empty") {
                    InsightCardPart(last = true) {
                        InsightEmptyText(R.string.stats_never_played_empty)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.stats_never_played_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                itemsIndexed(shown, key = { _, track -> "insight:never:${track.path}" }, contentType = { _, _ -> "insight-row" }) { index, track ->
                    InsightCardPart(last = index == shown.lastIndex && !hasMore) {
                        PlaybackInsightRow(
                            track = track,
                            label = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                            onClick = { onTrackClick(track) }
                        )
                    }
                }
                if (hasMore) {
                    item(key = "insights-more") {
                        InsightCardPart(last = true) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = onShowMore) { Text(stringResource(R.string.stats_show_more)) }
                            }
                        }
                    }
                }
            }
            return
        }
    }
    if (entries.isEmpty()) {
        item(key = "insights-empty") {
            InsightCardPart(last = true) {
                InsightEmptyText(if (selectedTab == PlaybackInsightTab.RECENT) R.string.stats_recent_empty else R.string.stats_most_played_empty)
            }
        }
    } else {
        itemsIndexed(entries, key = { _, entry -> "insight:${selectedTab.name}:${entry.path}" }, contentType = { _, _ -> "insight-row" }) { index, entry ->
            val track = remember(entry) {
                MediaLibraryTrack(entry.path, entry.title, entry.artist, entry.album, entry.isVideo, entry.durationMs, entry.playedAtMs)
            }
            InsightCardPart(last = index == entries.lastIndex) {
                PlaybackInsightRow(
                    track = track,
                    label = if (selectedTab == PlaybackInsightTab.RECENT) timeFormat.format(Date(entry.playedAtMs))
                    else stringResource(R.string.stats_play_count, entry.playCount),
                    onClick = { onTrackClick(track) }
                )
            }
        }
    }
}

@Composable
private fun InsightCardPart(first: Boolean = false, last: Boolean = false, content: @Composable () -> Unit) {
    val composition = if (first) "Stats:insights-header:compose" else "Stats:insights-row:compose"
    val measurement = if (first) "Stats:insights-header:measure" else "Stats:insights-row:measure"
    traceStats(composition) {
        Surface(
            modifier = Modifier.traceStatsMeasure(measurement).fillMaxWidth().padding(bottom = if (last) 32.dp else 0.dp),
            shape = RoundedCornerShape(
                topStart = if (first) 18.dp else 0.dp, topEnd = if (first) 18.dp else 0.dp,
                bottomStart = if (last) 18.dp else 0.dp, bottomEnd = if (last) 18.dp else 0.dp
            ),
            color = MaterialTheme.colorScheme.cardContainer
        ) {
            Column(Modifier.padding(horizontal = 14.dp).padding(top = if (first) 14.dp else 0.dp, bottom = if (last) 14.dp else 10.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun InsightEmptyText(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun PlaybackInsightRow(
    track: MediaLibraryTrack,
    label: String,
    onClick: () -> Unit
) {
    val cover by produceState<Bitmap?>(initialValue = null, key1 = track.path) {
        value = withContext(Dispatchers.IO) { loadStatsArtwork(track.path, 128) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stats-insight-row:${track.path}")
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            val bmp = cover
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    painter = painterResource(
                        if (track.isVideo) R.drawable.ic_file_video else R.drawable.ic_file_audio
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.displayTitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (label.isNotBlank()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SummarySection(
    summary: StatsSummaryData,
    granularity: StatsGranularity,
    topArtworkPath: String?,
    artworkRefreshVersion: Int,
    animateChanges: Boolean,
    onGranularitySelected: (StatsGranularity) -> Unit
) {
    var cover by remember(topArtworkPath, granularity, artworkRefreshVersion) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(topArtworkPath, granularity, artworkRefreshVersion) {
        cover = topArtworkPath?.let {
            withContext(Dispatchers.IO) { loadStatsArtwork(it, 480, allowVideoFrame = false) }
        }
    }

    Column(Modifier.testTag("stats-summary"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(granularity.titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            GranularitySelector(
                selected = granularity,
                onSelected = onGranularitySelected
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                val bmp = cover
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize().testTag("stats-summary-artwork"),
                        contentScale = ContentScale.Crop,
                        alpha = 0.14f
                    )
                }
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SummaryMetricCard(
                            animateChanges = animateChanges,
                            iconRes = R.drawable.ic_schedule,
                            label = stringResource(R.string.stats_metric_duration),
                            value = formatStatsDuration(summary.totalDurationMs),
                            deltaText = formatDeltaDuration(summary.deltaDurationMs),
                            deltaSign = summary.deltaDurationMs.compareTo(0L),
                            referenceText = stringResource(R.string.stats_compared_to, summary.referenceLabel),
                            modifier = Modifier.weight(1f)
                        )
                        SummaryMetricCard(
                            animateChanges = animateChanges,
                            iconRes = R.drawable.ic_music_note,
                            label = stringResource(R.string.stats_metric_tracks),
                            value = "${summary.totalTrackCount}",
                            deltaText = formatSignedCount(summary.deltaTrackCount, "首"),
                            deltaSign = summary.deltaTrackCount.compareTo(0),
                            referenceText = stringResource(R.string.stats_compared_to, summary.referenceLabel),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SummaryMetricCard(
                            animateChanges = animateChanges,
                            iconRes = R.drawable.ic_event_available,
                            label = stringResource(R.string.stats_metric_active_days),
                            value = "${summary.activeDayCount}",
                            deltaText = if (granularity == StatsGranularity.DAY) "-" else formatSignedCount(summary.deltaActiveDays, "天"),
                            deltaSign = if (granularity == StatsGranularity.DAY) 0 else summary.deltaActiveDays.compareTo(0),
                            referenceText = if (granularity == StatsGranularity.DAY) "" else stringResource(R.string.stats_compared_to, summary.referenceLabel),
                            modifier = Modifier.weight(1f)
                        )
                        SummaryMetricCard(
                            animateChanges = animateChanges,
                            iconRes = R.drawable.ic_video_play,
                            label = stringResource(R.string.stats_metric_plays),
                            value = "${summary.totalPlayCount}",
                            deltaText = formatSignedCount(summary.deltaPlayCount, "次"),
                            deltaSign = summary.deltaPlayCount.compareTo(0),
                            referenceText = stringResource(R.string.stats_compared_to, summary.referenceLabel),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GranularitySelector(
    selected: StatsGranularity,
    onSelected: (StatsGranularity) -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatsGranularity.entries.forEach { item ->
            val isSelected = item == selected
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable(
                        role = Role.Button,
                        interactionSource = remember(item) { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelected(item) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(item.labelRes),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SummaryMetricCard(
    animateChanges: Boolean,
    iconRes: Int,
    label: String,
    value: String,
    deltaText: String,
    deltaSign: Int,
    referenceText: String,
    modifier: Modifier = Modifier
) {
    val deltaColor = when {
        deltaSign > 0 -> MaterialTheme.colorScheme.primary
        deltaSign < 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier.heightIn(min = 74.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.cardContainer.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (animateChanges) {
                AnimatedContent(
                    targetState = deltaText to referenceText,
                    transitionSpec = {
                        (slideInVertically(tween(200)) { it / 2 } + fadeIn(tween(150)))
                            .togetherWith(slideOutVertically(tween(200)) { -it / 2 } + fadeOut(tween(120)))
                    },
                    label = "stats-delta"
                ) { (headline, caption) ->
                    SummaryMetricDelta(headline, caption, deltaColor)
                }
            } else {
                // The placeholder never animated: the first real snapshot starts a fresh transition.
                SummaryMetricDelta(deltaText, referenceText, deltaColor)
            }
        }
    }
}

@Composable
private fun SummaryMetricDelta(headline: String, caption: String, color: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = headline,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            textAlign = TextAlign.End,
            maxLines = 1
        )
        if (caption.isNotBlank()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun HeatmapSection(
    days: List<StatsHeatmapDay>,
    selectedDate: String,
    availableYears: List<Int>,
    selectedYear: Int,
    scrollState: ScrollState,
    onYearSelected: (Int) -> Unit,
    onDayClick: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("stats-heatmap"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.cardContainer)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.stats_heatmap_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                YearDropdownButton(
                    years = availableYears,
                    selectedYear = selectedYear,
                    onYearSelected = onYearSelected
                )
            }

            if (days.isEmpty()) {
                Text(
                    text = stringResource(R.string.stats_no_records),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val columns = remember(days) { StatsCalculator.buildHeatmapColumns(days) }
                val monthLabels = remember(columns) {
                    traceStats("Stats:heatmap:months") { StatsCalculator.buildMonthLabels(columns) }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val density = LocalDensity.current
                    val stridePx = with(density) { (STATS_HEATMAP_CELL_SIZE + STATS_HEATMAP_CELL_GAP).toPx() }
                    val gapPx = with(density) { STATS_HEATMAP_CELL_GAP.toPx() }
                    val viewportWidthPx = constraints.maxWidth
                    // Keep the original horizontal ScrollState and month-label layout. Only the
                    // weeks in or next to the viewport need seven clickable cell compositions.
                    val visibleColumns by remember(scrollState, columns.size, stridePx, viewportWidthPx) {
                        derivedStateOf {
                            val width = viewportWidthPx.coerceAtLeast(1)
                            val maxScroll = (columns.size * stridePx - gapPx - width).coerceAtLeast(0f)
                            val offset = scrollState.value.toFloat().coerceIn(0f, maxScroll)
                            val first = (offset / stridePx).toInt().minus(1).coerceAtLeast(0)
                            val last = ((offset + width) / stridePx).toInt().plus(1).coerceAtMost(columns.lastIndex)
                            first..last
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("stats-heatmap-scroll")
                            .horizontalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(STATS_HEATMAP_CELL_GAP)) {
                            monthLabels.forEach { (label, span) ->
                                Box(
                                    modifier = Modifier.width(STATS_HEATMAP_CELL_SIZE * span + STATS_HEATMAP_CELL_GAP * (span - 1)),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        traceStats("Stats:heatmap:grid:compose") {
                            StatsHeatmapGrid(columns, visibleColumns, selectedDate, onDayClick)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.stats_legend_less),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    (0..4).forEach { lvl ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .size(11.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(heatmapColor(lvl))
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.stats_legend_more),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun YearDropdownButton(
    years: List<Int>,
    selectedYear: Int,
    onYearSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = years.size > 1) { expanded = true },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = "${selectedYear}年",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            years.forEach { year ->
                DropdownMenuItem(
                    text = { Text("${year}年") },
                    onClick = {
                        expanded = false
                        onYearSelected(year)
                    }
                )
            }
        }
    }
}

@Composable
private fun TopWorksSection(
    items: List<TopListeningItem>,
    onItemClick: (TopListeningItem) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.cardContainer)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.stats_top_works),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            items.forEachIndexed { index, item ->
                val cover by produceState<Bitmap?>(initialValue = null, key1 = item.mediaPath) {
                    value = withContext(Dispatchers.IO) { loadStatsArtwork(item.mediaPath, 128) }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onItemClick(item) }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(18.dp),
                        textAlign = TextAlign.Center
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = cover
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_file_audio),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title.ifBlank { File(item.mediaPath).nameWithoutExtension },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val meta = listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" · ")
                        if (meta.isNotBlank()) {
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Text(
                        text = formatStatsDuration(item.totalDurationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun DayTimelineHeader(date: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_calendar),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = formatDateHeader(date),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun EmptyDayTimeline() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.cardContainer)
    ) {
        Text(
            text = stringResource(R.string.stats_day_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
        )
    }
}

@Composable
private fun rememberStatsTimeFormat(pattern: String): SimpleDateFormat {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val timeZone = TimeZone.getDefault()
    return remember(pattern, locale, timeZone) {
        SimpleDateFormat(pattern, locale).apply {
            this.timeZone = timeZone
        }
    }
}

@Composable
private fun TimelineRow(
    session: ListeningSessionRecord,
    isLast: Boolean,
    timeFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    val cover by produceState<Bitmap?>(initialValue = null, key1 = session.mediaPath) {
        value = withContext(Dispatchers.IO) { loadStatsArtwork(session.mediaPath, 128) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stats-timeline-row:${session.id}")
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.width(50.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = remember(session.startAtMs, timeFormat) {
                    timeFormat.format(Date(session.startAtMs))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }

        Card(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.cardContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = cover
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                if (session.isVideo) R.drawable.ic_file_video else R.drawable.ic_file_audio
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title.ifBlank { File(session.mediaPath).nameWithoutExtension },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val meta = listOf(session.artist, session.album).filter { it.isNotBlank() }.joinToString(" / ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = formatStatsDuration(session.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun loadStatsArtwork(path: String, size: Int, allowVideoFrame: Boolean = true): Bitmap? {
    return SharedFileCache.attributes.get(File(path))?.let {
        SharedMediaCache.artwork(it, size, allowVideoFrame = allowVideoFrame)
    }
}

private const val INSIGHT_PAGE_SIZE = 20

private fun formatStatsDuration(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L -> "${hours}h${minutes}m"
        minutes > 0L -> "${minutes}m"
        ms >= 1000L -> "${ms / 1000L}s"
        else -> "0m"
    }
}

private fun formatDeltaDuration(deltaMs: Long): String {
    if (deltaMs == 0L) return "0m"
    val sign = if (deltaMs > 0L) "+" else "-"
    return "$sign${formatStatsDuration(abs(deltaMs))}"
}

private fun formatSignedCount(delta: Int, unit: String): String {
    if (delta == 0) return "0$unit"
    val sign = if (delta > 0) "+" else "-"
    return "$sign${abs(delta)}$unit"
}

private fun formatDateHeader(date: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date) ?: return date
    DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault()).format(parsed)
}.getOrDefault(date)
