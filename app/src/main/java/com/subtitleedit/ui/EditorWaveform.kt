package com.subtitleedit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.subtitleedit.editor.EditorWaveformDisplayMode
import com.subtitleedit.editor.EditorWaveformDragMode
import com.subtitleedit.editor.EditorWaveformState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.roundToInt

/** Events emitted by [EditorWaveform] and handled by the editor controller. */
internal data class EditorWaveformActions(
    val onViewportSizeChanged: (widthPx: Int, heightPx: Int) -> Unit = { _, _ -> },
    val onViewportPan: (deltaMs: Long) -> Unit = {},
    val onZoom: (scale: Float, anchorMs: Long) -> Unit = { _, _ -> },
    val onSeek: (positionMs: Long) -> Unit = {},
    val onSelectSubtitle: (index: Int?) -> Unit = {},
    val onSubtitleDrag: (index: Int, mode: EditorWaveformDragMode, deltaMs: Long, isFinal: Boolean) -> Unit =
        { _, _, _, _ -> },
    val onTimestampDrag: (anchorMs: Long) -> Unit = {},
    val onTimestampFinished: () -> Unit = {},
    val onToggleExpanded: () -> Unit = {},
    val onToggleDisplayMode: () -> Unit = {},
    val onAmplitudeZoomIn: () -> Unit = {},
    val onAmplitudeZoomOut: () -> Unit = {},
    val onAmplitudeReset: () -> Unit = {},
    val onGenerate: () -> Unit = {},
    val onStartTimestamping: () -> Unit = {},
    val onQuickTranscribe: () -> Unit = {},
    val onQuickTts: () -> Unit = {},
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(185.dp)
            ) {
                EditorWaveform(
                    state = state,
                    actions = actions,
                    modifier = Modifier.fillMaxSize()
                )
                if (showGenerationButton(state)) {
                    Button(
                        onClick = actions.onGenerate,
                        enabled = state.hasAudioTrack && !state.isPreparingCacheIndex &&
                            !state.isWaveformGenerating,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Text(generationLabel(state))
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorWaveformToolbar(
    state: EditorWaveformState,
    actions: EditorWaveformActions
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(onClick = actions.onToggleExpanded) {
                Text(if (state.isExpanded) "▼" else "▶")
            }
            OutlinedButton(onClick = actions.onStartTimestamping) {
                Text("打轴")
            }
            OutlinedButton(onClick = actions.onQuickTranscribe) {
                Text("转录")
            }
            OutlinedButton(onClick = actions.onQuickTts) {
                Text("朗读")
            }
            OutlinedButton(onClick = actions.onQuickSplit) {
                Text("拆分")
            }
            OutlinedButton(onClick = actions.onToggleDisplayMode) {
                Text(if (state.displayMode == EditorWaveformDisplayMode.WAVEFORM) "频谱" else "波形")
            }
            IconButton(
                onClick = actions.onAmplitudeZoomOut,
                enabled = state.displayMode == EditorWaveformDisplayMode.WAVEFORM
            ) {
                Text("−")
            }
            IconButton(
                onClick = actions.onAmplitudeZoomIn,
                enabled = state.displayMode == EditorWaveformDisplayMode.WAVEFORM
            ) {
                Text("+")
            }
            if (state.isSpectrogramGenerationStarted && state.spectrogramTotalChunks > 0) {
                Text(
                    text = "${state.spectrogramDoneChunks}/${state.spectrogramTotalChunks}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
    val density = androidx.compose.ui.platform.LocalDensity.current
    val playheadTolerance = with(density) { 24.dp.toPx() }
    val colorScheme = MaterialTheme.colorScheme

    LaunchedEffect(
        state.visibleStartMs,
        state.visibleDurationMs,
        state.durationMs,
        state.displayMode,
        state.isPreparingCacheIndex,
        state.isWaveformGenerated,
        state.isSpectrogramGenerationStarted,
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
        for (chunkIndex in startChunk..endChunk) {
            val start = chunkIndex * EditorWaveformState.CHUNK_DURATION_MS
            val end = min(
                (chunkIndex + 1L) * EditorWaveformState.CHUNK_DURATION_MS,
                state.durationMs
            )
            if (state.displayMode == EditorWaveformDisplayMode.WAVEFORM) {
                if (state.chunkData.getOrNull(chunkIndex) == null) {
                    actions.onChunkRequested(chunkIndex, start, end, targetSamples)
                }
            } else if (state.spectrogramChunks[chunkIndex] == null) {
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

    Canvas(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .onSizeChanged {
                canvasSize = it
                actions.onViewportSizeChanged(it.width, it.height)
            }
            .pointerInput(
                state.durationMs,
                state.visibleStartMs,
                state.visibleDurationMs,
                state.subtitles,
                state.selectedIndices,
                state.isTimestamping,
                state.currentPosition
            ) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Main
                    )
                    val downPosition = down.position
                    var lastPosition = downPosition
                    var moved = false
                    var draggedSubtitle = -1
                    var dragMode = EditorWaveformDragMode.NONE
                    var pointerCount = 1
                    var pinchDistance = 0f
                    var pinchAnchor = downPosition
                    var finished = false

                    while (!finished) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val changes = event.changes
                        pointerCount = changes.count { it.pressed }
                        if (pointerCount >= 2) {
                            val active = changes.filter { it.pressed }
                            val first = active[0].position
                            val second = active[1].position
                            val distance = (second - first).getDistance().coerceAtLeast(1f)
                            if (pinchDistance == 0f) {
                                pinchDistance = distance
                                pinchAnchor = (first + second) / 2f
                            } else {
                                val zoom = (distance / pinchDistance).coerceIn(0.2f, 5f)
                                if (abs(zoom - 1f) > 0.01f) {
                                    actions.onZoom(zoom, xToTime(pinchAnchor.x, canvasSize.width.toFloat(), state))
                                    pinchDistance = distance
                                    moved = true
                                }
                            }
                            continue
                        }

                        val change = changes.firstOrNull() ?: continue
                        if (!change.pressed) {
                            if (state.isTimestamping && moved) actions.onTimestampFinished()
                            else if (!moved) {
                                val tapIndex = subtitleIndexAt(
                                    downPosition.x,
                                    canvasSize.width,
                                    state
                                )
                                if (downPosition.y >= subtitleTop(canvasSize.height)) {
                                    actions.onSelectSubtitle(tapIndex.takeIf { it >= 0 })
                                } else if (downPosition.y < rulerHeight(canvasSize.height) &&
                                    abs(downPosition.x - playheadX(canvasSize.width.toFloat(), state)) <= playheadTolerance
                                ) {
                                    actions.onSeek(xToTime(downPosition.x, canvasSize.width.toFloat(), state))
                                } else if (!state.isTimestamping) {
                                    actions.onSeek(xToTime(downPosition.x, canvasSize.width.toFloat(), state))
                                }
                            } else if (draggedSubtitle >= 0 && dragMode != EditorWaveformDragMode.NONE) {
                                actions.onSubtitleDrag(
                                    draggedSubtitle,
                                    dragMode,
                                    xToTime(change.position.x, canvasSize.width.toFloat(), state) -
                                        xToTime(downPosition.x, canvasSize.width.toFloat(), state),
                                    true
                                )
                            }
                            if (state.isTimestamping) actions.onTimestampFinished()
                            finished = true
                            continue
                        }

                        val delta = change.position - lastPosition
                        if (delta.getDistance() > 0.5f) {
                            moved = true
                            if (draggedSubtitle < 0 &&
                                downPosition.y >= subtitleTop(canvasSize.height)
                            ) {
                                draggedSubtitle = subtitleIndexAt(
                                    downPosition.x,
                                    canvasSize.width,
                                    state
                                )
                                if (draggedSubtitle >= 0) {
                                    dragMode = dragModeAt(
                                        downPosition.x,
                                        draggedSubtitle,
                                        canvasSize.width,
                                        state
                                    )
                                }
                            }
                            if (state.isTimestamping) {
                                actions.onTimestampDrag(xToTime(change.position.x, canvasSize.width.toFloat(), state))
                            } else if (draggedSubtitle >= 0 && dragMode != EditorWaveformDragMode.NONE) {
                                actions.onSubtitleDrag(
                                    draggedSubtitle,
                                    dragMode,
                                    xToTime(change.position.x, canvasSize.width.toFloat(), state) -
                                        xToTime(downPosition.x, canvasSize.width.toFloat(), state),
                                    false
                                )
                            } else if (downPosition.y < subtitleTop(canvasSize.height)) {
                                actions.onViewportPan(
                                    (-delta.x / canvasSize.width.coerceAtLeast(1) * state.visibleDurationMs)
                                        .roundToLong()
                                )
                            }
                            lastPosition = change.position
                        }
                    }
                }
            }
    ) {
        drawWaveformContents(state, canvasSize, colorScheme)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaveformContents(
    state: EditorWaveformState,
    canvasSize: IntSize,
    colors: androidx.compose.material3.ColorScheme
) {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) return
    val ruler = height * RULER_HEIGHT_FRACTION
    val waveformHeight = height * WAVE_HEIGHT_FRACTION
    val subtitleHeight = height - ruler - waveformHeight
    drawRect(
        if (state.initialized) colors.surfaceVariant.copy(alpha = 0.55f) else colors.surface,
        size = Size(width, ruler)
    )
    drawRect(colors.surface, topLeft = Offset(0f, ruler), size = Size(width, waveformHeight))
    drawRect(
        colors.surfaceVariant.copy(alpha = 0.28f),
        topLeft = Offset(0f, ruler + waveformHeight),
        size = Size(width, subtitleHeight)
    )

    val interval = niceRulerInterval(state.visibleDurationMs)
    val minorInterval = (interval / 5L).coerceAtLeast(100L)
    var minor = (state.visibleStartMs / minorInterval) * minorInterval
    while (minor <= state.visibleStartMs + state.visibleDurationMs) {
        val x = timeToX(minor, width, state)
        drawLine(
            colors.onSurfaceVariant.copy(alpha = 0.25f),
            Offset(x, ruler * 0.65f),
            Offset(x, ruler),
            strokeWidth = 1f
        )
        minor += minorInterval
    }
    var major = (state.visibleStartMs / interval) * interval
    while (major <= state.visibleStartMs + state.visibleDurationMs) {
        val x = timeToX(major, width, state)
        drawLine(colors.onSurfaceVariant, Offset(x, 0f), Offset(x, ruler), strokeWidth = 1.2f)
        major += interval
    }

    if (state.displayMode == EditorWaveformDisplayMode.WAVEFORM) {
        drawWaveformSamples(state, width, ruler, waveformHeight, colors)
    } else {
        drawSpectrogram(state, width, ruler, waveformHeight)
    }
    drawSubtitleBlocks(state, width, ruler + waveformHeight, subtitleHeight, colors)
    drawSubtitleEdges(state, width, ruler, waveformHeight + subtitleHeight, colors)

    if (state.isTimestamping) {
        val left = min(
            timeToX(state.timestampStartMs, width, state),
            timeToX(state.timestampAnchorMs, width, state)
        )
        val right = max(
            timeToX(state.timestampStartMs, width, state),
            timeToX(state.timestampAnchorMs, width, state)
        )
        drawRect(
            colors.tertiary.copy(alpha = 0.22f),
            topLeft = Offset(left, ruler),
            size = Size((right - left).coerceAtLeast(1f), height - ruler)
        )
    }

    state.limitedPlaybackIndex?.let { index ->
        state.subtitles.getOrNull(index)?.let { subtitle ->
            val left = timeToXUnclamped(subtitle.startTime, width, state)
            val right = timeToXUnclamped(subtitle.endTime, width, state)
            drawRect(
                colors.secondary.copy(alpha = 0.18f),
                topLeft = Offset(left, ruler),
                size = Size((right - left).coerceAtLeast(1f), height - ruler)
            )
        }
    }

    val playhead = playheadX(width, state)
    drawLine(colors.error, Offset(playhead, 0f), Offset(playhead, height), strokeWidth = 2f)
    drawLine(colors.error, Offset(playhead - 7f, 0f), Offset(playhead, 7f), strokeWidth = 2f)
    drawLine(colors.error, Offset(playhead + 7f, 0f), Offset(playhead, 7f), strokeWidth = 2f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaveformSamples(
    state: EditorWaveformState,
    width: Float,
    top: Float,
    height: Float,
    colors: androidx.compose.material3.ColorScheme
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
                colors.onSurfaceVariant.copy(alpha = 0.35f),
                Offset(pixel.toFloat(), center - 3f),
                Offset(pixel.toFloat(), center + 3f),
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
            colors.primary,
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
    for ((index, bitmap) in state.spectrogramChunks) {
        val start = index * EditorWaveformState.CHUNK_DURATION_MS
        val end = min((index + 1L) * EditorWaveformState.CHUNK_DURATION_MS, state.durationMs)
        val visibleStart = max(start, state.visibleStartMs)
        val visibleEnd = min(end, state.visibleStartMs + state.visibleDurationMs)
        if (visibleEnd <= visibleStart) continue
        val sourceLeft = ((visibleStart - start).toFloat() / (end - start).coerceAtLeast(1L) * bitmap.width)
            .toInt().coerceIn(0, bitmap.width - 1)
        val sourceRight = ((visibleEnd - start).toFloat() / (end - start).coerceAtLeast(1L) * bitmap.width)
            .toInt().coerceIn(sourceLeft + 1, bitmap.width)
        val image: ImageBitmap = bitmap.asImageBitmap()
        drawImage(
            image,
            srcOffset = androidx.compose.ui.unit.IntOffset(sourceLeft, 0),
            srcSize = androidx.compose.ui.unit.IntSize(sourceRight - sourceLeft, bitmap.height),
            dstOffset = androidx.compose.ui.unit.IntOffset(
                timeToX(visibleStart, width, state).roundToInt(),
                top.roundToInt()
            ),
            dstSize = androidx.compose.ui.unit.IntSize(
                (timeToX(visibleEnd, width, state) - timeToX(visibleStart, width, state)).roundToInt().coerceAtLeast(1),
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
    colors: androidx.compose.material3.ColorScheme
) {
    val boxTop = top + height * 0.15f
    val boxHeight = height * 0.72f
    state.subtitles.forEachIndexed { index, subtitle ->
        if (subtitle.endTime < state.visibleStartMs || subtitle.startTime > state.visibleStartMs + state.visibleDurationMs) return@forEachIndexed
        val left = timeToX(subtitle.startTime, width, state)
        val right = timeToX(subtitle.endTime, width, state)
        val selected = index in state.selectedIndices
        drawRoundRect(
            color = if (selected) colors.primary else colors.secondary,
            topLeft = Offset(left, boxTop),
            size = Size((right - left).coerceAtLeast(4f), boxHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
        )
        if (selected) {
            val handle = min(30f, (right - left).coerceAtLeast(4f) / 3f)
            drawRect(
                colors.primaryContainer,
                topLeft = Offset(left, boxTop),
                size = Size(handle, boxHeight)
            )
            drawRect(
                colors.primaryContainer,
                topLeft = Offset((right - handle).coerceAtLeast(left), boxTop),
                size = Size(handle, boxHeight)
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSubtitleEdges(
    state: EditorWaveformState,
    width: Float,
    top: Float,
    height: Float,
    colors: androidx.compose.material3.ColorScheme
) {
    state.subtitles.forEachIndexed { index, subtitle ->
        if (subtitle.endTime < state.visibleStartMs || subtitle.startTime > state.visibleStartMs + state.visibleDurationMs) return@forEachIndexed
        val edgeColor = if (index in state.selectedIndices) colors.primary else colors.outline
        val startX = timeToX(subtitle.startTime, width, state)
        val endX = timeToX(subtitle.endTime, width, state)
        if (startX in 0f..width) drawLine(edgeColor, Offset(startX, top), Offset(startX, top + height))
        if (endX in 0f..width) drawLine(edgeColor, Offset(endX, top), Offset(endX, top + height))
    }
}

private fun subtitleIndexAt(x: Float, width: Int, state: EditorWaveformState): Int {
    state.subtitles.indices.reversed().forEach { index ->
        val subtitle = state.subtitles[index]
        if (x in timeToXUnclamped(subtitle.startTime, width.toFloat(), state)..timeToXUnclamped(subtitle.endTime, width.toFloat(), state)) {
            return index
        }
    }
    return -1
}

private fun dragModeAt(x: Float, index: Int, width: Int, state: EditorWaveformState): EditorWaveformDragMode {
    val subtitle = state.subtitles.getOrNull(index) ?: return EditorWaveformDragMode.NONE
    val start = timeToXUnclamped(subtitle.startTime, width.toFloat(), state)
    val end = timeToXUnclamped(subtitle.endTime, width.toFloat(), state)
    return when {
        abs(x - start) <= 30f -> EditorWaveformDragMode.RESIZE_START
        abs(x - end) <= 30f -> EditorWaveformDragMode.RESIZE_END
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
    !state.hasAudioTrack -> "没有音频轨道"
    state.isPreparingCacheIndex -> "正在准备缓存..."
    state.cacheIndexFailure != null -> "缓存准备失败"
    state.isWaveformGenerating -> "生成中..."
    state.displayMode == EditorWaveformDisplayMode.WAVEFORM -> "生成波形图"
    else -> "生成频谱图"
}

private fun rulerHeight(height: Int): Float = height * RULER_HEIGHT_FRACTION
private fun subtitleTop(height: Int): Float = height * (RULER_HEIGHT_FRACTION + WAVE_HEIGHT_FRACTION)
private fun timeToChunk(timeMs: Long): Int = (timeMs / EditorWaveformState.CHUNK_DURATION_MS).toInt()

private fun timeToX(timeMs: Long, width: Float, state: EditorWaveformState): Float =
    timeToXUnclamped(timeMs, width, state).coerceIn(0f, width)

private fun timeToXUnclamped(timeMs: Long, width: Float, state: EditorWaveformState): Float =
    if (state.visibleDurationMs <= 0L) 0f
    else (timeMs - state.visibleStartMs).toFloat() / state.visibleDurationMs * width

private fun xToTime(x: Float, width: Float, state: EditorWaveformState): Long =
    (state.visibleStartMs + x / width.coerceAtLeast(1f) * state.visibleDurationMs)
        .roundToLong().coerceIn(0L, state.durationMs)

private fun playheadX(width: Float, state: EditorWaveformState): Float =
    timeToX(state.currentPositionMs, width, state)

private fun targetSamplesForZoom(width: Int, visibleDurationMs: Long): Int =
    (width.toFloat() / visibleDurationMs.coerceAtLeast(1L) * EditorWaveformState.CHUNK_DURATION_MS * 4f)
        .roundToInt().coerceIn(150, 30_000)

private fun niceRulerInterval(visibleDurationMs: Long): Long {
    val candidates = longArrayOf(1000L, 2000L, 5000L, 10_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L)
    val target = visibleDurationMs / 6L
    return candidates.minByOrNull { abs(it - target) } ?: 30_000L
}

private const val RULER_HEIGHT_FRACTION = 0.08f
private const val WAVE_HEIGHT_FRACTION = 0.67f
private const val SPECTROGRAM_WIDTH = 2048
