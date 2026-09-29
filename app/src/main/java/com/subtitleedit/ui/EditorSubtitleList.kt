package com.subtitleedit.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.adapter.SubtitleAdapter
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SearchTextMatcher
import com.subtitleedit.util.SubtitleTimeConflict
import com.subtitleedit.util.TimeUtils
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun EditorSubtitleList(
    entries: List<SubtitleEntry>,
    contentRevision: Int,
    listState: LazyListState,
    adapter: SubtitleAdapter,
    hasPlayableMedia: Boolean,
    onLongClick: (SubtitleEntry, Int) -> Unit,
    onTimeClick: (SubtitleEntry, Int, Boolean) -> Unit,
    onTextClick: (SubtitleEntry, Int) -> Unit,
    onJumpToTime: (SubtitleEntry, Int) -> Unit,
    onSetTime: (SubtitleEntry, Int) -> Unit
) {
    @Suppress("UNUSED_VARIABLE")
    val adapterRevision = adapter.composeRevision.value

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.stableId }) { position, entry ->
                SubtitleRow(
                    entry = entry,
                    position = position,
                    contentRevision = contentRevision,
                    entries = entries,
                    selected = position < adapter.itemCount && adapter.isSelected(position),
                    playing = adapter.isPlayingPosition(position),
                    searchHighlighted = adapter.searchHighlightPosition() == position,
                    searchQuery = adapter.searchQuery(),
                    searchMatchCase = adapter.searchMatchCase(),
                    searchWholeWord = adapter.searchWholeWord(),
                    hasPlayableMedia = hasPlayableMedia,
                    onSelect = { if (position < adapter.itemCount) adapter.toggleSelection(position) },
                    onLongClick = { onLongClick(entry, position) },
                    onStartTimeClick = { onTimeClick(entry, position, true) },
                    onEndTimeClick = { onTimeClick(entry, position, false) },
                    onTextClick = { onTextClick(entry, position) },
                    onJumpToTime = { onJumpToTime(entry, position) },
                    onSetTime = { onSetTime(entry, position) }
                )
            }
        }
        DraggableScrollbar(listState, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun DraggableScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val layoutInfo by remember(state) { derivedStateOf { state.layoutInfo } }
    val visibleItems = layoutInfo.visibleItemsInfo
    val totalCount = layoutInfo.totalItemsCount
    if (totalCount <= visibleItems.size || visibleItems.isEmpty()) return

    val averageItemHeight = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
    val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
    if (averageItemHeight <= 0f || viewportHeight <= 0f) return
    val maxItemIndex = (totalCount - visibleItems.size).coerceAtLeast(1)
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    BoxWithConstraints(
        modifier = modifier
            .width(28.dp)
            .fillMaxHeight()
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val trackHeightPx = with(density) { maxHeight.toPx() }
        val minThumbPx = with(density) { 48.dp.toPx() }
        val thumbHeight = (trackHeightPx * viewportHeight / (averageItemHeight * totalCount))
            .coerceIn(minThumbPx.coerceAtMost(trackHeightPx), trackHeightPx)
        val maxThumbTop = (trackHeightPx - thumbHeight).coerceAtLeast(0f)
        val thumbTop = maxThumbTop * state.firstVisibleItemIndex / maxItemIndex
        val currentTrackHeight by rememberUpdatedState(trackHeightPx)
        val currentThumbHeight by rememberUpdatedState(thumbHeight)
        val currentMaxItemIndex by rememberUpdatedState(maxItemIndex)
        val currentThumbTop by rememberUpdatedState(thumbTop)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state) {
                    var touchOffset = 0f
                    detectDragGestures(
                        onDragStart = { touch ->
                            touchOffset = touch.y - currentThumbTop
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val thumbTopNow = (change.position.y - touchOffset)
                                .coerceIn(0f, (currentTrackHeight - currentThumbHeight).coerceAtLeast(0f))
                            val scrollFraction = thumbTopNow /
                                (currentTrackHeight - currentThumbHeight).coerceAtLeast(1f)
                            scope.launch {
                                state.scrollToItem((scrollFraction * currentMaxItemIndex).roundToInt())
                            }
                        }
                    )
                }
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val thumbWidth = 6.dp.toPx()
                drawRoundRect(
                    color = scheme.onSurfaceVariant.copy(alpha = 0.55f),
                    topLeft = Offset(size.width - thumbWidth - 3.dp.toPx(), thumbTop),
                    size = Size(thumbWidth, thumbHeight),
                    cornerRadius = CornerRadius(thumbWidth / 2)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubtitleRow(
    entry: SubtitleEntry,
    position: Int,
    contentRevision: Int,
    entries: List<SubtitleEntry>,
    selected: Boolean,
    playing: Boolean,
    searchHighlighted: Boolean,
    searchQuery: String,
    searchMatchCase: Boolean,
    searchWholeWord: Boolean,
    hasPlayableMedia: Boolean,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    onStartTimeClick: () -> Unit,
    onEndTimeClick: () -> Unit,
    onTextClick: () -> Unit,
    onJumpToTime: () -> Unit,
    onSetTime: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val containerColor = when {
        searchHighlighted -> scheme.primaryContainer
        playing -> scheme.secondaryContainer
        selected -> scheme.surfaceVariant
        else -> scheme.surface
    }
    val markers = SubtitleTimeConflict.markers(entries, position)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onSelect, onLongClick = onLongClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(32.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                        color = scheme.primary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = entry.index.toString(),
                                color = scheme.onPrimary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = TimeUtils.formatForInput(entry.startTime),
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(onClick = onStartTimeClick, onLongClick = onLongClick)
                            .padding(vertical = 4.dp),
                        color = if (markers.start) scheme.error else scheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    Text(
                        text = "-",
                        modifier = Modifier.padding(horizontal = 6.dp),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = TimeUtils.formatForInput(entry.endTime),
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(onClick = onEndTimeClick, onLongClick = onLongClick)
                            .padding(vertical = 4.dp),
                        color = if (markers.end) scheme.error else scheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
                val metadata = buildList {
                    if (entry.cueIdentifier.isNotBlank()) add("ID: ${entry.cueIdentifier}")
                    if (entry.cueSettings.isNotBlank()) add(entry.cueSettings)
                }.joinToString(" · ")
                if (metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        modifier = Modifier.padding(start = 4.dp),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = highlightedText(
                        text = entry.text,
                        query = if (searchHighlighted) searchQuery else "",
                        matchCase = searchMatchCase,
                        wholeWord = searchWholeWord,
                        highlightColor = scheme.inversePrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = onTextClick, onLongClick = onLongClick)
                        .padding(start = 4.dp, top = 2.dp, end = 4.dp, bottom = 2.dp),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (hasPlayableMedia) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onJumpToTime, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_video_play),
                            contentDescription = "跳转到字幕时间",
                            tint = scheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onSetTime, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_auto_timestamp),
                            contentDescription = "设置字幕时间为当前进度",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            } else {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onSelect() },
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}

private fun highlightedText(
    text: String,
    query: String,
    matchCase: Boolean,
    wholeWord: Boolean,
    highlightColor: Color
) = buildAnnotatedString {
    append(text)
    if (query.isBlank()) return@buildAnnotatedString
    val range = SearchTextMatcher.firstMatchRange(text, query, matchCase, wholeWord) ?: return@buildAnnotatedString
    addStyle(
        SpanStyle(
            background = highlightColor,
            fontWeight = FontWeight.Bold
        ),
        range.first,
        range.last + 1
    )
}
