package com.subtitleedit.editor

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.subtitleedit.mpv.MPVLib
import com.subtitleedit.mpv.MpvPlayerHost
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class MpvPlaybackEngine(
    private val playerHost: MpvPlayerHost,
    private val mediaLabel: String,
    private val interpolateAudioPosition: Boolean,
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
    private val seekStateLock = Any()
    private val audioClock = if (interpolateAudioPosition) AudioPlaybackClock() else null
    @Volatile private var playbackPhase = PlaybackPhase.IDLE
    @Volatile private var initialized = false
    @Volatile private var cachedPositionMs = 0L
    @Volatile private var cachedDurationMs = 0L
    @Volatile private var paused = true
    @Volatile private var eofReached = false
    @Volatile private var seekInProgress = false
    private var pendingSeek: SeekRequest? = null
    private var seekCommandInFlight = false
    private var seekDispatchPosted = false
    private var seekEventObserved = false
    private var readyNotified = false

    override val phase: PlaybackPhase
        get() = playbackPhase

    override val currentPositionMs: Long
        get() {
            requestPositionRead()
            val position = if (seekInProgress) cachedPositionMs else {
                audioClock?.position(SystemClock.elapsedRealtimeNanos()) ?: cachedPositionMs
            }
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
                playerHost.initialize(configDir.absolutePath, cacheDir.absolutePath)
                initialized = true
            }
            cachedPositionMs = 0L
            audioClock?.reset(0L, SystemClock.elapsedRealtimeNanos())
            cachedDurationMs = 0L
            paused = true
            eofReached = false
            resetSeekState()
            readyNotified = false
            playerHost.playFile(file.absolutePath)
        } catch (error: Throwable) {
            runCatching { MPVLib.removeLogObserver(this) }
            runCatching { MPVLib.removeObserver(this) }
            if (initialized) runCatching { playerHost.destroyPlayer() }
            initialized = false
            playbackPhase = PlaybackPhase.ERROR
            listener?.onError("加载${mediaLabel}播放器失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun play() {
        if (!phase.canAccessPlayer) return
        eofReached = false
        paused = false
        audioClock?.setPlaying(true, SystemClock.elapsedRealtimeNanos())
        postMpvAccess { MPVLib.setPropertyBoolean("pause", false) }
    }

    override fun pause() {
        if (!phase.canAccessPlayer) return
        paused = true
        audioClock?.setPlaying(false, SystemClock.elapsedRealtimeNanos())
        postMpvAccess { MPVLib.setPropertyBoolean("pause", true) }
    }

    override fun seekTo(positionMs: Long) {
        if (!phase.canAccessPlayer) return
        val targetPositionMs = if (cachedDurationMs > 0L) {
            positionMs.coerceIn(0L, cachedDurationMs)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        val shouldPostDispatch = synchronized(seekStateLock) {
            positionReadGeneration.incrementAndGet()
            pendingSeek = SeekRequest(targetPositionMs)
            seekInProgress = true
            cachedPositionMs = targetPositionMs
            audioClock?.reset(targetPositionMs, SystemClock.elapsedRealtimeNanos())
            eofReached = false
            if (!seekCommandInFlight && !seekDispatchPosted) {
                seekDispatchPosted = true
                true
            } else {
                false
            }
        }
        if (shouldPostDispatch) postSeekDispatch()
    }

    override fun setSpeed(speed: Float) {
        audioClock?.setSpeed(speed, SystemClock.elapsedRealtimeNanos())
        postMpvAccess { MPVLib.setPropertyDouble("speed", speed.toDouble()) }
    }

    fun replaceSubtitleTrack(file: File?) {
        if (!phase.canAccessPlayer) return
        postMpvAccess {
            runCatching { MPVLib.command(arrayOf("sub-remove")) }
            if (file != null && file.isFile && file.length() > 0L) {
                MPVLib.command(
                    arrayOf("sub-add", file.absolutePath, "select", "TsumugiSub live preview")
                )
            }
        }
    }

    override fun release() {
        val wasInitialized = initialized
        initialized = false
        mpvAccessHandler.removeCallbacksAndMessages(null)
        positionReadPending.set(false)
        resetSeekState()
        if (wasInitialized) {
            MPVLib.removeLogObserver(this)
            MPVLib.removeObserver(this)
            synchronized(mpvAccessLock) {
                playerHost.destroyPlayer()
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
                            synchronized(seekStateLock) {
                                if (!seekInProgress && generation == positionReadGeneration.get()) {
                                    val positionMs = (seconds * 1000.0).toLong().coerceAtLeast(0L)
                                    cachedPositionMs = positionMs
                                    audioClock?.acceptSample(positionMs, SystemClock.elapsedRealtimeNanos())
                                }
                            }
                        }
                }
            } finally {
                positionReadPending.set(false)
            }
        }
        if (!posted) positionReadPending.set(false)
    }

    private fun postSeekDispatch() {
        val posted = mpvAccessHandler.post { dispatchPendingSeek() }
        if (!posted) {
            synchronized(seekStateLock) {
                seekDispatchPosted = false
                pendingSeek = null
                if (!seekCommandInFlight) seekInProgress = false
            }
        }
    }

    private fun dispatchPendingSeek() {
        val request = synchronized(seekStateLock) {
            seekDispatchPosted = false
            if (!initialized || playbackPhase == PlaybackPhase.RELEASED || seekCommandInFlight) {
                return
            }
            val nextRequest = pendingSeek ?: run {
                seekInProgress = false
                return
            }
            pendingSeek = null
            seekCommandInFlight = true
            seekEventObserved = false
            nextRequest
        }

        val submitted = runCatching {
            synchronized(mpvAccessLock) {
                if (!initialized || playbackPhase == PlaybackPhase.RELEASED) return@synchronized false
                MPVLib.command(
                    arrayOf(
                        "seek",
                        (request.positionMs / 1000.0).toString(),
                        "absolute+exact"
                    )
                )
                true
            }
        }.getOrElse { error ->
            Log.e(TAG, "Submitting mpv seek failed", error)
            false
        }

        if (!submitted) finishSeek(requireObservedSeekEvent = false)
    }

    private fun finishSeek(requireObservedSeekEvent: Boolean): Boolean {
        var shouldPostDispatch = false
        val settled = synchronized(seekStateLock) {
            when {
                seekCommandInFlight -> {
                    if (requireObservedSeekEvent && !seekEventObserved) return@synchronized false
                    seekCommandInFlight = false
                    seekEventObserved = false
                    if (pendingSeek != null && initialized &&
                        playbackPhase != PlaybackPhase.RELEASED
                    ) {
                        if (!seekDispatchPosted) {
                            seekDispatchPosted = true
                            shouldPostDispatch = true
                        }
                        false
                    } else {
                        pendingSeek = null
                        seekInProgress = false
                        true
                    }
                }
                pendingSeek != null || seekDispatchPosted -> false
                else -> {
                    seekInProgress = false
                    true
                }
            }
        }
        if (shouldPostDispatch) postSeekDispatch()
        return settled
    }

    private fun resetSeekState() {
        synchronized(seekStateLock) {
            pendingSeek = null
            seekCommandInFlight = false
            seekDispatchPosted = false
            seekEventObserved = false
            seekInProgress = false
        }
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
                synchronized(seekStateLock) {
                    if (!seekInProgress) {
                        val positionMs = (value * 1000.0).toLong().coerceAtLeast(0L)
                        cachedPositionMs = positionMs
                        audioClock?.acceptSample(positionMs, SystemClock.elapsedRealtimeNanos())
                    }
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
        audioClock?.setPlaying(!paused && !eofReached, SystemClock.elapsedRealtimeNanos())
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
                    audioClock?.reset(0L, SystemClock.elapsedRealtimeNanos())
                    audioClock?.setPlaying(!paused, SystemClock.elapsedRealtimeNanos())
                    notifyReadyState()
                }
                MPVLib.MpvEvent.SEEK -> {
                    synchronized(seekStateLock) {
                        if (seekCommandInFlight) seekEventObserved = true
                        seekInProgress = true
                    }
                }
                MPVLib.MpvEvent.PLAYBACK_RESTART -> {
                    val settled = finishSeek(requireObservedSeekEvent = true)
                    if (settled) {
                        requestPositionRead()
                        listener?.onPlaybackStateChanged()
                    }
                }
                MPVLib.MpvEvent.END_FILE -> {
                    paused = true
                    audioClock?.setPlaying(false, SystemClock.elapsedRealtimeNanos())
                    listener?.onCompleted()
                }
                MPVLib.MpvEvent.SHUTDOWN -> {
                    if (playbackPhase != PlaybackPhase.RELEASED) {
                        playbackPhase = PlaybackPhase.ERROR
                        listener?.onError("${mediaLabel}播放器已停止")
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
            listener?.onError("${mediaLabel}加载失败：$message（$error）")
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
        const val TAG = "MpvPlaybackEngine"
        const val DEFAULT_AUDIO_STREAM = -1
    }

    private data class SeekRequest(
        val positionMs: Long
    )
}
