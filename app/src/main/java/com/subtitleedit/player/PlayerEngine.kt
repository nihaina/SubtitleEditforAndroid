package com.subtitleedit.player

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.subtitleedit.mpv.MPVLib
import com.subtitleedit.mpv.MpvPlayerHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Owns one player session. Commands and native callbacks are serialized on the access thread. */
internal class PlayerEngine(private val playerHost: MpvPlayerHost) :
    MPVLib.EventObserver, MPVLib.LogObserver {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val accessThread = HandlerThread("player-mpv").apply { start() }
    private val accessHandler = Handler(accessThread.looper)
    private val accessLock = Any()
    private val released = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val _state = MutableStateFlow(PlayerEngineState())
    val state: StateFlow<PlayerEngineState> = _state.asStateFlow()

    // Session fields are serialized by accessLock; release alone may run on the caller.
    private var initialized = false
    private var observing = false
    private var mediaGeneration = 0L
    private var mediaPath = ""
    private var ready = false
    private var playRequested = false
    private var completed = false
    private var pendingSeekMs: Long? = null
    private var pendingSubtitle: PlayerSubtitleOption? = null
    private var durationMs = 0L
    private var speed = 1f
    private var volume = 100
    private var audioFilter = ""
    private var subtitleFontSize = 18
    private var loudnessEnabled = false
    private var loudnessSampleId = 0L
    private val loudnessPoll = object : Runnable {
        override fun run() {
            synchronized(accessLock) {
                if (released.get() || mediaGeneration != generation.get() || !shouldPollLoudness()) return
                val audible = MPVLib.getPropertyBoolean("pause") == false &&
                    MPVLib.getPropertyBoolean("paused-for-cache") != true &&
                    MPVLib.getPropertyBoolean("seeking") != true
                val level = if (audible) {
                    PlayerLoudness.level(MPVLib.getPropertyString(PlayerLoudness.RMS_PROPERTY), volume)
                } else 0f
                publishLoudness(level)
                accessHandler.postDelayed(this, PlayerLoudness.SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun start(configDir: File, cacheDir: File, file: File, autoplay: Boolean = true, loadId: Long = 0L) {
        if (released.get()) return
        val request = generation.incrementAndGet()
        publish(request) { PlayerEngineState(loadId = loadId) }
        post(request) {
            mediaGeneration = request
            mediaPath = file.absolutePath
            ready = false
            accessHandler.removeCallbacks(loudnessPoll)
            completed = false
            durationMs = 0L
            pendingSeekMs = null
            pendingSubtitle = null
            playRequested = autoplay
            check(file.isFile && file.canRead()) { "无法读取文件：${file.name}" }
            check(configDir.isDirectory || configDir.mkdirs()) { "无法创建播放器配置目录" }
            if (!initialized) {
                MPVLib.addObserver(this)
                MPVLib.addLogObserver(this)
                observing = true
                playerHost.initialize(configDir.absolutePath, cacheDir.absolutePath)
                initialized = true
            }
            // Prepare paused, then apply resume/seek and the latest user intent at FILE_LOADED.
            MPVLib.setPropertyBoolean("pause", true)
            playerHost.playFile(mediaPath)
        }
    }

    fun play() = post {
        playRequested = true
        if (ready) {
            val atEnd = completed || MPVLib.getPropertyBoolean("eof-reached") == true ||
                (durationMs > 0L && secondsToMs(MPVLib.getPropertyDouble("time-pos")) >= durationMs)
            if (atEnd) seek(0L)
            completed = false
            MPVLib.setPropertyBoolean("pause", false)
            publish { copy(playing = true, ended = false) }
            resetLoudnessPolling()
        }
    }

    fun pause() = post {
        playRequested = false
        if (initialized) MPVLib.setPropertyBoolean("pause", true)
        accessHandler.removeCallbacks(loudnessPoll)
        publish { copy(playing = false, loudness = 0f) }
    }

    fun stop() = post {
        playRequested = false
        ready = false
        completed = false
        pendingSeekMs = null
        pendingSubtitle = null
        accessHandler.removeCallbacks(loudnessPoll)
        if (initialized) MPVLib.command(arrayOf("stop"))
        publish { copy(ready = false, playing = false, ended = false, loudness = 0f) }
    }

    fun seekTo(position: Long) = post {
        val target = clampPosition(position)
        completed = !playRequested && durationMs > 0L && target >= durationMs
        if (ready) seek(target) else pendingSeekMs = target
        publish { copy(positionMs = target, ended = false, loudness = 0f) }
    }

    fun setSpeed(value: Float) = post {
        speed = value.takeIf { it.isFinite() }?.coerceIn(PlayerPreferences.MIN_SPEED, PlayerPreferences.MAX_SPEED) ?: 1f
        if (initialized) MPVLib.setPropertyDouble("speed", speed.toDouble())
    }

    fun setAudioFilter(af: String) = post {
        audioFilter = af
        if (initialized) applyAudioFilter()
    }

    private fun applyAudioFilter() {
        val filters = PlayerLoudness.audioFilters(audioFilter, loudnessEnabled)
        MPVLib.setPropertyString("af", filters)
        check(filters.isEmpty() || !MPVLib.getPropertyString("af").isNullOrBlank()) {
            "无法应用均衡器"
        }
    }

    fun setLoudnessEnabled(enabled: Boolean) = post {
        if (loudnessEnabled == enabled) return@post
        loudnessEnabled = enabled
        if (initialized) applyAudioFilter()
        publishLoudness(0f)
        resetLoudnessPolling()
    }

    private fun shouldPollLoudness(): Boolean =
        loudnessEnabled && initialized && ready && playRequested && !completed

    private fun resetLoudnessPolling() {
        accessHandler.removeCallbacks(loudnessPoll)
        if (shouldPollLoudness()) accessHandler.postDelayed(loudnessPoll, PlayerLoudness.SAMPLE_INTERVAL_MS)
    }

    private fun publishLoudness(level: Float) {
        val sampleId = ++loudnessSampleId
        publish { copy(loudness = level, loudnessSampleId = sampleId) }
    }

    fun setVolume(percent: Int) = post {
        volume = percent.coerceIn(0, 200)
        if (initialized) MPVLib.setPropertyDouble("volume", volume.toDouble())
    }

    fun setSubtitleFontSize(size: Int) = post {
        subtitleFontSize = size.coerceIn(10, 32)
        if (initialized) MPVLib.setPropertyDouble("sub-font-size", subtitleFontSize.toDouble())
    }

    fun selectSubtitle(option: PlayerSubtitleOption) = post {
        if (ready) applySubtitle(option) else pendingSubtitle = option
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        generation.incrementAndGet()
        accessHandler.removeCallbacksAndMessages(null)
        // Complete teardown before a replacement Activity/view can create the global mpv
        // context. Queuing cleanup would race the next player's initialize() call.
        synchronized(accessLock) {
            try {
                removeObservers()
                if (initialized) playerHost.destroyPlayer()
            } catch (error: Throwable) {
                Log.e(TAG, "Releasing libmpv failed", error)
            } finally {
                initialized = false
                accessThread.quitSafely()
            }
        }
    }

    private fun post(request: Long = generation.get(), action: () -> Unit) {
        if (released.get()) return
        accessHandler.post {
            synchronized(accessLock) task@{
                if (released.get() || request != generation.get()) return@task
                try {
                    action()
                } catch (error: Throwable) {
                    Log.e(TAG, "Player command failed", error)
                    ready = false
                    playRequested = false
                    if (initialized) runCatching { MPVLib.setPropertyBoolean("pause", true) }
                    if (!initialized) removeObservers()
                    publish(request) {
                        copy(ready = false, playing = false, ended = false, loudness = 0f,
                            error = "播放失败：${error.message ?: error.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun removeObservers() {
        if (!observing) return
        MPVLib.removeLogObserver(this)
        MPVLib.removeObserver(this)
        observing = false
    }

    private fun publish(request: Long = mediaGeneration, transform: PlayerEngineState.() -> PlayerEngineState) {
        mainHandler.post {
            if (!released.get() && request == generation.get()) _state.value = _state.value.transform()
        }
    }

    private fun clampPosition(position: Long): Long =
        if (durationMs > 0L) position.coerceIn(0L, durationMs) else position.coerceAtLeast(0L)

    private fun seek(position: Long) {
        MPVLib.command(arrayOf("seek", (clampPosition(position) / 1000.0).toString(), "absolute+exact"))
    }

    override fun eventProperty(property: String, value: Double) = post {
        if (!ready || !value.isFinite()) return@post
        val milliseconds = (value * 1000.0).toLong().coerceAtLeast(0L)
        when (property) {
            "time-pos" -> publish { copy(positionMs = milliseconds) }
            "duration/full" -> {
                durationMs = milliseconds
                publish { copy(durationMs = milliseconds) }
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) = post {
        if (!ready) return@post
        when (property) {
            "pause", "paused-for-cache", "seeking" -> refreshPlaybackState()
            // keep-open retains the file at EOF. END_FILE also occurs when replacing files.
            "eof-reached" -> if (value && MPVLib.getPropertyBoolean("eof-reached") == true && !completed) {
                completed = true
                val naturalEnd = playRequested
                playRequested = false
                val position = durationMs
                accessHandler.removeCallbacks(loudnessPoll)
                publish { copy(positionMs = position, playing = false, ended = naturalEnd, loudness = 0f) }
            }
        }
    }

    override fun eventProperty(property: String, value: String) = post {
        if (!ready) return@post
        when (property) {
            "sub-text" -> publish { copy(subtitleText = value) }
            "sid" -> refreshSubtitleTracks()
        }
    }

    override fun eventProperty(property: String) = post {
        if (ready && property == "track-list") refreshSubtitleTracks()
    }

    override fun event(eventId: Int) = post {
        when (eventId) {
            MPVLib.MpvEvent.FILE_LOADED -> {
                if (!initialized || MPVLib.getPropertyString("path") != mediaPath) return@post
                ready = true
                completed = false
                durationMs = secondsToMs(MPVLib.getPropertyDouble("duration/full"))
                MPVLib.setPropertyDouble("speed", speed.toDouble())
                MPVLib.setPropertyDouble("volume", volume.toDouble())
                applyAudioFilter()
                MPVLib.setPropertyDouble("sub-font-size", subtitleFontSize.toDouble())
                val resumePosition = clampPosition(pendingSeekMs ?: 0L)
                pendingSeekMs = null
                if (resumePosition > 0L) seek(resumePosition)
                MPVLib.setPropertyString("sid", "no")
                pendingSubtitle?.let(::applySubtitle)
                pendingSubtitle = null
                MPVLib.setPropertyBoolean("pause", !playRequested)
                val playing = playRequested
                val duration = durationMs
                publish {
                    copy(ready = true, playing = playing, durationMs = duration,
                        positionMs = resumePosition, ended = false, error = null)
                }
                refreshSubtitleTracks()
                resetLoudnessPolling()
            }
            MPVLib.MpvEvent.PLAYBACK_RESTART -> if (ready) refreshPlaybackState()
            MPVLib.MpvEvent.SHUTDOWN -> {
                ready = false
                accessHandler.removeCallbacks(loudnessPoll)
                publish { copy(ready = false, playing = false, ended = false, loudness = 0f, error = "播放器已停止") }
            }
        }
    }

    private fun refreshPlaybackState() {
        val playing = !completed && MPVLib.getPropertyBoolean("pause") == false &&
            MPVLib.getPropertyBoolean("paused-for-cache") != true &&
            MPVLib.getPropertyBoolean("seeking") != true
        publish { copy(playing = playing, loudness = if (playing) loudness else 0f) }
        resetLoudnessPolling()
    }

    override fun endFileError(error: Int, message: String) = post {
        ready = false
        playRequested = false
        accessHandler.removeCallbacks(loudnessPoll)
        publish { copy(ready = false, playing = false, ended = false, loudness = 0f, error = "加载失败：$message（$error）") }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (!released.get() && level <= 20) Log.e(TAG, "[$prefix] ${text.trim()}")
    }

    private fun applySubtitle(option: PlayerSubtitleOption) {
        when (option.kind) {
            PlayerSubtitleKind.NONE -> MPVLib.setPropertyString("sid", "no")
            PlayerSubtitleKind.INTERNAL -> MPVLib.setPropertyString("sid", option.mpvTrackId.toString())
            PlayerSubtitleKind.EXTERNAL -> {
                check(File(option.path).isFile) { "字幕文件不存在：${option.label}" }
                val count = MPVLib.getPropertyInt("track-list/count") ?: 0
                val existing = (0 until count).firstOrNull { index ->
                    MPVLib.getPropertyString("track-list/$index/type") == "sub" &&
                        MPVLib.getPropertyString("track-list/$index/external-filename") == option.path
                }
                if (existing == null) {
                    MPVLib.command(arrayOf("sub-add", option.path, "select", File(option.path).name))
                } else {
                    MPVLib.getPropertyInt("track-list/$existing/id")?.let { id ->
                        MPVLib.setPropertyString("sid", id.toString())
                    }
                }
            }
        }
        val selectedId = if (option.kind == PlayerSubtitleKind.NONE) "none" else option.id
        publish { copy(selectedSubtitleId = selectedId, subtitleText = "") }
    }

    private fun refreshSubtitleTracks() {
        val count = MPVLib.getPropertyInt("track-list/count") ?: 0
        val tracks = mutableListOf<PlayerSubtitleOption>()
        var selectedId = "none"
        for (index in 0 until count) {
            if (MPVLib.getPropertyString("track-list/$index/type") != "sub") continue
            val id = MPVLib.getPropertyInt("track-list/$index/id") ?: continue
            val external = MPVLib.getPropertyString("track-list/$index/external-filename")?.takeIf { it.isNotBlank() }
            val optionId = if (external == null) "internal:$id" else "external:$external"
            if (MPVLib.getPropertyBoolean("track-list/$index/selected") == true) selectedId = optionId
            // The view model supplies sidecar files. Do not list an added file twice.
            if (external != null) continue
            val title = MPVLib.getPropertyString("track-list/$index/title")?.takeIf { it.isNotBlank() }
            val language = MPVLib.getPropertyString("track-list/$index/lang")?.takeIf { it.isNotBlank() }
            tracks += PlayerSubtitleOption(optionId, title ?: language ?: "轨道 ${tracks.size + 1}",
                PlayerSubtitleKind.INTERNAL, mpvTrackId = id)
        }
        val options = tracks.toList()
        val selected = selectedId
        publish { copy(subtitleTracks = options, selectedSubtitleId = selected) }
    }

    private fun secondsToMs(value: Double?): Long =
        value?.takeIf { it.isFinite() }?.let { (it * 1000.0).toLong().coerceAtLeast(0L) } ?: 0L

    private companion object { const val TAG = "PlayerEngine" }
}
