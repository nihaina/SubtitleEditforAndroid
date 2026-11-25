package com.subtitleedit.player

import android.content.res.Resources
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.subtitleedit.R
import com.subtitleedit.mpv.MPVLib
import com.subtitleedit.mpv.MpvVideoDecoding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlin.math.roundToLong

/** Owns one player session. Commands and native callbacks are serialized on the access thread. */
internal class PlayerEngine(
    private val playerHost: PlayerMpvView,
    private val resources: Resources,
    private val onUsbInterrupted: () -> Unit,
) :
    MPVLib.EventObserver, MPVLib.LogObserver {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val accessThread = HandlerThread("player-mpv").apply { start() }
    private val accessHandler = Handler(accessThread.looper)
    private val accessLock = Any()
    private val usbAudio = PlayerUsbAudio(playerHost.context.applicationContext, accessHandler, accessLock, ::onUsbRouteChanged)
    private var usbWakeLock: PowerManager.WakeLock? = null
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
        set(value) {
            if (field == value) return
            field = value
            updateUsbPlaybackPower()
            resetUsbProgress()
        }
    private var completed = false
    private var pendingSeekMs: Long? = null
    private var pendingSubtitle: PlayerSubtitleOption? = null
    private var subtitleRefreshQueued = false
    private var activeSubtitleTiming: PlayerSubtitleCueTiming? = null
    private var fallbackSubtitle: PlayerSubtitleOption? = null
    private var embeddedSubtitles: List<PlayerSubtitleOption> = emptyList()
    private var embeddedSubtitleDir: File? = null
    private var cacheDirectory: File? = null
    private var durationMs = 0L
    private var speed = 1f
    private var volume = 100
    private var usbVolume = 100
    private var usbVolumeMode = PlayerUsbVolumeMode.SOFTWARE
    private var usbExclusiveRequested = false
    private var usbFailedPath: String? = null
    @Volatile private var usbOutputDescriptor: Int? = null
    val usesDirectUsb: Boolean get() = usbOutputDescriptor != null
    private var usbStalled = false
    private var usbLastPositionMs = -1L
    private var usbProgressAt = 0L
    private val usbProgressCheck = object : Runnable {
        override fun run() = synchronized(accessLock) {
            if (released.get() || !shouldWatchUsbProgress()) return@synchronized
            if (SystemClock.elapsedRealtime() - usbProgressAt >= USB_PROGRESS_TIMEOUT_MS && canAdvancePlayback()) {
                setUsbStalled(true)
            }
            accessHandler.postDelayed(this, USB_PROGRESS_INTERVAL_MS)
        }
    }
    private var muted = false
    private var mirrored = false
    private var mirrorFilter = ""
    private var audioOnly = false
    private var audioFilter = ""
    private var subtitleFontSize = PlayerPreferences.DEFAULT_SUBTITLE_FONT_SIZE
    private var loudnessEnabled = false
    private var loudnessSampleId = 0L
    private val loudnessPoll = object : Runnable {
        override fun run() {
            synchronized(accessLock) {
                if (released.get() || mediaGeneration != generation.get() || !shouldPollLoudness()) return
                val audible = !muted && MPVLib.getPropertyBoolean("pause") == false &&
                    MPVLib.getPropertyBoolean("paused-for-cache") != true &&
                    MPVLib.getPropertyBoolean("seeking") != true
                val level = if (audible) {
                    PlayerLoudness.level(MPVLib.getPropertyString(PlayerLoudness.RMS_PROPERTY), outputVolume())
                } else 0f
                publishLoudness(level)
                accessHandler.postDelayed(this, PlayerLoudness.SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun start(
        configDir: File,
        cacheDir: File,
        file: File,
        autoplay: Boolean = true,
        loadId: Long = 0L,
        fallbackSubtitle: PlayerSubtitleOption? = null,
        muted: Boolean = false,
        mirrored: Boolean = false,
        audioOnly: Boolean = false,
        usbExclusive: Boolean = false,
        usbVolumeMode: PlayerUsbVolumeMode = PlayerUsbVolumeMode.SOFTWARE,
        volume: PlayerVolumeSnapshot,
    ) {
        if (released.get()) return
        val request = generation.incrementAndGet()
        publish(request) { PlayerEngineState(loadId = loadId) }
        post(request) {
            mediaGeneration = request
            resetUsbProgress()
            mediaPath = file.absolutePath
            if (usbFailedPath != mediaPath) usbFailedPath = null
            ready = false
            accessHandler.removeCallbacks(loudnessPoll)
            completed = false
            durationMs = 0L
            pendingSeekMs = null
            pendingSubtitle = null
            subtitleRefreshQueued = false
            activeSubtitleTiming = null
            this.fallbackSubtitle = fallbackSubtitle
            clearEmbeddedSubtitles()
            cacheDirectory = cacheDir
            playRequested = autoplay
            this.muted = muted
            this.mirrored = mirrored
            this.audioOnly = audioOnly
            this.volume = volume.softwarePercent
            usbVolume = volume.percent
            this.usbVolumeMode = usbVolumeMode
            check(file.isFile && file.canRead()) { "无法读取文件：${file.name}" }
            check(configDir.isDirectory || configDir.mkdirs()) { "无法创建播放器配置目录" }
            if (audioOnly) playerHost.setVideoOutputEnabled(false)
            if (!initialized) {
                MPVLib.addObserver(this)
                MPVLib.addLogObserver(this)
                observing = true
                playerHost.initialize(configDir.absolutePath, cacheDir.absolutePath)
                MPVLib.observeProperty("current-ao", MPVLib.MpvFormat.STRING)
                initialized = true
            }
            // Prepare paused, then apply resume/seek and the latest user intent at FILE_LOADED.
            MPVLib.setPropertyBoolean("pause", true)
            MPVLib.setPropertyBoolean("mute", muted)
            configureUsbOutput(usbExclusive)
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
            val playing = !usbAudio.awaitingPermission
            MPVLib.setPropertyBoolean("pause", !playing)
            publish { copy(playing = playing, ended = false) }
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
        activeSubtitleTiming = null
        fallbackSubtitle = null
        mediaPath = ""
        clearEmbeddedSubtitles()
        accessHandler.removeCallbacks(loudnessPoll)
        if (initialized) {
            MPVLib.command(arrayOf("stop"))
            configureUsbOutput(false)
        }
        publish { copy(ready = false, playing = false, ended = false, loudness = 0f, subtitleCueTiming = null) }
    }

    fun seekTo(position: Long) = post {
        val target = clampPosition(position)
        completed = !playRequested && durationMs > 0L && target >= durationMs
        if (ready) seek(target) else pendingSeekMs = target
        val timing = activeSubtitleTiming?.takeIf { target in it }
        activeSubtitleTiming = timing
        publish {
            copy(positionMs = target, ended = false, loudness = 0f,
                subtitleCueTiming = timing)
        }
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

    fun setVolume(snapshot: PlayerVolumeSnapshot) = post {
        volume = snapshot.softwarePercent
        usbVolume = snapshot.percent
        if (initialized) applyVolume()
    }

    private fun applyVolume() {
        MPVLib.setPropertyDouble("volume", PlayerVolumePolicy.mpvVolume(outputVolume()))
    }

    private fun outputVolume() = when {
        !usesDirectUsb -> volume
        usbVolumeMode == PlayerUsbVolumeMode.FIXED -> 100
        else -> usbVolume.coerceAtMost(100)
    }

    fun setUsbVolumeMode(mode: PlayerUsbVolumeMode) = post {
        usbVolumeMode = mode
        if (initialized && usesDirectUsb) applyVolume()
    }

    fun setUsbExclusive(enabled: Boolean) = post {
        if (!initialized || mediaPath.isEmpty() || usbExclusiveRequested == enabled) return@post
        usbFailedPath = null
        MPVLib.setPropertyBoolean("pause", true)
        configureUsbOutput(enabled)
        resumeAfterUsbChange()
    }

    private fun configureUsbOutput(enabled: Boolean) {
        usbExclusiveRequested = enabled
        val useUsb = enabled && usbFailedPath != mediaPath
        if (useUsb) usbAudio.start()
        setUsbOutput(usbAudio.descriptor.takeIf { useUsb })
        if (!enabled) usbAudio.close()
    }

    private fun setUsbOutput(descriptor: Int?) {
        if (usbOutputDescriptor == descriptor) return
        usbOutputDescriptor = descriptor
        updateUsbPlaybackPower()
        resetUsbProgress()
        if (descriptor == null) {
            PlayerUsbDiagnostics.setActive(false)
            MPVLib.setOptionString("android-usb-fd", "-1")
            MPVLib.setOptionString("ao", "audiotrack,opensles")
            applyVolume()
        } else {
            applyVolume()
            val fdResult = MPVLib.setOptionString("android-usb-fd", descriptor.toString())
            val aoResult = if (fdResult >= 0) MPVLib.setOptionString("ao", "android-usb") else fdResult
            if (aoResult < 0) {
                PlayerUsbDiagnostics.record("mpv rejected USB output configuration: $aoResult", failed = true)
                setUsbOutput(null)
                usbAudio.close()
                notifyUsbOutput(R.string.player_usb_exclusive_failed)
            }
        }
    }

    private fun onUsbRouteChanged(change: UsbRouteChange) {
        if (!initialized || released.get()) return
        val lost = change == UsbRouteChange.DETACHED
        if (lost) playRequested = false
        if (usbAudio.descriptor != null) usbFailedPath = null
        MPVLib.setPropertyBoolean("pause", true)
        setUsbOutput(usbAudio.descriptor)
        resumeAfterUsbChange()
        if (lost) notifyUsbOutput(R.string.player_usb_exclusive_disconnected, interrupted = true)
        else if (change == UsbRouteChange.PERMISSION_RESULT && usbAudio.descriptor == null) {
            notifyUsbOutput(R.string.player_usb_exclusive_failed)
        }
    }

    private fun resumeAfterUsbChange() {
        if (ready) {
            MPVLib.setPropertyBoolean("pause", !playRequested || usbAudio.awaitingPermission)
            refreshPlaybackState()
        }
    }

    private fun notifyUsbOutput(message: Int, interrupted: Boolean = false) {
        val context = playerHost.context.applicationContext
        val request = mediaGeneration
        mainHandler.post {
            if (!released.get() && generation.get() == request) {
                if (interrupted) onUsbInterrupted()
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateUsbPlaybackPower() {
        if (usesDirectUsb && playRequested && !released.get()) {
            if (usbWakeLock == null) {
                val power = playerHost.context.getSystemService(PowerManager::class.java) ?: return
                usbWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SubtitleEdit:UsbPlayback").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                PlayerUsbDiagnostics.record("USB playback wake lock acquired")
            }
        } else {
            usbWakeLock?.let {
                it.release()
                usbWakeLock = null
                PlayerUsbDiagnostics.record("USB playback wake lock released")
            }
        }
    }

    private fun shouldWatchUsbProgress(): Boolean =
        ready && playRequested && usesDirectUsb && PlayerUsbDiagnostics.active.value

    private fun updateUsbProgressCheck() {
        accessHandler.removeCallbacks(usbProgressCheck)
        if (shouldWatchUsbProgress()) accessHandler.postDelayed(usbProgressCheck, USB_PROGRESS_INTERVAL_MS)
        else setUsbStalled(false)
    }

    private fun resetUsbProgress() {
        usbLastPositionMs = -1L
        usbProgressAt = SystemClock.elapsedRealtime()
        setUsbStalled(false)
        updateUsbProgressCheck()
    }

    private fun setUsbStalled(stalled: Boolean) {
        if (usbStalled == stalled) return
        usbStalled = stalled
        PlayerUsbDiagnostics.record(if (stalled) "USB playback position stopped advancing" else "USB playback stall cleared")
        publish { copy(usbStalled = stalled) }
    }

    fun setMuted(muted: Boolean) = post {
        if (this.muted == muted) return@post
        this.muted = muted
        if (initialized) MPVLib.setPropertyBoolean("mute", muted)
        if (muted) publishLoudness(0f)
    }

    fun refreshHardwareDecoding() = post {
        if (initialized) MpvVideoDecoding.refresh(playerHost.context)
    }

    fun setMirrored(mirrored: Boolean) = post {
        this.mirrored = mirrored
        if (ready) applyMirrorFilter()
    }

    fun setAudioOnly(audioOnly: Boolean) = post {
        if (this.audioOnly == audioOnly) return@post
        this.audioOnly = audioOnly
        if (ready) applyVideoMode()
        else if (audioOnly) playerHost.setVideoOutputEnabled(false)
    }

    private fun applyVideoMode() {
        if (audioOnly) playerHost.setVideoOutputEnabled(false)
        // Without an audio track mpv needs the video clock to advance playback and subtitles.
        // The headless output still discards all frames in that case.
        val track = if (audioOnly && MPVLib.getPropertyInt("current-tracks/audio/id") != null) "no" else "auto"
        if (MPVLib.getPropertyString("options/vid") != track) {
            MPVLib.setPropertyString("file-local-options/vid", track)
        }
        applyMirrorFilter()
        if (!audioOnly) playerHost.setVideoOutputEnabled(true)
    }

    private fun applyMirrorFilter() {
        val filter = when {
            !mirrored || audioOnly -> ""
            // mpv applies orientation after the filter. Swap axes for quarter turns.
            MPVLib.getPropertyInt("video-params/rotate")?.rem(180) == 90 -> "vflip"
            else -> "hflip"
        }
        if (filter == mirrorFilter) return
        // Filter the video before mpv draws subtitles.
        MPVLib.setPropertyString("vf", filter)
        mirrorFilter = filter
    }

    fun setSubtitleFontSize(size: Int) = post {
        subtitleFontSize = size.coerceIn(PlayerPreferences.MIN_SUBTITLE_FONT_SIZE, PlayerPreferences.MAX_SUBTITLE_FONT_SIZE)
        if (initialized) applySubtitleFontSize()
    }

    private fun applySubtitleFontSize() {
        MPVLib.setPropertyDouble("sub-font-size", PlayerMpvOptions.subtitleFontSizePx(subtitleFontSize, resources))
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
                updateUsbPlaybackPower()
                if (usesDirectUsb) PlayerUsbDiagnostics.setActive(false)
                usbAudio.close()
                clearEmbeddedSubtitles()
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
                        copy(ready = false, playing = false, ended = false, loudness = 0f, subtitleCueTiming = null,
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
        if (usesDirectUsb) resetUsbProgress()
        MPVLib.command(arrayOf("seek", (clampPosition(position) / 1000.0).toString(), "absolute+exact"))
    }

    override fun eventProperty(property: String, value: Long) = post {
        if (property == "video-params/rotate" && ready && mirrored && !audioOnly) {
            applyMirrorFilter()
        }
    }

    override fun eventProperty(property: String, value: Double) = post {
        if (!ready || !value.isFinite()) return@post
        when (property) {
            "time-pos" -> {
                val position = secondsToMs(value)
                if (usesDirectUsb && position != usbLastPositionMs) {
                    usbLastPositionMs = position
                    usbProgressAt = SystemClock.elapsedRealtime()
                    setUsbStalled(false)
                }
                publish { copy(positionMs = position) }
                // Consecutive equal-text cues may cross the old end with no sub-text event.
                if (activeSubtitleTiming?.let { position >= it.endMs } == true) queueSubtitleRefresh()
            }
            "duration/full" -> {
                durationMs = secondsToMs(value)
                val duration = durationMs
                publish { copy(durationMs = duration) }
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
        if (property == "current-ao") {
            val direct = usesDirectUsb && value == "android-usb"
            if (direct && !PlayerUsbDiagnostics.active.value) PlayerUsbDiagnostics.record("USB exclusive output active")
            PlayerUsbDiagnostics.setActive(direct)
            resetUsbProgress()
            return@post
        }
        if (!ready) return@post
        when (property) {
            "sub-text" -> queueSubtitleRefresh()
            "sid" -> {
                refreshSubtitleTracks()
                queueSubtitleRefresh()
            }
        }
    }

    override fun eventProperty(property: String) = post {
        if (property == "current-ao" && usesDirectUsb) {
            PlayerUsbDiagnostics.setActive(false)
            resetUsbProgress()
        }
        if (!ready) return@post
        when (property) {
            "track-list" -> refreshSubtitleTracks()
            "sub-text" -> queueSubtitleRefresh()
        }
    }

    override fun event(eventId: Int) = post {
        when (eventId) {
            MPVLib.MpvEvent.FILE_LOADED -> {
                if (!initialized || MPVLib.getPropertyString("path") != mediaPath) return@post
                ready = true
                completed = false
                durationMs = secondsToMs(MPVLib.getPropertyDouble("duration/full"))
                MPVLib.setPropertyDouble("speed", speed.toDouble())
                applyVolume()
                applyVideoMode()
                applyAudioFilter()
                applySubtitleFontSize()
                val resumePosition = clampPosition(pendingSeekMs ?: 0L)
                pendingSeekMs = null
                if (resumePosition > 0L) seek(resumePosition)
                loadEmbeddedSubtitles()
                if (mediaGeneration != generation.get()) return@post
                val internalTracks = refreshSubtitleTracks()
                // A recreated surface has a fresh private cache. Restore the logical lyric
                // choice using its stable ID rather than the previous cache file's path.
                val explicit = pendingSubtitle?.let { requested ->
                    if (requested.id.startsWith("embedded:")) embeddedSubtitles.firstOrNull { it.id == requested.id }
                        ?: PlayerSubtitleOption("none", "", PlayerSubtitleKind.NONE)
                    else requested
                }
                applySubtitle(PlayerSubtitleSelection.initial(explicit, internalTracks,
                    embeddedSubtitles.firstOrNull() ?: fallbackSubtitle))
                pendingSubtitle = null
                val playing = playRequested && !usbAudio.awaitingPermission
                MPVLib.setPropertyBoolean("pause", !playing)
                if (usesDirectUsb) resetUsbProgress()
                val duration = durationMs
                publish {
                    copy(ready = true, playing = playing, durationMs = duration,
                        positionMs = resumePosition, ended = false, error = null)
                }
                refreshSubtitleTracks()
                resetLoudnessPolling()
            }
            MPVLib.MpvEvent.PLAYBACK_RESTART -> if (ready) {
                refreshPlaybackState()
                queueSubtitleRefresh()
            }
            MPVLib.MpvEvent.SHUTDOWN -> {
                ready = false
                playRequested = false
                accessHandler.removeCallbacks(loudnessPoll)
                publish { copy(ready = false, playing = false, ended = false, loudness = 0f,
                    subtitleCueTiming = null, error = "播放器已停止") }
            }
        }
    }

    private fun refreshPlaybackState() {
        val playing = !completed && canAdvancePlayback()
        if (!playing && usesDirectUsb) resetUsbProgress()
        publish { copy(playing = playing, loudness = if (playing) loudness else 0f) }
        resetLoudnessPolling()
    }

    private fun canAdvancePlayback() = MPVLib.getPropertyBoolean("pause") == false &&
        MPVLib.getPropertyBoolean("paused-for-cache") != true && MPVLib.getPropertyBoolean("seeking") != true

    override fun endFileError(error: Int, message: String) = post {
        ready = false
        playRequested = false
        accessHandler.removeCallbacks(loudnessPoll)
        publish { copy(ready = false, playing = false, ended = false, loudness = 0f, subtitleCueTiming = null,
            error = "加载失败：$message（$error）") }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (!released.get() && level <= 20) Log.e(TAG, "[$prefix] ${text.trim()}")
        if (released.get() || prefix != "ao/android-usb") return
        PlayerUsbDiagnostics.record(text, failed = level <= 20)
        if (text.startsWith("USB direct output failed:")) post {
            if (!initialized || usbOutputDescriptor == null) return@post
            usbFailedPath = mediaPath
            playRequested = false
            MPVLib.setPropertyBoolean("pause", true)
            setUsbOutput(null)
            usbAudio.releaseForRetry()
            if (ready) refreshPlaybackState()
            val disconnected = text.contains("device disconnected", ignoreCase = true) || text.contains("LIBUSB_ERROR_NO_DEVICE")
            notifyUsbOutput(if (disconnected) R.string.player_usb_exclusive_disconnected else R.string.player_usb_exclusive_failed,
                interrupted = true)
        }
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
        activeSubtitleTiming = null
        publish { copy(selectedSubtitleId = selectedId, subtitleText = "", subtitleCueTiming = null) }
        queueSubtitleRefresh()
    }

    /** Property changes can arrive together. Read the active cue rather than pairing separate event values. */
    private fun queueSubtitleRefresh() {
        if (subtitleRefreshQueued) return
        subtitleRefreshQueued = true
        post(mediaGeneration) {
            subtitleRefreshQueued = false
            if (ready) refreshSubtitle()
        }
    }

    private fun refreshSubtitle() {
        val text = MPVLib.getPropertyString("sub-text").orEmpty()
        val timing = if (text.isBlank()) null else {
            val start = readSubtitleTime("sub-start")
            val end = readSubtitleTime("sub-end")
            // A boundary between the native reads can mix times from two equal-text cues.
            if (readSubtitleTime("sub-start") != start || MPVLib.getPropertyString("sub-text") != text) {
                queueSubtitleRefresh()
                return
            }
            val startMs = start?.coerceAtLeast(0L)
            if (startMs != null && end != null && end > startMs) PlayerSubtitleCueTiming(startMs, end) else null
        }
        activeSubtitleTiming = timing
        publish { copy(subtitleText = text, subtitleCueTiming = timing) }
    }

    private fun readSubtitleTime(property: String): Long? {
        // This player disables mpv config loading and keeps subtitle delay, FPS and speed at defaults.
        return MPVLib.getPropertyDouble(property)?.takeIf { it.isFinite() }?.let { (it * 1000.0).roundToLong() }
    }

    /** Tag lyrics are prepared off the UI thread, without changing the source audio. */
    private fun loadEmbeddedSubtitles() {
        val root = cacheDirectory ?: return
        val directory = File(root, "player-lyrics/${UUID.randomUUID()}")
        embeddedSubtitleDir = directory
        embeddedSubtitles = try {
            PlayerEmbeddedLyrics.load(File(mediaPath), directory, durationMs,
                resources.getString(R.string.player_embedded_lyrics)).map { option ->
                option.copy(id = "embedded:${File(option.path).nameWithoutExtension}")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Reading embedded lyrics failed", error)
            emptyList()
        } catch (error: LinkageError) {
            Log.w(TAG, "Embedded lyric reader unavailable", error)
            emptyList()
        }
    }

    private fun clearEmbeddedSubtitles() {
        embeddedSubtitles = emptyList()
        embeddedSubtitleDir?.let { directory ->
            runCatching { directory.deleteRecursively() }
                .onFailure { Log.w(TAG, "Removing private subtitle cache failed", it) }
        }
        embeddedSubtitleDir = null
    }

    private fun refreshSubtitleTracks(): List<PlayerSubtitleOption> {
        val count = MPVLib.getPropertyInt("track-list/count") ?: 0
        val tracks = mutableListOf<PlayerSubtitleOption>()
        var selectedId = "none"
        for (index in 0 until count) {
            if (MPVLib.getPropertyString("track-list/$index/type") != "sub") continue
            val id = MPVLib.getPropertyInt("track-list/$index/id") ?: continue
            val external = MPVLib.getPropertyString("track-list/$index/external-filename")?.takeIf { it.isNotBlank() }
            val optionId = if (external == null) "internal:$id" else
                embeddedSubtitles.firstOrNull { it.path == external }?.id ?: "external:$external"
            if (MPVLib.getPropertyBoolean("track-list/$index/selected") == true) selectedId = optionId
            // The view model supplies sidecar files. Do not list an added file twice.
            if (external != null) continue
            val title = MPVLib.getPropertyString("track-list/$index/title")?.takeIf { it.isNotBlank() }
            val language = MPVLib.getPropertyString("track-list/$index/lang")?.takeIf { it.isNotBlank() }
            tracks += PlayerSubtitleOption(optionId, title ?: language ?: "轨道 ${tracks.size + 1}",
                PlayerSubtitleKind.INTERNAL, mpvTrackId = id)
        }
        val options = tracks + embeddedSubtitles
        val selected = selectedId
        publish {
            if (selectedSubtitleId == selected) copy(subtitleTracks = options)
            else copy(subtitleTracks = options, selectedSubtitleId = selected, subtitleCueTiming = null)
        }
        return options
    }

    private fun secondsToMs(value: Double?): Long =
        value?.takeIf { it.isFinite() }?.let { (it * 1000.0).toLong().coerceAtLeast(0L) } ?: 0L

    private companion object {
        const val TAG = "PlayerEngine"
        const val USB_PROGRESS_INTERVAL_MS = 1_000L
        const val USB_PROGRESS_TIMEOUT_MS = 3_000L
    }
}
