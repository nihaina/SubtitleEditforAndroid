package com.subtitleedit.ui

import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R
import com.subtitleedit.library.ListeningDay
import com.subtitleedit.library.ListeningDayStat
import com.subtitleedit.library.ListeningSessionRecord
import com.subtitleedit.library.SharedMediaCache
import com.subtitleedit.util.SharedFileCache
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.PlaybackHistoryEntry
import com.subtitleedit.library.TopListeningItem
import com.subtitleedit.player.PlayerActivity
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
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
internal fun ListeningStatsScreen(
    refreshVersion: Int,
    pageState: ListeningStatsPageState = remember { ListeningStatsPageState() },
    contentLoader: ListeningStatsLoader? = null
) {
    val context = LocalContext.current
    val repository = remember(context) { MediaLibraryRepository.getInstance(context) }
    val revision by repository.revision.collectAsState()

    val scope = rememberCoroutineScope()
    val currentDate = rememberListeningDate(refreshVersion, revision)
    val currentYear = currentDate.take(4).toInt()

    val granularity = pageState.granularity
    val selectedYear = pageState.selectedYear
    val selectedDate = pageState.selectedDate

    val content = pageState.content ?: ListeningStatsContent()
    val allStats = content.allStats
    val availableYears = content.availableYears.ifEmpty { listOf(currentYear) }
    val heatmapDays = remember(content.heatmapDays, currentDate, selectedYear) {
        content.heatmapDays.takeIf { it.firstOrNull()?.date?.take(4)?.toIntOrNull() == selectedYear }
            ?: StatsCalculator.yearRange(selectedYear, currentDate).let { (start, end) ->
                ListeningDay.datesBetween(start, end).map { StatsHeatmapDay(it, 0L, 0) }
            }
    }
    val sessions = content.sessions
    val topItems = content.topItems
    val summaryRange = StatsCalculator.currentRange(currentDate, granularity)
    // A retained snapshot can belong to the previous period while the new query is running.
    val topArtworkPath = topItems.firstOrNull()?.mediaPath.takeIf { content.topItemsRange == summaryRange }

    LaunchedEffect(currentDate) {
        if (pageState.selectedDate == pageState.previousCurrentDate) pageState.selectedDate = currentDate
        if (pageState.selectedYear == pageState.previousCurrentDate.take(4).toInt()) pageState.selectedYear = currentYear
        pageState.previousCurrentDate = currentDate
    }

    LaunchedEffect(revision, refreshVersion, currentDate, selectedYear, granularity, selectedDate) {
        val year = selectedYear
        val day = selectedDate
        val mode = granularity
        val range = StatsCalculator.currentRange(currentDate, mode)
        try {
            val loaded = withContext(Dispatchers.IO) {
                contentLoader?.let { return@withContext it(currentDate, year, mode, day) }
                val stats = repository.getAllDailyStats()
                val (startDate, endDate) = StatsCalculator.yearRange(year, currentDate)
                val yearStats = stats.filter { it.date in startDate..endDate }.associateBy { it.date }
                val days = ListeningDay.datesBetween(startDate, endDate).map { date ->
                    val duration = yearStats[date]?.durationMs ?: 0L
                    StatsHeatmapDay(date, duration, StatsCalculator.levelFor(duration))
                }
                ListeningStatsContent(
                    allStats = stats,
                    availableYears = StatsCalculator.availableYears(stats.map { it.date }, currentYear),
                    heatmapDays = days,
                    sessions = repository.getSessionsForDate(day),
                    topItems = range?.let { repository.getTopListeningItems(it.first, it.second, limit = 5) }.orEmpty(),
                    recentHistory = repository.getPlaybackHistory(limit = 20),
                    mostPlayed = repository.getMostPlayed(limit = 20),
                    neverPlayed = repository.getNeverPlayedTracks(limit = 100)
                )
            }
            pageState.content = loaded.copy(topItemsRange = range)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showLibraryError(context, error)
        }
    }

    val rawSummary = remember(allStats, currentDate, selectedDate, granularity) {
        StatsCalculator.buildSummary(allStats, currentDate, selectedDate, granularity)
    }
    val summary = rawSummary.copy(referenceLabel = rawSummary.referenceLabelRes?.let { stringResource(it) }
        ?: rawSummary.referenceLabel)

    fun playFile(path: String) {
        scope.launch {
            try {
                val exists = withContext(Dispatchers.IO) { File(path).isFile }
                if (!exists) {
                    Toast.makeText(context, context.getString(R.string.media_lib_file_missing), Toast.LENGTH_SHORT).show()
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
            .testTag("listening-stats-page"),
        state = pageState.listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "summary") {
            SummarySection(
                summary = summary,
                granularity = granularity,
                topArtworkPath = topArtworkPath,
                artworkRefreshVersion = refreshVersion,
                animateChanges = pageState.content != null,
                onGranularitySelected = { pageState.granularity = it }
            )
        }

        item(key = "heatmap") {
            HeatmapSection(
                days = heatmapDays,
                selectedDate = selectedDate,
                availableYears = availableYears,
                selectedYear = selectedYear,
                onYearSelected = { year ->
                    pageState.selectedYear = year
                    pageState.selectedDate = if (year == currentYear) currentDate else "%04d-12-31".format(Locale.US, year)
                },
                onDayClick = { pageState.selectedDate = it }
            )
        }

        if (topItems.isNotEmpty()) {
            item(key = "top-works") {
                TopWorksSection(
                    items = topItems,
                    onItemClick = { playFile(it.mediaPath) }
                )
            }
        }

        item(key = "playback-insights") {
            PlaybackInsightsSection(
                recentHistory = content.recentHistory,
                mostPlayed = content.mostPlayed,
                neverPlayed = content.neverPlayed,
                onTrackClick = { playFile(it.path) }
            )
        }

        item(key = "timeline") {
            DayTimelineSection(
                date = selectedDate,
                sessions = sessions,
                onSessionClick = { playFile(it.mediaPath) }
            )
        }
    }
}

private enum class PlaybackInsightTab(val labelRes: Int, val iconRes: Int) {
    RECENT(R.string.stats_section_recent, R.drawable.ic_history),
    MOST_PLAYED(R.string.stats_section_most_played, R.drawable.ic_whatshot),
    NEVER_PLAYED(R.string.stats_section_never_played, R.drawable.ic_block)
}

@Composable
private fun PlaybackInsightsSection(
    recentHistory: List<PlaybackHistoryEntry>,
    mostPlayed: List<PlaybackHistoryEntry>,
    neverPlayed: List<MediaLibraryTrack>,
    onTrackClick: (MediaLibraryTrack) -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(PlaybackInsightTab.RECENT) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.cardContainer)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                            .clickable(role = Role.Button) { selectedTab = tab },
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

            when (selectedTab) {
                PlaybackInsightTab.RECENT -> PlaybackHistoryInsightList(
                    entries = recentHistory,
                    emptyText = stringResource(R.string.stats_recent_empty),
                    timeLabel = { entry ->
                        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.playedAtMs))
                    },
                    onTrackClick = onTrackClick
                )

                PlaybackInsightTab.MOST_PLAYED -> PlaybackHistoryInsightList(
                    entries = mostPlayed,
                    emptyText = stringResource(R.string.stats_most_played_empty),
                    timeLabel = { entry -> stringResource(R.string.stats_play_count, entry.playCount) },
                    onTrackClick = onTrackClick
                )

                PlaybackInsightTab.NEVER_PLAYED -> {
                    if (neverPlayed.isEmpty()) {
                        Text(
                            text = stringResource(R.string.stats_never_played_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.stats_never_played_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        neverPlayed.forEach { track ->
                            PlaybackInsightRow(
                                track = track,
                                label = listOf(track.artist, track.album)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                onClick = { onTrackClick(track) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackHistoryInsightList(
    entries: List<PlaybackHistoryEntry>,
    emptyText: String,
    timeLabel: @Composable (PlaybackHistoryEntry) -> String,
    onTrackClick: (MediaLibraryTrack) -> Unit
) {
    if (entries.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        return
    }
    entries.forEach { entry ->
        val track = remember(entry) {
            MediaLibraryTrack(
                path = entry.path,
                title = entry.title,
                artist = entry.artist,
                album = entry.album,
                isVideo = entry.isVideo,
                durationMs = entry.durationMs,
                addedAtMs = entry.playedAtMs
            )
        }
        PlaybackInsightRow(
            track = track,
            label = timeLabel(entry),
            onClick = { onTrackClick(track) }
        )
    }
}

@Composable
private fun PlaybackInsightRow(
    track: MediaLibraryTrack,
    label: String,
    onClick: () -> Unit
) {
    val cover by produceState<Bitmap?>(initialValue = null, key1 = track.path) {
        value = withContext(Dispatchers.IO) { SharedFileCache.attributes.get(File(track.path))?.let { metadata -> SharedMediaCache.artwork(metadata, 128) } }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            withContext(Dispatchers.IO) { SharedFileCache.attributes.get(File(it))?.let { metadata -> SharedMediaCache.artwork(metadata, 480, allowVideoFrame = false) } }
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
            key(animateChanges) {
                AnimatedContent(
                    targetState = deltaText to referenceText,
                    transitionSpec = {
                        (slideInVertically(tween(200)) { it / 2 } + fadeIn(tween(150)))
                            .togetherWith(slideOutVertically(tween(200)) { -it / 2 } + fadeOut(tween(120)))
                    },
                    label = "stats-delta"
                ) { (headline, caption) ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = headline,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = deltaColor,
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
            }
        }
    }
}

@Composable
private fun HeatmapSection(
    days: List<StatsHeatmapDay>,
    selectedDate: String,
    availableYears: List<Int>,
    selectedYear: Int,
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
                val monthLabels = remember(columns) { StatsCalculator.buildMonthLabels(columns) }
                val scrollState = remember(selectedYear, columns.size) { ScrollState(Int.MAX_VALUE) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                        monthLabels.forEach { (label, span) ->
                            Box(
                                modifier = Modifier.width(CELL_SIZE * span + CELL_GAP * (span - 1)),
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
                    Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                        columns.forEach { week ->
                            Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                                week.forEach { day ->
                                    if (day == null) {
                                        Spacer(Modifier.size(CELL_SIZE))
                                    } else {
                                        val isSelected = day.date == selectedDate
                                        val cellColor = heatmapColor(day.level)
                                        val shape = RoundedCornerShape(3.dp)
                                        Box(
                                            modifier = Modifier
                                                .size(CELL_SIZE)
                                                .clip(shape)
                                                .background(cellColor)
                                                .then(
                                                    if (isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                                                    else if (day.level == 0) Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
                                                    else Modifier
                                                )
                                                .clickable { onDayClick(day.date) }
                                        )
                                    }
                                }
                            }
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
                    value = withContext(Dispatchers.IO) { SharedFileCache.attributes.get(File(item.mediaPath))?.let { metadata -> SharedMediaCache.artwork(metadata, 128) } }
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
private fun DayTimelineSection(
    date: String,
    sessions: List<ListeningSessionRecord>,
    onSessionClick: (ListeningSessionRecord) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
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

        if (sessions.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
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
        } else {
            sessions.forEachIndexed { index, session ->
                TimelineRow(
                    session = session,
                    isLast = index == sessions.lastIndex,
                    onClick = { onSessionClick(session) }
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(
    session: ListeningSessionRecord,
    isLast: Boolean,
    onClick: () -> Unit
) {
    val cover by produceState<Bitmap?>(initialValue = null, key1 = session.mediaPath) {
        value = withContext(Dispatchers.IO) { SharedFileCache.attributes.get(File(session.mediaPath))?.let { metadata -> SharedMediaCache.artwork(metadata, 128) } }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.width(50.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(session.startAtMs)),
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

@Composable
private fun heatmapColor(level: Int): Color {
    val base = MaterialTheme.colorScheme.primary
    return when (level) {
        0 -> MaterialTheme.colorScheme.surfaceContainerHighest
        1 -> base.copy(alpha = 0.28f)
        2 -> base.copy(alpha = 0.50f)
        3 -> base.copy(alpha = 0.74f)
        else -> base
    }
}

private val CELL_SIZE = 13.dp
private val CELL_GAP = 3.dp

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
