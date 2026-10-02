package com.subtitleedit.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.subtitleedit.audio.FfmpegWaveformChunkLoader
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.repository.MediaRepository
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleEntryOps
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class EditorWaveformController(
    private val context: Context,
    private val scope: CoroutineScope,
    private var hasPlayableMedia: Boolean,
    private val appCacheDir: File,
    private val mediaRepository: MediaRepository,
    private val currentPlaybackPositionMs: () -> Long,
    private val onSubtitleChanged: (Int, SubtitleEntry, Long, Boolean) -> Unit,
    private val onSelectedIndexChanged: (Int) -> Unit,
    private val onLimitedPlaybackRangeChanged: (Int?) -> Unit,
    private val onTimestampInserted: (Long, Long) -> Unit,
    private val showMessage: (String) -> Unit
) {
    private val _composeState = MutableStateFlow(EditorWaveformState())

    /** Observable timeline snapshot consumed by the Compose editor surface. */
    val composeState: StateFlow<EditorWaveformState> = _composeState.asStateFlow()

    private val composeChunkData = mutableListOf<FloatArray?>()
    /** Requested sample count per chunk, matching the legacy view's de-duplication rules. */
    private val composeChunkRequestedSamples = mutableListOf<Int>()
    private val composeSpectrogramChunks = mutableMapOf<Int, Bitmap>()
    private var composeVisibleStartMs = 0L
    private var composeVisibleDurationMs = EditorWaveformState.DEFAULT_VISIBLE_DURATION_MS
    private var composeCurrentPosition = 0f
    private var composeSelectedIndices: Set<Int> = emptySet()
    private var composeLimitedPlaybackIndex: Int? = null
    private var composeTimestampSession: EditorWaveformTimestampSession? = null
    /** Changes whenever the toolbar starts a timestamping session. */
    private var composeTimestampGestureGeneration = 0L
    private var composeViewportWidthPx = 0
    private var composeViewportHeightPx = 0
    private var composeDragSessionKey = 0L
    private val composeDragBases = mutableMapOf<Int, SubtitleEntry>()

    private var chunkLoader: FfmpegWaveformChunkLoader? = null
    private var cacheIndexJob: Job? = null
    private var cacheIndexGeneration = 0L
    private var audioFile: File? = null
    private var audioCacheKey: String? = null
    private var durationMs = 0L
    private var audioStreamIndex: Int? = null
    private var hasAudioTrack = true
    private var isWaveformExpanded = true
    private var currentDisplayMode = EditorWaveformDisplayMode.WAVEFORM
    private var spectrogramTotalChunks = 0
    private var spectrogramDoneChunks = 0
    private var spectrogramIsGenerating = false
    private var isWaveformGenerated = false
    private var isSpectrogramGenerationStarted = false
    /** Mirrors WaveformTimelineView.refreshVisibleChunks() after loader reconnection. */
    private var composeChunkRequestGeneration = 0L
    private var isWaveformGenerating = false
    private var isPreparingCacheIndex = false
    private var cacheIndexFailure: String? = null
    private val spectrogramStateLock = Any()
    private val spectrogramGenerationSemaphore = Semaphore(MAX_CONCURRENT_SPECTROGRAM_GENERATIONS)
    private val spectrogramJobs = mutableMapOf<SpectrogramChunkKey, Job>()
    private val spectrogramReadyChunks = mutableSetOf<Int>()
    private var spectrogramGenerationVersion = 0L
    private var spectrogramCacheDimensions: Pair<Int, Int>? = null

    private fun publishComposeState() {
        _composeState.value = EditorWaveformState(
            durationMs = durationMs,
            subtitles = _composeState.value.subtitles,
            visibleStartMs = composeVisibleStartMs,
            visibleDurationMs = composeVisibleDurationMs,
            currentPosition = composeCurrentPosition,
            chunkData = composeChunkData.toList(),
            spectrogramChunks = composeSpectrogramChunks.toMap(),
            selectedIndices = composeSelectedIndices,
            limitedPlaybackIndex = composeLimitedPlaybackIndex,
            amplitudeScale = _composeState.value.amplitudeScale,
            displayMode = currentDisplayMode,
            initialized = durationMs > 0L,
            isExpanded = isWaveformExpanded,
            isTimestamping = composeTimestampSession != null,
            timestampStartMs = composeTimestampSession?.startMs ?: 0L,
            timestampAnchorMs = composeTimestampSession?.endTime(
                composeVisibleStartMs, composeVisibleDurationMs, composeViewportWidthPx, durationMs
            ) ?: 0L,
            timestampAnchorX = composeTimestampSession?.anchorX ?: 0f,
            timestampGestureGeneration = composeTimestampGestureGeneration,
            hasPlayableMedia = hasPlayableMedia,
            hasAudioTrack = hasAudioTrack,
            isPreparingCacheIndex = isPreparingCacheIndex,
            cacheIndexFailure = cacheIndexFailure,
            isWaveformGenerated = isWaveformGenerated,
            isWaveformGenerating = isWaveformGenerating,
            chunkRequestGeneration = composeChunkRequestGeneration,
            isSpectrogramGenerationStarted = isSpectrogramGenerationStarted,
            spectrogramTotalChunks = spectrogramTotalChunks,
            spectrogramDoneChunks = spectrogramDoneChunks,
            spectrogramIsGenerating = spectrogramIsGenerating,
            viewportWidthPx = composeViewportWidthPx,
            viewportHeightPx = composeViewportHeightPx
        )
    }

    /** Updates the Compose surface size used to select spectrogram cache dimensions. */
    fun updateComposeViewport(widthPx: Int, heightPx: Int) {
        composeViewportWidthPx = widthPx.coerceAtLeast(0)
        composeViewportHeightPx = heightPx.coerceAtLeast(0)
        if (composeViewportWidthPx > 0 && composeViewportHeightPx > 0 && audioFile != null) {
            val dimensions = SPECTROGRAM_WIDTH to
                (composeViewportHeightPx * WAVE_HEIGHT_FRACTION).toInt().coerceAtLeast(64)
            if (spectrogramCacheDimensions != dimensions) {
                // A bitmap is decoded at the exact viewport height.  Drop the old
                // in-memory chunks before loading the cache index for the new size;
                // otherwise Compose can keep drawing a bitmap from the previous layout.
                cancelSpectrogramJobs()
                composeSpectrogramChunks.values.forEach { bitmap ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                composeSpectrogramChunks.clear()
                restoreSpectrogramCacheState(audioFile!!)
            }
        }
        publishComposeState()
    }

    /** Updates the playhead without touching an Android View. */
    fun setComposeCurrentPosition(position: Float) {
        composeCurrentPosition = position.coerceIn(0f, 1f)
        if (durationMs > 0L) {
            val positionMs = (durationMs * composeCurrentPosition).toLong()
            val visibleEnd = composeVisibleStartMs + composeVisibleDurationMs
            if (positionMs < composeVisibleStartMs || positionMs > visibleEnd) {
                composeVisibleStartMs = positionMs.coerceIn(
                    0L,
                    (durationMs - composeVisibleDurationMs).coerceAtLeast(0L)
                )
                requestComposeChunksAroundTime(positionMs)
            }
        }
        publishComposeState()
    }

    /** Seeks playback from the visible timeline without moving its viewport. */
    fun seekComposeToTime(timeMs: Long) {
        requestComposeChunksAroundTime(timeMs)
        // Keep the local playhead update as one StateFlow publication.  Publishing once before
        // and once after the update makes a ruler drag render an intermediate old position and
        // lets a playback callback win between the two emissions, which appears as stutter.
        onTimelineClickFromCompose(timeMs)
    }

    /** Applies a horizontal viewport drag and returns a corrected playhead time when needed. */
    fun panComposeViewport(deltaMs: Long): Long? {
        return setComposeViewportStart(composeVisibleStartMs + deltaMs)
    }

    /** Sets the viewport start using the absolute position calculated from a legacy gesture. */
    fun setComposeViewportStart(startMs: Long): Long? {
        val previousStart = composeVisibleStartMs
        composeVisibleStartMs = startMs.coerceIn(
            0L,
            (durationMs - composeVisibleDurationMs).coerceAtLeast(0L)
        )
        if (composeVisibleStartMs == previousStart || durationMs <= 0L) {
            publishComposeState()
            return null
        }
        val playheadMs = (durationMs * composeCurrentPosition).toLong()
        val visibleEnd = composeVisibleStartMs + composeVisibleDurationMs
        if (playheadMs in composeVisibleStartMs..visibleEnd) {
            publishComposeState()
            return null
        }
        val corrected = composeVisibleStartMs.coerceIn(0L, durationMs)
        composeCurrentPosition = corrected.toFloat() / durationMs.toFloat()
        publishComposeState()
        return corrected
    }

    /** Applies pinch zoom around an anchor time. scale > 1 zooms in. */
    fun zoomComposeViewport(scale: Float, anchorMs: Long) {
        if (durationMs <= 0L || scale <= 0f) return
        val newDuration = (composeVisibleDurationMs / scale).toLong().coerceIn(
            EditorWaveformState.MIN_VISIBLE_DURATION_MS,
            // Keep the legacy 2-second minimum even for very short media. The old View
            // allowed the viewport to extend past the media edges and clamped its start;
            // constraining the upper bound to duration here makes coerceIn throw when the
            // media is shorter than the minimum window.
            EditorWaveformState.MAX_VISIBLE_DURATION_MS
        )
        val anchorFraction = if (composeVisibleDurationMs <= 0L) 0.5f else
            ((anchorMs - composeVisibleStartMs).toFloat() / composeVisibleDurationMs).coerceIn(0f, 1f)
        composeVisibleDurationMs = newDuration
        composeVisibleStartMs = (anchorMs - (newDuration * anchorFraction).toLong()).coerceIn(
            0L,
            (durationMs - newDuration).coerceAtLeast(0L)
        )
        publishComposeState()
    }

    /** Applies the legacy pinch formula from a gesture's fixed start viewport. */
    fun setComposeViewportAbsolute(startMs: Long, durationMs: Long) {
        if (this.durationMs <= 0L) return
        composeVisibleDurationMs = durationMs.coerceIn(
            EditorWaveformState.MIN_VISIBLE_DURATION_MS,
            EditorWaveformState.MAX_VISIBLE_DURATION_MS
        )
        composeVisibleStartMs = startMs.coerceIn(
            0L,
            (this.durationMs - composeVisibleDurationMs).coerceAtLeast(0L)
        )
        publishComposeState()
    }

    fun toggleComposeExpanded() {
        isWaveformExpanded = !isWaveformExpanded
        publishComposeState()
    }

    fun toggleComposeDisplayMode() {
        currentDisplayMode = if (currentDisplayMode == EditorWaveformDisplayMode.WAVEFORM) {
            EditorWaveformDisplayMode.SPECTROGRAM
        } else {
            EditorWaveformDisplayMode.WAVEFORM
        }
        if (currentDisplayMode == EditorWaveformDisplayMode.SPECTROGRAM) {
            spectrogramTotalChunks = calcTotalChunks()
            spectrogramIsGenerating = isSpectrogramGenerationStarted &&
                spectrogramDoneChunks < spectrogramTotalChunks
        }
        publishComposeState()
    }

    fun zoomComposeAmplitudeIn() {
        val value = (_composeState.value.amplitudeScale * 1.25f).coerceAtMost(10f)
        _composeState.value = _composeState.value.copy(amplitudeScale = value)
    }

    fun zoomComposeAmplitudeOut() {
        val value = (_composeState.value.amplitudeScale * 0.8f).coerceAtLeast(0.2f)
        _composeState.value = _composeState.value.copy(amplitudeScale = value)
    }

    fun resetComposeAmplitude() {
        _composeState.value = _composeState.value.copy(amplitudeScale = 1f)
        showMessage("振幅已重置")
    }

    fun selectComposeSubtitle(index: Int?) {
        val candidate = index?.takeIf { it in _composeState.value.subtitles.indices }
        composeSelectedIndices = when {
            candidate == null || candidate in composeSelectedIndices -> emptySet()
            else -> setOf(candidate)
        }
        composeSelectedIndices.firstOrNull()?.let(onSelectedIndexChanged)
        publishComposeState()
    }

    /** Toggles the subtitle range used by the playback controller's limited-playback mode. */
    fun toggleComposeLimitedPlayback(index: Int?) {
        composeLimitedPlaybackIndex = index?.takeIf { it in _composeState.value.subtitles.indices }
            ?.let { candidate -> if (composeLimitedPlaybackIndex == candidate) null else candidate }
        publishComposeState()
    }

    fun clearComposeLimitedPlayback() {
        if (composeLimitedPlaybackIndex == null) return
        composeLimitedPlaybackIndex = null
        publishComposeState()
    }

    fun composeLimitedPlaybackIndex(): Int? = composeLimitedPlaybackIndex

    fun isComposeLimitedPlaybackOutOfView(): Boolean {
        val index = composeLimitedPlaybackIndex ?: return false
        val subtitle = _composeState.value.subtitles.getOrNull(index) ?: return true
        val visibleEnd = composeVisibleStartMs + composeVisibleDurationMs
        return subtitle.endTime < composeVisibleStartMs || subtitle.startTime > visibleEnd
    }

    fun updateComposeSubtitleDrag(
        index: Int,
        mode: EditorWaveformDragMode,
        deltaMs: Long,
        isFinal: Boolean
    ) {
        val original = _composeState.value.subtitles.getOrNull(index) ?: return
        if (!isFinal && index !in composeDragBases) {
            composeDragSessionKey++
            composeDragBases[index] = original.copy()
        }
        val base = composeDragBases[index] ?: original
        val updated = base.copy()
        val entries = _composeState.value.subtitles
        val bounds = SubtitleEntryOps.dragNeighborBounds(
            currentStartTime = base.startTime,
            currentEndTime = base.endTime,
            entries = entries,
            currentIndex = index
        )
        when (mode) {
            EditorWaveformDragMode.MOVE -> {
                val range = SubtitleEntryOps.clampMoveToNeighbors(
                    originalStartTime = base.startTime,
                    originalEndTime = base.endTime,
                    desiredStartTime = base.startTime + deltaMs,
                    previousEndTime = bounds.previousEndTime,
                    nextStartTime = bounds.nextStartTime
                )
                updated.startTime = range.startTime
                updated.endTime = range.endTime
            }
            EditorWaveformDragMode.RESIZE_START -> {
                updated.startTime = SubtitleEntryOps.clampStartToNeighbors(
                    originalStartTime = base.startTime,
                    currentEndTime = base.endTime,
                    desiredStartTime = base.startTime + deltaMs,
                    previousEndTime = bounds.previousEndTime,
                    minimumDurationMs = MIN_SUBTITLE_DURATION_MS
                )
            }
            EditorWaveformDragMode.RESIZE_END -> {
                updated.endTime = SubtitleEntryOps.clampEndToNeighbors(
                    originalEndTime = base.endTime,
                    currentStartTime = base.startTime,
                    desiredEndTime = base.endTime + deltaMs,
                    nextStartTime = bounds.nextStartTime,
                    minimumDurationMs = MIN_SUBTITLE_DURATION_MS
                )
                updated.endTimeModified = true
            }
            EditorWaveformDragMode.NONE -> return
        }
        // Keep the timeline inside the media duration after applying neighbor bounds. The
        // neighbor helpers intentionally use an open upper bound for the last subtitle, so a
        // large rightward drag still needs this final clamp.
        updated.startTime = updated.startTime.coerceIn(0L, durationMs)
        updated.endTime = updated.endTime.coerceIn(
            updated.startTime + MIN_SUBTITLE_DURATION_MS,
            durationMs.coerceAtLeast(updated.startTime + MIN_SUBTITLE_DURATION_MS)
        )
        val updatedSubtitles = _composeState.value.subtitles.toMutableList()
        if (index in updatedSubtitles.indices) updatedSubtitles[index] = updated
        _composeState.value = _composeState.value.copy(subtitles = updatedSubtitles)
        // WaveformTimelineView mutates its own block before notifying the Activity. Publish the
        // detached Compose snapshot in the same order so the timeline is immediately current
        // while the Activity refreshes the list and document model in its callback.
        publishComposeState()
        onSubtitleChanged(index, updated, composeDragSessionKey, isFinal)
        if (isFinal) composeDragBases.remove(index)
    }

    /** Drop a drag origin when Compose cancels a pointer stream before ACTION_UP. */
    fun resetComposeSubtitleDragSession() {
        composeDragBases.clear()
    }

    fun startComposeTimestamping() {
        composeTimestampGestureGeneration++
        composeTimestampSession = EditorWaveformTimestampSession.start(
            startMs = currentPlaybackPositionMs().coerceIn(0L, durationMs),
            visibleStartMs = composeVisibleStartMs,
            visibleDurationMs = composeVisibleDurationMs,
            viewportWidthPx = composeViewportWidthPx
        )
        composeDragBases.clear()
        publishComposeState()
    }

    /** Pans the timeline while preserving the timestamp button's fixed screen anchor. */
    fun panComposeTimestampViewport(deltaMs: Long) {
        val session = composeTimestampSession ?: return
        composeVisibleStartMs = session.panViewport(
            composeVisibleStartMs, deltaMs, composeVisibleDurationMs, durationMs
        )
        publishComposeState()
    }

    fun finishComposeTimestamping() {
        val session = composeTimestampSession ?: return
        val end = session.endTime(
            composeVisibleStartMs, composeVisibleDurationMs, composeViewportWidthPx, durationMs
        )
        composeTimestampSession = null
        composeVisibleStartMs = composeVisibleStartMs.coerceIn(
            0L, (durationMs - composeVisibleDurationMs).coerceAtLeast(0L)
        )
        // The existing insert operation orders the endpoints and rejects ranges under 100 ms.
        onTimestampInserted(session.startMs, end)
        publishComposeState()
    }

    private fun onTimelineClickFromCompose(timeMs: Long) {
        // The playback controller owns actual media seeking. The state update above keeps the
        // Compose playhead responsive while the controller catches up asynchronously.
        composeCurrentPosition = if (durationMs <= 0L) 0f else timeMs.toFloat() / durationMs
        publishComposeState()
    }

    private fun requestComposeChunksAroundTime(timeMs: Long) {
        val total = composeChunkData.size
        if (total == 0) return
        val center = (timeMs / EditorWaveformState.CHUNK_DURATION_MS).toInt()
        val target = targetComposeSamples()
        for (offset in 0..4) {
            val deltas = if (offset == 0) intArrayOf(0) else intArrayOf(-offset, offset)
            deltas.forEach { delta ->
                val index = center + delta
                if (index in 0 until total && composeChunkData[index] == null) {
                    requestComposeChunk(index, target)
                }
            }
        }
    }

    private fun targetComposeSamples(): Int =
        (composeViewportWidthPx.toFloat() / composeVisibleDurationMs.coerceAtLeast(1L) *
            EditorWaveformState.CHUNK_DURATION_MS * 4f).toInt().coerceIn(150, 30_000)

    private fun requestComposeChunk(index: Int, targetSamples: Int) {
        val loader = chunkLoader ?: return
        if (index !in composeChunkData.indices) return

        val requested = composeChunkRequestedSamples.getOrNull(index) ?: 0
        val existing = composeChunkData[index]
        val needsUpgrade = existing != null &&
            existing.size < targetSamples * LEGACY_RESAMPLE_THRESHOLD
        if (existing != null && (!needsUpgrade || requested >= targetSamples)) return
        if (existing == null && requested >= targetSamples) return

        val requestSamples = maxOf(requested, targetSamples)
        composeChunkRequestedSamples[index] = requestSamples
        val start = index * EditorWaveformState.CHUNK_DURATION_MS
        val end = minOf(start + EditorWaveformState.CHUNK_DURATION_MS, durationMs)
        loader.requestChunk(index, start, end, requestSamples) { chunkIndex, data ->
            if (chunkIndex in composeChunkData.indices) {
                // An empty response means the cache is disabled or generation failed. Keep it
                // nullable so enabling generation can retry the same visible chunk.
                composeChunkData[chunkIndex] = data.takeIf { it.isNotEmpty() }
                publishComposeState()
            }
        }
    }

    /** Requests a waveform chunk for the Compose surface. */
    fun requestComposeChunk(
        chunkIndex: Int,
        startMs: Long,
        endMs: Long,
        targetSamples: Int
    ) {
        val loader = chunkLoader ?: return
        if (chunkIndex !in composeChunkData.indices) return
        val requested = composeChunkRequestedSamples.getOrNull(chunkIndex) ?: 0
        val existing = composeChunkData[chunkIndex]
        val needsUpgrade = existing != null &&
            existing.size < targetSamples * LEGACY_RESAMPLE_THRESHOLD
        if ((existing != null && (!needsUpgrade || requested >= targetSamples)) ||
            (existing == null && requested >= targetSamples)
        ) return
        val requestSamples = maxOf(requested, targetSamples)
        composeChunkRequestedSamples[chunkIndex] = requestSamples
        loader.requestChunk(chunkIndex, startMs, endMs, requestSamples) { index, data ->
            if (index in composeChunkData.indices) {
                composeChunkData[index] = data.takeIf { it.isNotEmpty() }
                publishComposeState()
            }
        }
    }

    /** Requests a spectrogram chunk for the Compose surface. */
    fun requestComposeSpectrogramChunk(
        chunkIndex: Int,
        startMs: Long,
        endMs: Long,
        widthPx: Int,
        heightPx: Int
    ) {
        if (!isSpectrogramGenerationStarted) return
        generateSpectrogramChunkAsync(chunkIndex, startMs, endMs, widthPx, heightPx)
    }

    fun startComposeGeneration() {
        if (currentDisplayMode == EditorWaveformDisplayMode.WAVEFORM) {
            startWaveformGeneration()
        } else {
            startSpectrogramGeneration()
        }
        publishComposeState()
    }

    private data class SpectrogramChunkKey(
        val chunkIndex: Int,
        val width: Int,
        val height: Int
    )

    /** The Compose surface owns gestures and controls; binding is no longer required. */
    fun bind() {
        publishComposeState()
    }

    /** Makes the waveform/media panel available after opening media in subtitle-only mode. */
    fun setMediaAvailable(available: Boolean) {
        hasPlayableMedia = available
        publishComposeState()
        if (!available) {
            release()
        }
    }

    fun load(
        audioFile: File,
        durationMs: Long,
        subtitles: List<SubtitleEntry>,
        audioStreamIndex: Int? = null
    ) {
        // WaveformTimelineView.initialize() always cleared the playback controller's
        // limited-range target. Preserve that contract when replacing the media/document.
        onLimitedPlaybackRangeChanged(null)
        cancelSpectrogramJobs()
        spectrogramReadyChunks.clear()
        spectrogramCacheDimensions = null
        cacheIndexJob?.cancel()
        val cacheIndexRequest = ++cacheIndexGeneration
        this.audioFile = audioFile
        this.audioCacheKey = null
        this.durationMs = durationMs
        this.audioStreamIndex = audioStreamIndex
        hasAudioTrack = true
        isPreparingCacheIndex = true
        cacheIndexFailure = null
        isWaveformGenerated = false
        isSpectrogramGenerationStarted = false
        spectrogramIsGenerating = false
        composeVisibleStartMs = 0L
        composeVisibleDurationMs = minOf(EditorWaveformState.DEFAULT_VISIBLE_DURATION_MS, durationMs)
        composeTimestampSession = null
        composeChunkData.clear()
        composeChunkRequestedSamples.clear()
        repeat(calcTotalChunks()) {
            composeChunkData += null
            composeChunkRequestedSamples += 0
        }
        composeSpectrogramChunks.clear()
        composeSelectedIndices = emptySet()
        composeLimitedPlaybackIndex = null
        // Keep the timeline snapshot detached from the mutable document model.  List edits
        // mutate SubtitleEntry in place; sharing those objects lets the waveform change without
        // a StateFlow publication, leaving gesture hit tests and rendered blocks out of sync.
        _composeState.value = _composeState.value.copy(subtitles = subtitles.map { it.copy() })
        publishComposeState()
        chunkLoader?.release()
        chunkLoader = null
        publishComposeState()

        cacheIndexJob = scope.launch(Dispatchers.IO) {
            val cacheKey = runCatching { mediaRepository.getCacheKey(audioFile) }
            withContext(Dispatchers.Main) {
                if (cacheIndexRequest != cacheIndexGeneration) return@withContext

                isPreparingCacheIndex = false
                cacheKey.onSuccess { key ->
                    audioCacheKey = key
                    initializeMediaCache(
                        audioFile,
                        durationMs,
                        audioStreamIndex,
                        key,
                        cacheIndexRequest
                    )
                }.onFailure { error ->
                    cacheIndexFailure = error.message ?: error.javaClass.simpleName
                    Log.e(TAG, "计算媒体缓存索引失败", error)
                    showMessage("无法读取媒体文件")
                    updateGenerateButton()
                }
            }
        }
    }

    fun showNoAudioTrack(durationMs: Long, subtitles: List<SubtitleEntry>) {
        onLimitedPlaybackRangeChanged(null)
        cancelSpectrogramJobs()
        spectrogramReadyChunks.clear()
        spectrogramCacheDimensions = null
        cacheIndexGeneration++
        cacheIndexJob?.cancel()
        cacheIndexJob = null
        this.audioFile = null
        this.audioCacheKey = null
        this.durationMs = durationMs
        this.audioStreamIndex = null
        hasAudioTrack = false
        isPreparingCacheIndex = false
        cacheIndexFailure = null
        chunkLoader?.release()
        chunkLoader = null
        isWaveformGenerated = false
        isSpectrogramGenerationStarted = false
        composeVisibleStartMs = 0L
        composeVisibleDurationMs = minOf(EditorWaveformState.DEFAULT_VISIBLE_DURATION_MS, durationMs)
        composeTimestampSession = null
        composeChunkData.clear()
        composeChunkRequestedSamples.clear()
        repeat(calcTotalChunks()) {
            composeChunkData += null
            composeChunkRequestedSamples += 0
        }
        composeSpectrogramChunks.clear()
        composeSelectedIndices = emptySet()
        composeLimitedPlaybackIndex = null
        _composeState.value = _composeState.value.copy(subtitles = subtitles.map { it.copy() })
        publishComposeState()
    }

    fun setSubtitles(subtitles: List<SubtitleEntry>) {
        if (!hasPlayableMedia) return
        val previousLimitedPlayback = composeLimitedPlaybackIndex
        composeLimitedPlaybackIndex = composeLimitedPlaybackIndex?.takeIf { it in subtitles.indices }
        if (previousLimitedPlayback != null && composeLimitedPlaybackIndex == null) {
            onLimitedPlaybackRangeChanged(null)
        }
        _composeState.value = _composeState.value.copy(
            subtitles = subtitles.map { it.copy() },
            selectedIndices = emptySet()
        )
        composeSelectedIndices = emptySet()
        publishComposeState()
    }

    fun updateSubtitleEntries(changes: Map<Int, SubtitleEntry>, totalCount: Int): Boolean {
        if (!hasPlayableMedia) return true
        val entries = _composeState.value.subtitles.toMutableList()
        // Match WaveformTimelineView.updateSubtitleEntries(): an invalid changed index
        // must fall back to a complete subtitle replacement. Returning success for a
        // bad index makes the caller skip that fallback and leaves the waveform stale.
        if (entries.size != totalCount || changes.keys.any { it !in entries.indices }) {
            return false
        }
        changes.forEach { (index, entry) -> entries[index] = entry.copy() }
        if (changes.isNotEmpty()) {
            _composeState.value = _composeState.value.copy(subtitles = entries)
            publishComposeState()
        }
        return true
    }

    fun setSubtitlesPreserveSelection(subtitles: List<SubtitleEntry>) {
        if (!hasPlayableMedia) return
        val previousSelection = composeSelectedIndices
        val previousLimitedPlayback = composeLimitedPlaybackIndex
        _composeState.value = _composeState.value.copy(
            subtitles = subtitles.map { it.copy() },
            selectedIndices = composeSelectedIndices.filter { it in subtitles.indices }.toSet()
        )
        composeSelectedIndices = _composeState.value.selectedIndices
        composeLimitedPlaybackIndex = composeLimitedPlaybackIndex?.takeIf { it in subtitles.indices }
        if (previousLimitedPlayback != null && composeLimitedPlaybackIndex == null) {
            onLimitedPlaybackRangeChanged(null)
        }
        if (composeSelectedIndices != previousSelection ||
            composeLimitedPlaybackIndex != previousLimitedPlayback
        ) {
            composeSelectedIndices.firstOrNull()?.let(onSelectedIndexChanged)
        }
        publishComposeState()
    }

    fun setSubtitlesKeepSelection(subtitles: List<SubtitleEntry>, selectedIndex: Int) {
        if (!hasPlayableMedia) return
        composeSelectedIndices = composeSelectedIndices.map { if (it >= selectedIndex) it + 1 else it }.toSet()
        composeLimitedPlaybackIndex = composeLimitedPlaybackIndex?.let {
            if (it >= selectedIndex) it + 1 else it
        }
        _composeState.value = _composeState.value.copy(
            subtitles = subtitles.map { it.copy() },
            selectedIndices = composeSelectedIndices
        )
        composeSelectedIndices.firstOrNull()?.let(onSelectedIndexChanged)
        publishComposeState()
    }

    fun getAudioCacheKey(file: File): String? =
        audioCacheKey.takeIf { audioFile?.absolutePath == file.absolutePath }

    fun replaceSubtitleRange(startIndex: Int, removedCount: Int, inserted: List<SubtitleEntry>): Boolean {
        if (!hasPlayableMedia) return true
        val entries = _composeState.value.subtitles.toMutableList()
        if (startIndex !in 0..entries.size || removedCount !in 0..(entries.size - startIndex)) return false
        val delta = inserted.size - removedCount
        repeat(removedCount) { entries.removeAt(startIndex) }
        entries.addAll(startIndex, inserted.map { it.copy() })
        _composeState.value = _composeState.value.copy(subtitles = entries)
        composeSelectedIndices = composeSelectedIndices.mapNotNull { index ->
            when {
                index < startIndex -> index
                index >= startIndex + removedCount -> index + delta
                else -> null
            }
        }.filter { it in entries.indices }.toSet()
        val previousLimitedPlayback = composeLimitedPlaybackIndex
        composeLimitedPlaybackIndex = composeLimitedPlaybackIndex?.let { index ->
            when {
                index < startIndex -> index
                index >= startIndex + removedCount -> (index + delta).takeIf { it in entries.indices }
                else -> null
            }
        }
        if (previousLimitedPlayback != null && composeLimitedPlaybackIndex == null) {
            onLimitedPlaybackRangeChanged(null)
        }
        _composeState.value = _composeState.value.copy(selectedIndices = composeSelectedIndices)
        composeSelectedIndices.firstOrNull()?.let(onSelectedIndexChanged)
        publishComposeState()
        return true
    }

    fun setSubtitlesAfterDelete(subtitles: List<SubtitleEntry>, deletedIndices: Set<Int>) {
        if (!hasPlayableMedia) return
        val sorted = deletedIndices.sorted()
        composeSelectedIndices = composeSelectedIndices.mapNotNull { index ->
            if (index in deletedIndices) null else index - sorted.count { it < index }
        }.toSet()
        val previousLimitedPlayback = composeLimitedPlaybackIndex
        composeLimitedPlaybackIndex = composeLimitedPlaybackIndex?.let { index ->
            when {
                index in deletedIndices -> null
                else -> index - sorted.count { it < index }
            }
        }
        if (previousLimitedPlayback != null && composeLimitedPlaybackIndex == null) {
            onLimitedPlaybackRangeChanged(null)
        }
        _composeState.value = _composeState.value.copy(
            subtitles = subtitles.map { it.copy() },
            selectedIndices = composeSelectedIndices
        )
        composeSelectedIndices.firstOrNull()?.let(onSelectedIndexChanged)
        publishComposeState()
    }

    fun removeSubtitleIndices(deletedIndices: Set<Int>): Boolean {
        if (!hasPlayableMedia) return true
        val entries = _composeState.value.subtitles
        if (deletedIndices.any { it !in entries.indices }) return false
        val remaining = entries.filterIndexed { index, _ -> index !in deletedIndices }
        setSubtitlesAfterDelete(remaining, deletedIndices)
        return true
    }

    fun release() {
        onLimitedPlaybackRangeChanged(null)
        cancelSpectrogramJobs()
        cacheIndexGeneration++
        cacheIndexJob?.cancel()
        cacheIndexJob = null
        chunkLoader?.release()
        chunkLoader = null
        audioCacheKey = null
        durationMs = 0L
        audioFile = null
        hasAudioTrack = false
        isPreparingCacheIndex = false
        cacheIndexFailure = null
        composeChunkData.clear()
        composeChunkRequestedSamples.clear()
        composeSpectrogramChunks.values.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        composeSpectrogramChunks.clear()
        composeVisibleStartMs = 0L
        composeVisibleDurationMs = EditorWaveformState.DEFAULT_VISIBLE_DURATION_MS
        composeCurrentPosition = 0f
        composeTimestampSession = null
        composeSelectedIndices = emptySet()
        composeLimitedPlaybackIndex = null
        publishComposeState()
    }

    private fun initializeMediaCache(
        audioFile: File,
        durationMs: Long,
        audioStreamIndex: Int?,
        audioCacheKey: String,
        cacheIndexRequest: Long
    ) {
        if (cacheIndexRequest != cacheIndexGeneration) return

        restoreSpectrogramCacheState(audioFile)

        val cacheDir = when (SettingsManager.getInstance(context).getWaveformCacheLocation()) {
            SettingsManager.WAVEFORM_CACHE_APP -> File(appCacheDir, "waveform")
            else -> null
        }

        chunkLoader = FfmpegWaveformChunkLoader(context, scope).also {
            it.prepare(audioFile.absolutePath, durationMs, cacheDir, audioStreamIndex, audioCacheKey)
        }

        if (chunkLoader?.isCacheReady() == true) {
            isWaveformGenerated = true
            connectWaveformLoader()
        } else {
            isWaveformGenerated = false
        }
        publishComposeState()
    }

    private fun calcTotalChunks(): Int {
        if (durationMs <= 0) return 0
        val chunkMs = EditorWaveformState.CHUNK_DURATION_MS
        return ((durationMs + chunkMs - 1) / chunkMs).toInt()
    }

    private fun refreshWaveformToolbarState() = publishComposeState()

    private fun generateSpectrogramChunkAsync(
        chunkIndex: Int,
        startMs: Long,
        endMs: Long,
        widthPx: Int,
        heightPx: Int
    ) {
        val currentAudioFile = audioFile ?: return
        val requestDimensions = widthPx to heightPx
        if (spectrogramCacheDimensions != requestDimensions) {
            cancelSpectrogramJobs()
            spectrogramReadyChunks.clear()
            spectrogramDoneChunks = 0
            spectrogramCacheDimensions = requestDimensions
        }
        val cacheBaseDir = spectrogramCacheBaseDir(currentAudioFile) ?: return
        val currentAudioStreamIndex = audioStreamIndex
        val streamSuffix = currentAudioStreamIndex?.let { ".a$it" }.orEmpty()
        val specFile = File(
            cacheBaseDir,
            "${currentAudioFile.nameWithoutExtension}$streamSuffix.spec_${chunkIndex}_${widthPx}x${heightPx}.png"
        )
        val key = SpectrogramChunkKey(chunkIndex, widthPx, heightPx)

        synchronized(spectrogramStateLock) {
            if (spectrogramJobs.containsKey(key)) return
            val requestVersion = spectrogramGenerationVersion
            val job = scope.launch(
                context = Dispatchers.IO,
                start = CoroutineStart.LAZY
            ) {
                processSpectrogramChunk(
                    key = key,
                    requestVersion = requestVersion,
                    audioFile = currentAudioFile,
                    audioStreamIndex = currentAudioStreamIndex,
                    cacheFile = specFile,
                    startMs = startMs,
                    endMs = endMs
                )
            }
            spectrogramJobs[key] = job
            job.start()
        }
    }

    private suspend fun processSpectrogramChunk(
        key: SpectrogramChunkKey,
        requestVersion: Long,
        audioFile: File,
        audioStreamIndex: Int?,
        cacheFile: File,
        startMs: Long,
        endMs: Long
    ) {
        var bitmap: Bitmap? = null
        try {
            bitmap = spectrogramGenerationSemaphore.withPermit {
                decodeSpectrogramCache(cacheFile, key.width, key.height)
                    ?: generateSpectrogramCache(
                        audioFile = audioFile,
                        audioStreamIndex = audioStreamIndex,
                        cacheFile = cacheFile,
                        startMs = startMs,
                        endMs = endMs,
                        width = key.width,
                        height = key.height
                    )
            }
        } catch (error: CancellationException) {
            bitmap?.recycle()
            throw error
        } catch (error: Exception) {
            bitmap?.recycle()
            bitmap = null
            Log.e(TAG, "频谱图分块处理失败：chunk=${key.chunkIndex}", error)
        } finally {
            synchronized(spectrogramStateLock) {
                if (requestVersion == spectrogramGenerationVersion) {
                    spectrogramJobs.remove(key)
                }
            }
        }

        withContext(Dispatchers.Main) {
            if (requestVersion != spectrogramGenerationVersion ||
                this@EditorWaveformController.audioFile?.absolutePath != audioFile.absolutePath
            ) {
                bitmap?.recycle()
                return@withContext
            }

            if (bitmap == null) {
                // The legacy timeline removed the failed request and invalidated itself
                // after a short delay.  Retry through the same Compose request path so a
                // transient ffmpeg/cache failure does not leave a permanent blank chunk.
                scope.launch {
                    delay(SPECTROGRAM_RETRY_DELAY_MS)
                    if (requestVersion == spectrogramGenerationVersion &&
                        this@EditorWaveformController.audioFile?.absolutePath == audioFile.absolutePath &&
                        isSpectrogramGenerationStarted &&
                        currentDisplayMode == EditorWaveformDisplayMode.SPECTROGRAM
                    ) {
                        generateSpectrogramChunkAsync(
                            chunkIndex = key.chunkIndex,
                            startMs = startMs,
                            endMs = endMs,
                            widthPx = key.width,
                            heightPx = key.height
                        )
                    }
                }
                return@withContext
            }
            composeSpectrogramChunks.put(key.chunkIndex, bitmap)?.let { previous ->
                if (previous !== bitmap && !previous.isRecycled) previous.recycle()
            }
            if (spectrogramReadyChunks.add(key.chunkIndex)) {
                spectrogramDoneChunks = spectrogramReadyChunks.size
            }
            spectrogramIsGenerating = spectrogramDoneChunks < spectrogramTotalChunks
            publishComposeState()
            if (!spectrogramIsGenerating && spectrogramTotalChunks > 0) {
                showMessage("频谱图缓存生成完成")
            }
        }
    }

    private suspend fun generateSpectrogramCache(
        audioFile: File,
        audioStreamIndex: Int?,
        cacheFile: File,
        startMs: Long,
        endMs: Long,
        width: Int,
        height: Int
    ): Bitmap? {
        val parent = cacheFile.parentFile ?: return null
        if (!parent.exists() && !parent.mkdirs()) {
            Log.e(TAG, "无法创建频谱缓存目录：${parent.absolutePath}")
            return null
        }
        val partFile = File(parent, ".spectrogram_part_${UUID.randomUUID()}.png")

        return try {
            val spectrumFilter = "showspectrumpic=s=${width}x${height}:" +
                "mode=combined:color=intensity:scale=log:legend=0"
            val arguments = mutableListOf(
                "-hide_banner",
                "-loglevel", "error",
                "-nostdin",
                "-y",
                "-ss", formatFfmpegSeconds(startMs),
                "-t", formatFfmpegSeconds(endMs - startMs),
                "-i", audioFile.absolutePath
            )
            if (audioStreamIndex != null) {
                arguments += listOf(
                    "-filter_complex", "[0:$audioStreamIndex]$spectrumFilter[spectrum]",
                    "-map", "[spectrum]"
                )
            } else {
                arguments += listOf("-lavfi", spectrumFilter)
            }
            arguments += listOf(
                "-frames:v", "1",
                "-f", "image2",
                "-c:v", "png",
                partFile.absolutePath
            )

            val session = executeFfmpeg(arguments.toTypedArray())
            if (session.getReturnCode()?.isValueSuccess() != true) {
                Log.e(TAG, "频谱图分块生成失败：${session.getOutput()}")
                return null
            }

            val bitmap = decodeSpectrogramCache(partFile, width, height) ?: return null
            try {
                publishSpectrogramCache(partFile, cacheFile)
                bitmap
            } catch (error: Exception) {
                bitmap.recycle()
                throw error
            }
        } finally {
            if (partFile.exists() && !partFile.delete()) {
                Log.w(TAG, "无法删除频谱临时文件：${partFile.absolutePath}")
            }
        }
    }

    private suspend fun executeFfmpeg(arguments: Array<String>): FFmpegSession {
        return suspendCancellableCoroutine { continuation ->
            val sessionRef = AtomicReference<FFmpegSession?>()
            continuation.invokeOnCancellation {
                sessionRef.get()?.cancel()
            }
            val session = FFmpegKit.executeWithArgumentsAsync(arguments) { completedSession ->
                if (continuation.isActive) {
                    continuation.resume(completedSession)
                }
            }
            sessionRef.set(session)
            if (!continuation.isActive) {
                session.cancel()
            }
        }
    }

    private fun decodeSpectrogramCache(file: File, width: Int, height: Int): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth != width || bounds.outHeight != height) {
            deleteInvalidSpectrogramCache(file)
            return null
        }

        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            bitmap?.recycle()
            deleteInvalidSpectrogramCache(file)
            return null
        }
        return bitmap
    }

    private fun isSpectrogramCacheValid(file: File, width: Int, height: Int): Boolean {
        return readPngDimensions(file) == (width to height)
    }

    private fun readPngDimensions(file: File): Pair<Int, Int>? {
        if (!file.isFile || file.length() < 45L) return null
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                val header = ByteArray(24)
                input.readFully(header)
                val signature = byteArrayOf(
                    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
                )
                if (!header.copyOfRange(0, 8).contentEquals(signature) ||
                    header[12] != 'I'.code.toByte() ||
                    header[13] != 'H'.code.toByte() ||
                    header[14] != 'D'.code.toByte() ||
                    header[15] != 'R'.code.toByte()
                ) {
                    return@use null
                }

                input.seek(input.length() - 12L)
                val trailer = ByteArray(12)
                input.readFully(trailer)
                val iend = byteArrayOf(
                    0, 0, 0, 0,
                    'I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(),
                    0xae.toByte(), 0x42, 0x60, 0x82.toByte()
                )
                if (!trailer.contentEquals(iend)) return@use null

                val width =
                    ((header[16].toInt() and 0xff) shl 24) or
                        ((header[17].toInt() and 0xff) shl 16) or
                        ((header[18].toInt() and 0xff) shl 8) or
                        (header[19].toInt() and 0xff)
                val height =
                    ((header[20].toInt() and 0xff) shl 24) or
                        ((header[21].toInt() and 0xff) shl 16) or
                        ((header[22].toInt() and 0xff) shl 8) or
                        (header[23].toInt() and 0xff)
                if (width > 0 && height > 0) width to height else null
            }
        }.getOrNull()
    }

    private fun deleteInvalidSpectrogramCache(file: File) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "无法删除损坏的频谱缓存：${file.absolutePath}")
        }
    }

    private fun publishSpectrogramCache(partFile: File, cacheFile: File) {
        try {
            Files.move(
                partFile.toPath(),
                cacheFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                partFile.toPath(),
                cacheFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun formatFfmpegSeconds(milliseconds: Long): String {
        val seconds = milliseconds / 1000L
        val remainder = milliseconds % 1000L
        return "$seconds.${remainder.toString().padStart(3, '0')}"
    }

    private fun restoreSpectrogramCacheState(audioFile: File) {
        spectrogramTotalChunks = calcTotalChunks()
        val width = SPECTROGRAM_WIDTH
        val height = (composeViewportHeightPx * WAVE_HEIGHT_FRACTION).toInt().coerceAtLeast(64)
        if (width <= 0 || height <= 0) return
        spectrogramCacheDimensions = width to height
        val cacheBaseDir = spectrogramCacheBaseDir(audioFile) ?: return
        val streamSuffix = audioStreamIndex?.let { ".a$it" }.orEmpty()
        val prefix = "${audioFile.nameWithoutExtension}$streamSuffix.spec_"

        spectrogramReadyChunks.clear()
        cleanupStaleSpectrogramParts(cacheBaseDir)
        for (chunkIndex in 0 until spectrogramTotalChunks) {
            val file = File(cacheBaseDir, "${prefix}${chunkIndex}_${width}x${height}.png")
            if (isSpectrogramCacheValid(file, width, height)) {
                spectrogramReadyChunks.add(chunkIndex)
            } else if (file.exists()) {
                deleteInvalidSpectrogramCache(file)
            }
        }
        spectrogramDoneChunks = spectrogramReadyChunks.size
        isSpectrogramGenerationStarted = spectrogramDoneChunks > 0
        spectrogramIsGenerating =
            isSpectrogramGenerationStarted && spectrogramDoneChunks < spectrogramTotalChunks
        publishComposeState()
    }

    private fun cleanupStaleSpectrogramParts(cacheDir: File) {
        val cutoff = System.currentTimeMillis() - STALE_SPECTROGRAM_PART_MAX_AGE_MS
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile &&
                file.name.startsWith(".spectrogram_part_") &&
                file.lastModified() < cutoff &&
                !file.delete()
            ) {
                Log.w(TAG, "无法删除过期频谱临时文件：${file.absolutePath}")
            }
        }
    }

    private fun spectrogramCacheBaseDir(audioFile: File): File? {
        val cacheKey = audioCacheKey.takeIf { this.audioFile?.absolutePath == audioFile.absolutePath }
            ?: return null
        val cacheRootDir = when (SettingsManager.getInstance(context).getWaveformCacheLocation()) {
            SettingsManager.WAVEFORM_CACHE_APP -> File(appCacheDir, "waveform")
            else -> audioFile.parentFile ?: File(appCacheDir, "waveform")
        }.apply { mkdirs() }
        return File(cacheRootDir, cacheKey).apply { mkdirs() }
    }

    private fun updateGenerateButton() = publishComposeState()

    private fun startWaveformGeneration() {
        if (chunkLoader == null) return
        isWaveformGenerating = true
        publishComposeState()
        updateGenerateButton()
        showMessage("已启用波形图按需生成")
        chunkLoader?.generateCache { success ->
            isWaveformGenerating = false
            if (success) {
                isWaveformGenerated = true
                showMessage("波形图将在浏览时按需生成")
                connectWaveformLoader()
            } else {
                showMessage("波形缓存生成失败")
            }
            publishComposeState()
            updateGenerateButton()
        }
    }

    private fun startSpectrogramGeneration() {
        if (audioCacheKey == null) return
        isSpectrogramGenerationStarted = true
        spectrogramTotalChunks = calcTotalChunks()
        spectrogramDoneChunks = spectrogramReadyChunks.size
        spectrogramIsGenerating = spectrogramDoneChunks < spectrogramTotalChunks
        publishComposeState()
        updateGenerateButton()
        showMessage("频谱图将在浏览时按需生成")
        publishComposeState()
    }

    private fun cancelSpectrogramJobs() {
        val jobs = synchronized(spectrogramStateLock) {
            spectrogramGenerationVersion++
            val activeJobs = spectrogramJobs.values.toList()
            spectrogramJobs.clear()
            activeJobs
        }
        jobs.forEach { it.cancel() }
    }

    private fun connectWaveformLoader() {
        // The legacy view reset its request counters after generation was enabled so empty
        // pre-generation responses are requested again at the current precision.
        composeChunkRequestedSamples.indices.forEach { index ->
            composeChunkRequestedSamples[index] = 0
        }
        // The legacy view explicitly refreshed the visible chunks whenever a loader became
        // available. A state token keeps that refresh observable even when the loader was
        // already marked generated and no other waveform flag changes.
        composeChunkRequestGeneration++
        publishComposeState()
    }

    private companion object {
        const val TAG = "EditorWaveformController"
        const val MIN_SUBTITLE_DURATION_MS = 100L
        const val LEGACY_RESAMPLE_THRESHOLD = 0.6f
        const val SPECTROGRAM_WIDTH = 2048
        const val WAVE_HEIGHT_FRACTION = 0.67f
        const val MAX_CONCURRENT_SPECTROGRAM_GENERATIONS = 2
        const val STALE_SPECTROGRAM_PART_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        const val SPECTROGRAM_RETRY_DELAY_MS = 5_000L
    }
}
