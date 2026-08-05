package com.subtitleedit.editor

import android.media.MediaPlayer
import android.media.PlaybackParams
import java.io.File

internal class MediaPlayerPlaybackEngine : EditorPlaybackEngine {
    override var listener: EditorPlaybackEngine.Listener? = null

    private var player: MediaPlayer? = null
    private var playbackSpeed = 1.0f
    private var playbackPhase = PlaybackPhase.IDLE

    override val phase: PlaybackPhase
        get() = playbackPhase

    override val currentPositionMs: Long
        get() = player?.takeIf { phase.canAccessPlayer }
            ?.let { runCatching { it.currentPosition.toLong() }.getOrDefault(0L) }
            ?: 0L

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
        try {
            player = MediaPlayer().apply {
                setOnCompletionListener {
                    listener?.onCompleted()
                }
                setOnErrorListener { _, what, extra ->
                    playbackPhase = PlaybackPhase.ERROR
                    listener?.onError("播放错误：$what, $extra")
                    true
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
        player?.takeIf { phase.canAccessPlayer }?.seekTo(positionMs.toInt())
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
            if (runCatching { mediaPlayer.isPlaying }.getOrDefault(false)) {
                runCatching { mediaPlayer.stop() }
            }
            mediaPlayer.release()
        }
        player = null
    }

    private fun applyPlaybackSpeedPreservingState(mediaPlayer: MediaPlayer, speed: Float) {
        val wasPlaying = mediaPlayer.isPlaying
        mediaPlayer.playbackParams = PlaybackParams().setSpeed(speed)
        if (!wasPlaying && mediaPlayer.isPlaying) {
            mediaPlayer.pause()
        }
    }
}
