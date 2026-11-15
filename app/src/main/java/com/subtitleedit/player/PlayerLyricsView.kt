package com.subtitleedit.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.abs

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
    modifier: Modifier = Modifier,
    lineSpacing: Int = PlayerPreferences.DEFAULT_AUDIO_SUBTITLE_LINE_SPACING,
    alignment: PlayerSubtitleAlignment = PlayerSubtitleAlignment.CENTER,
    onUserScroll: () -> Unit = {}
) {
    val textStyle = playerAudioSubtitleTextStyle(fontSize, lineSpacing, alignment)
    Column(modifier.fillMaxSize().testTag("player-lyrics-view")) {
        when {
            loading && lines.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(32.dp), color = PlayerColors.Accent)
            }
            lines.isNotEmpty() -> PlayerLyricList(lines, positionMs, fontSize, lineSpacing, textStyle, onSeek, onUserScroll,
                Modifier.fillMaxWidth().weight(1f))
            else -> Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        subtitleText.takeIf { it.isNotBlank() } ?: stringResource(R.string.player_lyrics_empty),
                        color = if (subtitleText.isBlank()) PlayerColors.OnDarkVariant else PlayerColors.OnDark,
                        modifier = Modifier.fillMaxWidth(),
                        style = textStyle
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
    lineSpacing: Int,
    textStyle: TextStyle,
    onSeek: (Long) -> Unit,
    onUserScroll: () -> Unit,
    modifier: Modifier
) {
    val listState = rememberLazyListState()
    var following by remember(lines) { mutableStateOf(true) }
    var firstScroll by remember(lines) { mutableStateOf(true) }
    var userScrolling by remember(lines) { mutableStateOf(false) }
    var userScrollRevision by remember(lines) { mutableLongStateOf(0L) }
    val currentOnUserScroll by rememberUpdatedState(onUserScroll)
    val scrollConnection = remember(listState, lines) {
        object : NestedScrollConnection {
            private fun userMoved() {
                following = false
                userScrolling = true
                userScrollRevision++
                currentOnUserScroll()
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) userMoved()
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // The same user session includes inertial movement after the finger lifts.
                // Programmatic following runs only after this session has ended.
                if (userScrolling && consumed.y != 0f) userMoved()
                return Offset.Zero
            }
        }
    }
    val activeIndex = remember(lines, positionMs) {
        PlayerLyricTimeline.activeLineIndex(lines, positionMs)
    }
    // Keep the nearest previous cue in view during intentional gaps between subtitle cues.
    val followIndex = remember(lines, positionMs, activeIndex) {
        if (activeIndex >= 0) activeIndex else lines.indices.filter { lines[it].startMs <= positionMs }
            .maxByOrNull { lines[it].startMs } ?: 0
    }
    LaunchedEffect(userScrollRevision) {
        if (userScrollRevision == 0L) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }.first { !it }
        delay(3_000L)
        userScrolling = false
        following = true
    }
    BoxWithConstraints(modifier) {
        val viewportHeightPx = with(LocalDensity.current) { maxHeight.roundToPx() }
        // Half a viewport of real content padding lets both the first and the last
        // line reach the center, regardless of font size or multiline row height.
        val verticalPadding = maxHeight / 2
        val spacing = lineSpacing.coerceIn(PlayerPreferences.MIN_AUDIO_SUBTITLE_LINE_SPACING,
            PlayerPreferences.MAX_AUDIO_SUBTITLE_LINE_SPACING)
        val measuredFollowHeight by remember(listState, followIndex) {
            derivedStateOf { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == followIndex }?.size }
        }
        val measuredViewport by remember(listState) {
            derivedStateOf { listState.layoutInfo.viewportStartOffset to listState.layoutInfo.viewportEndOffset }
        }
        LaunchedEffect(followIndex, following, viewportHeightPx, maxWidth, lines, fontSize, spacing,
            measuredFollowHeight, measuredViewport) {
            if (!following || lines.isEmpty() || viewportHeightPx <= 0) return@LaunchedEffect
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == followIndex }) {
                listState.scrollToItem(followIndex)
            }
            val item = snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == followIndex }
            }.filterNotNull().first()
            val viewport = listState.layoutInfo
            val center = (viewport.viewportStartOffset + viewport.viewportEndOffset) / 2f
            val distance = item.offset + item.size / 2f - center
            if (abs(distance) > 0.5f) {
                if (firstScroll) listState.scrollBy(distance) else listState.animateScrollBy(distance)
            }
            firstScroll = false
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection).testTag("player-lyrics-list"),
            contentPadding = PaddingValues(vertical = verticalPadding)
        ) {
            itemsIndexed(lines) { index, line ->
                val active = index == activeIndex
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .background(if (active) PlayerColors.SurfaceHigh.copy(alpha = 0.65f) else Color.Transparent)
                        .clickable { onSeek(line.startMs) }
                        .testTag("player-lyrics-line:$index").semantics { selected = active }
                        .padding(horizontal = 28.dp, vertical = spacing.dp / 2),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = line.text,
                        modifier = Modifier.fillMaxWidth().testTag("player-lyrics-text:$index"),
                        color = if (active) PlayerColors.OnDark else PlayerColors.OnDarkVariant.copy(alpha = 0.72f),
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        style = textStyle
                    )
                }
            }
        }
    }
}

internal val PlayerSubtitleAlignment.textAlign: TextAlign
    get() = when (this) {
        PlayerSubtitleAlignment.LEFT -> TextAlign.Left
        PlayerSubtitleAlignment.CENTER -> TextAlign.Center
        PlayerSubtitleAlignment.RIGHT -> TextAlign.Right
    }

/** Line-height trim keeps a single line compact while preserving spacing inside multiline cues. */
@Composable
internal fun playerAudioSubtitleTextStyle(fontSize: Int, lineSpacing: Int, alignment: PlayerSubtitleAlignment): TextStyle {
    val spacing = lineSpacing.coerceIn(PlayerPreferences.MIN_AUDIO_SUBTITLE_LINE_SPACING,
        PlayerPreferences.MAX_AUDIO_SUBTITLE_LINE_SPACING)
    val lineHeight = with(LocalDensity.current) { (fontSize.sp.toDp() + spacing.dp).toSp() }
    return LocalTextStyle.current.copy(
        fontSize = fontSize.sp,
        lineHeight = lineHeight,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        textAlign = alignment.textAlign,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
    )
}
