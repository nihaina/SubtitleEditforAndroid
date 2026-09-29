package com.subtitleedit.editor

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.subtitleedit.ComposeDialogHost
import com.subtitleedit.R
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.mpv.EditorMpvAudioPlayer
import com.subtitleedit.mpv.MpvPlayerHost
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleHighlightCursor
import com.subtitleedit.util.TimeUtils
import java.io.File
import java.util.Locale

internal class EditorPlaybackController(
    private val context: Context,
    private var mediaType: EditorMediaType,
    private val subtitles: () -> List<SubtitleEntry>,
    private val isSourceViewMode: () -> Boolean,
    private val onPlayingSubtitleChanged: (Int?) -> Unit,
    private val onMediaReady: (Long, Int?) -> Unit,
    private val showMessage: (String) -> Unit,
    private val videoPlayerHost: MpvPlayerHost? = null
) {
    var uiState by mutableStateOf(EditorPlaybackUiState(mediaType = mediaType))
        private set

    var currentPositionMs: Long = 0L
        private set

    var durationMs: Long = 0L
        private set

    var playbackSpeed: Float = 1.0f
        private set

    /** Optional observer used by the Compose waveform to render the live playhead. */
    var onPositionChanged: ((positionMs: Long, durationMs: Long) -> Unit)? = null

    private var isPlaying = false
    private var isUserSeeking = false
    private var engine: EditorPlaybackEngine? = null
    private var limitedPlaybackEntry: SubtitleEntry? = null
    private var isLimitedRangePlaybackActive = false
    private val highlightCursor = SubtitleHighlightCursor()

    private val frameCallback = Choreographer.FrameCallback { onProgressFrame() }
    private var progressScheduled = false
    private var lastPlayPauseShowsPause: Boolean? = null
    private var lastTotalTimeText: String? = null
    private var lastCurrentTimeText: String? = null
    private var lastVideoTimeText: String? = null
    private var lastSeekBarProgress: Int? = null
    private val videoControlsHandler = Handler(Looper.getMainLooper())
    private var videoControlsVisible = true
    private val hideVideoControlsRunnable = Runnable {
        setVideoControlsVisible(visible = false, animate = true)
    }

    fun bind() {
        createEngine()
        publishUiState()
        if (mediaType == EditorMediaType.VIDEO) {
            showVideoControls(scheduleAutoHide = false)
        }
    }

    /** Switch the active editor media type without recreating the activity. */
    fun setMediaType(newMediaType: EditorMediaType) {
        if (mediaType == newMediaType) return
        release()
        mediaType = newMediaType
        currentPositionMs = 0L
        durationMs = 0L
        lastPlayPauseShowsPause = null
        lastTotalTimeText = null
        lastCurrentTimeText = null
        lastVideoTimeText = null
        lastSeekBarProgress = null
        uiState = uiState.copy(mediaType = newMediaType)
        createEngine()
        publishUiState()
    }

    private fun createEngine() {
        if (!mediaType.hasPlayableMedia) {
            engine = null
            return
        }

        engine = when (mediaType) {
            EditorMediaType.AUDIO -> MpvPlaybackEngine(
                playerHost = EditorMpvAudioPlayer(context),
                mediaLabel = "音频",
                interpolateAudioPosition = true,
                configDir = context.filesDir,
                cacheDir = context.cacheDir
            )
            EditorMediaType.VIDEO -> videoPlayerHost?.let { host ->
                MpvPlaybackEngine(
                    playerHost = host,
                    mediaLabel = "视频",
                    interpolateAudioPosition = false,
                    configDir = context.filesDir,
                    cacheDir = context.cacheDir
                )
            }
            EditorMediaType.SUBTITLE_ONLY -> null
        }?.also { playbackEngine ->
            playbackEngine.listener = object : EditorPlaybackEngine.Listener {
                override fun onReady(durationMs: Long, audioStreamIndex: Int?) {
                    this@EditorPlaybackController.durationMs = durationMs
                    updatePlayerUi()
                    renderControlAvailability()
                    if (mediaType == EditorMediaType.VIDEO) {
                        uiState = uiState.copy(videoStatus = null)
                        showVideoControls(scheduleAutoHide = isPlaying)
                    }
                    // Audio playback can use a different file or track than waveform analysis.
                    // Keep the analysis stream index supplied by media preparation.
                    onMediaReady(
                        durationMs,
                        if (mediaType == EditorMediaType.VIDEO) audioStreamIndex else null
                    )
                }

                override fun onPlaybackStateChanged() {
                    updatePlayerUi()
                    if (playbackEngine.isPlaying) startProgressUpdate() else stopProgressUpdate()
                    syncVideoControlsWithPlayback()
                }

                override fun onCompleted() {
                    isPlaying = false
                    stopProgressUpdate()
                    updatePlayerUi()
                    showVideoControls(scheduleAutoHide = false)
                }

                override fun onError(message: String) {
                    isPlaying = false
                    stopProgressUpdate()
                    renderControlAvailability()
                    renderPlayPauseIcon()
                    if (mediaType == EditorMediaType.VIDEO) {
                        uiState = uiState.copy(videoStatus = message)
                        showVideoControls(scheduleAutoHide = false)
                    }
                    showMessage(message)
                }
            }
        }
    }

    fun prepare(mediaFile: File) {
        if (!mediaType.hasPlayableMedia) return
        if (engine == null) createEngine()
        currentPositionMs = 0L
        durationMs = 0L
        if (mediaType == EditorMediaType.VIDEO) {
            uiState = uiState.copy(videoStatus = "正在加载视频…")
            showVideoControls(scheduleAutoHide = false)
        }
        engine?.prepare(mediaFile)
        renderControlAvailability()
    }

    fun seekTo(timeMs: Long) {
        val playbackEngine = engine?.takeIf { it.phase.canAccessPlayer } ?: return
        isLimitedRangePlaybackActive = false
        val clampedTime = timeMs.coerceIn(0L, durationMs)
        playbackEngine.seekTo(clampedTime)
        currentPositionMs = clampedTime
        updatePlayerUiAtKnownPosition(clampedTime)
        if (isPlaying) startProgressUpdate()
    }

    fun pauseForLifecycle() {
        engine?.takeIf { it.phase.canAccessPlayer && it.isPlaying }?.pause()
        isPlaying = false
        stopProgressUpdate()
        updatePlayerUi()
        showVideoControls(scheduleAutoHide = false)
    }

    fun replaceVideoSubtitleTrack(file: File?) {
        if (mediaType == EditorMediaType.VIDEO) {
            (engine as? MpvPlaybackEngine)?.replaceSubtitleTrack(file)
        }
    }

    fun release() {
        stopProgressUpdate()
        videoControlsHandler.removeCallbacksAndMessages(null)
        engine?.release()
        engine = null
        isPlaying = false
    }

    fun invalidateHighlightCache() {
        highlightCursor.invalidate()
    }

    fun showVideoControlsForInteraction() {
        showVideoControls(scheduleAutoHide = isPlaying)
    }

    private fun onProgressFrame() {
        progressScheduled = false
        val playbackEngine = engine?.takeIf { it.phase.canAccessPlayer } ?: return
        if (!playbackEngine.isPlaying) return
        isPlaying = true
        renderPlayPauseIcon()

        if (!isUserSeeking) {
            val position = playbackEngine.currentPositionMs
            if (position >= currentPositionMs || currentPositionMs - position > 200L) {
                currentPositionMs = position
            }
        }
        renderProgress(currentPositionMs)

        val rangeTarget = limitedPlaybackEntry
        if (isLimitedRangePlaybackActive && rangeTarget != null) {
            when {
                currentPositionMs >= rangeTarget.endTime -> {
                    if (SettingsManager.getInstance(context).isLoopSelectedSubtitleEnabled()) {
                        playbackEngine.seekTo(rangeTarget.startTime)
                        updatePlayerUiAtKnownPosition(rangeTarget.startTime)
                    } else {
                        playbackEngine.pause()
                        playbackEngine.seekTo(rangeTarget.endTime)
                        isPlaying = false
                        isLimitedRangePlaybackActive = false
                        stopProgressUpdate()
                        updatePlayerUiAtKnownPosition(rangeTarget.endTime)
                        return
                    }
                }
                currentPositionMs < rangeTarget.startTime -> {
                    playbackEngine.seekTo(rangeTarget.startTime)
                    updatePlayerUiAtKnownPosition(rangeTarget.startTime)
                }
            }
        }

        startProgressUpdate()
    }

    /** Timeline callbacks consumed by the Compose waveform surface. */
    fun onTimelineClick(position: Float) = seekTo((durationMs * position).toLong())

    fun onViewportPlayheadCorrection(positionMs: Long) = correctPlaybackAfterViewportDrag(positionMs)

    fun onLimitedPlaybackRangeChanged(subtitleIndex: Int?) {
        limitedPlaybackEntry = subtitleIndex?.let { subtitles().getOrNull(it) }
        isLimitedRangePlaybackActive = false
        publishUiState()
    }

    fun onLimitedPlaybackStartRequest(subtitleIndex: Int) = startLimitedRangePlayback(subtitleIndex)

    fun onLimitedPlaybackRangeOutOfView() {
        if (!isLimitedRangePlaybackActive) return
        isLimitedRangePlaybackActive = false
        engine?.pause()
        isPlaying = false
        stopProgressUpdate()
        updatePlayerUi()
    }

    fun onSeekStarted() {
        isUserSeeking = true
        if (mediaType == EditorMediaType.VIDEO) showVideoControls(scheduleAutoHide = false)
    }

    fun onSeekProgress(fraction: Float) {
        currentPositionMs = (durationMs * fraction.coerceIn(0f, 1f)).toLong()
        renderProgress(currentPositionMs)
    }

    fun onSeekFinished() {
        isUserSeeking = false
        seekTo(currentPositionMs)
        if (mediaType == EditorMediaType.VIDEO && isPlaying) scheduleVideoControlsAutoHide()
    }

    fun togglePlayPause() {
        val playbackEngine = engine?.takeIf { it.phase.canAccessPlayer } ?: return
        if (playbackEngine.isPlaying) {
            playbackEngine.pause()
            isPlaying = false
            stopProgressUpdate()
        } else {
            isLimitedRangePlaybackActive = false
            playbackEngine.play()
            isPlaying = true
            startProgressUpdate()
        }
        updatePlayerUi()
        syncVideoControlsWithPlayback()
    }

    private fun correctPlaybackAfterViewportDrag(positionMs: Long) {
        val playbackEngine = engine?.takeIf { it.phase.canAccessPlayer } ?: return
        val correctedPositionMs = positionMs.coerceIn(0L, durationMs)
        val wasPlaying = playbackEngine.isPlaying
        playbackEngine.seekTo(correctedPositionMs)
        isPlaying = wasPlaying
        updatePlayerUiAtKnownPosition(correctedPositionMs)
        if (wasPlaying) startProgressUpdate() else stopProgressUpdate()
    }

    private fun startLimitedRangePlayback(subtitleIndex: Int) {
        val playbackEngine = engine?.takeIf { it.phase.canAccessPlayer } ?: return
        val target = subtitles().getOrNull(subtitleIndex) ?: return
        limitedPlaybackEntry = target
        isLimitedRangePlaybackActive = true
        playbackEngine.seekTo(target.startTime)
        if (!playbackEngine.isPlaying) playbackEngine.play()
        isPlaying = true
        updatePlayerUiAtKnownPosition(target.startTime)
        startProgressUpdate()
    }

    private fun updatePlayerUiAtKnownPosition(positionMs: Long) {
        val clampedPositionMs = positionMs.coerceIn(0L, durationMs)
        currentPositionMs = clampedPositionMs
        val previousUserSeeking = isUserSeeking
        isUserSeeking = true
        updatePlayerUi()
        isUserSeeking = previousUserSeeking
    }

    private fun highlightSubtitleAtTime(timeMs: Long) {
        if (isSourceViewMode()) return
        val index = highlightCursor.resolve(subtitles(), timeMs)
        onPlayingSubtitleChanged(if (index >= 0) index else null)
    }

    private fun renderProgress(positionMs: Long) {
        val currentTimeText = TimeUtils.formatForDisplay(positionMs)
        val videoTimeText = "${currentTimeText} / ${TimeUtils.formatForDisplay(durationMs)}"
        val progress = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else 0f
        lastCurrentTimeText = currentTimeText
        lastVideoTimeText = videoTimeText
        lastSeekBarProgress = (progress * 1000f).toInt()
        uiState = uiState.copy(
            currentPositionMs = positionMs,
            durationMs = durationMs,
            progress = progress,
            currentTimeText = currentTimeText,
            videoTimeText = videoTimeText
        )
        onPositionChanged?.invoke(positionMs, durationMs)
        highlightSubtitleAtTime(positionMs)
    }

    private fun renderPlayPauseIcon() {
        lastPlayPauseShowsPause = isPlaying
        uiState = uiState.copy(isPlaying = isPlaying)
    }

    private fun renderTotalTime() {
        val text = TimeUtils.formatForDisplay(durationMs)
        lastTotalTimeText = text
        uiState = uiState.copy(totalTimeText = text)
    }

    private fun renderControlAvailability() {
        val enabled = engine?.phase?.canAccessPlayer == true
        uiState = uiState.copy(enabled = enabled)
    }

    private fun updatePlayerUi() {
        engine?.takeIf { it.phase.canAccessPlayer }?.let { playbackEngine ->
            if (!isUserSeeking) {
                val position = playbackEngine.currentPositionMs
                if (position >= currentPositionMs || currentPositionMs - position > 200L) {
                    currentPositionMs = position
                }
            }
            durationMs = playbackEngine.durationMs.takeIf { it > 0L } ?: durationMs
            isPlaying = playbackEngine.isPlaying
        }
        renderProgress(currentPositionMs)
        renderPlayPauseIcon()
        renderTotalTime()
        renderControlAvailability()
    }

    private fun startProgressUpdate() {
        if (progressScheduled) return
        progressScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopProgressUpdate() {
        if (!progressScheduled) return
        progressScheduled = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    fun showSpeedInputDialog() {
        val activity = context as? Activity ?: return
        val input = mutableStateOf(formatPlaybackSpeedValue(playbackSpeed))
        val handle = ComposeDialogHost.show(activity) { dialog ->
            val focusRequester = androidx.compose.runtime.remember { FocusRequester() }
            val keyboardController = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
            AlertDialog(
                onDismissRequest = dialog::dismiss,
                title = { Text("设置播放速率") },
                text = {
                    Column {
                        Text("请输入倍数（0.25 ~ 4.0）")
                        OutlinedTextField(
                            value = input.value,
                            onValueChange = { input.value = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            singleLine = true,
                            placeholder = { Text("例如：0.5、1.0、1.5、2.0") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dialog.dismiss()
                            val speed = input.value.trim().toFloatOrNull()
                            when {
                                speed == null -> showMessage("请输入有效数字")
                                speed < 0.25f || speed > 4.0f -> showMessage("速率范围：0.25 ~ 4.0")
                                else -> applyPlaybackSpeed(speed)
                            }
                        }
                    ) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = dialog::dismiss) { Text("取消") }
                }
            )
        }
        handle.addOnDismissListener {
            if (mediaType == EditorMediaType.VIDEO && isPlaying) {
                scheduleVideoControlsAutoHide()
            }
        }
    }

    fun applyPlaybackSpeed(speed: Float, showConfirmation: Boolean = true) {
        playbackSpeed = speed
        val label = if (speed == speed.toLong().toFloat()) {
            "${speed.toLong()}×"
        } else {
            formatPlaybackSpeedValue(speed) + "×"
        }
        uiState = uiState.copy(playbackSpeed = speed, playbackSpeedLabel = label)
        try {
            engine?.setSpeed(speed)
        } catch (error: Exception) {
            showMessage("设置速率失败：${error.message}")
            return
        }
        if (showConfirmation) showMessage("播放速率已设置为 $label")
    }

    private fun formatPlaybackSpeedValue(speed: Float): String =
        String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')

    private fun syncVideoControlsWithPlayback() {
        if (mediaType != EditorMediaType.VIDEO) return
        if (isPlaying) {
            showVideoControls(scheduleAutoHide = true)
        } else {
            showVideoControls(scheduleAutoHide = false)
        }
    }

    private fun showVideoControls(scheduleAutoHide: Boolean) {
        if (mediaType != EditorMediaType.VIDEO) return
        setVideoControlsVisible(visible = true, animate = true)
        if (scheduleAutoHide) scheduleVideoControlsAutoHide()
    }

    private fun scheduleVideoControlsAutoHide() {
        if (mediaType != EditorMediaType.VIDEO || isUserSeeking || !isPlaying) return
        videoControlsHandler.removeCallbacks(hideVideoControlsRunnable)
        videoControlsHandler.postDelayed(hideVideoControlsRunnable, VIDEO_CONTROLS_HIDE_DELAY_MS)
    }

    private fun setVideoControlsVisible(visible: Boolean, animate: Boolean) {
        if (mediaType != EditorMediaType.VIDEO) return
        videoControlsHandler.removeCallbacks(hideVideoControlsRunnable)
        videoControlsVisible = visible
        uiState = uiState.copy(videoControlsVisible = visible)
    }

    private fun publishUiState() {
        val progress = if (durationMs > 0L) {
            (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else 0f
        uiState = uiState.copy(
            mediaType = mediaType,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            progress = progress,
            isPlaying = isPlaying,
            enabled = engine?.phase?.canAccessPlayer == true,
            playbackSpeed = playbackSpeed,
            playbackSpeedLabel = formatPlaybackSpeedValue(playbackSpeed) + "×",
            videoControlsVisible = videoControlsVisible
        )
    }

    private companion object {
        const val VIDEO_CONTROLS_HIDE_DELAY_MS = 3_000L
    }
}

internal data class EditorPlaybackUiState(
    val mediaType: EditorMediaType,
    val enabled: Boolean = false,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val progress: Float = 0f,
    val playbackSpeed: Float = 1f,
    val playbackSpeedLabel: String = "1×",
    val currentTimeText: String = "00:00:00.000",
    val totalTimeText: String = "00:00:00.000",
    val videoTimeText: String = "00:00:00.000 / 00:00:00.000",
    val videoStatus: String? = null,
    val videoControlsVisible: Boolean = true
)
