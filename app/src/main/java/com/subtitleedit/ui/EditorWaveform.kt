package com.subtitleedit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.subtitleedit.R
import com.subtitleedit.editor.EditorWaveformDisplayMode
import com.subtitleedit.editor.EditorWaveformDragMode
import com.subtitleedit.editor.EditorWaveformState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import android.os.SystemClock
import android.graphics.Paint
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Events emitted by [EditorWaveform] and handled by the editor controller. */
internal data class EditorWaveformActions(
    val onViewportSizeChanged: (widthPx: Int, heightPx: Int) -> Unit = { _, _ -> },
    val onViewportPan: (deltaMs: Long) -> Unit = {},
    val onViewportPanTo: (startMs: Long) -> Unit = {},
    val onZoom: (scale: Float, anchorMs: Long) -> Unit = { _, _ -> },
    val onZoomAbsolute: (startMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    val onSeek: (positionMs: Long) -> Unit = {},
    /** Timeline playhead drag lifecycle; progress is committed to the player on release. */
    val onSeekStarted: () -> Unit = {},
    val onSeekProgress: (positionMs: Long) -> Unit = {},
    val onSeekFinished: () -> Unit = {},
    val onSelectSubtitle: (index: Int?) -> Unit = {},
    val onLimitedPlaybackRangeChanged: (index: Int?) -> Unit = {},
    val onLimitedPlaybackStartRequest: (index: Int) -> Unit = {},
    val onSubtitleStartSeekRequest: (positionMs: Long) -> Unit = {},
    val onLimitedPlaybackRangeOutOfView: () -> Unit = {},
    val onSubtitleDrag: (index: Int, mode: EditorWaveformDragMode, deltaMs: Long, isFinal: Boolean) -> Unit =
        { _, _, _, _ -> },
    /** Clears a cancelled pointer sequence before the next subtitle drag establishes its base. */
    val onSubtitleDragGestureStarted: () -> Unit = {},
    val onTimestampViewportPan: (deltaMs: Long) -> Unit = {},
    val onTimestampFinished: () -> Unit = {},
    val onToggleExpanded: () -> Unit = {},
    val onToggleDisplayMode: () -> Unit = {},
    val onAmplitudeZoomIn: () -> Unit = {},
    val onAmplitudeZoomOut: () -> Unit = {},
    val onAmplitudeReset: () -> Unit = {},
    val onGenerate: () -> Unit = {},
    val onStartTimestampingLongClick: () -> Unit = {},
    val onQuickTranscribe: () -> Unit = {},
    val onQuickTranscribeLongClick: () -> Unit = {},
    val onQuickTts: () -> Unit = {},
    val onQuickTtsLongClick: () -> Unit = {},
    val onQuickSplit: () -> Unit = {},
    val onChunkRequested: (chunkIndex: Int, startMs: Long, endMs: Long, targetSamples: Int) -> Unit =
        { _, _, _, _ -> },
    val onSpectrogramChunkRequested: (chunkIndex: Int, startMs: Long, endMs: Long, widthPx: Int, heightPx: Int) -> Unit =
        { _, _, _, _, _ -> }
)

/**
 * Material 3 waveform controls and timeline. The timeline is rendered entirely by Compose;
 * no Android View is created or embedded here.
 */
@Composable
internal fun EditorWaveformPanel(
    state: EditorWaveformState,
    actions: EditorWaveformActions,
    modifier: Modifier = Modifier
) {
    if (!state.hasPlayableMedia) return
    Column(modifier = modifier.fillMaxWidth()) {
        EditorWaveformToolbar(state, actions)
        if (state.isExpanded) {
            Card(
                modifier = Modifier.fillMaxWidth().height(185.dp),
                shape = RoundedCornerShape(4.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Box(Modifier.fillMaxSize()) {
                    EditorWaveform(
                        state = state,
                        actions = actions,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (showGenerationButton(state)) {
                        FilledTonalButton(
                            onClick = actions.onGenerate,
                            enabled = state.hasAudioTrack && !state.isPreparingCacheIndex &&
                                state.cacheIndexFailure == null && !state.isWaveformGenerating,
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Text(generationLabel(state))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EditorWaveformToolbar(
    state: EditorWaveformState,
    actions: EditorWaveformActions
) {
    Surface(color = Color(0xFF1E1E1E)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            WaveformCompactButton(
                onClick = actions.onToggleExpanded,
                width = 24.dp,
                height = 24.dp,
                contentDescription = stringResource(R.string.activity_editor_contentdescription_02),
                content = {
                    Text(
                        if (state.isExpanded) "▼" else "▶",
                        color = LEGACY_TOOL_TEXT,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            letterSpacing = 0.sp
                        )
                    )
                }
            )
            WaveformTimestampButton(actions)
            WaveformCompactButton(
                onClick = actions.onQuickTranscribe,
                onLongClick = actions.onQuickTranscribeLongClick,
                contentDescription = stringResource(R.string.activity_editor_contentdescription_04),
                modifier = Modifier.padding(start = 4.dp),
                content = { Icon(painterResource(R.drawable.ic_quick_transcribe), null, tint = LEGACY_TOOL_TEXT) }
            )
            WaveformCompactButton(
                onClick = actions.onQuickTts,
                onLongClick = actions.onQuickTtsLongClick,
                contentDescription = stringResource(R.string.quick_tts_selected_subtitles),
                modifier = Modifier.padding(start = 4.dp),
                content = { Icon(painterResource(R.drawable.ic_tts), null, tint = LEGACY_TOOL_TEXT) }
            )
            WaveformCompactButton(
                onClick = actions.onQuickSplit,
                contentDescription = stringResource(R.string.quick_split_subtitle),
                modifier = Modifier.padding(start = 4.dp),
                content = { Icon(painterResource(R.drawable.ic_quick_split), null, tint = LEGACY_TOOL_TEXT) }
            )
            Spacer(Modifier.weight(1f))
            WaveformCompactButton(
                onClick = actions.onToggleDisplayMode,
                width = 40.dp,
                height = 24.dp,
                modifier = Modifier.padding(end = 4.dp),
                contentDescription = stringResource(R.string.activity_editor_contentdescription_05),
                content = {
                    Text(
                        if (state.displayMode == EditorWaveformDisplayMode.WAVEFORM) "频谱" else "波形",
                        color = LEGACY_TOOL_TEXT,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            letterSpacing = 0.sp
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            )
            val amplitudeEnabled = state.isExpanded &&
                state.displayMode == EditorWaveformDisplayMode.WAVEFORM
            WaveformCompactButton(
                onClick = actions.onAmplitudeZoomOut,
                width = 24.dp,
                height = 24.dp,
                modifier = Modifier.padding(end = 4.dp),
                enabled = amplitudeEnabled,
                contentDescription = stringResource(R.string.activity_editor_contentdescription_06),
                content = {
                    Text(
                        "−",
                        color = if (amplitudeEnabled) LEGACY_TOOL_TEXT else Color(0xFF555555),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            letterSpacing = 0.sp
                        )
                    )
                }
            )
            WaveformCompactButton(
                onClick = actions.onAmplitudeZoomIn,
                width = 24.dp,
                height = 24.dp,
                onLongClick = actions.onAmplitudeReset,
                enabled = amplitudeEnabled,
                contentDescription = stringResource(R.string.activity_editor_contentdescription_07),
                content = {
                    Text(
                        "+",
                        color = if (amplitudeEnabled) LEGACY_TOOL_TEXT else Color(0xFF555555),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            letterSpacing = 0.sp
                        )
                    )
                }
            )
            if (state.isSpectrogramGenerationStarted && state.spectrogramTotalChunks > 0) {
                Text(
                    text = "${state.spectrogramDoneChunks}/${state.spectrogramTotalChunks}",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                    color = LEGACY_TOOL_TEXT,
                    modifier = Modifier.padding(start = 2.dp, end = 2.dp)
                )
            }
        }
    }
}

/** Legacy insert button: long press starts timestamping and release commits the range. */
@Composable
private fun WaveformTimestampButton(actions: EditorWaveformActions) {
    val latestActions by rememberUpdatedState(actions)
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(width = 28.dp, height = 28.dp)
            .background(LEGACY_TOOL_BUTTON_BACKGROUND, LEGACY_TOOL_BUTTON_SHAPE)
            .border(1.dp, LEGACY_TOOL_BUTTON_BORDER, LEGACY_TOOL_BUTTON_SHAPE)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    longPress.consume()
                    latestActions.onStartTimestampingLongClick()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        latestActions.onTimestampFinished()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_insert_subtitle),
            contentDescription = stringResource(R.string.activity_editor_contentdescription_03),
            tint = Color.LightGray,
            modifier = Modifier.padding(3.dp)
        )
    }
}

@Composable
@androidx.compose.foundation.ExperimentalFoundationApi
private fun WaveformCompactButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    width: androidx.compose.ui.unit.Dp = 28.dp,
    height: androidx.compose.ui.unit.Dp = 28.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .background(LEGACY_TOOL_BUTTON_BACKGROUND, LEGACY_TOOL_BUTTON_SHAPE)
            .border(1.dp, LEGACY_TOOL_BUTTON_BORDER, LEGACY_TOOL_BUTTON_SHAPE)
            .semantics { this.contentDescription = contentDescription }
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * A low level canvas surface. Gesture coordinates are translated to timeline milliseconds before
 * they leave this composable, so callers never need to know the canvas size.
 */
@Composable
internal fun EditorWaveform(
    state: EditorWaveformState,
    actions: EditorWaveformActions,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Unspecified) }
    val gestureScope = androidx.compose.runtime.rememberCoroutineScope()
    var pendingSingleTapJob by remember { mutableStateOf<Job?>(null) }
    val latestState by rememberUpdatedState(state)
    val latestActions by rememberUpdatedState(actions)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val playheadTolerance = with(density) { 24.dp.toPx() }
    val viewConfiguration = LocalViewConfiguration.current
    val doubleTapSlop = viewConfiguration.touchSlop * 2f
    val subtitleTextPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            isFakeBoldText = true
        }
    }
    val handleTextPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 22f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
    }
    val rulerTextPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#9E9E9E")
            textSize = 24f
        }
    }
    val loadingPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#9E9E9E")
            textSize = 32f
            textAlign = Paint.Align.CENTER
        }
    }

    LaunchedEffect(
        state.visibleStartMs,
        state.visibleDurationMs,
        state.durationMs,
        state.displayMode,
        state.isPreparingCacheIndex,
        state.isWaveformGenerated,
        state.isSpectrogramGenerationStarted,
        state.chunkRequestGeneration,
        canvasSize,
        state.initialized
    ) {
        if (!state.initialized || canvasSize.width <= 0 || state.durationMs <= 0L) return@LaunchedEffect
        val totalChunks = state.totalChunks
        if (totalChunks <= 0) return@LaunchedEffect
        val startChunk = timeToChunk(state.visibleStartMs).coerceIn(0, totalChunks - 1)
        val endChunk = timeToChunk(state.visibleStartMs + state.visibleDurationMs)
            .coerceIn(0, totalChunks - 1)
        val targetSamples = targetSamplesForZoom(canvasSize.width, state.visibleDurationMs)
        val requestedChunks = LinkedHashMap<Int, Int>()
        for (chunkIndex in startChunk..endChunk) requestedChunks[chunkIndex] = targetSamples
        // Keep the legacy two-chunk low-resolution prefetch on both sides of the viewport.
        for (chunkIndex in (startChunk - 2).coerceAtLeast(0) until startChunk) {
            requestedChunks.putIfAbsent(chunkIndex, 150)
        }
        for (chunkIndex in (endChunk + 1)..(endChunk + 2).coerceAtMost(totalChunks - 1)) {
            requestedChunks.putIfAbsent(chunkIndex, 150)
        }
        requestedChunks.forEach { (chunkIndex, samples) ->
            val start = chunkIndex * EditorWaveformState.CHUNK_DURATION_MS
            val end = min(
                (chunkIndex + 1L) * EditorWaveformState.CHUNK_DURATION_MS,
                state.durationMs
            )
            if (state.displayMode == EditorWaveformDisplayMode.WAVEFORM) {
                actions.onChunkRequested(chunkIndex, start, end, samples)
            } else if (samples == targetSamples && state.spectrogramChunks[chunkIndex] == null) {
                actions.onSpectrogramChunkRequested(
                    chunkIndex,
                    start,
                    end,
                    SPECTROGRAM_WIDTH,
                    (canvasSize.height * WAVE_HEIGHT_FRACTION).roundToInt().coerceAtLeast(64)
                )
            }
        }
    }

    // The legacy timeline cancels a queued single-tap selection as soon as timestamping starts.
    // The toolbar starts that mode from a separate pointer stream, so handle the state transition
    // here before the delayed selection can fire.
    LaunchedEffect(state.isTimestamping) {
        if (state.isTimestamping) {
            pendingSingleTapJob?.cancel()
            pendingSingleTapJob = null
            lastTapTime = 0L
            lastTapPosition = Offset.Unspecified
        }
    }

    Canvas(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .onSizeChanged {
                canvasSize = it
                actions.onViewportSizeChanged(it.width, it.height)
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Snapshot once per gesture. This keeps the pointer coroutine alive while
                    // playback and drag callbacks publish new Compose state.
                    val state = latestState
                    val actions = latestActions
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Main
                    )
                    // WaveformTimelineView returned true from ACTION_DOWN and owned the full
                    // sequence. Consume the down event so a surrounding gesture cannot claim the
                    // timeline after this pointer stream has started.
                    down.consume()
                    // A cancelled sequence cannot deliver the final drag callback. Reset the
                    // controller's base now so the next sequence cannot reuse that stale origin.
                    actions.onSubtitleDragGestureStarted()
                    val downPosition = down.position
                    val rulerTouch = !state.isTimestamping &&
                        downPosition.y < rulerHeight(canvasSize.height)
                    if (rulerTouch) {
                        // A ruler DOWN cancels a delayed subtitle tap in the legacy view.
                        pendingSingleTapJob?.cancel()
                        pendingSingleTapJob = null
                    }
                    val draggingPlayhead = rulerTouch &&
                        abs(downPosition.x - playheadX(canvasSize.width.toFloat(), state)) <= playheadTolerance
                    var seekDragActive = draggingPlayhead
                    if (seekDragActive) actions.onSeekStarted()
                    fun finishTimelineSeek() {
                        if (seekDragActive) {
                            actions.onSeekFinished()
                            seekDragActive = false
                        }
                    }
                    var selectedSubtitleAtDown = if (downPosition.y >= touchSubtitleTop(canvasSize.height)) {
                        subtitleIndexAt(downPosition.x, canvasSize.width, state)
                            .takeIf { it in state.selectedIndices }
                    } else null
                    var lastPosition = downPosition
                    var activePointerId = down.id
                    var timestampGesture = state.isTimestamping
                    // A timestamp session can start and finish from the toolbar while this
                    // timeline pointer remains down. The legacy View marked that sequence as
                    // discarded at startTimestamping(); retain the same ownership with a
                    // monotonic token because the final Compose snapshot may already have
                    // isTimestamping=false by the time the pointer is released.
                    val timestampGenerationAtDown = state.timestampGestureGeneration
                    var timestampMultiplePointers = false
                    var moved = false
                    var draggedSubtitle = -1
                    var dragMode = EditorWaveformDragMode.NONE
                    var subtitleDragStartX = downPosition.x
                    var subtitleDragCommittedDeltaMs = 0L
                    var subtitleDragLastDeltaMs = 0L
                    var pointerCount = 1
                    var pinchDistance = 0f
                    var pinchStartDurationMs = 0L
                    var pinchAnchorTimeMs = 0L
                    var viewportPanStartMs = state.visibleStartMs
                    var viewportPanStartX = downPosition.x
                    // StateFlow publication from a zoom callback can be observed by Compose one
                    // frame later, while a surviving pointer may already produce its next MOVE.
                    var gestureVisibleStartMs = state.visibleStartMs
                    var gestureVisibleDurationMs = state.visibleDurationMs
                    fun gestureXToTime(x: Float): Long =
                        (gestureVisibleStartMs +
                            x / canvasSize.width.coerceAtLeast(1) * gestureVisibleDurationMs)
                            .toLong().coerceIn(0L, latestState.durationMs)
                    fun finishSubtitleDrag(position: Offset) {
                        if (draggedSubtitle < 0 || dragMode == EditorWaveformDragMode.NONE) return
                        val totalDelta = subtitleDragCommittedDeltaMs +
                            gestureXToTime(position.x) - gestureXToTime(subtitleDragStartX)
                        subtitleDragLastDeltaMs = totalDelta
                        actions.onSubtitleDrag(draggedSubtitle, dragMode, totalDelta, true)
                    }
                    var finished = false
                    val downTime = SystemClock.uptimeMillis()
                    var longPressHandled = false

                    fun handleLongPress() {
                        if (longPressHandled) return
                        pendingSingleTapJob?.cancel()
                        pendingSingleTapJob = null
                        val current = latestState
                        val tapIndex = subtitleIndexAt(
                            downPosition.x,
                            canvasSize.width,
                            current
                        )
                        if (downPosition.y >= touchSubtitleTop(canvasSize.height) && tapIndex >= 0) {
                            actions.onLimitedPlaybackRangeChanged(
                                if (current.limitedPlaybackIndex == tapIndex) null else tapIndex
                            )
                        }
                        lastTapTime = 0L
                        lastTapPosition = Offset.Unspecified
                        longPressHandled = true
                    }

                    while (!finished) {
                        // GestureDetector used by the legacy view fired long press while the
                        // pointer was still down. Waiting only for the eventual ACTION_UP would
                        // delay the selection and misses sequences without a final move event.
                        // Subtitle dragging activates at the legacy 8 px threshold, which is
                        // smaller than Compose's touch slop. Once it is active, cancel the
                        // pending long press immediately; otherwise holding a finger after an
                        // 8-18 px drag can also toggle limited playback.
                        val longPressEligible = !moved && !longPressHandled &&
                            draggedSubtitle < 0 && dragMode == EditorWaveformDragMode.NONE &&
                            downPosition.y >= touchSubtitleTop(canvasSize.height)
                        val remainingLongPressMs = viewConfiguration.longPressTimeoutMillis -
                            (SystemClock.uptimeMillis() - downTime)
                        val event = if (longPressEligible && remainingLongPressMs > 0L) {
                            val timedEvent = withTimeoutOrNull(remainingLongPressMs) {
                                awaitPointerEvent(PointerEventPass.Main)
                            }
                            if (timedEvent == null) {
                                handleLongPress()
                                continue
                            }
                            timedEvent
                        } else {
                            if (longPressEligible && remainingLongPressMs <= 0L) handleLongPress()
                            awaitPointerEvent(PointerEventPass.Main)
                        }
                        val changes = event.changes
                        pointerCount = changes.count { it.pressed }

                        // Timestamping may begin while this finger is already on the timeline.
                        // Its viewport gesture remains active until the toolbar button is released.
                        val liveState = latestState
                        // The legacy View consumed every event in the timeline sequence. Keep the
                        // same ownership in Compose; the Canvas has no child gesture that needs
                        // an unconsumed change.
                        changes.forEach { it.consume() }
                        if (liveState.isTimestamping) {
                            finishTimelineSeek()
                            pendingSingleTapJob?.cancel()
                            pendingSingleTapJob = null
                            timestampGesture = true
                            lastTapTime = 0L
                            lastTapPosition = Offset.Unspecified
                            selectedSubtitleAtDown = null
                            draggedSubtitle = -1
                            dragMode = EditorWaveformDragMode.NONE
                            if (pointerCount == 0) {
                                finished = true
                            } else if (pointerCount >= 2) {
                                timestampMultiplePointers = true
                            } else {
                                val remaining = changes.first { it.pressed }
                                if (timestampMultiplePointers || remaining.id != activePointerId) {
                                    activePointerId = remaining.id
                                    lastPosition = remaining.position
                                    timestampMultiplePointers = false
                                } else {
                                    val deltaX = remaining.position.x - lastPosition.x
                                    if (deltaX != 0f) {
                                        actions.onTimestampViewportPan(
                                            (-deltaX / canvasSize.width.coerceAtLeast(1) * liveState.visibleDurationMs)
                                                .toLong()
                                        )
                                        lastPosition = remaining.position
                                    }
                                }
                            }
                            continue
                        }
                        if (liveState.timestampGestureGeneration != timestampGenerationAtDown) {
                            // The timestamp session ended before this pointer sequence ended.
                            // Consume the remainder so it cannot become a seek, selection, or
                            // subtitle drag after the toolbar release.
                            timestampGesture = true
                            pendingSingleTapJob?.cancel()
                            pendingSingleTapJob = null
                            lastTapTime = 0L
                            lastTapPosition = Offset.Unspecified
                            selectedSubtitleAtDown = null
                            draggedSubtitle = -1
                            dragMode = EditorWaveformDragMode.NONE
                            finishTimelineSeek()
                            finished = pointerCount == 0
                            continue
                        }
                        if (timestampGesture) {
                            // A finger that outlives the button release cannot turn into a seek,
                            // subtitle edit, or ordinary viewport drag.
                            if (pointerCount == 0) finishTimelineSeek()
                            finished = pointerCount == 0
                            continue
                        }
                        // The ruler owns the complete pointer sequence. Keep this before the
                        // generic pinch branch: the legacy View never zoomed when a second
                        // finger was added over the ruler.
                        if (rulerTouch) {
                            val activeChange = changes.firstOrNull { it.pressed }
                            if (activeChange == null) {
                                finishTimelineSeek()
                                finished = true
                            } else {
                                if (seekDragActive && pointerCount == 1 &&
                                    (activeChange.position - lastPosition).getDistance() > 0.5f
                                ) {
                                    actions.onSeekProgress(xToTime(
                                        activeChange.position.x,
                                        canvasSize.width.toFloat(),
                                        latestState
                                    ))
                                }
                                lastPosition = activeChange.position
                            }
                            continue
                        }
                        if (pointerCount >= 2) {
                            moved = true
                            // A second pointer owns the gesture from this point. A delayed
                            // single-tap callback from the preceding DOWN must not toggle the
                            // subtitle selection while pinch zoom is updating the timeline.
                            pendingSingleTapJob?.cancel()
                            pendingSingleTapJob = null
                            val active = changes.filter { it.pressed }
                            val first = active[0].position
                            val second = active[1].position
                            val distance = (second - first).getDistance().coerceAtLeast(1f)
                            if (pinchDistance == 0f) {
                                pinchDistance = distance
                                pinchStartDurationMs = gestureVisibleDurationMs
                                val midpoint = (first + second) / 2f
                                pinchAnchorTimeMs = (
                                    gestureVisibleStartMs +
                                        midpoint.x / canvasSize.width.coerceAtLeast(1) *
                                            gestureVisibleDurationMs
                                    ).toLong().coerceIn(0L, latestState.durationMs)
                            } else {
                                // Keep the gesture's initial span, viewport and anchor fixed,
                                // matching WaveformTimelineView.updatePinch(). This preserves
                                // the correct result when zooming and moving the midpoint in
                                // the same pointer event.
                                val midpoint = (first + second) / 2f
                                val scaleFactor = (distance / pinchDistance).coerceAtLeast(0.01f)
                                val newDuration = (pinchStartDurationMs / scaleFactor)
                                    .toLong().coerceIn(
                                        EditorWaveformState.MIN_VISIBLE_DURATION_MS,
                                        EditorWaveformState.MAX_VISIBLE_DURATION_MS
                                    )
                                val newStart = (pinchAnchorTimeMs -
                                    midpoint.x / canvasSize.width.coerceAtLeast(1) * newDuration)
                                    .toLong().coerceIn(
                                        0L,
                                        (latestState.durationMs - newDuration).coerceAtLeast(0L)
                                    )
                                actions.onZoomAbsolute(newStart, newDuration)
                                gestureVisibleStartMs = newStart
                                gestureVisibleDurationMs = newDuration
                                if (abs(distance - pinchDistance) > 0.5f ||
                                    abs(midpoint.x - downPosition.x) > 0.5f
                                ) {
                                    moved = true
                                }
                            }
                            continue
                        }

                        if (pinchDistance > 0f) {
                            pinchDistance = 0f
                            pinchStartDurationMs = 0L
                            pinchAnchorTimeMs = 0L
                            changes.firstOrNull { it.pressed }?.position?.let { remaining ->
                                // Match WaveformTimelineView.rebaseToRemainingPointer(): after
                                // a pinch, continuing with one finger must pan from the current
                                // viewport and surviving pointer instead of the original DOWN.
                                lastPosition = remaining
                                viewportPanStartMs = gestureVisibleStartMs
                                viewportPanStartX = remaining.x
                                // WaveformTimelineView.rebaseToRemainingPointer() also resets
                                // the subtitle drag origin. Without this, a pinch followed by a
                                // surviving finger computes the mode and delta from the original
                                // DOWN point instead of the current pointer position.
                                if (draggedSubtitle >= 0) {
                                    // Preserve the last business delta already sent to the
                                    // controller. Recomputing it through the post-pinch mapping
                                    // would mistake zoom-induced coordinate changes for dragging.
                                    subtitleDragCommittedDeltaMs = subtitleDragLastDeltaMs
                                }
                                subtitleDragStartX = remaining.x
                            }
                            if (pointerCount == 0) {
                                finishSubtitleDrag(changes.firstOrNull()?.position ?: lastPosition)
                                finished = true
                            }
                            continue
                        }

                        val change = changes.firstOrNull() ?: continue
                        if (!change.pressed) {
                            finishTimelineSeek()
                            val elapsed = SystemClock.uptimeMillis() - downTime
                            // Subtitle dragging has its own 8 px activation threshold, which is
                            // intentionally smaller than Compose's touch slop. Once that
                            // threshold activated a drag, always send the final callback on
                            // release so source-view synchronization can commit the edit.
                            if (draggedSubtitle >= 0 && dragMode != EditorWaveformDragMode.NONE) {
                                finishSubtitleDrag(change.position)
                            } else if (!moved && !longPressHandled &&
                                elapsed >= viewConfiguration.longPressTimeoutMillis
                            ) {
                                handleLongPress()
                            } else if (!moved && !longPressHandled) {
                                val tapIndex = subtitleIndexAt(
                                    downPosition.x,
                                    canvasSize.width,
                                    state
                                )
                                val now = SystemClock.uptimeMillis()
                                val isDoubleTap = downPosition.y >= touchSubtitleTop(canvasSize.height) && tapIndex >= 0 &&
                                    lastTapPosition != Offset.Unspecified &&
                                    now - lastTapTime <= viewConfiguration.doubleTapTimeoutMillis &&
                                    (downPosition - lastTapPosition).getDistance() <= doubleTapSlop
                                if (isDoubleTap) {
                                    pendingSingleTapJob?.cancel()
                                    pendingSingleTapJob = null
                                    if (tapIndex == state.limitedPlaybackIndex) {
                                        actions.onLimitedPlaybackStartRequest(tapIndex)
                                    } else {
                                        state.subtitles.getOrNull(tapIndex)?.let { subtitle ->
                                            actions.onSubtitleStartSeekRequest(subtitle.startTime)
                                        }
                                    }
                                    lastTapTime = 0L
                                    lastTapPosition = Offset.Unspecified
                                } else {
                                    // WaveformTimelineView scheduled a single tap for the whole
                                    // non-ruler surface. A blank tap in the subtitle track clears
                                    // selection, while a blank tap in either the waveform or the
                                    // subtitle track seeks the playhead.
                                    pendingSingleTapJob?.cancel()
                                    val tapPosition = downPosition
                                    pendingSingleTapJob = gestureScope.launch {
                                        delay(SINGLE_TAP_SELECTION_DELAY_MS)
                                        val current = latestState
                                        val currentIndex = if (
                                            tapPosition.y >= touchSubtitleTop(canvasSize.height)
                                        ) {
                                            subtitleIndexAt(
                                                tapPosition.x,
                                                canvasSize.width,
                                                current
                                            )
                                        } else {
                                            -1
                                        }
                                        if (currentIndex >= 0) {
                                            actions.onSelectSubtitle(currentIndex)
                                        } else {
                                            if (tapPosition.y >= touchSubtitleTop(canvasSize.height)) {
                                                actions.onSelectSubtitle(null)
                                            }
                                            actions.onSeek(
                                                xToTime(
                                                    tapPosition.x,
                                                    canvasSize.width.toFloat(),
                                                    current
                                                )
                                            )
                                        }
                                        pendingSingleTapJob = null
                                    }
                                    lastTapTime = now
                                    lastTapPosition = downPosition
                                }
                            }
                            finished = true
                            continue
                        }

                        val delta = change.position - lastPosition
                        if (delta.getDistance() > 0.5f) {
                            if ((change.position - downPosition).getDistance() > viewConfiguration.touchSlop) {
                                pendingSingleTapJob?.cancel()
                                pendingSingleTapJob = null
                                moved = true
                            }
                            if (draggedSubtitle < 0 && selectedSubtitleAtDown != null &&
                                abs(change.position.x - subtitleDragStartX) > 8f
                            ) {
                                pendingSingleTapJob?.cancel()
                                pendingSingleTapJob = null
                                draggedSubtitle = selectedSubtitleAtDown ?: -1
                                dragMode = dragModeAt(
                                    subtitleDragStartX,
                                    draggedSubtitle,
                                    canvasSize.width,
                                    state.copy(
                                        visibleStartMs = gestureVisibleStartMs,
                                        visibleDurationMs = gestureVisibleDurationMs
                                    )
                                )
                            }
                            if (draggedSubtitle >= 0 && dragMode != EditorWaveformDragMode.NONE) {
                                val totalDelta = subtitleDragCommittedDeltaMs +
                                    gestureXToTime(change.position.x) - gestureXToTime(subtitleDragStartX)
                                subtitleDragLastDeltaMs = totalDelta
                                actions.onSubtitleDrag(
                                    draggedSubtitle,
                                    dragMode,
                                    totalDelta,
                                    false
                                )
                            } else if (downPosition.y < touchSubtitleTop(canvasSize.height)) {
                                // The legacy View computed every MOVE from dragStartX and its
                                // original viewport, truncating only once. Passing the absolute
                                // target preserves that behavior for slow drags as well.
                                val dt = ((change.position.x - viewportPanStartX) /
                                    canvasSize.width.coerceAtLeast(1) * gestureVisibleDurationMs)
                                    .toLong()
                                val targetStartMs = (viewportPanStartMs - dt).coerceIn(
                                    0L,
                                    (latestState.durationMs - gestureVisibleDurationMs)
                                        .coerceAtLeast(0L)
                                )
                                actions.onViewportPanTo(targetStartMs)
                                gestureVisibleStartMs = targetStartMs
                                // Check the viewport produced by this MOVE. Using the gesture's
                                // initial state here misses the transition when a limited range
                                // is dragged out of view after the first MOVE.
                                latestState.limitedPlaybackIndex?.let { index ->
                                    latestState.subtitles.getOrNull(index)?.let { subtitle ->
                                        if (subtitle.endTime < targetStartMs ||
                                            subtitle.startTime > targetStartMs + gestureVisibleDurationMs
                                        ) actions.onLimitedPlaybackRangeOutOfView()
                                    }
                                }
                            }
                            lastPosition = change.position
                        }
                    }
                }
            }
    ) {
        drawWaveformContents(
            state, canvasSize, density.density,
            subtitleTextPaint, handleTextPaint, rulerTextPaint, loadingPaint
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaveformContents(
    state: EditorWaveformState,
    canvasSize: IntSize,
    density: Float,
    subtitleTextPaint: Paint,
    handleTextPaint: Paint,
    rulerTextPaint: Paint,
    loadingPaint: Paint
) {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) return
    if (!state.initialized) {
        val centerX = width / 2f
        val centerY = height / 2f
        loadingPaint.style = Paint.Style.STROKE
        loadingPaint.strokeWidth = 4f
        drawContext.canvas.nativeCanvas.drawCircle(centerX, centerY, 40f, loadingPaint)
        loadingPaint.style = Paint.Style.FILL
        drawContext.canvas.nativeCanvas.drawText("加载中...", centerX, centerY + 60f, loadingPaint)
        return
    }
    val ruler = height * RULER_HEIGHT_FRACTION
    val waveformHeight = height * WAVE_HEIGHT_FRACTION
    val subtitleHeight = height - ruler - waveformHeight
    // Keep the timeline's legacy palette independent from the app theme. The old View used
    // these fixed colors for contrast while the rest of the screen followed Material colors.
    drawRect(LEGACY_RULER_BACKGROUND, size = Size(width, ruler))
    drawRect(
        LEGACY_WAVEFORM_BACKGROUND,
        topLeft = Offset(0f, ruler),
        size = Size(width, waveformHeight)
    )
    drawRect(
        LEGACY_SUBTITLE_BACKGROUND,
        topLeft = Offset(0f, ruler + waveformHeight),
        size = Size(width, subtitleHeight)
    )

    val interval = niceRulerInterval(state.visibleDurationMs)
    val minorInterval = (interval / 5L).coerceAtLeast(100L)
    var minor = (state.visibleStartMs / minorInterval) * minorInterval
    while (minor <= state.visibleStartMs + state.visibleDurationMs) {
        if (minor % interval != 0L) {
            val x = timeToXUnclamped(minor, width, state)
            if (x in 0f..width) {
                drawLine(
                    LEGACY_RULER_COLOR.copy(alpha = 0.31f),
                    Offset(x, ruler * 0.65f),
                    Offset(x, ruler),
                    strokeWidth = 1f
                )
            }
        }
        minor += minorInterval
    }
    var major = (state.visibleStartMs / interval) * interval
    var previousLabelRight = -Float.MAX_VALUE
    while (major <= state.visibleStartMs + state.visibleDurationMs) {
        val x = timeToXUnclamped(major, width, state)
        if (x in 0f..width) {
            drawLine(
                LEGACY_RULER_COLOR,
                Offset(x, ruler * 0.25f),
                Offset(x, ruler),
                strokeWidth = 1.2f
            )
            val label = formatRulerTime(major, interval)
            val labelWidth = rulerTextPaint.measureText(label)
            val labelX = (x + 4f).coerceAtMost(width - labelWidth - 2f)
            if (labelX > previousLabelRight + 4f) {
                drawContext.canvas.nativeCanvas.drawText(
                    label, labelX, ruler * 0.78f, rulerTextPaint
                )
                previousLabelRight = labelX + labelWidth
            }
        }
        major += interval
    }

    if (state.displayMode == EditorWaveformDisplayMode.WAVEFORM) {
        drawWaveformSamples(state, width, ruler, waveformHeight)
    } else {
        drawSpectrogram(state, width, ruler, waveformHeight)
    }
    drawSubtitleBlocks(
        state, width, ruler + waveformHeight, subtitleHeight,
        subtitleTextPaint, handleTextPaint
    )
    drawSubtitleEdges(state, width, ruler, waveformHeight + subtitleHeight)

    if (state.isTimestamping) {
        val left = min(
            timeToX(state.timestampStartMs, width, state),
            state.timestampAnchorX
        )
        val right = max(
            timeToX(state.timestampStartMs, width, state),
            state.timestampAnchorX
        )
        drawRect(
            LEGACY_TIMESTAMP_PREVIEW,
            topLeft = Offset(left, ruler),
            size = Size((right - left).coerceAtLeast(1f), height - ruler)
        )
    }

    state.limitedPlaybackIndex?.let { index ->
        state.subtitles.getOrNull(index)?.let { subtitle ->
            val left = timeToXUnclamped(subtitle.startTime, width, state)
            val right = timeToXUnclamped(subtitle.endTime, width, state)
            drawRect(
                LEGACY_LIMITED_PLAYBACK,
                topLeft = Offset(left, ruler),
                size = Size((right - left).coerceAtLeast(1f), height - ruler)
            )
        }
    }

    val playhead = playheadX(width, state)
    val playheadStroke = 0.75f * density
    drawLine(LEGACY_PLAYHEAD, Offset(playhead, 0f), Offset(playhead, height), strokeWidth = playheadStroke)
    drawLine(LEGACY_PLAYHEAD, Offset(playhead - 8f, 0f), Offset(playhead, 8f), strokeWidth = playheadStroke)
    drawLine(LEGACY_PLAYHEAD, Offset(playhead + 8f, 0f), Offset(playhead, 8f), strokeWidth = playheadStroke)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaveformSamples(
    state: EditorWaveformState,
    width: Float,
    top: Float,
    height: Float
) {
    val center = top + height / 2f
    val amplitude = height * 0.45f
    val pixels = width.roundToInt().coerceAtLeast(1)
    for (pixel in 0 until pixels) {
        val t0 = xToTime(pixel.toFloat(), width, state)
        val t1 = xToTime((pixel + 1).toFloat(), width, state)
        val chunkIndex = timeToChunk(t0)
        val data = state.chunkData.getOrNull(chunkIndex)
        if (data == null || data.isEmpty()) {
            drawLine(
                LEGACY_PLACEHOLDER_COLOR,
                Offset(pixel.toFloat(), center - 4f),
                Offset(pixel.toFloat(), center + 4f),
                strokeWidth = 1f
            )
            continue
        }
        val chunkStart = chunkIndex * EditorWaveformState.CHUNK_DURATION_MS
        val chunkEnd = min(
            (chunkIndex + 1L) * EditorWaveformState.CHUNK_DURATION_MS,
            state.durationMs
        )
        val frameCount = (data.size / 2).coerceAtLeast(1)
        val from = (((t0 - chunkStart).toFloat() / (chunkEnd - chunkStart).coerceAtLeast(1L)) * frameCount)
            .toInt().coerceIn(0, frameCount - 1)
        val to = (((t1 - chunkStart).toFloat() / (chunkEnd - chunkStart).coerceAtLeast(1L)) * frameCount)
            .toInt().coerceIn(from + 1, frameCount)
        var peak = 0f
        var trough = 0f
        for (frame in from until to) {
            peak = max(peak, data[frame * 2])
            trough = max(trough, data[frame * 2 + 1])
        }
        drawLine(
            LEGACY_WAVEFORM_COLOR,
            Offset(pixel.toFloat(), center - (peak * amplitude * state.amplitudeScale).coerceAtMost(amplitude)),
            Offset(pixel.toFloat(), center + (trough * amplitude * state.amplitudeScale).coerceAtMost(amplitude)),
            strokeWidth = 1f
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpectrogram(
    state: EditorWaveformState,
    width: Float,
    top: Float,
    height: Float
) {
    val visibleStartChunk = timeToChunk(state.visibleStartMs).coerceAtLeast(0)
    val visibleEndChunk = timeToChunk(state.visibleStartMs + state.visibleDurationMs)
        .coerceAtMost(state.totalChunks - 1)
    if (state.totalChunks <= 0) return
    for (index in visibleStartChunk..visibleEndChunk) {
        val start = index * EditorWaveformState.CHUNK_DURATION_MS
        val end = min((index + 1L) * EditorWaveformState.CHUNK_DURATION_MS, state.durationMs)
        val visibleStart = max(start, state.visibleStartMs)
        val visibleEnd = min(end, state.visibleStartMs + state.visibleDurationMs)
        if (visibleEnd <= visibleStart) continue
        val left = timeToX(visibleStart, width, state)
        val right = timeToX(visibleEnd, width, state)
        val bitmap = state.spectrogramChunks[index]
        if (bitmap == null) {
            drawRect(
                LEGACY_PLACEHOLDER_COLOR,
                topLeft = Offset(left, top),
                size = Size((right - left).coerceAtLeast(1f), height),
                style = Stroke(width = 1f)
            )
            continue
        }
        val sourceLeft = ((visibleStart - start).toFloat() /
            (end - start).coerceAtLeast(1L) * bitmap.width)
            .roundToInt().coerceIn(0, bitmap.width - 1)
        val sourceRight = ((visibleEnd - start).toFloat() /
            (end - start).coerceAtLeast(1L) * bitmap.width)
            .roundToInt().coerceIn(sourceLeft + 1, bitmap.width)
        drawImage(
            bitmap.asImageBitmap(),
            srcOffset = androidx.compose.ui.unit.IntOffset(sourceLeft, 0),
            srcSize = androidx.compose.ui.unit.IntSize(sourceRight - sourceLeft, bitmap.height),
            dstOffset = androidx.compose.ui.unit.IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = androidx.compose.ui.unit.IntSize(
                (right - left).roundToInt().coerceAtLeast(1),
                height.roundToInt().coerceAtLeast(1)
            )
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSubtitleBlocks(
    state: EditorWaveformState,
    width: Float,
    top: Float,
    height: Float,
    subtitleTextPaint: Paint,
    handleTextPaint: Paint
) {
    val boxTop = top + height * 0.15f
    val boxHeight = height * 0.75f
    val visibleIndices = visibleSubtitleIndices(state)
    // The selected block is drawn last so its resize handles remain accessible in overlaps.
    val drawOrder = visibleIndices.filter { it !in state.selectedIndices } +
        visibleIndices.filter { it in state.selectedIndices }
    drawOrder.forEach { index ->
        val subtitle = state.subtitles[index]
        if (subtitle.endTime < state.visibleStartMs ||
            subtitle.startTime > state.visibleStartMs + state.visibleDurationMs
        ) return@forEach
        val left = timeToX(subtitle.startTime, width, state)
        val right = timeToX(subtitle.endTime, width, state)
        val selected = index in state.selectedIndices
        val rawLeft = timeToXUnclamped(subtitle.startTime, width, state)
        val blockWidth = (right - left).coerceAtLeast(4f)
        if (!selected) {
            drawRoundRect(
                color = LEGACY_SUBTITLE_COLOR,
                topLeft = Offset(left, boxTop),
                size = Size(blockWidth, boxHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
            )
        } else {
            val handle = min(30f, blockWidth / 3f)
            val moveLeft = max(left, rawLeft + handle)
            val moveRight = left + blockWidth - handle
            drawRoundRect(
                color = LEGACY_RESIZE_HANDLE_COLOR,
                topLeft = Offset(rawLeft, boxTop),
                size = Size(handle, boxHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
            )
            drawRoundRect(
                color = LEGACY_RESIZE_HANDLE_COLOR,
                topLeft = Offset(left + blockWidth - handle, boxTop),
                size = Size(handle, boxHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
            )
            if (moveRight > moveLeft) {
                drawRect(
                    LEGACY_SELECTED_MOVE_COLOR,
                    topLeft = Offset(moveLeft, boxTop),
                    size = Size(moveRight - moveLeft, boxHeight)
                )
            }
            if (handle >= 14f) {
                val baseline = boxTop + boxHeight / 2f + 8f
                drawContext.canvas.nativeCanvas.drawText("‹", rawLeft + handle / 2f, baseline, handleTextPaint)
                drawContext.canvas.nativeCanvas.drawText(
                    "›", left + blockWidth - handle / 2f, baseline, handleTextPaint
                )
            }
        }
        if (subtitle.text.isNotEmpty()) {
            val handle = if (selected) min(30f, (right - left).coerceAtLeast(4f) / 3f) else 0f
            val textStart = if (selected) max(left, rawLeft + handle) + 4f else left + 10f
            val textEnd = if (selected) left + blockWidth - handle - 4f else left + blockWidth - 10f
            val availableWidth = textEnd - textStart
            if (availableWidth > 12f) {
                drawContext.canvas.nativeCanvas.drawText(
                    clipSubtitleText(subtitle.text, availableWidth, subtitleTextPaint),
                    textStart,
                    boxTop + boxHeight / 2f + 8f,
                    subtitleTextPaint
                )
            }
        }
    }
}

private fun clipSubtitleText(text: String, maxWidth: Float, paint: Paint): String {
    if (paint.measureText(text) <= maxWidth) return text
    var low = 0
    var high = text.length
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (paint.measureText(text.substring(0, middle) + "...") <= maxWidth) {
            low = middle
        } else {
            high = middle - 1
        }
    }
    return if (low > 0) text.substring(0, low) + "..." else ""
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSubtitleEdges(
    state: EditorWaveformState,
    width: Float,
    top: Float,
    height: Float
) {
    visibleSubtitleIndices(state).forEach { index ->
        val subtitle = state.subtitles[index]
        if (subtitle.endTime < state.visibleStartMs ||
            subtitle.startTime > state.visibleStartMs + state.visibleDurationMs
        ) return@forEach
        val edgeColor = if (index in state.selectedIndices) {
            LEGACY_SELECTED_EDGE_COLOR
        } else {
            LEGACY_SUBTITLE_EDGE_COLOR
        }
        val startX = timeToX(subtitle.startTime, width, state)
        val endX = timeToX(subtitle.endTime, width, state)
        if (startX in 0f..width) {
            drawLine(
                edgeColor,
                Offset(startX, top),
                Offset(startX, top + height),
                pathEffect = LEGACY_EDGE_DASH
            )
        }
        if (endX in 0f..width) {
            drawLine(
                edgeColor,
                Offset(endX, top),
                Offset(endX, top + height),
                pathEffect = LEGACY_EDGE_DASH
            )
        }
    }
}

private fun subtitleIndexAt(x: Float, width: Int, state: EditorWaveformState): Int {
    val visible = visibleSubtitleIndices(state)
    // Match the paint order: selected blocks win hit tests when cue ranges overlap.
    visible.reversed().filter { it in state.selectedIndices }.forEach { index ->
        val subtitle = state.subtitles[index]
        if (x in timeToXUnclamped(subtitle.startTime, width.toFloat(), state)..timeToXUnclamped(subtitle.endTime, width.toFloat(), state)) {
            return index
        }
    }
    visible.reversed().forEach { index ->
        // Selected blocks were already tested using their unclamped coordinates.  The
        // legacy hit test skipped them in this pass so a selected cue that is completely
        // off the left edge cannot steal a tap at x=0 through the clamped range.
        if (index in state.selectedIndices) return@forEach
        val subtitle = state.subtitles[index]
        // The legacy View used clamped coordinates for unselected blocks.  A cue that
        // starts before the viewport is therefore still hit-testable across its visible
        // portion (its left edge is pinned to x=0).  Keep selected blocks on the raw
        // coordinates above so their off-screen resize handle remains addressable.
        if (x in timeToX(subtitle.startTime, width.toFloat(), state)..timeToX(subtitle.endTime, width.toFloat(), state)) {
            return index
        }
    }
    return -1
}

private fun visibleSubtitleIndices(state: EditorWaveformState): IntRange {
    val subtitles = state.subtitles
    var low = 0
    var high = subtitles.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (subtitles[middle].startTime < state.visibleStartMs) low = middle + 1
        else high = middle
    }
    val first = (low - 1).coerceAtLeast(0)
    low = 0
    high = subtitles.size
    val visibleEnd = state.visibleStartMs + state.visibleDurationMs
    while (low < high) {
        val middle = (low + high) ushr 1
        if (subtitles[middle].startTime <= visibleEnd) low = middle + 1
        else high = middle
    }
    return first until low
}

private fun dragModeAt(x: Float, index: Int, width: Int, state: EditorWaveformState): EditorWaveformDragMode {
    val subtitle = state.subtitles.getOrNull(index) ?: return EditorWaveformDragMode.NONE
    val start = timeToXUnclamped(subtitle.startTime, width.toFloat(), state)
    val end = timeToXUnclamped(subtitle.endTime, width.toFloat(), state)
    return when {
        abs(x - start) < 30f -> EditorWaveformDragMode.RESIZE_START
        abs(x - end) < 30f -> EditorWaveformDragMode.RESIZE_END
        else -> EditorWaveformDragMode.MOVE
    }
}

private fun showGenerationButton(state: EditorWaveformState): Boolean = when {
    !state.hasAudioTrack -> state.isExpanded
    state.isPreparingCacheIndex || state.cacheIndexFailure != null -> state.isExpanded
    state.displayMode == EditorWaveformDisplayMode.WAVEFORM ->
        !state.isWaveformGenerated && state.isExpanded
    else -> !state.isSpectrogramGenerationStarted && state.isExpanded
}

private fun generationLabel(state: EditorWaveformState): String = when {
    // Keep the legacy editor_video_no_audio string exactly; this button is disabled but its
    // label is still visible whenever a video has no usable audio track.
    !state.hasAudioTrack -> "视频没有可用音轨"
    state.isPreparingCacheIndex -> "正在准备缓存..."
    state.cacheIndexFailure != null -> "缓存准备失败"
    state.isWaveformGenerating -> "生成中..."
    state.displayMode == EditorWaveformDisplayMode.WAVEFORM -> "生成波形图"
    else -> "生成频谱图"
}

private fun rulerHeight(height: Int): Float = height * RULER_HEIGHT_FRACTION
private fun subtitleTop(height: Int): Float = height * (RULER_HEIGHT_FRACTION + WAVE_HEIGHT_FRACTION)
/** Legacy touch hit-test boundary; the old View used 65% despite its 75% draw origin. */
private fun touchSubtitleTop(height: Int): Float = height * 0.65f
private fun timeToChunk(timeMs: Long): Int = (timeMs / EditorWaveformState.CHUNK_DURATION_MS).toInt()

private fun timeToX(timeMs: Long, width: Float, state: EditorWaveformState): Float =
    // The legacy View only clamped the left edge. Keeping right-edge coordinates outside the
    // canvas lets clipping suppress an end marker that is beyond the viewport.
    timeToXUnclamped(timeMs, width, state).coerceAtLeast(0f)

private fun timeToXUnclamped(timeMs: Long, width: Float, state: EditorWaveformState): Float =
    if (state.visibleDurationMs <= 0L) 0f
    else (timeMs - state.visibleStartMs).toFloat() / state.visibleDurationMs * width

private fun xToTime(x: Float, width: Float, state: EditorWaveformState): Long =
    (state.visibleStartMs + x / width.coerceAtLeast(1f) * state.visibleDurationMs)
        .toLong().coerceIn(0L, state.durationMs)

private fun playheadX(width: Float, state: EditorWaveformState): Float =
    timeToX(state.currentPositionMs, width, state)

private fun targetSamplesForZoom(width: Int, visibleDurationMs: Long): Int =
    (width.toFloat() / visibleDurationMs.coerceAtLeast(1L) * EditorWaveformState.CHUNK_DURATION_MS * 4f)
        .toInt().coerceIn(150, 30_000)

private fun niceRulerInterval(visibleDurationMs: Long): Long {
    val candidates = longArrayOf(
        100L, 200L, 500L, 1_000L, 2_000L, 5_000L, 10_000L,
        15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L
    )
    val target = visibleDurationMs / 6L
    return candidates.minByOrNull { abs(it - target) } ?: 1_000L
}

private fun formatRulerTime(timeMs: Long, intervalMs: Long): String {
    val hours = timeMs / 3_600_000L
    val minutes = (timeMs % 3_600_000L) / 60_000L
    val seconds = (timeMs % 60_000L) / 1_000L
    val tenths = (timeMs % 1_000L) / 100L
    return when {
        intervalMs < 1_000L && hours > 0 ->
            String.format(Locale.getDefault(), "%d:%02d:%02d.%d", hours, minutes, seconds, tenths)
        intervalMs < 1_000L ->
            String.format(Locale.getDefault(), "%d:%02d.%d", minutes, seconds, tenths)
        hours > 0 ->
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        else -> String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

private const val RULER_HEIGHT_FRACTION = 0.08f
private const val WAVE_HEIGHT_FRACTION = 0.67f
private const val SPECTROGRAM_WIDTH = 2048
private const val SINGLE_TAP_SELECTION_DELAY_MS = 120L

// These values mirror WaveformTimelineView. Keeping them local to the Compose renderer avoids
// changing the editor timeline's contrast when the app theme changes.
private val LEGACY_WAVEFORM_COLOR = Color(0xFF4FC3F7)
private val LEGACY_TOOL_BUTTON_BACKGROUND = Color(0xFF2C2C2C)
private val LEGACY_TOOL_BUTTON_BORDER = Color(0xFF444444)
private val LEGACY_TOOL_TEXT = Color(0xFFCCCCCC)
private val LEGACY_TOOL_BUTTON_SHAPE = RoundedCornerShape(3.dp)
private val LEGACY_PLACEHOLDER_COLOR = Color(0xFF3A3A3A)
private val LEGACY_RULER_COLOR = Color(0xFF9E9E9E)
private val LEGACY_WAVEFORM_BACKGROUND = Color(0xFF262626)
private val LEGACY_RULER_BACKGROUND = Color(0xFF1A1A1A)
private val LEGACY_SUBTITLE_BACKGROUND = Color(0xFF1A1A1A)
private val LEGACY_SUBTITLE_COLOR = Color(0xFF4CAF50)
private val LEGACY_SELECTED_MOVE_COLOR = Color(0xFF1976D2)
private val LEGACY_RESIZE_HANDLE_COLOR = Color(0xFFF57C00)
private val LEGACY_PLAYHEAD = Color(0xFFFF5722)
private val LEGACY_TIMESTAMP_PREVIEW = Color(0x644CAF50)
private val LEGACY_LIMITED_PLAYBACK = Color(0x48F44336)
private val LEGACY_SUBTITLE_EDGE_COLOR = Color(0xA54CAF50)
private val LEGACY_SELECTED_EDGE_COLOR = Color(0xCC64B5F6)
private val LEGACY_EDGE_DASH = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
