package com.subtitleedit.player

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.subtitleedit.R
import com.subtitleedit.util.FileUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

internal enum class PlayerPanel { NONE, PLAYLIST, EQUALIZER, SUBTITLE, BOOKMARK, TIMER, SPEED, SETTINGS }
internal data class PlayerGestureHint(val text: String, val token: Long = 0L)

internal data class PlayerUiState(
    val playlist: List<PlayerTrack> = emptyList(),
    val currentIndex: Int = -1,
    val shuffleOrder: List<Int> = emptyList(),
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null,
    val speed: Float = 1f,
    val mode: PlayerPlayMode = PlayerPlayMode.REPEAT_ALL,
    val volumePercent: Int = 100,
    val equalizer: PlayerEqualizerState = PlayerEqualizerState(),
    val showLoudnessWaveform: Boolean = true,
    val loudness: Float = 0f,
    val loudnessSampleId: Long = 0L,
    val resumePlayback: Boolean = true,
    val subtitleFontSize: Int = 16,
    val sleepTimer: PlayerSleepTimer = PlayerSleepTimer(),
    val sleepRemainingMs: Long = 0L,
    val scheduledStartRemainingMs: Long? = null,
    val scheduledStartMinutes: Int? = null,
    val bookmarks: List<PlayerBookmark> = emptyList(),
    val subtitleOptions: List<PlayerSubtitleOption> = emptyList(),
    val selectedSubtitleId: String = "none",
    val subtitleText: String = "",
    val controlsVisible: Boolean = true,
    val panel: PlayerPanel = PlayerPanel.NONE,
    val isVideo: Boolean = false,
    val seekPreviewMs: Long? = null,
    val gestureHint: PlayerGestureHint? = null
) {
    val currentTrack: PlayerTrack? get() = playlist.getOrNull(currentIndex)
    val hasPrevious: Boolean get() = currentTrack != null && (positionMs > 5_000L ||
        PlayerPlaylistOrder.manualPrevious(currentIndex, playlist.size, mode, shuffleOrder) != null)
    val hasNext: Boolean get() =
        PlayerPlaylistOrder.manualNext(currentIndex, playlist.size, mode, shuffleOrder) != null
}

