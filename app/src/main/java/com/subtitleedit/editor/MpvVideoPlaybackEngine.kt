package com.subtitleedit.editor

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.subtitleedit.mpv.EditorMpvView
import com.subtitleedit.mpv.MPVLib
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class MpvVideoPlaybackEngine(
    private val view: EditorMpvView,
    private val configDir: File,
    private val cacheDir: File
) : EditorPlaybackEngine, MPVLib.EventObserver, MPVLib.LogObserver {
    override var listener: EditorPlaybackEngine.Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mpvAccessThread = HandlerThread("mpv-access").apply { start() }
    private val mpvAccessHandler = Handler(mpvAccessThread.looper)
    private val positionReadPending = AtomicBoolean(false)
    private val positionReadGeneration = AtomicLong(0L)
    private val mpvAccessLock = Any()
    @Volatile private var playbackPhase = PlaybackPhase.IDLE
    @Volatile private var initialized = false
    @Volatile private var cachedPositionMs = 0L
    @Volatile private var cachedDurationMs = 0L
    @Volatile private var paused = true
    @Volatile private var eofReached = false
    @Volatile private var seekInProgress = false
    private var readyNotified = false

    override val phase: PlaybackPhase
        get() = playbackPhase

    override val currentPositionMs: Long
        get() {
            requestPositionRead()
            val position = cachedPositionMs
            return if (cachedDurationMs > 0L) {
                position.coerceIn(0L, cachedDurationMs)
            } else {
                position.coerceAtLeast(0L)
            }
        }

    override val durationMs: Long
        get() = cachedDurationMs

    override val isPlaying: Boolean
        get() = phase.canAccessPlayer && !paused && !eofReached

    override fun prepare(file: File) {
        playbackPhase = PlaybackPhase.LOADING
        try {
            if (!initialized) {
                MPVLib.addObserver(this)
                MPVLib.addLogObserver(this)
                view.initialize(configDir.absolutePath, cacheDir.absolutePath)
                initialized = true
            }
            cachedPositionMs = 0L
            cachedDurationMs = 0L
            paused = true
            eofReached = false
            seekInProgress = false
            readyNotified = false
            view.playFile(file.absolutePath)
        } catch (error: Throwable) {
            runCatching { MPVLib.removeLogObserver(this) }
            runCatching { MPVLib.removeObserver(this) }
            initialized = false
            playbackPhase = PlaybackPhase.ERROR
            listener?.onError("加载视频播放器失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun play() {
        if (!phase.canAccessPlayer) return
        eofReached = false
        paused = false
        postMpvAccess { MPVLib.setPropertyBoolean("pause", false) }
    }

    override fun pause() {
        if (!phase.canAccessPlayer) return
        paused = true
        postMpvAccess { MPVLib.setPropertyBoolean("pause", true) }
    }

    override fun seekTo(positionMs: Long) {
        if (!phase.canAccessPlayer) return
        positionReadGeneration.incrementAndGet()
        seekInProgress = true
        cachedPositionMs = positionMs
        eofReached = false
        val generation = positionReadGeneration.get()
        val posted = postMpvAccess {
            if (generation == positionReadGeneration.get()) {
                MPVLib.setPropertyDouble("time-pos", positionMs / 1000.0)
            }
        }
        if (!posted) seekInProgress = false
    }

    override fun setSpeed(speed: Float) {
        postMpvAccess { MPVLib.setPropertyDouble("speed", speed.toDouble()) }
    }

    fun replaceSubtitleTrack(file: File?) {
        if (!phase.canAccessPlayer) return
        postMpvAccess {
            runCatching { MPVLib.command(arrayOf("sub-remove")) }
            if (file != null && file.isFile && file.length() > 0L) {
                MPVLib.command(
                    arrayOf("sub-add", file.absolutePath, "select", "SubtitleEdit live preview")
                )
            }
        }
    }

    override fun release() {
        val wasInitialized = initialized
        initialized = false
        mpvAccessHandler.removeCallbacksAndMessages(null)
        positionReadPending.set(false)
        if (wasInitialized) {
            MPVLib.removeLogObserver(this)
            MPVLib.removeObserver(this)
            synchronized(mpvAccessLock) {
                view.destroyPlayer()
            }
        }
        mpvAccessThread.quitSafely()
        playbackPhase = PlaybackPhase.RELEASED
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun requestPositionRead() {
        if (!initialized || !phase.canAccessPlayer || seekInProgress) return
        if (!positionReadPending.compareAndSet(false, true)) return
        val generation = positionReadGeneration.get()
        val posted = mpvAccessHandler.post {
            try {
                synchronized(mpvAccessLock) {
                    if (!initialized || !phase.canAccessPlayer || seekInProgress ||
                        generation != positionReadGeneration.get()
                    ) {
                        return@synchronized
                    }
                    MPVLib.getPropertyDouble("time-pos")
                        ?.takeIf { it.isFinite() }
                        ?.let { seconds ->
                            if (!seekInProgress && generation == positionReadGeneration.get()) {
                                cachedPositionMs = (seconds * 1000.0).toLong().coerceAtLeast(0L)
                            }
                        }
                }
            } finally {
                positionReadPending.set(false)
            }
        }
        if (!posted) positionReadPending.set(false)
    }

    private fun postMpvAccess(action: () -> Unit): Boolean {
        if (!initialized || playbackPhase == PlaybackPhase.RELEASED) return false
        return mpvAccessHandler.post {
            synchronized(mpvAccessLock) {
                if (initialized && playbackPhase != PlaybackPhase.RELEASED) action()
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> {
                if (!seekInProgress) {
                    cachedPositionMs = (value * 1000.0).toLong().coerceAtLeast(0L)
                }
            }
            "duration/full" -> {
                cachedDurationMs = (value * 1000.0).toLong().coerceAtLeast(0L)
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> paused = value
            "eof-reached" -> eofReached = value
        }
        mainHandler.post {
            listener?.onPlaybackStateChanged()
        }
    }

    override fun eventProperty(property: String) {
        if (property == "track-list") mainHandler.post { notifyReadyState() }
    }

    override fun event(eventId: Int) {
        mainHandler.post {
            when (eventId) {
                MPVLib.MpvEvent.FILE_LOADED -> {
                    playbackPhase = PlaybackPhase.READY
                    cachedDurationMs = ((MPVLib.getPropertyDouble("duration/full") ?: 0.0) * 1000.0)
                        .toLong()
                    paused = MPVLib.getPropertyBoolean("pause") ?: true
                    notifyReadyState()
                }
                MPVLib.MpvEvent.SEEK -> seekInProgress = true
                MPVLib.MpvEvent.PLAYBACK_RESTART -> {
                    seekInProgress = false
                    requestPositionRead()
                    listener?.onPlaybackStateChanged()
                }
                MPVLib.MpvEvent.END_FILE -> {
                    paused = true
                    listener?.onCompleted()
                }
                MPVLib.MpvEvent.SHUTDOWN -> {
                    if (playbackPhase != PlaybackPhase.RELEASED) {
                        playbackPhase = PlaybackPhase.ERROR
                        listener?.onError("视频播放器已停止")
                    }
                }
            }
        }
    }

    override fun endFileError(error: Int, message: String) {
        mainHandler.post {
            playbackPhase = PlaybackPhase.ERROR
            paused = true
            eofReached = true
            listener?.onError("视频加载失败：$message（$error）")
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level <= 20) Log.e(TAG, "[$prefix] ${text.trim()}")
    }

    private fun notifyReadyState() {
        if (!phase.canAccessPlayer || readyNotified) return
        readyNotified = true
        listener?.onReady(cachedDurationMs, selectedAudioStreamIndex())
        listener?.onPlaybackStateChanged()
    }

    private fun selectedAudioStreamIndex(): Int? {
        val count = MPVLib.getPropertyInt("track-list/count") ?: return null
        for (index in 0 until count) {
            if (MPVLib.getPropertyString("track-list/$index/type") != "audio") continue
            if (MPVLib.getPropertyBoolean("track-list/$index/selected") != true) continue
            return MPVLib.getPropertyInt("track-list/$index/ff-index") ?: DEFAULT_AUDIO_STREAM
        }
        return null
    }

    private companion object {
        const val TAG = "MpvVideoEngine"
        const val DEFAULT_AUDIO_STREAM = -1
    }
}
