package com.subtitleedit.editor

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Choreographer
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.SeekBar
import com.subtitleedit.R
import com.subtitleedit.databinding.ActivityEditorBinding
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleHighlightCursor
import com.subtitleedit.util.TimeUtils
import java.io.File
import java.util.Locale

internal class EditorPlaybackController(
    private val context: Context,
    private val binding: ActivityEditorBinding,
    private val mediaType: EditorMediaType,
    private val subtitles: () -> List<SubtitleEntry>,
    private val isSourceViewMode: () -> Boolean,
    private val onPlayingSubtitleChanged: (Int?) -> Unit,
    private val onMediaReady: (Long, Int?) -> Unit,
    private val showMessage: (String) -> Unit
) {
    var currentPositionMs: Long = 0L
        private set

    var durationMs: Long = 0L
        private set

    var playbackSpeed: Float = 1.0f
        private set

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
        if (!mediaType.hasPlayableMedia) return

        engine = when (mediaType) {
            EditorMediaType.AUDIO -> MediaPlayerPlaybackEngine()
            EditorMediaType.VIDEO -> MpvVideoPlaybackEngine(
                view = binding.mpvView,
                configDir = context.filesDir,
                cacheDir = context.cacheDir
            )
            EditorMediaType.SUBTITLE_ONLY -> null
        }?.also { playbackEngine ->
            playbackEngine.listener = object : EditorPlaybackEngine.Listener {
                override fun onReady(durationMs: Long, audioStreamIndex: Int?) {
                    this@EditorPlaybackController.durationMs = durationMs
                    updatePlayerUi()
                    renderControlAvailability()
                    if (mediaType == EditorMediaType.VIDEO) {
                        binding.tvVideoStatus.visibility = View.GONE
                        showVideoControls(scheduleAutoHide = isPlaying)
                    }
                    onMediaReady(durationMs, audioStreamIndex)
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
                        binding.tvVideoStatus.text = message
                        binding.tvVideoStatus.visibility = View.VISIBLE
                        showVideoControls(scheduleAutoHide = false)
                    }
                    showMessage(message)
                }
            }
        }

        bindTimelinePlaybackCallbacks()
        bindPlayerControls()
        renderPlayPauseIcon()
        renderTotalTime()
        renderProgress(currentPositionMs)
        renderControlAvailability()
        if (mediaType == EditorMediaType.VIDEO) {
            showVideoControls(scheduleAutoHide = false)
        }
    }

    fun prepare(mediaFile: File) {
        currentPositionMs = 0L
        durationMs = 0L
        if (mediaType == EditorMediaType.VIDEO) {
            binding.tvVideoStatus.visibility = View.VISIBLE
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
        if (mediaType == EditorMediaType.AUDIO) {
            // MediaPlayer seeks asynchronously. Keep the head on its last actual position
            // until OnSeekComplete supplies the new media-clock position.
            stopProgressUpdate()
            return
        }
        currentPositionMs = clampedTime
        highlightSubtitleAtTime(currentPositionMs)
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
        (engine as? MpvVideoPlaybackEngine)?.replaceSubtitleTrack(file)
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
        highlightSubtitleAtTime(currentPositionMs)

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

    private fun bindTimelinePlaybackCallbacks() {
        binding.waveformTimelineView.onTimelineClickListener = { position ->
            seekTo((durationMs * position).toLong())
        }
        binding.waveformTimelineView.onDraggedViewportPlayheadCorrection = { positionMs, isFinal ->
            correctPlaybackAfterViewportDrag(positionMs, isFinal)
        }
        binding.waveformTimelineView.onLimitedPlaybackRangeChange = { subtitleIndex ->
            limitedPlaybackEntry = subtitleIndex?.let { subtitles().getOrNull(it) }
            isLimitedRangePlaybackActive = false
        }
        binding.waveformTimelineView.onLimitedPlaybackStartRequest = { subtitleIndex ->
            startLimitedRangePlayback(subtitleIndex)
        }
        binding.waveformTimelineView.onSubtitleStartSeekRequest = ::seekTo
        binding.waveformTimelineView.onLimitedPlaybackRangeOutOfView = {
            if (isLimitedRangePlaybackActive) {
                isLimitedRangePlaybackActive = false
                engine?.pause()
                isPlaying = false
                stopProgressUpdate()
                updatePlayerUi()
            }
        }
    }

    private fun bindPlayerControls() {
        when (mediaType) {
            EditorMediaType.AUDIO -> {
                binding.btnPlayPause.setOnClickListener { togglePlayPause() }
                binding.tvPlaybackSpeed.setOnClickListener { showSpeedInputDialog() }
                bindSeekBar(binding.seekBar)
            }
            EditorMediaType.VIDEO -> {
                binding.btnVideoPlayPause.setOnClickListener {
                    togglePlayPause()
                    showVideoControls(scheduleAutoHide = isPlaying)
                }
                binding.tvVideoPlaybackSpeed.setOnClickListener {
                    showVideoControls(scheduleAutoHide = false)
                    showSpeedInputDialog()
                }
                bindSeekBar(binding.videoSeekBar)
                binding.mpvView.setOnClickListener {
                    showVideoControls(scheduleAutoHide = isPlaying)
                }
                binding.videoControlsOverlay.setOnClickListener {
                    setVideoControlsVisible(visible = false, animate = true)
                }
            }
            EditorMediaType.SUBTITLE_ONLY -> Unit
        }
    }

    private fun bindSeekBar(seekBar: SeekBar) {
        seekBar.max = 1000
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val targetTime = durationMs * progress / 1000L
                currentPositionMs = targetTime
                if (mediaType == EditorMediaType.AUDIO) {
                    binding.tvCurrentTime.text = TimeUtils.formatForDisplay(targetTime)
                } else if (mediaType == EditorMediaType.VIDEO) {
                    renderVideoTime(targetTime)
                }
                val wavePosition = if (durationMs > 0L) targetTime.toFloat() / durationMs else 0f
                binding.waveformTimelineView.setCurrentPosition(wavePosition)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
                if (mediaType == EditorMediaType.VIDEO) {
                    showVideoControls(scheduleAutoHide = false)
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                seekTo(currentPositionMs)
                if (mediaType == EditorMediaType.VIDEO && isPlaying) {
                    scheduleVideoControlsAutoHide()
                }
            }
        })
    }

    private fun togglePlayPause() {
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

    private fun correctPlaybackAfterViewportDrag(positionMs: Long, isFinal: Boolean) {
        if (isFinal && mediaType != EditorMediaType.VIDEO) return
        val playbackEngine = engine?.takeIf { it.phase.canAccessPlayer } ?: return
        val correctedPositionMs = positionMs.coerceIn(0L, durationMs)
        val wasPlaying = playbackEngine.isPlaying
        val seekMode = if (mediaType == EditorMediaType.VIDEO && !isFinal) {
            PlaybackSeekMode.KEYFRAME
        } else {
            PlaybackSeekMode.EXACT
        }
        playbackEngine.seekTo(correctedPositionMs, seekMode)
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
        highlightSubtitleAtTime(clampedPositionMs)
        val previousUserSeeking = isUserSeeking
        isUserSeeking = true
        updatePlayerUi()
        isUserSeeking = previousUserSeeking
        activeSeekBar().progress = if (durationMs > 0L) {
            (clampedPositionMs * 1000L / durationMs).toInt().coerceIn(0, 1000)
        } else {
            0
        }
    }

    private fun highlightSubtitleAtTime(timeMs: Long) {
        if (isSourceViewMode()) return
        val index = highlightCursor.resolve(subtitles(), timeMs)
        onPlayingSubtitleChanged(if (index >= 0) index else null)
    }

    private fun renderProgress(positionMs: Long) {
        if (mediaType == EditorMediaType.AUDIO) {
            val currentTimeText = TimeUtils.formatForDisplay(positionMs)
            if (lastCurrentTimeText != currentTimeText) {
                lastCurrentTimeText = currentTimeText
                binding.tvCurrentTime.text = currentTimeText
            }
        } else if (mediaType == EditorMediaType.VIDEO) {
            renderVideoTime(positionMs)
        }
        if (!isUserSeeking) {
            val seekBarProgress = if (durationMs > 0L) {
                (positionMs * 1000L / durationMs).toInt().coerceIn(0, 1000)
            } else {
                0
            }
            if (lastSeekBarProgress != seekBarProgress) {
                lastSeekBarProgress = seekBarProgress
                activeSeekBar().progress = seekBarProgress
            }
        }
        val wavePosition = if (durationMs > 0L) positionMs.toFloat() / durationMs else 0f
        binding.waveformTimelineView.setCurrentPosition(wavePosition)
    }

    private fun renderPlayPauseIcon() {
        if (lastPlayPauseShowsPause == isPlaying) return
        lastPlayPauseShowsPause = isPlaying
        when (mediaType) {
            EditorMediaType.AUDIO -> binding.btnPlayPause.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            EditorMediaType.VIDEO -> {
                binding.btnVideoPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_video_pause else R.drawable.ic_video_play
                )
                binding.btnVideoPlayPause.contentDescription = context.getString(
                    if (isPlaying) R.string.editor_video_pause else R.string.editor_video_play
                )
            }
            EditorMediaType.SUBTITLE_ONLY -> Unit
        }
    }

    private fun renderTotalTime() {
        if (mediaType != EditorMediaType.AUDIO) return
        val text = TimeUtils.formatForDisplay(durationMs)
        if (lastTotalTimeText == text) return
        lastTotalTimeText = text
        binding.tvTotalTime.text = text
    }

    private fun renderVideoTime(positionMs: Long) {
        val text = "${TimeUtils.formatForDisplay(positionMs)} / " +
            TimeUtils.formatForDisplay(durationMs)
        if (lastVideoTimeText == text) return
        lastVideoTimeText = text
        binding.tvVideoTime.text = text
    }

    private fun renderControlAvailability() {
        val enabled = engine?.phase?.canAccessPlayer == true
        when (mediaType) {
            EditorMediaType.AUDIO -> {
                binding.btnPlayPause.isEnabled = enabled
                binding.seekBar.isEnabled = enabled
                binding.tvPlaybackSpeed.isEnabled = enabled
            }
            EditorMediaType.VIDEO -> {
                binding.btnVideoPlayPause.isEnabled = enabled
                binding.btnVideoPlayPause.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
                binding.videoSeekBar.isEnabled = enabled
                binding.tvVideoPlaybackSpeed.isEnabled = enabled
            }
            EditorMediaType.SUBTITLE_ONLY -> Unit
        }
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
        renderPlayPauseIcon()
        renderTotalTime()
        renderProgress(currentPositionMs)
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

    private fun showSpeedInputDialog() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatPlaybackSpeedValue(playbackSpeed))
            hint = "例如：0.5、1.0、1.5、2.0"
            selectAll()
            setPadding(48, 32, 48, 16)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("设置播放速率")
            .setMessage("请输入倍数（0.25 ~ 4.0）")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val speed = input.text?.toString()?.trim()?.toFloatOrNull()
                when {
                    speed == null -> showMessage("请输入有效数字")
                    speed < 0.25f || speed > 4.0f -> showMessage("速率范围：0.25 ~ 4.0")
                    else -> applyPlaybackSpeed(speed)
                }
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnDismissListener {
            if (mediaType == EditorMediaType.VIDEO && isPlaying) {
                scheduleVideoControlsAutoHide()
            }
        }
        dialog.show()

        input.postDelayed({
            val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
            inputMethodManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 100L)
    }

    fun applyPlaybackSpeed(speed: Float, showConfirmation: Boolean = true) {
        playbackSpeed = speed
        val label = if (speed == speed.toLong().toFloat()) {
            "${speed.toLong()}×"
        } else {
            formatPlaybackSpeedValue(speed) + "×"
        }
        when (mediaType) {
            EditorMediaType.AUDIO -> binding.tvPlaybackSpeed.text = label
            EditorMediaType.VIDEO -> binding.tvVideoPlaybackSpeed.text = label
            EditorMediaType.SUBTITLE_ONLY -> Unit
        }
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

    private fun activeSeekBar(): SeekBar = when (mediaType) {
        EditorMediaType.VIDEO -> binding.videoSeekBar
        EditorMediaType.AUDIO, EditorMediaType.SUBTITLE_ONLY -> binding.seekBar
    }

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
        binding.videoControlsOverlay.animate().cancel()

        if (visible) {
            binding.videoControlsOverlay.visibility = View.VISIBLE
            if (animate && binding.videoControlsOverlay.alpha < 1f) {
                binding.videoControlsOverlay.animate()
                    .alpha(1f)
                    .setDuration(VIDEO_CONTROLS_ANIMATION_MS)
                    .start()
            } else {
                binding.videoControlsOverlay.alpha = 1f
            }
        } else if (animate) {
            binding.videoControlsOverlay.animate()
                .alpha(0f)
                .setDuration(VIDEO_CONTROLS_ANIMATION_MS)
                .withEndAction {
                    if (!videoControlsVisible) binding.videoControlsOverlay.visibility = View.GONE
                }
                .start()
        } else {
            binding.videoControlsOverlay.alpha = 0f
            binding.videoControlsOverlay.visibility = View.GONE
        }
    }

    private companion object {
        const val VIDEO_CONTROLS_HIDE_DELAY_MS = 3_000L
        const val VIDEO_CONTROLS_ANIMATION_MS = 180L
    }
}
