package com.subtitleedit.editor

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Choreographer
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.SeekBar
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
    private var lastSeekBarProgress: Int? = null

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
                    }
                    onMediaReady(durationMs, audioStreamIndex)
                }

                override fun onPlaybackStateChanged() {
                    updatePlayerUi()
                    if (playbackEngine.isPlaying) startProgressUpdate() else stopProgressUpdate()
                }

                override fun onCompleted() {
                    isPlaying = false
                    stopProgressUpdate()
                    updatePlayerUi()
                }

                override fun onError(message: String) {
                    isPlaying = false
                    stopProgressUpdate()
                    renderControlAvailability()
                    renderPlayPauseIcon()
                    if (mediaType == EditorMediaType.VIDEO) {
                        binding.tvVideoStatus.text = message
                        binding.tvVideoStatus.visibility = View.VISIBLE
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
    }

    fun prepare(mediaFile: File) {
        currentPositionMs = 0L
        durationMs = 0L
        if (mediaType == EditorMediaType.VIDEO) {
            binding.tvVideoStatus.visibility = View.VISIBLE
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
        highlightSubtitleAtTime(currentPositionMs)
        updatePlayerUiAtKnownPosition(clampedTime)
        if (isPlaying) startProgressUpdate()
    }

    fun pauseForLifecycle() {
        engine?.takeIf { it.phase.canAccessPlayer && it.isPlaying }?.pause()
        isPlaying = false
        stopProgressUpdate()
        updatePlayerUi()
    }

    fun replaceVideoSubtitleTrack(file: File?) {
        (engine as? MpvVideoPlaybackEngine)?.replaceSubtitleTrack(file)
    }

    fun release() {
        stopProgressUpdate()
        engine?.release()
        engine = null
        isPlaying = false
    }

    fun invalidateHighlightCache() {
        highlightCursor.invalidate()
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
        binding.waveformTimelineView.onDraggedViewportPlayheadCorrection = { positionMs ->
            correctPlaybackAfterViewportDrag(positionMs)
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
        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.seekBar.max = 1000
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val targetTime = durationMs * progress / 1000L
                currentPositionMs = targetTime
                binding.tvCurrentTime.text = TimeUtils.formatForDisplay(targetTime)
                val wavePosition = if (durationMs > 0L) targetTime.toFloat() / durationMs else 0f
                binding.waveformTimelineView.setCurrentPosition(wavePosition)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                seekTo(currentPositionMs)
            }
        })
        binding.tvPlaybackSpeed.setOnClickListener { showSpeedInputDialog() }
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
        highlightSubtitleAtTime(clampedPositionMs)
        val previousUserSeeking = isUserSeeking
        isUserSeeking = true
        updatePlayerUi()
        isUserSeeking = previousUserSeeking
        binding.seekBar.progress = if (durationMs > 0L) {
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
        val currentTimeText = TimeUtils.formatForDisplay(positionMs)
        if (lastCurrentTimeText != currentTimeText) {
            lastCurrentTimeText = currentTimeText
            binding.tvCurrentTime.text = currentTimeText
        }
        if (!isUserSeeking) {
            val seekBarProgress = if (durationMs > 0L) {
                (positionMs * 1000L / durationMs).toInt().coerceIn(0, 1000)
            } else {
                0
            }
            if (lastSeekBarProgress != seekBarProgress) {
                lastSeekBarProgress = seekBarProgress
                binding.seekBar.progress = seekBarProgress
            }
        }
        val wavePosition = if (durationMs > 0L) positionMs.toFloat() / durationMs else 0f
        binding.waveformTimelineView.setCurrentPosition(wavePosition)
    }

    private fun renderPlayPauseIcon() {
        if (lastPlayPauseShowsPause == isPlaying) return
        lastPlayPauseShowsPause = isPlaying
        binding.btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun renderTotalTime() {
        val text = TimeUtils.formatForDisplay(durationMs)
        if (lastTotalTimeText == text) return
        lastTotalTimeText = text
        binding.tvTotalTime.text = text
    }

    private fun renderControlAvailability() {
        val enabled = engine?.phase?.canAccessPlayer == true
        binding.btnPlayPause.isEnabled = enabled
        binding.seekBar.isEnabled = enabled
        binding.tvPlaybackSpeed.isEnabled = enabled
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

        AlertDialog.Builder(context)
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
            .show()

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
        binding.tvPlaybackSpeed.text = label
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
}
