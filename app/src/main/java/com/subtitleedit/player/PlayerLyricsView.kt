package com.subtitleedit.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** The selected subtitle's complete document, sharing the playback clock with native subtitles. */
@Composable
internal fun PlayerLyricsView(
    lines: List<PlayerLyricLine>,
    positionMs: Long,
    subtitleText: String,
    loading: Boolean,
    fontSize: Int,
    onSeek: (Long) -> Unit,
    onOpenSubtitles: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().testTag("player-lyrics-view")) {
        when {
            loading && lines.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(32.dp), color = PlayerColors.Accent)
            }
            lines.isNotEmpty() -> PlayerLyricList(lines, positionMs, fontSize, onSeek,
                Modifier.fillMaxWidth().weight(1f))
            else -> Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        subtitleText.takeIf { it.isNotBlank() } ?: stringResource(R.string.player_lyrics_empty),
                        color = if (subtitleText.isBlank()) PlayerColors.OnDarkVariant else PlayerColors.OnDark,
                        fontSize = fontSize.sp,
                        textAlign = TextAlign.Center
                    )
                    if (subtitleText.isBlank()) TextButton(onClick = onOpenSubtitles,
                        modifier = Modifier.testTag("player-lyrics-select-subtitle")) {
                        Text(stringResource(R.string.player_lyrics_select_subtitle), color = PlayerColors.Accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerLyricList(
    lines: List<PlayerLyricLine>,
    positionMs: Long,
    fontSize: Int,
    onSeek: (Long) -> Unit,
    modifier: Modifier
) {
    val listState = rememberLazyListState()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var following by remember(lines) { mutableStateOf(true) }
    var firstScroll by remember(lines) { mutableStateOf(true) }
    val activeIndex = remember(lines, positionMs) {
        PlayerLyricTimeline.activeLineIndex(lines, positionMs)
    }
    // Keep the nearest previous cue in view during intentional gaps between subtitle cues.
    val followIndex = remember(lines, positionMs, activeIndex) {
        if (activeIndex >= 0) activeIndex else lines.indices.filter { lines[it].startMs <= positionMs }
            .maxByOrNull { lines[it].startMs } ?: 0
    }
    LaunchedEffect(dragging) {
        if (dragging) following = false
        else if (!following) {
            snapshotFlow { listState.isScrollInProgress }.first { !it }
            delay(3_000L)
            following = true
        }
    }
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val viewportHeightPx = with(density) { maxHeight.roundToPx() }
        val verticalPadding = (maxHeight / 2 - 32.dp).coerceAtLeast(0.dp)
        val paddingPx = with(density) { verticalPadding.roundToPx() }
        val estimatedLineHeightPx = with(density) { (fontSize.sp.toDp() * 1.5f + 24.dp).roundToPx() }
        LaunchedEffect(followIndex, following, viewportHeightPx, lines) {
            if (!following || lines.isEmpty() || viewportHeightPx <= 0) return@LaunchedEffect
            val height = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == followIndex }?.size
                ?: estimatedLineHeightPx
            val offset = paddingPx - ((viewportHeightPx - height) / 2).coerceAtLeast(0)
            if (firstScroll) {
                listState.scrollToItem(followIndex, offset)
                firstScroll = false
            } else listState.animateScrollToItem(followIndex, offset)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("player-lyrics-list"),
            contentPadding = PaddingValues(vertical = verticalPadding)
        ) {
            itemsIndexed(lines) { index, line ->
                val active = index == activeIndex
                Text(
                    text = line.text,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (active) PlayerColors.SurfaceHigh.copy(alpha = 0.65f) else Color.Transparent)
                        .clickable { onSeek(line.startMs) }
                        .testTag("player-lyrics-line:$index").semantics { selected = active }
                        .heightIn(min = 56.dp).padding(horizontal = 28.dp, vertical = 12.dp),
                    color = if (active) PlayerColors.OnDark else PlayerColors.OnDarkVariant.copy(alpha = 0.72f),
                    fontSize = fontSize.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
