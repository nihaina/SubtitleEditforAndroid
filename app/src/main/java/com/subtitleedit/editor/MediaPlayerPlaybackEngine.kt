package com.subtitleedit.editor

import android.media.MediaPlayer
import android.media.PlaybackParams
import java.io.File

internal class MediaPlayerPlaybackEngine : EditorPlaybackEngine {
    override var listener: EditorPlaybackEngine.Listener? = null

    private var player: MediaPlayer? = null
    private var playbackSpeed = 1.0f
    private var playbackPhase = PlaybackPhase.IDLE
    private var seekInProgress = false
    private var pendingSeekMs: Long? = null
    private var cachedSeekPositionMs = 0L

    override val phase: PlaybackPhase
        get() = playbackPhase

    override val currentPositionMs: Long
        get() {
            val mediaPlayer = player?.takeIf { phase.canAccessPlayer } ?: return 0L
            return runCatching {
                mediaPlayer.currentPosition.toLong()
            }.getOrDefault(cachedSeekPositionMs)
        }

    override val durationMs: Long
        get() = player?.takeIf { phase.canAccessPlayer }
            ?.let { runCatching { it.duration.toLong() }.getOrDefault(0L) }
            ?: 0L

    override val isPlaying: Boolean
        get() = player?.takeIf { phase.canAccessPlayer }
            ?.let { runCatching { it.isPlaying }.getOrDefault(false) }
            ?: false

    override fun prepare(file: File) {
        releasePlayer()
        playbackPhase = PlaybackPhase.LOADING
        resetSeekState()
        try {
            player = MediaPlayer().apply {
                setOnCompletionListener {
                    listener?.onCompleted()
                }
                setOnErrorListener { _, what, extra ->
                    playbackPhase = PlaybackPhase.ERROR
                    resetSeekState()
                    listener?.onError("播放错误：$what, $extra")
                    true
                }
                setOnSeekCompleteListener { completedPlayer ->
                    handleSeekComplete(completedPlayer)
                }
                setDataSource(file.absolutePath)
                prepare()
                if (playbackSpeed != 1.0f) {
                    applyPlaybackSpeedPreservingState(this, playbackSpeed)
                }
            }
            playbackPhase = PlaybackPhase.READY
            listener?.onReady(durationMs, null)
            listener?.onPlaybackStateChanged()
        } catch (error: Exception) {
            playbackPhase = PlaybackPhase.ERROR
            releasePlayer()
            listener?.onError("加载音频失败：${error.message ?: "未知错误"}")
        }
    }

    override fun play() {
        player?.takeIf { phase.canAccessPlayer }?.start()
        listener?.onPlaybackStateChanged()
    }

    override fun pause() {
        player?.takeIf { phase.canAccessPlayer && it.isPlaying }?.pause()
        listener?.onPlaybackStateChanged()
    }

    override fun seekTo(positionMs: Long) {
        val mediaPlayer = player?.takeIf { phase.canAccessPlayer } ?: return
        val targetMs = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
        cachedSeekPositionMs = targetMs
        if (seekInProgress) {
            pendingSeekMs = targetMs
            return
        }
        submitSeek(mediaPlayer, targetMs)
    }

    override fun setSpeed(speed: Float) {
        playbackSpeed = speed
        player?.takeIf { phase.canAccessPlayer }?.let { mediaPlayer ->
            applyPlaybackSpeedPreservingState(mediaPlayer, speed)
        }
    }

    override fun release() {
        releasePlayer()
        playbackPhase = PlaybackPhase.RELEASED
    }

    private fun releasePlayer() {
        player?.let { mediaPlayer ->
            runCatching { mediaPlayer.setOnCompletionListener(null) }
            runCatching { mediaPlayer.setOnErrorListener(null) }
            runCatching { mediaPlayer.setOnSeekCompleteListener(null) }
            if (runCatching { mediaPlayer.isPlaying }.getOrDefault(false)) {
                runCatching { mediaPlayer.stop() }
            }
            runCatching { mediaPlayer.release() }
        }
        player = null
        resetSeekState()
    }

    private fun submitSeek(mediaPlayer: MediaPlayer, positionMs: Long) {
        seekInProgress = true
        pendingSeekMs = null
        try {
            mediaPlayer.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST)
        } catch (error: Exception) {
            resetSeekState()
            playbackPhase = PlaybackPhase.ERROR
            listener?.onError("跳转失败：${error.message ?: "未知错误"}")
        }
    }

    private fun handleSeekComplete(completedPlayer: MediaPlayer) {
        if (completedPlayer !== player || !phase.canAccessPlayer) return
        val nextTargetMs = pendingSeekMs
        if (nextTargetMs != null) {
            submitSeek(completedPlayer, nextTargetMs)
            return
        }

        seekInProgress = false
        cachedSeekPositionMs = runCatching {
            completedPlayer.currentPosition.toLong()
        }.getOrDefault(cachedSeekPositionMs)
        listener?.onPlaybackStateChanged()
    }

    private fun resetSeekState() {
        seekInProgress = false
        pendingSeekMs = null
        cachedSeekPositionMs = 0L
    }

    private fun applyPlaybackSpeedPreservingState(mediaPlayer: MediaPlayer, speed: Float) {
        val wasPlaying = mediaPlayer.isPlaying
        mediaPlayer.playbackParams = PlaybackParams().setSpeed(speed)
        if (!wasPlaying && mediaPlayer.isPlaying) {
            mediaPlayer.pause()
        }
    }
}
