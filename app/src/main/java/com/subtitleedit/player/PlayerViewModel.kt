package com.subtitleedit.player

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.subtitleedit.R
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaPlaylist
import com.subtitleedit.library.PlayerTrackDetails
import com.subtitleedit.library.PlayerTrackMetadataReader
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsBackupManager
import com.subtitleedit.util.SettingsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

internal enum class PlayerPanel { NONE, PLAYLIST, EQUALIZER, SUBTITLE, BOOKMARK, TIMER, SPEED, SETTINGS }
internal data class PlayerGestureHint(val text: String, val token: Long = 0L, val iconRes: Int = R.drawable.ic_player_mode)

internal data class PlayerUiState(
    val playlist: List<PlayerTrack> = emptyList(),
    val queueVersion: Long = 0L,
    val currentIndex: Int = -1,
    val shuffleOrder: List<Int> = emptyList(),
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null,
    val speed: Float = 1f,
    val mode: PlayerPlayMode = PlayerPlayMode.REPEAT_ALL,
    val endBehavior: PlayerEndBehavior = PlayerEndBehavior(),
    val queueSource: PlayerQueueSource? = null,
    val showEndBehaviorDialog: Boolean = false,
    val volumePercent: Int = 100,
    val volumeGestureActive: Boolean = false,
    val equalizer: PlayerEqualizerState = PlayerEqualizerState(),
    val showLoudnessWaveform: Boolean = true,
    val loudness: Float = 0f,
    val loudnessSampleId: Long = 0L,
    val resumePlayback: Boolean = true,
    val subtitleFontSize: Int = PlayerPreferences.DEFAULT_SUBTITLE_FONT_SIZE,
    val audioSubtitleLineSpacing: Int = PlayerPreferences.DEFAULT_AUDIO_SUBTITLE_LINE_SPACING,
    val audioSubtitleAlignment: PlayerSubtitleAlignment = PlayerSubtitleAlignment.CENTER,
    val sleepTimer: PlayerSleepTimer = PlayerSleepTimer(),
    val sleepRemainingMs: Long = 0L,
    val scheduledStartRemainingMs: Long? = null,
    val scheduledStartMinutes: Int? = null,
    val bookmarks: List<PlayerBookmark> = emptyList(),
    val subtitleOptions: List<PlayerSubtitleOption> = emptyList(),
    val selectedSubtitleId: String = "none",
    val subtitleText: String = "",
    val subtitleCueTiming: PlayerSubtitleCueTiming? = null,
    val lyricsVisible: Boolean = false,
    val lyricLines: List<PlayerLyricLine> = emptyList(),
    val lyricsLoading: Boolean = false,
    val controlsVisible: Boolean = true,
    val controlsLocked: Boolean = false,
    val panel: PlayerPanel = PlayerPanel.NONE,
    val isVideo: Boolean = false,
    val muted: Boolean = false,
    val mirrored: Boolean = false,
    val audioMode: Boolean = false,
    val seekPreviewMs: Long? = null,
    val gestureHint: PlayerGestureHint? = null,
    val isFavorite: Boolean = false,
    val showTrackInfoDialog: Boolean = false,
    val trackInfoLoading: Boolean = false,
    val trackInfo: PlayerTrackDetails? = null,
    val showPlaylistPickerDialog: Boolean = false,
    val userPlaylists: List<MediaPlaylist> = emptyList(),
    val trackContainedPlaylistIds: Set<Long> = emptySet()
) {
    val currentTrack: PlayerTrack? get() = playlist.getOrNull(currentIndex)
    val showsVideo: Boolean get() = isVideo && !audioMode
    val movesBetweenQueues: Boolean get() = endBehavior.enabled && endBehavior.queueAction == PlayerQueueEndAction.NEXT_QUEUE
    val hasPrevious: Boolean get() = currentTrack != null && (positionMs > 5_000L ||
        if (movesBetweenQueues) queueSource != null || adjacentTrack(forward = false) != null
        else PlayerPlaylistOrder.manualPrevious(currentIndex, playlist.size, mode, shuffleOrder) != null)
    val hasNext: Boolean get() = if (movesBetweenQueues)
        currentTrack != null && (queueSource != null || adjacentTrack(forward = true) != null)
        else PlayerPlaylistOrder.manualNext(currentIndex, playlist.size, mode, shuffleOrder) != null

    fun adjacentTrack(forward: Boolean): Int? = PlayerEndBehaviorPolicy.adjacentIndex(
        currentIndex, playlist.size, shuffleOrder, mode == PlayerPlayMode.SHUFFLE, forward)
}

