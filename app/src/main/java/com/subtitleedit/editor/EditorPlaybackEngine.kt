package com.subtitleedit.editor

import java.io.File

internal enum class PlaybackPhase {
    IDLE,
    LOADING,
    READY,
    ERROR,
    RELEASED;

    val canAccessPlayer: Boolean
        get() = this == READY
}

internal enum class PlaybackSeekMode {
    EXACT,
    KEYFRAME
}

internal interface EditorPlaybackEngine {
    interface Listener {
        fun onReady(durationMs: Long, audioStreamIndex: Int?)
        fun onPlaybackStateChanged()
        fun onCompleted()
        fun onError(message: String)
    }

    var listener: Listener?
    val phase: PlaybackPhase
    val currentPositionMs: Long
    val durationMs: Long
    val isPlaying: Boolean

    fun prepare(file: File)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long, mode: PlaybackSeekMode = PlaybackSeekMode.EXACT)
    fun setSpeed(speed: Float)
    fun release()
}
