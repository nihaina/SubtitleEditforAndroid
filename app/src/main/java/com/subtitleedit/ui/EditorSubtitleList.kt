package com.subtitleedit.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLocale
import com.subtitleedit.R
import com.subtitleedit.adapter.SubtitleAdapter
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SearchTextMatcher
import com.subtitleedit.util.SubtitleTimeConflict
import com.subtitleedit.util.TimeUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.snapshotFlow
import kotlin.math.roundToInt
import java.util.Locale

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
    val adapterRevision = adapter.composeRevision.value

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.stableId }) { position, entry ->
                // A stable-keyed row can survive a structural list change. Resolve its current
                // position for callbacks just like RecyclerView's adapterPosition, while the
                // positional value remains useful for rendering conflict markers in `entries`.
                fun currentRow(): Pair<SubtitleEntry, Int>? {
                    val currentPosition = adapter.positionOfStableId(entry.stableId)
                    if (currentPosition !in adapter.currentList.indices) return null
                    return adapter.currentList[currentPosition] to currentPosition
                }
                SubtitleRow(
                    entry = entry,
                    position = position,
                    contentRevision = contentRevision,
                    selectionRevision = adapterRevision,
                    entries = entries,
                    selected = currentRow()?.second?.let(adapter::isSelected) == true,
                    playing = currentRow()?.second?.let(adapter::isPlayingPosition) == true,
                    // Search results are calculated against the live document. Match by stable
                    // ID when available so an inserted/removed row cannot move the highlight to
                    // a different subtitle while Compose retains the keyed item group.
                    searchHighlighted = adapter.searchHighlightStableId()?.let { stableId ->
                        stableId == entry.stableId
                    } ?: (currentRow()?.second == adapter.searchHighlightPosition()),
                    searchQuery = adapter.searchQuery(),
                    searchMatchCase = adapter.searchMatchCase(),
                    searchWholeWord = adapter.searchWholeWord(),
                    hasPlayableMedia = hasPlayableMedia,
                    onSelect = { currentRow()?.second?.let(adapter::toggleSelection) },
                    onLongClick = {
                        currentRow()?.let { (currentEntry, currentPosition) ->
                            onLongClick(currentEntry, currentPosition)
                        }
                    },
                    onStartTimeClick = {
                        currentRow()?.let { (currentEntry, currentPosition) ->
                            onTimeClick(currentEntry, currentPosition, true)
                        }
                    },
                    onEndTimeClick = {
                        currentRow()?.let { (currentEntry, currentPosition) ->
                            onTimeClick(currentEntry, currentPosition, false)
                        }
                    },
                    onTextClick = {
                        currentRow()?.let { (currentEntry, currentPosition) ->
                            onTextClick(currentEntry, currentPosition)
                        }
                    },
                    onJumpToTime = {
                        currentRow()?.let { (currentEntry, currentPosition) ->
                            onJumpToTime(currentEntry, currentPosition)
                        }
                    },
                    onSetTime = {
                        currentRow()?.let { (currentEntry, currentPosition) ->
                            onSetTime(currentEntry, currentPosition)
                        }
                    }
                )
            }
        }
        DraggableScrollbar(listState, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
internal fun DraggableScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    fixedThumbSize: Boolean = false
) {
    val layoutInfo by remember(state) { derivedStateOf { state.layoutInfo } }
    val visibleItems = layoutInfo.visibleItemsInfo
    val totalCount = layoutInfo.totalItemsCount
    if (totalCount <= visibleItems.size || visibleItems.isEmpty()) return

    val averageItemHeight = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
    val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
    if (averageItemHeight <= 0f || viewportHeight <= 0f) return
    val maxItemIndex = (totalCount - visibleItems.size).coerceAtLeast(1)
    // Include the partially scrolled first row. The legacy RecyclerView thumb was
    // derived from the complete pixel scroll offset, so the handle must move while
    // the first visible item is still the same item.
    val scrollPosition = state.firstVisibleItemIndex +
        state.firstVisibleItemScrollOffset.toFloat() / averageItemHeight
    val scope = rememberCoroutineScope()
    var thumbVisible by remember(state) { mutableStateOf(false) }
    var dragging by remember(state) { mutableStateOf(false) }
    var scrollJob by remember(state) { mutableStateOf<Job?>(null) }
    val thumbAlpha by animateFloatAsState(
        targetValue = if (thumbVisible) 220f / 255f else 0f,
        animationSpec = tween(durationMillis = if (thumbVisible) 0 else 300),
        label = "subtitle scrollbar alpha"
    )
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .drop(1)
            .collectLatest {
                thumbVisible = true
                delay(1200)
                if (!dragging) thumbVisible = false
            }
    }

    BoxWithConstraints(
        modifier = modifier
            .width(24.dp)
            .fillMaxHeight()
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val trackHeightPx = with(density) { maxHeight.toPx() }
        val minThumbPx = with(density) { 48.dp.toPx() }
        val thumbHeight = if (fixedThumbSize) {
            minThumbPx.coerceAtMost(trackHeightPx)
        } else {
            (trackHeightPx * viewportHeight / (averageItemHeight * totalCount))
                .coerceIn(minThumbPx.coerceAtMost(trackHeightPx), trackHeightPx)
        }
        val maxThumbTop = (trackHeightPx - thumbHeight).coerceAtLeast(0f)
        val thumbTop = maxThumbTop * (scrollPosition / maxItemIndex).coerceIn(0f, 1f)
        val currentTrackHeight by rememberUpdatedState(trackHeightPx)
        val currentThumbHeight by rememberUpdatedState(thumbHeight)
        val currentMaxItemIndex by rememberUpdatedState(maxItemIndex)
        val currentThumbTop by rememberUpdatedState(thumbTop)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state) {
                    var touchOffset = 0f
                    var firstItemOffset = 0
                    detectDragGestures(
                        onDragStart = { touch ->
                            dragging = true
                            thumbVisible = true
                            touchOffset = touch.y - currentThumbTop
                            firstItemOffset = state.firstVisibleItemScrollOffset
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val thumbTopNow = (change.position.y - touchOffset)
                                .coerceIn(0f, (currentTrackHeight - currentThumbHeight).coerceAtLeast(0f))
                            val scrollFraction = thumbTopNow /
                                (currentTrackHeight - currentThumbHeight).coerceAtLeast(1f)
                            val target = (scrollFraction * currentMaxItemIndex).roundToInt()
                            val offset = if (scrollFraction <= 0f || scrollFraction >= 1f) 0 else firstItemOffset
                            // Match DraggableRecyclerView's one-pending-target behavior. A
                            // separate coroutine for every MOVE leaves stale scroll requests
                            // queued behind the finger and makes the thumb appear not to track.
                            scrollJob?.cancel()
                            scrollJob = scope.launch {
                                state.scrollToItem(target, offset)
                            }
                        },
                        onDragEnd = {
                            dragging = false
                            scope.launch {
                                delay(1200)
                                if (!dragging) thumbVisible = false
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            scope.launch {
                                delay(1200)
                                if (!dragging) thumbVisible = false
                            }
                        }
                    )
                }
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val thumbWidth = 8.dp.toPx()
                drawRoundRect(
                    color = Color(0xFFA0A0A0).copy(alpha = thumbAlpha),
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
    selectionRevision: Int,
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
        searchHighlighted -> colorResource(R.color.primary_container)
        playing -> colorResource(R.color.playing_highlight)
        else -> scheme.surface
    }
    // The row key is intentionally the stable subtitle id, so Compose may retain the item
    // group while a waveform drag replaces the entry snapshot. Include the editor revision in a
    // real composition dependency; otherwise the timestamp labels can remain from the previous
    // snapshot even though the parent list has published a new one.
    // Keep selection changes as an explicit row dependency. Stable LazyColumn keys preserve the
    // item group while SubtitleAdapter replaces its detached snapshot; including both revisions
    // prevents a reused row from retaining stale timing or selection visuals.
    val markers = remember(contentRevision, selectionRevision, entries, position) {
        SubtitleTimeConflict.markers(entries, position)
    }
    val locale = LocalLocale.current.platformLocale

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .combinedClickable(onClick = onSelect, onLongClick = onLongClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .then(if (selected) Modifier.alpha(0.6f) else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = scheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = String.format(locale, "%d", entry.index),
                        color = scheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 14.sp,
                            letterSpacing = 0.sp
                        ),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = TimeUtils.formatForInput(entry.startTime),
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(onClick = onStartTimeClick, onLongClick = onLongClick)
                            .padding(4.dp),
                        color = if (markers.start) scheme.error else scheme.primary,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 12.sp,
                            letterSpacing = 0.sp
                        ),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    Text(
                        text = stringResource(R.string.item_subtitle_text_01),
                        modifier = Modifier.padding(horizontal = 4.dp),
                        color = scheme.onSurface,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            letterSpacing = 0.sp
                        )
                    )
                    Text(
                        text = TimeUtils.formatForInput(entry.endTime),
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(onClick = onEndTimeClick, onLongClick = onLongClick)
                            .padding(4.dp),
                        color = if (markers.end) scheme.error else scheme.primary,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 12.sp,
                            letterSpacing = 0.sp
                        ),
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
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp, end = 4.dp),
                        color = scheme.onSurface,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            letterSpacing = 0.sp
                        ),
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
                        highlightColor = colorResource(R.color.inverse_primary)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = onTextClick, onLongClick = onLongClick)
                        .padding(top = 4.dp, bottom = 4.dp, start = 4.dp, end = 4.dp),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        letterSpacing = 0.sp
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                modifier = Modifier.padding(start = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (selected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        // ic_check.xml carries the legacy secondary tint. Let the drawable
                        // resolve its own tint instead of replacing it with the M3 primary.
                        contentDescription = stringResource(R.string.item_subtitle_contentdescription_01),
                        tint = Color.Unspecified,
                        modifier = Modifier.size(24.dp)
                    )
                }
                if (hasPlayableMedia) {
                    SubtitleActionButton(
                        onClick = onJumpToTime,
                        contentDescription = stringResource(R.string.item_subtitle_contentdescription_02),
                        icon = android.R.drawable.ic_media_play
                    )
                    SubtitleActionButton(
                        onClick = onSetTime,
                        contentDescription = stringResource(R.string.item_subtitle_contentdescription_03),
                        icon = android.R.drawable.ic_menu_edit
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleActionButton(
    onClick: () -> Unit,
    contentDescription: String,
    @DrawableRes icon: Int
) {
    // ImageButton in item_subtitle.xml is exactly 32dp. A Material3 IconButton applies
    // a 48dp minimum touch target, which changes the legacy row width, so use an explicit
    // 32dp clickable box for these row actions.
    Box(
        modifier = Modifier
            .size(32.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = Color.Unspecified,
            modifier = Modifier.size(24.dp)
        )
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
    if (query.isEmpty()) return@buildAnnotatedString
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