/** Owns one surface session and folds engine events into a single main-thread UI state. */
internal class PlayerViewModel @JvmOverloads constructor(
    application: Application,
    private val libraryRepository: MediaLibraryRepository = MediaLibraryRepository.getInstance(application),
    systemVolume: PlayerSystemVolume = AndroidPlayerSystemVolume(application)
) : AndroidViewModel(application) {
    private val preferences = PlayerPreferences.getInstance(application)
    private var settingsRevision = SettingsBackupManager.playerSettingsRevision.value
    private val bookmarkStore = PlayerBookmarkStore.create(application.filesDir)
    private val volumeController = PlayerVolumeController(systemVolume)
    private val volumeGesture = PlayerVolumeGesture()
    private val _state = MutableStateFlow(PlayerUiState(
        mode = preferences.defaultPlayMode,
        endBehavior = preferences.endBehavior,
        speed = preferences.defaultSpeed,
        equalizer = preferences.equalizer,
        showLoudnessWaveform = preferences.showLoudnessWaveform,
        resumePlayback = preferences.resumePlayback,
        subtitleFontSize = preferences.subtitleFontSize,
        audioSubtitleLineSpacing = preferences.audioSubtitleLineSpacing,
        audioSubtitleAlignment = preferences.audioSubtitleAlignment,
        volumePercent = volumeController.snapshot.percent
    ))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private val _finishRequests = MutableStateFlow(0L)
    val finishRequests: StateFlow<Long> = _finishRequests.asStateFlow()
    private val _expanded = MutableStateFlow(false)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()
    private val _outputSuspended = MutableStateFlow(false)
    val outputSuspended: StateFlow<Boolean> = _outputSuspended.asStateFlow()
    private val _detailedLyricsInForeground = MutableStateFlow(false)
    val detailedLyricsInForeground = _detailedLyricsInForeground.asStateFlow()
    private val _outputGeneration = MutableStateFlow(0L)
    val outputGeneration: StateFlow<Long> = _outputGeneration.asStateFlow()
    private var mediaNotifications: PlayerMediaNotificationConnection? = null
    private var mainSessionStore: MainPlayerSessionStore? = null
    private var savedMainSession: MainPlayerSession? = null

    fun connectMediaNotifications() {
        if (mediaNotifications == null) mediaNotifications =
            PlayerMediaNotificationConnection(getApplication(), this, viewModelScope)
    }

    private var engine: PlayerEngine? = null
    private var host: PlayerMpvView? = null
    private var engineJob: Job? = null
    private var tickerJob: Job? = null
    private var loadId = 0L
    private var nextQueueJob: Job? = null
    private var nextQueueVersion = 0L
    private val queueSourceResolver = PlayerQueueSourceResolver(application, libraryRepository)
    private var handledEndId = -1L
    private var trackEnded = false
    private var configured = false
    private var queueVersionCounter = 0L
    @Volatile private var appendGeneration = 0L
    private var hintToken = 0L
    private var externalSubtitles: List<PlayerSubtitleOption> = emptyList()
    private val pickedSubtitles = mutableMapOf<String, List<PlayerSubtitleOption>>()
    private var lyricLoadJob: Job? = null
    private var subtitleImportJob: Job? = null
    private var subtitleImportVersion = 0L
    private var loadedTrackPath: String? = null
    private data class LyricRequest(val loadId: Long, val subtitleId: String, val path: String, val durationMs: Long)
    private var lyricRequest: LyricRequest? = null
    private var scheduledStart = PlayerSleepTimer()
    private var detachedPosition: Long? = null
    private var detachedSubtitle: PlayerSubtitleOption? = null
    private var playOnAttach = true
    private data class TagEditResume(val path: String, val positionMs: Long, val subtitle: PlayerSubtitleOption?)
    private var tagEditResume: TagEditResume? = null
    private val listening = PlayerListeningTracker()
    private data class HistoryProgress(val playbackId: String, val positionMs: Long, val durationMs: Long)
    private var lastHistoryProgress: HistoryProgress? = null
    private val audioFocus = PlayerAudioFocus(application) { interrupted ->
        if (interrupted) pause()
    }
    private val timerAlarm = PlayerTimerAlarm(application) { tickTimers() }
    private val libraryController = PlayerLibraryController(
        application = application,
        repository = libraryRepository,
        scope = viewModelScope,
        state = _state,
        currentGeneration = { loadId },
        showHint = ::hint
    )

    init {
        viewModelScope.launch {
            SettingsManager.getInstance(application).hardwareDecodingChanges.collect {
                engine?.refreshHardwareDecoding()
            }
        }
    }

    fun configure(playlist: List<PlayerTrack>, startIndex: Int, initialMode: PlayerPlayMode? = null,
        queueSource: PlayerQueueSource? = null, allowDuplicates: Boolean = false) {
        if (configured) return
        configured = true
        configureQueue(playlist, startIndex, initialMode, queueSource, allowDuplicates)
    }

    fun restoreSession(playlist: List<PlayerTrack>, startIndex: Int, mode: PlayerPlayMode?,
        positionMs: Long, playing: Boolean, expanded: Boolean, queueSource: PlayerQueueSource? = null,
        audioMode: Boolean = false) {
        if (configured) return
        configure(playlist, startIndex, mode, queueSource, allowDuplicates = true)
        detachedPosition = if (_state.value.currentTrack?.path == playlist.getOrNull(startIndex)?.path)
            positionMs.coerceAtLeast(0L) else 0L
        playOnAttach = playing
        _state.value = _state.value.copy(audioMode = audioMode)
        _expanded.value = expanded
    }

    fun restoreMainSession(expanded: Boolean) {
        val store = mainSessionStore ?: MainPlayerSessionStore(getApplication()).also { mainSessionStore = it }
        if (configured) return
        if (!preferences.playerModeEnabled) {
            store.clear()
            savedMainSession = null
            return
        }
        val session = store.read() ?: return
        savedMainSession = session
        val tracks = session.paths.map { path ->
            val file = File(path)
            PlayerTrack(path, file.name, FileTypePolicy.isVideo(file))
        }
        restoreSession(tracks, session.index, session.mode,
            positionMs = if (preferences.resumePlayback) session.positionMs else 0L,
            playing = false, expanded = expanded, queueSource = session.queueSource, audioMode = session.audioMode)
        if (_state.value.currentTrack == null) {
            store.clear()
            savedMainSession = null
        }
    }

    /** A new library selection replaces the queue without replacing the output view. */
    fun openQueue(playlist: List<PlayerTrack>, startIndex: Int, initialMode: PlayerPlayMode? = null,
        queueSource: PlayerQueueSource? = null, allowDuplicates: Boolean = false) {
        if (playlist.none { it.file.isFile }) {
            hint(R.string.player_playlist_empty)
            return
        }
        cancelNextQueue()
        val selected = playlist.getOrNull(startIndex)?.path
        if (selected == _state.value.currentTrack?.path &&
            playlist.map { it.path } == _state.value.playlist.map { it.path } && initialMode == null &&
            (!allowDuplicates || startIndex == _state.value.currentIndex)) {
            _state.value = _state.value.copy(queueSource = queueSource)
            expand()
            return
        }
        rememberPosition()
        stopListening()
        cancelScheduledStart()
        tagEditResume = null
        detachedPosition = null
        detachedSubtitle = null
        playOnAttach = true
        configured = true
        configureQueue(playlist, startIndex, initialMode ?: preferences.defaultPlayMode, queueSource, allowDuplicates)
        _state.value = _state.value.copy(positionMs = 0L, durationMs = 0L, ready = false,
            playing = false, seekPreviewMs = null, panel = PlayerPanel.NONE,
            subtitleCueTiming = null,
            controlsLocked = false, controlsVisible = true, lyricsVisible = false,
            showTrackInfoDialog = false, showPlaylistPickerDialog = false, showEndBehaviorDialog = false)
        if (engine == null) {
            val current = _state.value
            trackEnded = false
            saveMainSession(current.currentTrack?.let { preferences.lastPosition(it.path) } ?: 0L)
        }
        loadCurrent(resume = _state.value.resumePlayback, autoplay = true)
        expand()
    }

    fun expand() {
        _expanded.value = true
        setControlsVisible(true)
    }

    fun collapse() {
        endVolumeGesture()
        clearSeekPreview()
        closePanel()
        closeTrackInfoDialog()
        closePlaylistPickerDialog()
        closeEndBehaviorDialog()
        _expanded.value = false
        saveProgress()
    }

    /** Leaving audio mode must not leave an invisible player or its timers running. */
    fun closeSession(preserveForRestore: Boolean = false) {
        appendGeneration++
        mediaNotifications?.invalidate()
        cancelNextQueue()
        collapse()
        cancelScheduledStart()
        clearSleepTimer()
        host?.let(::detach)
        subtitleImportVersion++
        subtitleImportJob?.cancel()
        lyricLoadJob?.cancel()
        tagEditResume = null
        detachedPosition = null
        detachedSubtitle = null
        loadedTrackPath = null
        configured = false
        _state.value = _state.value.copy(playlist = emptyList(), queueVersion = 0L, currentIndex = -1, queueSource = null,
            ready = false, playing = false, positionMs = 0L, durationMs = 0L, error = null,
            controlsLocked = false, lyricsVisible = false, lyricLines = emptyList(),
            isVideo = false, muted = false, mirrored = false, audioMode = false,
            subtitleOptions = emptyList(), subtitleText = "", subtitleCueTiming = null, gestureHint = null)
        if (!preserveForRestore) clearMainSession()
    }

    fun pauseForAppExit() {
        if (_state.value.currentTrack == null) return
        cancelScheduledStart()
        pause()
    }

    /** Release the global mpv context before an editor Activity claims it. */
    fun suspendOutput() {
        mediaNotifications?.invalidate()
        _outputSuspended.value = true
        host?.let(::detach)
    }

    fun resumeOutput() {
        if (!_outputSuspended.value) return
        _outputGeneration.value++
        _outputSuspended.value = false
    }

    private fun configureQueue(playlist: List<PlayerTrack>, startIndex: Int, initialMode: PlayerPlayMode?,
        queueSource: PlayerQueueSource?, allowDuplicates: Boolean = false) {
        cancelNextQueue()
        val selectedPath = playlist.getOrNull(startIndex)?.path
        val valid = playlist.withIndex().filter { it.value.file.isFile }
            .let { if (allowDuplicates) it else it.distinctBy { item -> item.value.path } }
        val usedIds = hashSetOf<String>()
        val tracks = valid.map { if (usedIds.add(it.value.queueEntryId)) it.value else it.value.newQueueEntry() }
        val index = if (tracks.isEmpty()) -1 else valid.indexOfFirst { it.index == startIndex }.takeIf { it >= 0 }
            ?: tracks.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0)
        val currentTrack = tracks.getOrNull(index)
        queueVersionCounter++
        _state.value = _state.value.copy(
            playlist = tracks,
            queueVersion = queueVersionCounter,
            queueSource = queueSource,
            currentIndex = index,
            // A launch action can request shuffle without changing the user's default mode.
            mode = (initialMode ?: _state.value.mode).let { if (_state.value.endBehavior.enabled) it.asSimpleMode() else it },
            shuffleOrder = PlayerPlaylistOrder.newShuffleOrder(tracks.size, index),
            isVideo = currentTrack?.isVideo == true,
            bookmarks = currentTrack?.let { bookmarkStore.load(it.path) }.orEmpty(),
            isFavorite = false,
            error = if (tracks.isEmpty()) applicationText(R.string.player_playlist_empty) else null
        )
        refreshFavorite()
        if (tracks.isNotEmpty()) {
            if (initialMode == PlayerPlayMode.SHUFFLE) hint(R.string.player_mode_shuffle)
            PlayerLibraryWriter.submit {
                libraryRepository.saveCurrentQueue(tracks.map { it.path }, index, allowDuplicates = true)
            }
        }
    }

    fun attach(view: PlayerMpvView) {
        if (host === view) return
        host?.let(::detach)
        host = view
        val session = PlayerEngine(view, view.resources)
        engine = session
        refreshSystemVolume()
        engineJob = viewModelScope.launch {
            session.state.collect { sample ->
                if (loadId == 0L || sample.loadId != loadId || engine !== session || _state.value.currentTrack == null) return@collect
                val snapshot = _state.value
                if (sample.error != null) rememberPosition()
                val previouslyPlayed = listening.hasPlayed
                updateListening(sample.ready && sample.playing && sample.error == null && !sample.ended &&
                    playOnAttach && tagEditResume == null)
                _state.value = snapshot.copy(
                    positionMs = sample.positionMs,
                    durationMs = sample.durationMs,
                    playing = sample.playing && sample.error == null && !sample.ended,
                    ready = sample.ready,
                    error = sample.error,
                    loudness = if (snapshot.showLoudnessWaveform && !snapshot.muted && !volumeController.snapshot.muted)
                        sample.loudness else 0f,
                    loudnessSampleId = sample.loudnessSampleId,
                    subtitleText = sample.subtitleText,
                    subtitleCueTiming = sample.subtitleCueTiming,
                    selectedSubtitleId = sample.selectedSubtitleId.ifBlank { "none" },
                    subtitleOptions = listOf(SUBTITLE_OFF) + sample.subtitleTracks + externalSubtitles.filter { external ->
                        sample.subtitleTracks.none { it.id == external.id }
                    }
                )
                refreshLyricLines()
                if (sample.ready && !snapshot.ready) saveMainSession()
                if (!previouslyPlayed && listening.hasPlayed) rememberPosition()
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
                resumeAt = resumeAt, subtitle = subtitle, preservePlayback = true)
        }
        tickerJob = viewModelScope.launch {
            var lastSave = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(250)
                refreshSystemVolume()
                tickTimers()
                val now = SystemClock.elapsedRealtime()
                if (now - lastSave >= 5_000L) {
                    rememberPosition()
                    flushListeningStats()
                    lastSave = now
                }
            }
        }
    }

    fun detach(view: PlayerMpvView) {
        if (host !== view) return
        cancelNextQueue()
        detachedPosition = _state.value.positionMs
        detachedSubtitle = _state.value.subtitleOptions.firstOrNull { it.id == _state.value.selectedSubtitleId }
        stopListening()
        rememberPosition()
        engineJob?.cancel()
        tickerJob?.cancel()
        lyricLoadJob?.cancel()
        lyricRequest = null
        engine?.release()
        engine = null
        host = null
        audioFocus.abandon()
        _state.value = _state.value.copy(ready = false, playing = false, loudness = 0f, seekPreviewMs = null,
            volumeGestureActive = false, subtitleCueTiming = null)
    }

    fun togglePlay() { if (_state.value.playing) pause() else play() }

    fun play() {
        cancelNextQueue()
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
        val current = _state.value
        if (trackEnded || (current.ready && current.durationMs > 0L && current.positionMs >= current.durationMs)) {
            stopListening()
            _state.value.currentTrack?.let { selectListening(it.path, preservePlayback = false) }
            trackEnded = false
            handledEndId = -1L
            engine?.seekTo(0L)
        }
        engine?.play()
    }

    fun pause() {
        cancelNextQueue()
        playOnAttach = false
        stopListening()
        engine?.pause()
        _state.value = _state.value.copy(playing = false, loudness = 0f)
        audioFocus.abandon()
        rememberPosition()
    }

    /** A drag is only a preview; one seek is sent on commit. */
    fun seekTo(positionMs: Long, fromGesture: Boolean = false) {
        cancelNextQueue()
        val current = _state.value
        if (current.currentTrack == null) return
        val target = positionMs.coerceIn(0L, current.durationMs.coerceAtLeast(0L))
        if (fromGesture) {
            _state.value = current.copy(seekPreviewMs = target)
            return
        }
        if (trackEnded) {
            stopListening()
            current.currentTrack?.let { selectListening(it.path, preservePlayback = false) }
        }
        trackEnded = false
        handledEndId = -1L
        engine?.seekTo(target)
        _state.value = current.copy(positionMs = target, seekPreviewMs = null, loudness = 0f,
            subtitleCueTiming = current.subtitleCueTiming?.takeIf { target in it })
        saveMainSession()
    }

    fun clearSeekPreview() { _state.value = _state.value.copy(seekPreviewMs = null) }

    fun skipBy(deltaMs: Long) {
        if (!_state.value.ready) return
        seekTo(_state.value.positionMs + deltaMs)
        hint(if (deltaMs < 0) R.string.player_seek_back else R.string.player_seek_forward)
    }

    fun next() {
        cancelNextQueue()
        val current = _state.value
        if (current.movesBetweenQueues) {
            val target = current.adjacentTrack(forward = true)
            if (target != null) jumpTo(target) else advanceToNextQueue(current, autoplay = true, naturalEnd = false)
            return
        }
        val target = PlayerPlaylistOrder.manualNext(current.currentIndex, current.playlist.size, current.mode, current.shuffleOrder)
        if (target != null) jumpTo(target) else hint(R.string.player_queue_end)
    }

    fun previous() {
        cancelNextQueue()
        val current = _state.value
        if (current.positionMs > 5_000L) { seekTo(0L); return }
        if (current.movesBetweenQueues) {
            val target = current.adjacentTrack(forward = false)
            if (target != null) jumpTo(target) else advanceToNextQueue(current, autoplay = true, forward = false, naturalEnd = false)
            return
        }
        val target = PlayerPlaylistOrder.manualPrevious(current.currentIndex, current.playlist.size, current.mode, current.shuffleOrder)
        if (target != null) jumpTo(target) else seekTo(0L)
    }

    fun playIndex(index: Int) {
        cancelNextQueue()
        if (index !in _state.value.playlist.indices) return
        if (index == _state.value.currentIndex) togglePlay() else jumpTo(index)
    }

    fun playQueueEntry(entryId: String) {
        val index = _state.value.playlist.indexOfFirst { it.queueEntryId == entryId }
        if (index >= 0) playIndex(index)
    }

    /** Appending is deliberately occurrence-based: the same file may be queued repeatedly. */
    fun appendQueueTracks(tracks: List<PlayerTrack>) {
        addQueueTracks(tracks, afterCurrent = false)
    }

    fun enqueueNextTracks(tracks: List<PlayerTrack>) {
        addQueueTracks(tracks, afterCurrent = true)
    }

    private fun addQueueTracks(tracks: List<PlayerTrack>, afterCurrent: Boolean) {
        val added = tracks.filter { it.file.isFile }.map { it.newQueueEntry() }
        if (added.isEmpty()) return
        if (_state.value.queueVersion == 0L) {
            val generation = appendGeneration
            viewModelScope.launch {
                try {
                    val saved = PlayerLibraryWriter.execute { libraryRepository.getCurrentQueue() }
                    if (generation != appendGeneration) return@launch
                    if (_state.value.queueVersion == 0L) {
                        val valid = saved.first.withIndex().filter { it.value.file.isFile }
                        val restored = valid.map { PlayerTrack(it.value.path, it.value.displayTitle, it.value.isVideo) }
                        val savedIndex = valid.indexOfFirst { it.index == saved.second }.coerceAtLeast(0)
                        val edit = if (afterCurrent && restored.isNotEmpty()) PlayerQueueEdits.insertNext(
                            restored, savedIndex, PlayerPlaylistOrder.newShuffleOrder(restored.size, savedIndex), added
                        ) else null
                        // Restore the dormant queue without starting playback or opening the player.
                        playOnAttach = false
                        configured = true
                        configureQueue(edit?.playlist ?: (restored + added), edit?.currentIndex ?: savedIndex,
                            null, null, allowDuplicates = true)
                        if (edit != null) _state.value = _state.value.copy(shuffleOrder = edit.shuffleOrder)
                    } else addQueueTracks(added, afterCurrent)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    hint(R.string.error)
                }
            }
            return
        }
        val current = _state.value
        if (current.playlist.isEmpty()) {
            playOnAttach = false
            configureQueue(added, 0, null, null, allowDuplicates = true)
            return
        }
        cancelNextQueue()
        val edit = if (afterCurrent) PlayerQueueEdits.insertNext(current.playlist, current.currentIndex,
            current.shuffleOrder, added) else PlayerQueueEdit(current.playlist + added, current.currentIndex,
            current.shuffleOrder + (current.playlist.size until current.playlist.size + added.size))
        _state.value = current.copy(playlist = edit.playlist, currentIndex = edit.currentIndex, shuffleOrder = edit.shuffleOrder)
        persistEditedQueue(edit)
    }

    fun moveQueueEntry(fromEntryId: String, toEntryId: String) {
        val current = _state.value
        val edit = PlayerQueueEdits.moveEntries(current.playlist, current.currentIndex, current.shuffleOrder,
            fromEntryId, toEntryId) ?: return
        cancelNextQueue()
        _state.value = current.copy(playlist = edit.playlist, currentIndex = edit.currentIndex,
            shuffleOrder = edit.shuffleOrder)
        persistEditedQueue(edit)
    }

    /** Reordering other queue rows does not reload the currently playing media. */
    fun moveQueueTrack(fromPath: String, toPath: String) {
        val current = _state.value
        val edit = PlayerQueueEdits.move(current.playlist, current.currentIndex, current.shuffleOrder,
            fromPath, toPath) ?: return
        cancelNextQueue()
        _state.value = current.copy(playlist = edit.playlist, currentIndex = edit.currentIndex,
            shuffleOrder = edit.shuffleOrder)
        persistEditedQueue(edit)
    }

    /** Removes queue entries only; files, favorites and source playlists remain untouched. */
    fun removeFromCurrentQueue(paths: Set<String>) {
        if (paths.isEmpty()) return
        val selected = paths.toSet()
        if (_state.value.queueVersion > 0L) {
            removeQueueTracks(selected)
            return
        }
        val generation = appendGeneration
        viewModelScope.launch {
            try {
                // A dormant queue is edited in storage without mounting a player or choosing
                // a new playback session. Keep this read/write together in the ordered writer.
                val handledInStorage = PlayerLibraryWriter.execute {
                    when {
                        generation != appendGeneration -> true
                        _state.value.queueVersion > 0L -> false
                        else -> {
                            val saved = libraryRepository.getCurrentQueue()
                            val tracks = saved.first.map { PlayerTrack(it.path, it.displayTitle, it.isVideo) }
                            PlayerQueueEdits.remove(tracks, saved.second, tracks.indices.toList(), selected)?.let { edit ->
                                libraryRepository.saveCurrentQueue(edit.playlist.map { it.path }, edit.currentIndex,
                                    allowDuplicates = true)
                            }
                            true
                        }
                    }
                }
                if (!handledInStorage && generation == appendGeneration) removeQueueTracks(selected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                hint(R.string.error)
            }
        }
    }

    /** Removes queue entries only; files, favorites and source playlists remain untouched. */
    fun removeQueueTracks(paths: Set<String>) {
        val current = _state.value
        val edit = PlayerQueueEdits.remove(current.playlist, current.currentIndex, current.shuffleOrder,
            paths) ?: return
        applyQueueRemoval(current, edit)
    }

    fun removeQueueEntries(entryIds: Set<String>) {
        val current = _state.value
        val edit = PlayerQueueEdits.removeEntries(current.playlist, current.currentIndex, current.shuffleOrder,
            entryIds) ?: return
        applyQueueRemoval(current, edit)
    }

    private fun applyQueueRemoval(current: PlayerUiState, edit: PlayerQueueEdit) {
        cancelNextQueue()
        val replacement = edit.playlist.getOrNull(edit.currentIndex)
        if (replacement != null && replacement.queueEntryId == current.currentTrack?.queueEntryId) {
            _state.value = current.copy(playlist = edit.playlist, currentIndex = edit.currentIndex,
                shuffleOrder = edit.shuffleOrder)
            persistEditedQueue(edit)
            return
        }

        val autoplay = playOnAttach && !scheduledStart.active
        rememberPosition()
        stopListening()
        cancelScheduledStart()
        endVolumeGesture()
        closeTrackInfoDialog()
        closePlaylistPickerDialog()
        tagEditResume = null
        detachedPosition = 0L
        detachedSubtitle = null
        trackEnded = false
        handledEndId = -1L
        subtitleImportVersion++
        subtitleImportJob?.cancel()
        lyricLoadJob?.cancel()
        lyricRequest = null
        externalSubtitles = emptyList()
        _state.value = _state.value.copy(
            playlist = edit.playlist, currentIndex = edit.currentIndex, shuffleOrder = edit.shuffleOrder,
            positionMs = 0L, durationMs = 0L, ready = false, playing = false, error = null,
            isVideo = replacement?.isVideo == true, bookmarks = emptyList(), isFavorite = false,
            seekPreviewMs = null, loudness = 0f, loudnessSampleId = 0L,
            lyricLines = emptyList(), lyricsLoading = false, subtitleOptions = emptyList(),
            subtitleText = "", subtitleCueTiming = null, selectedSubtitleId = "none", showEndBehaviorDialog = false
        )
        playOnAttach = replacement != null && autoplay
        if (replacement == null) {
            // Keep queueVersion so both list surfaces see the edited empty queue before the DB write.
            loadId++
            loadedTrackPath = null
            engine?.stop()
            audioFocus.abandon()
            clearSleepTimer()
            _expanded.value = false
            _state.value = _state.value.copy(queueSource = null, controlsLocked = false,
                lyricsVisible = false, gestureHint = null)
            clearMainSession()
            persistEditedQueue(edit)
        } else {
            refreshFavorite()
            val hasEngine = engine != null
            loadCurrent(resume = false, autoplay = autoplay)
            if (!hasEngine) persistEditedQueue(edit)
        }
    }

    private fun persistEditedQueue(edit: PlayerQueueEdit) {
        saveMainSession()
        val paths = edit.playlist.map { it.path }
        PlayerLibraryWriter.submit { libraryRepository.saveCurrentQueue(paths, edit.currentIndex, allowDuplicates = true) }
    }

    private fun jumpTo(index: Int) {
        if (index !in _state.value.playlist.indices) return
        rememberPosition()
        cancelScheduledStart()
        _state.value = _state.value.copy(currentIndex = index)
        loadCurrent(resume = _state.value.resumePlayback, autoplay = true)
    }

    private fun loadCurrent(
        resume: Boolean,
        autoplay: Boolean,
        resumeAt: Long? = null,
        subtitle: PlayerSubtitleOption? = null,
        preservePlayback: Boolean = false
    ) {
        cancelNextQueue()
        stopListening()
        refreshSystemVolume()
        val current = _state.value
        val track = current.currentTrack ?: return
        val session = engine ?: return
        val app = getApplication<Application>()
        val saved = resumeAt ?: if (resume) preferences.lastPosition(track.path) else 0L
        if (loadedTrackPath != track.path) {
            volumeGesture.reset()
            loadedTrackPath = track.path
            subtitleImportVersion++
            subtitleImportJob?.cancel()
        }
        loadId++
        trackEnded = false
        selectListening(track.path, preservePlayback)
        closeTrackInfoDialog()
        closePlaylistPickerDialog()
        externalSubtitles = (FileUtils.getPossibleSubtitleFiles(track.file).filter { it.isFile }.map { file ->
            PlayerSubtitleOption("external:${file.absolutePath}", file.name, PlayerSubtitleKind.EXTERNAL, path = file.absolutePath)
        } + pickedSubtitles[track.path].orEmpty().filter { File(it.path).isFile }).distinctBy { it.id }
        lyricLoadJob?.cancel()
        lyricRequest = null
        _state.value = current.copy(
            positionMs = 0L, durationMs = 0L, ready = false, playing = false, error = null,
            isVideo = track.isVideo, subtitleText = "", subtitleCueTiming = null, selectedSubtitleId = "none",
            muted = track.isVideo && current.muted,
            controlsLocked = current.controlsLocked && track.isVideo,
            controlsVisible = if (current.controlsLocked && !track.isVideo) true else current.controlsVisible,
            subtitleOptions = listOf(SUBTITLE_OFF) + externalSubtitles,
            lyricLines = emptyList(), lyricsLoading = false,
            lyricsVisible = current.lyricsVisible && (!track.isVideo || current.audioMode),
            bookmarks = bookmarkStore.load(track.path), seekPreviewMs = null,
            loudness = 0f, loudnessSampleId = 0L,
            volumeGestureActive = false,
            isFavorite = false,
            trackInfo = null,
            showTrackInfoDialog = false,
            trackInfoLoading = false,
            showPlaylistPickerDialog = false,
            userPlaylists = emptyList(),
            trackContainedPlaylistIds = emptySet()
        )
        refreshFavorite()
        val shouldPlay = autoplay && audioFocus.request()
        if (!shouldPlay) audioFocus.abandon()
        playOnAttach = shouldPlay
        saveMainSession(saved)
        session.start(File(app.filesDir, "player/config"), app.cacheDir, track.file, shouldPlay, loadId,
            fallbackSubtitle = externalSubtitles.firstOrNull(),
            audioOnly = track.isVideo && current.audioMode, mirrored = track.isVideo && current.mirrored,
            muted = track.isVideo && current.muted)
        session.setSpeed(current.speed)
        session.setAudioFilter(PlayerEqualizer.afFilter(current.equalizer))
        session.setLoudnessEnabled(current.showLoudnessWaveform && !track.isVideo)
        session.setVolume(volumeController.snapshot.softwarePercent)
        session.setSubtitleFontSize(current.subtitleFontSize)
        if (saved > 0L) session.seekTo(saved)
        val restoredSubtitle = subtitle?.takeIf {
            it.kind != PlayerSubtitleKind.EXTERNAL || it.id.startsWith("embedded:") ||
                (File(it.path).isFile && File(it.path).canRead())
        } ?: pickedSubtitles[track.path]?.lastOrNull { picked -> externalSubtitles.any { it.id == picked.id } }
        restoredSubtitle?.let(session::selectSubtitle)
        val queuePaths = current.playlist.map { it.path }
        val queueIdx = current.currentIndex
        PlayerLibraryWriter.submit {
            libraryRepository.saveCurrentQueue(queuePaths, queueIdx, allowDuplicates = true)
        }
    }

    private fun onTrackEnded() {
        val current = _state.value
        stopListening()
        current.currentTrack?.let { preferences.saveLastPosition(it.path, 0L) }
        trackEnded = true
        rememberPosition()
        if (!playOnAttach) return
        if (PlayerSleepTimerPolicy.isExpired(current.sleepTimer, SystemClock.elapsedRealtime())) {
            tickTimers()
            return
        }
        if (current.endBehavior.enabled) {
            val decision = PlayerEndBehaviorPolicy.decide(current.currentIndex, current.playlist.size,
                current.shuffleOrder, current.mode, current.endBehavior)
            if (decision.moveToNextQueue) advanceToNextQueue(current, decision.autoplay)
            else applyEndDecision(decision)
            return
        }
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

    private fun applyEndDecision(decision: PlayerEndDecision) {
        val current = _state.value
        _state.value = current.copy(currentIndex = decision.targetIndex ?: current.currentIndex,
            shuffleOrder = decision.newShuffleOrder ?: current.shuffleOrder)
        if (decision.targetIndex != null) {
            loadCurrent(resume = false, autoplay = decision.autoplay)
        } else {
            playOnAttach = false
            audioFocus.abandon()
        }
        if (decision.targetIndex == null) hint(R.string.player_queue_end)
    }

    private fun advanceToNextQueue(ended: PlayerUiState, autoplay: Boolean, forward: Boolean = true,
        naturalEnd: Boolean = true) {
        cancelNextQueue()
        if (!naturalEnd) cancelScheduledStart()
        if (autoplay && !audioFocus.request()) { pause(); return }
        rememberPosition()
        stopListening()
        playOnAttach = false
        engine?.pause()
        _state.value = _state.value.copy(playing = false, loudness = 0f)
        val version = nextQueueVersion
        val generation = loadId
        nextQueueJob = viewModelScope.launch {
            val queue = try {
                withContext(Dispatchers.IO) {
                    ended.queueSource?.let {
                        if (forward) queueSourceResolver.next(it, ended.endBehavior.wrapQueues)
                        else queueSourceResolver.previous(it, ended.endBehavior.wrapQueues)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            ensureActive()
            if (version != nextQueueVersion || generation != loadId || engine == null ||
                _state.value.queueSource != ended.queueSource || _state.value.endBehavior != ended.endBehavior) return@launch
            nextQueueJob = null
            if (PlayerSleepTimerPolicy.isExpired(_state.value.sleepTimer, SystemClock.elapsedRealtime())) {
                tickTimers()
                return@launch
            }
            if (queue == null) {
                if (naturalEnd) applyEndDecision(PlayerEndBehaviorPolicy.decide(ended.currentIndex,
                    ended.playlist.size, ended.shuffleOrder, ended.mode,
                    ended.endBehavior.copy(queueAction = PlayerQueueEndAction.STOP)))
                else {
                    if (ended.playing) play()
                    else audioFocus.abandon()
                    hint(R.string.player_queue_end)
                }
            } else {
                val index = if (ended.mode == PlayerPlayMode.SHUFFLE) queue.tracks.indices.random()
                    else if (forward) 0 else queue.tracks.lastIndex
                configureQueue(queue.tracks, index, ended.mode, queue.source)
                if (!forward && ended.mode == PlayerPlayMode.SHUFFLE) {
                    val current = _state.value
                    _state.value = current.copy(shuffleOrder = current.shuffleOrder.filterNot { it == index } + index)
                }
                loadCurrent(resume = false, autoplay = autoplay)
                hint(queue.title)
            }
        }
    }

    private fun cancelNextQueue() {
        nextQueueVersion++
        if (nextQueueJob != null) audioFocus.abandon()
        nextQueueJob?.cancel()
        nextQueueJob = null
    }

    private fun rememberPosition() {
        val current = _state.value
        val track = current.currentTrack ?: return
        saveMainSession()
        if (!current.ready || current.error != null || !listening.hasPlayed || track.path != listening.path) return
        val completed = trackEnded || (current.durationMs > 0L && current.positionMs >= current.durationMs - 1_000L)
        val position = if (completed) 0L else current.positionMs
        if (current.resumePlayback) preferences.saveLastPosition(track.path, position)
        val progress = HistoryProgress(listening.playbackId, position, current.durationMs)
        if (lastHistoryProgress == progress) return
        val newPlayback = lastHistoryProgress?.playbackId != progress.playbackId
        lastHistoryProgress = progress
        val playedAt = System.currentTimeMillis()
        PlayerLibraryWriter.submit {
            libraryRepository.recordPlaybackProgress(track.path, progress.playbackId,
                progress.durationMs, progress.positionMs, playedAt)
            if (newPlayback && libraryRepository.isIndexedTrack(track.path)) {
                libraryRepository.indexTrack(track.path, progress.durationMs, forceFileRevision = false)
            }
        }
    }

    private fun saveMainSession(initialPositionMs: Long? = null) {
        val store = mainSessionStore ?: return
        val current = _state.value
        if (current.currentTrack == null || !preferences.playerModeEnabled) return
        if (current.error != null) return
        val currentPosition = initialPositionMs ?: current.positionMs.takeIf { current.ready } ?: detachedPosition ?: return
        val position = if (!current.resumePlayback || trackEnded) 0L else currentPosition
        val session = MainPlayerSession(current.playlist.map { it.path }, current.currentIndex, current.mode, position,
            current.queueSource, current.audioMode)
        if (session == savedMainSession) return
        store.save(session)
        savedMainSession = session
    }

    private fun clearMainSession() {
        mainSessionStore?.clear()
        savedMainSession = null
    }

    private fun selectListening(path: String, preservePlayback: Boolean) {
        persistListening(listening.select(path, preservePlayback, SystemClock.elapsedRealtime(), System.currentTimeMillis()))
    }

    private fun updateListening(active: Boolean) {
        persistListening(listening.update(active, SystemClock.elapsedRealtime(), System.currentTimeMillis()))
    }

    private fun stopListening() = updateListening(false)

    private fun flushListeningStats() {
        persistListening(listening.flush(SystemClock.elapsedRealtime(), System.currentTimeMillis()))
    }

    private fun persistListening(intervals: List<PlayerListeningInterval>) {
        intervals.forEach { interval ->
            PlayerLibraryWriter.submit {
                libraryRepository.recordListeningInterval(interval.path, interval.playbackId,
                    interval.startedAtMs, interval.durationMs)
            }
        }
    }

    fun saveProgress() {
        flushListeningStats()
        rememberPosition()
    }

    /** Release the media file before the existing tag editor updates it. */
    fun prepareTagEditing(): String? {
        val current = _state.value
        if (current.controlsLocked) return null
        val track = current.currentTrack ?: return null
        if (tagEditResume != null) return null
        cancelNextQueue()
        stopListening()
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
            val duration = _state.value.durationMs
            PlayerLibraryWriter.submit {
                PlayerTrackMetadataReader.invalidate(saved.path)
                libraryRepository.refreshStoredTrack(saved.path, duration)
            }
            loadCurrent(resume = false, autoplay = playOnAttach && !scheduledStart.active,
                resumeAt = saved.positionMs, subtitle = saved.subtitle, preservePlayback = true)
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

    /** Apply imported settings when returning from settings, preserving the current queue and position. */
    fun refreshRestoredSettings() {
        val revision = SettingsBackupManager.playerSettingsRevision.value
        if (settingsRevision == revision) return
        settingsRevision = revision
        val current = _state.value
        val mode = preferences.defaultPlayMode
        val behavior = preferences.endBehavior
        val next = current.copy(
            mode = mode,
            endBehavior = behavior,
            speed = preferences.defaultSpeed,
            equalizer = preferences.equalizer,
            showLoudnessWaveform = preferences.showLoudnessWaveform,
            resumePlayback = preferences.resumePlayback,
            subtitleFontSize = preferences.subtitleFontSize,
            audioSubtitleLineSpacing = preferences.audioSubtitleLineSpacing,
            audioSubtitleAlignment = preferences.audioSubtitleAlignment,
            shuffleOrder = if (mode == PlayerPlayMode.SHUFFLE && current.mode != mode)
                PlayerPlaylistOrder.newShuffleOrder(current.playlist.size, current.currentIndex)
            else current.shuffleOrder
        )
        if (current.mode != mode || current.endBehavior != behavior) cancelNextQueue()
        if (current.speed != next.speed) engine?.setSpeed(next.speed)
        if (current.equalizer != next.equalizer) engine?.setAudioFilter(PlayerEqualizer.afFilter(next.equalizer))
        if (current.subtitleFontSize != next.subtitleFontSize) engine?.setSubtitleFontSize(next.subtitleFontSize)
        if (current.showLoudnessWaveform != next.showLoudnessWaveform) {
            engine?.setLoudnessEnabled(next.showLoudnessWaveform && !next.isVideo)
        }
        _state.value = next.copy(loudness = if (next.showLoudnessWaveform) current.loudness else 0f)
    }

    fun setSpeed(speed: Float) {
        if (!speed.isFinite()) return
        val value = (speed.coerceIn(PlayerPreferences.MIN_SPEED, PlayerPreferences.MAX_SPEED) * 100).roundToInt() / 100f
        engine?.setSpeed(value)
        preferences.defaultSpeed = value
        _state.value = _state.value.copy(speed = value)
    }

    fun setMode(mode: PlayerPlayMode) {
        applyEndBehavior(mode, _state.value.endBehavior)
    }

    fun setAdvancedPlaybackEnabled(enabled: Boolean) {
        applyEndBehavior(_state.value.mode, _state.value.endBehavior.copy(enabled = enabled))
    }

    fun applyEndBehavior(mode: PlayerPlayMode, behavior: PlayerEndBehavior) {
        cancelNextQueue()
        val current = _state.value
        val resolvedMode = if (behavior.enabled) mode.asSimpleMode() else mode
        preferences.savePlaybackBehavior(resolvedMode, behavior)
        _state.value = current.copy(mode = resolvedMode, endBehavior = behavior, showEndBehaviorDialog = false, shuffleOrder =
            if (resolvedMode == PlayerPlayMode.SHUFFLE && current.mode != resolvedMode)
                PlayerPlaylistOrder.newShuffleOrder(current.playlist.size, current.currentIndex)
            else current.shuffleOrder)
        saveMainSession()
    }

    fun openEndBehaviorDialog() {
        if (_state.value.controlsLocked || !_state.value.endBehavior.enabled) return
        closeTrackInfoDialog()
        closePlaylistPickerDialog()
        _state.value = _state.value.copy(showEndBehaviorDialog = true, panel = PlayerPanel.NONE, controlsVisible = true)
    }

    fun closeEndBehaviorDialog() {
        _state.value = _state.value.copy(showEndBehaviorDialog = false)
    }

    fun cycleMode() {
        val modes = if (_state.value.endBehavior.enabled) PlayerPlayMode.simpleModes else PlayerPlayMode.entries
        val mode = modes[(modes.indexOf(_state.value.mode) + 1) % modes.size]
        setMode(mode)
        hint(when (mode) {
            PlayerPlayMode.SEQUENCE -> R.string.player_mode_sequence
            PlayerPlayMode.REPEAT_ONE -> R.string.player_mode_repeat_one
            PlayerPlayMode.REPEAT_ALL -> R.string.player_mode_repeat_all
            PlayerPlayMode.SHUFFLE -> R.string.player_mode_shuffle
        })
    }

    fun setVolume(percent: Int) {
        volumeGesture.reset()
        applyVolume(volumeController.setVolume(percent))
    }

    fun toggleMute() {
        val current = _state.value
        if (!current.isVideo) return
        val muted = !current.muted
        engine?.setMuted(muted)
        _state.value = current.copy(muted = muted, loudness = 0f)
    }

    fun toggleMirror() {
        val current = _state.value
        if (!current.showsVideo) return
        val mirrored = !current.mirrored
        engine?.setMirrored(mirrored)
        _state.value = current.copy(mirrored = mirrored)
    }

    fun toggleAudioMode() {
        val current = _state.value
        if (!current.isVideo) return
        val audioMode = !current.audioMode
        engine?.setAudioOnly(audioMode)
        _state.value = current.copy(audioMode = audioMode, lyricsVisible = false)
        saveMainSession()
    }

    /** Read before a drag as well as periodically, so hardware keys and system sliders stay in sync. */
    fun refreshSystemVolume(resetBoost: Boolean = false): Int {
        val previous = volumeController.snapshot
        val current = if (resetBoost) volumeController.resetBoost() else volumeController.refresh()
        if (resetBoost || current != previous) volumeGesture.reset()
        if (current != previous) {
            applyVolume(current)
        }
        return current.percent
    }

    private fun applyVolume(volume: PlayerVolumeSnapshot) {
        engine?.setVolume(volume.softwarePercent)
        _state.value = _state.value.copy(
            volumePercent = volume.percent,
            loudness = if (volume.muted) 0f else _state.value.loudness
        )
        if (_state.value.volumeGestureActive) showVolumeHint(volume)
    }

    fun setVolumeFromGesture(percent: Int) {
        _state.value = _state.value.copy(volumeGestureActive = true)
        setVolume(percent)
    }

    fun beginVolumeGesture() {
        volumeGesture.begin(refreshSystemVolume())
    }

    fun adjustVolumeGesture(deltaPercent: Float): PlayerVolumeGestureResult {
        _state.value = _state.value.copy(volumeGestureActive = true)
        val result = volumeGesture.adjust(deltaPercent, _state.value.volumePercent) { requested ->
            val accepted = volumeController.setVolume(requested)
            applyVolume(accepted)
            accepted.percent
        }
        if (result.blockedAtUnity) {
            val text = applicationText(R.string.player_volume_boost_warning)
            if (_state.value.gestureHint?.text != text) {
                _state.value = _state.value.copy(gestureHint = PlayerGestureHint(text, ++hintToken, R.drawable.ic_player_volume))
            }
        }
        return result
    }

    private fun showVolumeHint(volume: PlayerVolumeSnapshot) {
        val text = getApplication<Application>().getString(
            if (volume.softwarePercent > 100) R.string.player_volume_boost_percent else R.string.player_volume_percent,
            volume.percent
        )
        _state.value = _state.value.copy(
            gestureHint = PlayerGestureHint(text, ++hintToken, R.drawable.ic_player_volume)
        )
    }

    fun endVolumeGesture(completed: Boolean = false) {
        volumeGesture.end(completed)
        if (_state.value.volumeGestureActive) _state.value = _state.value.copy(volumeGestureActive = false)
    }

    fun setResumePlayback(enabled: Boolean) {
        preferences.resumePlayback = enabled
        _state.value = _state.value.copy(resumePlayback = enabled)
        if (enabled) rememberPosition()
        else saveMainSession()
    }

    fun setShowLoudnessWaveform(enabled: Boolean) {
        preferences.showLoudnessWaveform = enabled
        engine?.setLoudnessEnabled(enabled && !_state.value.isVideo)
        _state.value = _state.value.copy(showLoudnessWaveform = enabled, loudness = 0f)
    }

    fun setSubtitleFontSize(size: Int) {
        val value = size.coerceIn(PlayerPreferences.MIN_SUBTITLE_FONT_SIZE, PlayerPreferences.MAX_SUBTITLE_FONT_SIZE)
        preferences.subtitleFontSize = value
        engine?.setSubtitleFontSize(value)
        _state.value = _state.value.copy(subtitleFontSize = value)
    }

    fun setAudioSubtitleLineSpacing(spacing: Int) {
        val value = spacing.coerceIn(PlayerPreferences.MIN_AUDIO_SUBTITLE_LINE_SPACING,
            PlayerPreferences.MAX_AUDIO_SUBTITLE_LINE_SPACING)
        preferences.audioSubtitleLineSpacing = value
        _state.value = _state.value.copy(audioSubtitleLineSpacing = value)
    }

    fun setAudioSubtitleAlignment(alignment: PlayerSubtitleAlignment) {
        preferences.audioSubtitleAlignment = alignment
        _state.value = _state.value.copy(audioSubtitleAlignment = alignment)
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
        val snapped = PlayerEqualizer.clamp(gain)
        if (gains[index] == snapped) return // dragging within one step must not restart the audio filter
        gains[index] = snapped
        setEqualizer(_state.value.equalizer.copy(gains = gains))
    }

    fun applyPreset(preset: PlayerEqualizer.Preset) {
        setEqualizer(_state.value.equalizer.copy(enabled = true, gains = preset.gains))
    }

    fun selectSubtitle(option: PlayerSubtitleOption) {
        if (_state.value.subtitleOptions.none { it.id == option.id }) return
        subtitleImportVersion++
        subtitleImportJob?.cancel()
        engine?.selectSubtitle(option)
    }

    fun toggleLyrics() = setLyricsVisible(!_state.value.lyricsVisible)

    fun setDetailedLyricsInForeground(visible: Boolean) {
        _detailedLyricsInForeground.value = visible
    }

    fun setLyricsVisible(visible: Boolean) {
        if (_state.value.showsVideo) return
        _state.value = _state.value.copy(lyricsVisible = visible)
        if (visible) refreshLyricLines()
    }

    /** Validate a picked file before changing the working subtitle selection. */
    fun importSubtitle(file: File, expectedTrackPath: String? = null) {
        val current = _state.value
        val track = current.currentTrack ?: return
        if (expectedTrackPath != null && expectedTrackPath != track.path) return
        subtitleImportJob?.cancel()
        val requestVersion = ++subtitleImportVersion
        subtitleImportJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    require(file.isFile && file.canRead()) { "Cannot read selected subtitle" }
                    PlayerLyricTimeline.read(file, 0L)
                }
                ensureActive()
                if (subtitleImportVersion != requestVersion || _state.value.currentTrack?.path != track.path) return@launch
                val option = PlayerSubtitleOption("external:${file.absolutePath}", file.name,
                    PlayerSubtitleKind.EXTERNAL, path = file.absolutePath)
                pickedSubtitles[track.path] = (pickedSubtitles[track.path].orEmpty() + option).distinctBy { it.id }
                externalSubtitles = (externalSubtitles + option).distinctBy { it.id }
                _state.value = _state.value.copy(subtitleOptions =
                    (_state.value.subtitleOptions + option).distinctBy { it.id }, panel = PlayerPanel.NONE)
                engine?.selectSubtitle(option) ?: run { detachedSubtitle = option }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (subtitleImportVersion == requestVersion && _state.value.currentTrack?.path == track.path)
                    hint(R.string.player_subtitle_read_failed)
            }
        }
    }

    private fun refreshLyricLines() {
        val current = _state.value
        val option = current.subtitleOptions.firstOrNull { it.id == current.selectedSubtitleId }
        val request = option?.takeIf { it.kind == PlayerSubtitleKind.EXTERNAL && it.path.isNotBlank() }
            ?.let { LyricRequest(loadId, it.id, it.path, current.durationMs) }
        if (request == lyricRequest) return
        lyricRequest = request
        lyricLoadJob?.cancel()
        _state.value = current.copy(lyricLines = emptyList(), lyricsLoading = request != null)
        if (request == null) return
        lyricLoadJob = viewModelScope.launch {
            val lines = try {
                withContext(Dispatchers.IO) { PlayerLyricTimeline.read(File(request.path), request.durationMs) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            ensureActive()
            if (lyricRequest == request && loadId == request.loadId && engine != null) {
                _state.value = _state.value.copy(lyricLines = lines, lyricsLoading = false)
            }
        }
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

    private fun refreshFavorite() = libraryController.refreshFavorite()
    fun toggleFavorite() = libraryController.toggleFavorite()
    fun openTrackInfoDialog() {
        if (!_state.value.controlsLocked) {
            closeEndBehaviorDialog()
            libraryController.openTrackInfoDialog()
        }
    }
    fun closeTrackInfoDialog() = libraryController.closeTrackInfoDialog()
    fun openPlaylistPickerDialog() {
        if (!_state.value.controlsLocked) {
            closeEndBehaviorDialog()
            libraryController.openPlaylistPickerDialog()
        }
    }
    fun closePlaylistPickerDialog() = libraryController.closePlaylistPickerDialog()
    fun createUserPlaylist(name: String, onCreated: (Long?) -> Unit) = libraryController.createUserPlaylist(name, onCreated)
    fun addCurrentTrackToPlaylists(playlistIds: Set<Long>) = libraryController.addCurrentTrackToPlaylists(playlistIds)

    fun requestFinish() { _finishRequests.value++ }
    fun setVideoControlsLocked(locked: Boolean) {
        val current = _state.value
        if (current.controlsLocked == locked || (locked && !current.isVideo)) return
        if (locked) {
            closeEndBehaviorDialog()
            closeTrackInfoDialog()
            closePlaylistPickerDialog()
        }
        _state.value = _state.value.copy(
            controlsLocked = locked,
            controlsVisible = !locked,
            panel = PlayerPanel.NONE,
            seekPreviewMs = null,
            gestureHint = null
        )
    }
    fun setControlsVisible(visible: Boolean) {
        val current = _state.value
        if (visible && current.controlsLocked) return
        _state.value = current.copy(controlsVisible = visible)
    }
    fun openPanel(panel: PlayerPanel) {
        val current = _state.value
        if (current.controlsLocked) return
        _state.value = current.copy(panel = panel, controlsVisible = true, showEndBehaviorDialog = false)
    }
    fun closePanel() { _state.value = _state.value.copy(panel = PlayerPanel.NONE) }
    fun hint(textRes: Int) { hint(applicationText(textRes)) }
    fun hint(text: String) { _state.value = _state.value.copy(gestureHint = PlayerGestureHint(text, ++hintToken)) }
    fun clearHint() { _state.value = _state.value.copy(gestureHint = null) }
    private fun applicationText(res: Int) = getApplication<Application>().getString(res)

    override fun onCleared() {
        mediaNotifications?.close()
        cancelNextQueue()
        stopListening()
        rememberPosition()
        engineJob?.cancel()
        tickerJob?.cancel()
        lyricLoadJob?.cancel()
        subtitleImportJob?.cancel()
        engine?.release()
        timerAlarm.cancel()
        audioFocus.abandon()
        super.onCleared()
    }

    private companion object {
        val SUBTITLE_OFF = PlayerSubtitleOption("none", "", PlayerSubtitleKind.NONE)
    }
}