/** Owns one surface session and folds engine events into a single main-thread UI state. */
internal class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PlayerPreferences.getInstance(application)
    private val bookmarkStore = PlayerBookmarkStore.create(application.filesDir)
    private val _state = MutableStateFlow(PlayerUiState(
        mode = preferences.defaultPlayMode,
        speed = preferences.defaultSpeed,
        equalizer = preferences.equalizer,
        showLoudnessWaveform = preferences.showLoudnessWaveform,
        resumePlayback = preferences.resumePlayback,
        subtitleFontSize = preferences.subtitleFontSize
    ))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private val _finishRequests = MutableStateFlow(0L)
    val finishRequests: StateFlow<Long> = _finishRequests.asStateFlow()

    private var engine: PlayerEngine? = null
    private var host: PlayerMpvView? = null
    private var engineJob: Job? = null
    private var tickerJob: Job? = null
    private var loadId = 0L
    private var handledEndId = -1L
    private var trackEnded = false
    private var configured = false
    private var hintToken = 0L
    private var externalSubtitles: List<PlayerSubtitleOption> = emptyList()
    private var scheduledStart = PlayerSleepTimer()
    private var detachedPosition: Long? = null
    private var detachedSubtitle: PlayerSubtitleOption? = null
    private var playOnAttach = true
    private data class TagEditResume(val path: String, val positionMs: Long, val subtitle: PlayerSubtitleOption?)
    private var tagEditResume: TagEditResume? = null
    private val audioFocus = PlayerAudioFocus(application) { interrupted ->
        if (interrupted) pause()
    }
    private val timerAlarm = PlayerTimerAlarm(application) { tickTimers() }

    fun configure(playlist: List<PlayerTrack>, startIndex: Int) {
        if (configured) return
        configured = true
        val selectedPath = playlist.getOrNull(startIndex)?.path
        val tracks = playlist.filter { it.file.isFile }.distinctBy { it.path }
        val index = if (tracks.isEmpty()) -1 else tracks.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0)
        _state.value = _state.value.copy(
            playlist = tracks,
            currentIndex = index,
            shuffleOrder = PlayerPlaylistOrder.newShuffleOrder(tracks.size, index),
            isVideo = tracks.getOrNull(index)?.isVideo == true,
            bookmarks = tracks.getOrNull(index)?.let { bookmarkStore.load(it.path) }.orEmpty(),
            error = if (tracks.isEmpty()) applicationText(R.string.player_playlist_empty) else null
        )
    }

    fun attach(view: PlayerMpvView) {
        if (host === view) return
        host?.let(::detach)
        host = view
        val session = PlayerEngine(view)
        engine = session
        engineJob = viewModelScope.launch {
            session.state.collect { sample ->
                if (loadId == 0L || sample.loadId != loadId || engine !== session || _state.value.currentTrack == null) return@collect
                val snapshot = _state.value
                _state.value = snapshot.copy(
                    positionMs = sample.positionMs,
                    durationMs = sample.durationMs,
                    playing = sample.playing && sample.error == null && !sample.ended,
                    ready = sample.ready,
                    error = sample.error,
                    loudness = if (snapshot.showLoudnessWaveform) sample.loudness else 0f,
                    loudnessSampleId = sample.loudnessSampleId,
                    subtitleText = sample.subtitleText,
                    selectedSubtitleId = sample.selectedSubtitleId.ifBlank { "none" },
                    subtitleOptions = listOf(SUBTITLE_OFF) + sample.subtitleTracks + externalSubtitles.filter { external ->
                        sample.subtitleTracks.none { it.id == external.id }
                    }
                )
                if (sample.error != null) audioFocus.abandon()
                if (sample.ended && sample.ready && sample.error == null && handledEndId != loadId) {
                    handledEndId = loadId
                    onTrackEnded()
                }
            }
        }
        val resumeAt = detachedPosition
        val subtitle = detachedSubtitle
        detachedPosition = null
        detachedSubtitle = null
        tickTimers()
        if (tagEditResume == null) {
            loadCurrent(resume = _state.value.resumePlayback, autoplay = playOnAttach && !scheduledStart.active,
                resumeAt = resumeAt, subtitle = subtitle)
        }
        tickerJob = viewModelScope.launch {
            var lastSave = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(250)
                tickTimers()
                val now = SystemClock.elapsedRealtime()
                if (now - lastSave >= 5_000L) {
                    rememberPosition()
                    lastSave = now
                }
            }
        }
    }

    fun detach(view: PlayerMpvView) {
        if (host !== view) return
        detachedPosition = _state.value.positionMs
        detachedSubtitle = _state.value.subtitleOptions.firstOrNull { it.id == _state.value.selectedSubtitleId }
        rememberPosition()
        engineJob?.cancel()
        tickerJob?.cancel()
        engine?.release()
        engine = null
        host = null
        audioFocus.abandon()
        _state.value = _state.value.copy(ready = false, playing = false, loudness = 0f, seekPreviewMs = null)
    }

    fun togglePlay() { if (_state.value.playing) pause() else play() }

    fun play() {
        if (_state.value.currentTrack == null || engine == null) return
        cancelScheduledStart()
        if (tagEditResume != null) {
            playOnAttach = true
            return
        }
        if (!audioFocus.request()) return
        playOnAttach = true
        if (_state.value.error != null) {
            loadCurrent(resume = false, autoplay = true)
            return
        }
        if (trackEnded) {
            trackEnded = false
            handledEndId = -1L
            engine?.seekTo(0L)
        }
        engine?.play()
    }

    fun pause() {
        playOnAttach = false
        engine?.pause()
        _state.value = _state.value.copy(playing = false, loudness = 0f)
        audioFocus.abandon()
        rememberPosition()
    }

    /** A drag is only a preview; one seek is sent on commit. */
    fun seekTo(positionMs: Long, fromGesture: Boolean = false) {
        val current = _state.value
        if (current.currentTrack == null) return
        val target = positionMs.coerceIn(0L, current.durationMs.coerceAtLeast(0L))
        if (fromGesture) {
            _state.value = current.copy(seekPreviewMs = target)
            return
        }
        trackEnded = false
        handledEndId = -1L
        engine?.seekTo(target)
        _state.value = current.copy(positionMs = target, seekPreviewMs = null, loudness = 0f)
    }

    fun clearSeekPreview() { _state.value = _state.value.copy(seekPreviewMs = null) }

    fun skipBy(deltaMs: Long) {
        if (!_state.value.ready) return
        seekTo(_state.value.positionMs + deltaMs)
        hint(if (deltaMs < 0) R.string.player_seek_back else R.string.player_seek_forward)
    }

    fun next() {
        val current = _state.value
        val target = PlayerPlaylistOrder.manualNext(current.currentIndex, current.playlist.size, current.mode, current.shuffleOrder)
        if (target != null) jumpTo(target) else hint(R.string.player_queue_end)
    }

    fun previous() {
        val current = _state.value
        if (current.positionMs > 5_000L) { seekTo(0L); return }
        val target = PlayerPlaylistOrder.manualPrevious(current.currentIndex, current.playlist.size, current.mode, current.shuffleOrder)
        if (target != null) jumpTo(target) else seekTo(0L)
    }

    fun playIndex(index: Int) {
        if (index !in _state.value.playlist.indices) return
        if (index == _state.value.currentIndex) togglePlay() else jumpTo(index)
    }

    private fun jumpTo(index: Int) {
        if (index !in _state.value.playlist.indices) return
        rememberPosition()
        cancelScheduledStart()
        _state.value = _state.value.copy(currentIndex = index)
        loadCurrent(resume = _state.value.resumePlayback, autoplay = true)
    }

    private fun loadCurrent(resume: Boolean, autoplay: Boolean, resumeAt: Long? = null, subtitle: PlayerSubtitleOption? = null) {
        val current = _state.value
        val track = current.currentTrack ?: return
        val session = engine ?: return
        val app = getApplication<Application>()
        loadId++
        trackEnded = false
        externalSubtitles = FileUtils.getPossibleSubtitleFiles(track.file).filter { it.isFile }.map { file ->
            PlayerSubtitleOption("external:${file.absolutePath}", file.name, PlayerSubtitleKind.EXTERNAL, path = file.absolutePath)
        }
        _state.value = current.copy(
            positionMs = 0L, durationMs = 0L, ready = false, playing = false, error = null,
            isVideo = track.isVideo, subtitleText = "", selectedSubtitleId = "none",
            subtitleOptions = listOf(SUBTITLE_OFF) + externalSubtitles,
            bookmarks = bookmarkStore.load(track.path), seekPreviewMs = null,
            loudness = 0f, loudnessSampleId = 0L
        )
        val shouldPlay = autoplay && audioFocus.request()
        playOnAttach = shouldPlay
        session.start(File(app.filesDir, "player/config"), app.cacheDir, track.file, shouldPlay, loadId)
        session.setSpeed(current.speed)
        session.setAudioFilter(PlayerEqualizer.afFilter(current.equalizer))
        session.setLoudnessEnabled(current.showLoudnessWaveform && !track.isVideo)
        session.setVolume(current.volumePercent)
        session.setSubtitleFontSize(current.subtitleFontSize)
        val saved = resumeAt ?: if (resume) preferences.lastPosition(track.path) else 0L
        if (saved > 0L) session.seekTo(saved)
        subtitle?.let(session::selectSubtitle)
    }

    private fun onTrackEnded() {
        val current = _state.value
        current.currentTrack?.let { preferences.saveLastPosition(it.path, 0L) }
        trackEnded = true
        val target = PlayerPlaylistOrder.afterEnd(current.currentIndex, current.playlist.size, current.mode, current.shuffleOrder)
        if (target == null) {
            playOnAttach = false
            audioFocus.abandon()
            hint(R.string.player_queue_end)
            return
        }
        _state.value = current.copy(currentIndex = target)
        loadCurrent(resume = false, autoplay = true)
    }

    private fun rememberPosition() {
        val current = _state.value
        val track = current.currentTrack ?: return
        if (!current.resumePlayback || !current.ready || current.error != null) return
        val completed = trackEnded || (current.durationMs > 0L && current.positionMs >= current.durationMs - 1_000L)
        preferences.saveLastPosition(track.path, if (completed) 0L else current.positionMs)
    }

    fun saveProgress() = rememberPosition()

    /** Release the media file before the existing tag editor updates it. */
    fun prepareTagEditing(): String? {
        val current = _state.value
        val track = current.currentTrack ?: return null
        if (tagEditResume != null) return null
        rememberPosition()
        tagEditResume = TagEditResume(track.path, current.positionMs,
            current.subtitleOptions.firstOrNull { it.id == current.selectedSubtitleId })
        engine?.stop()
        audioFocus.abandon()
        _state.value = current.copy(playing = false, loudness = 0f, panel = PlayerPanel.NONE)
        return track.path
    }

    fun resumeAfterTagEditing() {
        val saved = tagEditResume ?: return
        tagEditResume = null
        if (_state.value.currentTrack?.path == saved.path) {
            loadCurrent(resume = false, autoplay = playOnAttach && !scheduledStart.active,
                resumeAt = saved.positionMs, subtitle = saved.subtitle)
        }
    }

    private fun tickTimers() {
        val now = SystemClock.elapsedRealtime()
        val current = _state.value
        val stop = PlayerSleepTimerPolicy.isExpired(current.sleepTimer, now)
        val start = PlayerSleepTimerPolicy.isExpired(scheduledStart, now)
        _state.value = current.copy(
            sleepTimer = if (stop) PlayerSleepTimer() else current.sleepTimer,
            sleepRemainingMs = if (stop) 0L else PlayerSleepTimerPolicy.remainingMs(current.sleepTimer, now),
            scheduledStartRemainingMs = if (!scheduledStart.active || start) null else PlayerSleepTimerPolicy.remainingMs(scheduledStart, now),
            scheduledStartMinutes = if (!scheduledStart.active || start) null else scheduledStart.durationMinutes
        )
        if (start) scheduledStart = PlayerSleepTimer()
        // A stop due in the same tick takes precedence over a scheduled start.
        if (stop) {
            playOnAttach = false
            pause()
            hint(R.string.player_sleep_stopped)
        } else if (start) play()
        updateTimerAlarm()
    }

    private fun updateTimerAlarm() {
        val deadlines = listOf(_state.value.sleepTimer, scheduledStart).filter { it.active }
        timerAlarm.scheduleAt(deadlines.minOfOrNull { it.endsAtElapsedMs })
    }

    fun setSpeed(speed: Float) {
        if (!speed.isFinite()) return
        val value = (speed.coerceIn(PlayerPreferences.MIN_SPEED, PlayerPreferences.MAX_SPEED) * 100).roundToInt() / 100f
        engine?.setSpeed(value)
        preferences.defaultSpeed = value
        _state.value = _state.value.copy(speed = value)
    }

    fun setMode(mode: PlayerPlayMode) {
        val current = _state.value
        preferences.defaultPlayMode = mode
        _state.value = current.copy(mode = mode, shuffleOrder =
            if (mode == PlayerPlayMode.SHUFFLE && current.mode != mode)
                PlayerPlaylistOrder.newShuffleOrder(current.playlist.size, current.currentIndex)
            else current.shuffleOrder)
    }

    fun cycleMode() {
        val mode = PlayerPlayMode.entries[(_state.value.mode.ordinal + 1) % PlayerPlayMode.entries.size]
        setMode(mode)
        hint(when (mode) {
            PlayerPlayMode.SEQUENCE -> R.string.player_mode_sequence
            PlayerPlayMode.REPEAT_ONE -> R.string.player_mode_repeat_one
            PlayerPlayMode.REPEAT_ALL -> R.string.player_mode_repeat_all
            PlayerPlayMode.SHUFFLE -> R.string.player_mode_shuffle
        })
    }

    fun setVolume(percent: Int) {
        val value = percent.coerceIn(0, 200)
        engine?.setVolume(value)
        _state.value = _state.value.copy(volumePercent = value)
    }

    fun setResumePlayback(enabled: Boolean) {
        preferences.resumePlayback = enabled
        _state.value = _state.value.copy(resumePlayback = enabled)
        if (enabled) rememberPosition()
    }

    fun setShowLoudnessWaveform(enabled: Boolean) {
        preferences.showLoudnessWaveform = enabled
        engine?.setLoudnessEnabled(enabled && !_state.value.isVideo)
        _state.value = _state.value.copy(showLoudnessWaveform = enabled, loudness = 0f)
    }

    fun setSubtitleFontSize(size: Int) {
        val value = size.coerceIn(10, 32)
        preferences.subtitleFontSize = value
        engine?.setSubtitleFontSize(value)
        _state.value = _state.value.copy(subtitleFontSize = value)
    }

    fun setEqualizer(equalizer: PlayerEqualizerState) {
        val value = equalizer.copy(gains = PlayerEqualizer.normalize(equalizer.gains))
        preferences.equalizer = value
        engine?.setAudioFilter(PlayerEqualizer.afFilter(value))
        _state.value = _state.value.copy(equalizer = value)
    }

    fun setBandGain(index: Int, gain: Float) {
        if (index !in 0 until PlayerEqualizer.BAND_COUNT) return
        val gains = PlayerEqualizer.normalize(_state.value.equalizer.gains).toMutableList()
        gains[index] = PlayerEqualizer.clamp(gain)
        setEqualizer(_state.value.equalizer.copy(gains = gains))
    }

    fun applyPreset(preset: PlayerEqualizer.Preset) {
        setEqualizer(_state.value.equalizer.copy(enabled = true, gains = preset.gains))
    }

    fun selectSubtitle(option: PlayerSubtitleOption) {
        if (_state.value.subtitleOptions.none { it.id == option.id }) return
        engine?.selectSubtitle(option)
    }

    fun addBookmark() {
        val current = _state.value
        val track = current.currentTrack ?: return
        if (!current.ready) return
        val position = current.seekPreviewMs ?: current.positionMs
        if (current.bookmarks.any { kotlin.math.abs(it.positionMs - position) < 1_500L }) {
            hint(R.string.player_bookmark_exists)
            return
        }
        val updated = (current.bookmarks + PlayerBookmark(position, System.currentTimeMillis())).sortedBy { it.positionMs }
        if (!bookmarkStore.save(track.path, updated)) {
            hint(R.string.player_bookmark_save_failed)
            return
        }
        _state.value = current.copy(bookmarks = updated)
        hint(R.string.player_bookmark_added)
    }

    fun removeBookmark(positionMs: Long) {
        val current = _state.value
        val track = current.currentTrack ?: return
        val updated = current.bookmarks.filter { it.positionMs != positionMs }
        if (!bookmarkStore.save(track.path, updated)) {
            hint(R.string.player_bookmark_save_failed)
            return
        }
        _state.value = current.copy(bookmarks = updated)
    }

    fun setSleepCountdown(minutes: Int) {
        if (minutes !in PlayerSleepTimerPolicy.MIN_MINUTES..PlayerSleepTimerPolicy.MAX_MINUTES) return
        val now = SystemClock.elapsedRealtime()
        val timer = PlayerSleepTimerPolicy.countdown(now, minutes)
        _state.value = _state.value.copy(sleepTimer = timer, sleepRemainingMs = PlayerSleepTimerPolicy.remainingMs(timer, now))
        updateTimerAlarm()
    }

    fun clearSleepTimer() {
        _state.value = _state.value.copy(sleepTimer = PlayerSleepTimer(), sleepRemainingMs = 0L)
        updateTimerAlarm()
    }

    fun scheduleStart(minutes: Int) {
        if (minutes !in PlayerSleepTimerPolicy.MIN_MINUTES..PlayerSleepTimerPolicy.MAX_MINUTES) return
        pause()
        val now = SystemClock.elapsedRealtime()
        scheduledStart = PlayerSleepTimerPolicy.countdown(now, minutes)
        _state.value = _state.value.copy(scheduledStartRemainingMs = PlayerSleepTimerPolicy.remainingMs(scheduledStart, now), scheduledStartMinutes = minutes)
        updateTimerAlarm()
    }

    fun cancelScheduledStart() {
        scheduledStart = PlayerSleepTimer()
        _state.value = _state.value.copy(scheduledStartRemainingMs = null, scheduledStartMinutes = null)
        updateTimerAlarm()
    }

    fun requestFinish() { _finishRequests.value++ }
    fun setControlsVisible(visible: Boolean) { _state.value = _state.value.copy(controlsVisible = visible) }
    fun openPanel(panel: PlayerPanel) { _state.value = _state.value.copy(panel = panel, controlsVisible = true) }
    fun closePanel() { _state.value = _state.value.copy(panel = PlayerPanel.NONE) }
    fun hint(textRes: Int) { hint(applicationText(textRes)) }
    fun hint(text: String) { _state.value = _state.value.copy(gestureHint = PlayerGestureHint(text, ++hintToken)) }
    fun clearHint() { _state.value = _state.value.copy(gestureHint = null) }
    private fun applicationText(res: Int) = getApplication<Application>().getString(res)

    override fun onCleared() {
        rememberPosition()
        engineJob?.cancel()
        tickerJob?.cancel()
        engine?.release()
        timerAlarm.cancel()
        audioFocus.abandon()
        super.onCleared()
    }

    private companion object {
        val SUBTITLE_OFF = PlayerSubtitleOption("none", "", PlayerSubtitleKind.NONE)
    }
}
