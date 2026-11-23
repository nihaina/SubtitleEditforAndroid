package com.subtitleedit.player

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.AudioAttributes
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.subtitleedit.MainActivity
import com.subtitleedit.R
import com.subtitleedit.library.MediaLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Publishes the existing audio-mode player to Android's media controls and lock screen. */
class PlayerMediaNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSession
    private var mediaSessionId = -1L
    private lateinit var manager: NotificationManager
    private var observer: Job? = null
    private var metadataJob: Job? = null
    private var current: PlayerMediaNotificationState? = null
    private var details: PlayerMediaNotificationMetadata? = null
    private var notificationKey: List<Any?>? = null
    private var notificationJob: Job? = null
    private var lastNotificationAtMs = 0L
    private var foreground = false
    private lateinit var floatingSubtitleWindow: PlayerFloatingSubtitleWindow
    private lateinit var statusBarLyricsWindow: PlayerStatusBarLyricsWindow
    private lateinit var appOps: AppOpsManager
    private val overlayPermissionListener = AppOpsManager.OnOpChangedListener { _, _ ->
        scope.launch { refreshSubtitleWindows() }
    }
    private val preferences by lazy { PlayerPreferences.getInstance(this) }
    private var floatingSubtitleObserver: Job? = null
    private var statusBarLyricsObserver: Job? = null
    private val floatingTrackTitle = MutableStateFlow<Pair<String, String>?>(null)

    private data class FloatingCaption(val sessionId: Long, val text: String?, val playback: PlayerFloatingSubtitlePlayback)

    override fun onCreate() {
        super.onCreate()
        floatingSubtitleWindow = PlayerFloatingSubtitleWindow(
            context = this,
            initialPosition = preferences.floatingSubtitlePosition,
            onPositionChanged = { preferences.floatingSubtitlePosition = it },
            onAction = ::onFloatingSubtitleAction,
            onColor = { target, color ->
                preferences.floatingSubtitleStyle = preferences.floatingSubtitleStyle.withColor(target, color)
            },
            onFontSizeDelta = { delta ->
                val style = preferences.floatingSubtitleStyle
                preferences.floatingSubtitleStyle = style.copy(fontSize = style.fontSize + delta)
            },
        )
        statusBarLyricsWindow = PlayerStatusBarLyricsWindow(this)
        appOps = getSystemService(AppOpsManager::class.java)
        try {
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, overlayPermissionListener)
        } catch (error: SecurityException) {
            Log.w("PlayerMediaNotification", "Unable to watch changes to overlay permission", error)
        }
        manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            getString(R.string.player_media_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.player_media_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        mediaSession = createMediaSession(PlayerMediaNotifications.state.value?.sessionId ?: -1L)
    }

    private fun createMediaSession(sessionId: Long): MediaSession {
        mediaSessionId = sessionId
        return MediaSession(this, "AudioModePlayer").apply {
            setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = PlayerMediaNotifications.dispatch(sessionId, PlayerMediaNotifications.ACTION_PLAY)
                override fun onPause() = PlayerMediaNotifications.dispatch(sessionId, PlayerMediaNotifications.ACTION_PAUSE)
                override fun onSkipToPrevious() = PlayerMediaNotifications.dispatch(sessionId, PlayerMediaNotifications.ACTION_PREVIOUS)
                override fun onSkipToNext() = PlayerMediaNotifications.dispatch(sessionId, PlayerMediaNotifications.ACTION_NEXT)
                override fun onStop() = PlayerMediaNotifications.dispatch(sessionId, PlayerMediaNotifications.ACTION_STOP)
                override fun onSeekTo(pos: Long) = PlayerMediaNotifications.dispatch(sessionId, "seek", pos)
            }, Handler(Looper.getMainLooper()))
            setSessionActivity(openPlayerIntent())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Fulfil the foreground deadline before any metadata or artwork IO, including a close race.
        if (!foreground) {
            val notification = buildNotification(PlayerMediaNotifications.state.value)
            if (Build.VERSION.SDK_INT >= 29) startForeground(PlayerMediaNotifications.NOTIFICATION_ID,
                notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(PlayerMediaNotifications.NOTIFICATION_ID, notification)
            foreground = true
            lastNotificationAtMs = SystemClock.elapsedRealtime()
        }
        val snapshot = PlayerMediaNotifications.state.value
        val sessionId = intent?.getLongExtra(PlayerMediaNotifications.EXTRA_SESSION_ID, -1L) ?: -1L
        if (snapshot != null && sessionId == snapshot.sessionId) intent?.action?.let { action ->
            PlayerMediaNotifications.dispatch(sessionId, action)
        }
        if (observer == null) observer = scope.launch {
            PlayerMediaNotifications.state.collect { state ->
                if (state == null) {
                    clearNotification()
                    stopSelf()
                } else update(state)
            }
        }
        if (floatingSubtitleObserver == null) floatingSubtitleObserver = scope.launch {
            val captions = combine(PlayerMediaNotifications.state, floatingTrackTitle, ::floatingCaption)
                .distinctUntilChanged()
            val appearances = combine(
                captions,
                preferences.floatingSubtitleEnabledChanges,
                preferences.floatingSubtitleStyleChanges,
                preferences.floatingSubtitleLockedChanges,
            ) { caption, enabled, style, locked -> Triple(caption.takeIf { enabled }, style, locked) }
            combine(appearances, PlayerMediaNotifications.subtitleCue, ::Pair)
                .collect { (appearance, cue) ->
                    val (caption, style, locked) = appearance
                    updateFloatingSubtitle(caption, style, locked, cue)
                }
        }
        if (statusBarLyricsObserver == null) statusBarLyricsObserver = scope.launch {
            combine(PlayerMediaNotifications.subtitleCue, preferences.statusBarLyricsChanges, ::Pair)
                .collect { (cue, settings) ->
                    statusBarLyricsWindow.render(cue.takeIf { foreground }, settings)
                }
        }
        return START_NOT_STICKY
    }

    private fun floatingCaption(state: PlayerMediaNotificationState?, title: Pair<String, String>?): FloatingCaption? {
        if (state == null || !state.floatingSubtitleAvailable) return null
        return FloatingCaption(state.sessionId, state.floatingSubtitleText, PlayerFloatingSubtitlePlayback(
            title = title?.takeIf { it.first == state.track.path }?.second ?: state.track.name,
            playing = state.playing,
            hasPrevious = state.hasPrevious,
            hasNext = state.hasNext,
        ))
    }

    private fun refreshSubtitleWindows() {
        if (!foreground) return
        val cue = PlayerMediaNotifications.subtitleCue.value
        val caption = floatingCaption(PlayerMediaNotifications.state.value, floatingTrackTitle.value)
        updateFloatingSubtitle(
            caption.takeIf { preferences.floatingSubtitleEnabled }, preferences.floatingSubtitleStyle, preferences.floatingSubtitleLocked,
            cue,
        )
        statusBarLyricsWindow.render(cue, preferences.statusBarLyrics)
    }

    private fun updateFloatingSubtitle(
        caption: FloatingCaption?, style: PlayerFloatingSubtitleStyle, locked: Boolean, cue: PlayerOverlaySubtitleCue?,
    ) {
        if (foreground && caption != null) {
            val activeCue = cue?.takeIf { it.sessionId == caption.sessionId && it.text == caption.text }
            floatingSubtitleWindow.render(caption.text, style, caption.playback, locked, activeCue)
        } else {
            floatingSubtitleWindow.hide()
        }
    }

    private fun onFloatingSubtitleAction(action: PlayerFloatingSubtitleAction) {
        if (!preferences.floatingSubtitleEnabled || preferences.floatingSubtitleLocked) return
        when (action) {
            PlayerFloatingSubtitleAction.LOCK -> preferences.floatingSubtitleLocked = true
            PlayerFloatingSubtitleAction.CLOSE -> preferences.floatingSubtitleEnabled = false
            PlayerFloatingSubtitleAction.PREVIOUS -> dispatch(PlayerMediaNotifications.ACTION_PREVIOUS)
            PlayerFloatingSubtitleAction.NEXT -> dispatch(PlayerMediaNotifications.ACTION_NEXT)
            PlayerFloatingSubtitleAction.PLAY_PAUSE -> current?.let {
                dispatch(if (it.playing) PlayerMediaNotifications.ACTION_PAUSE else PlayerMediaNotifications.ACTION_PLAY)
            }
            PlayerFloatingSubtitleAction.TOGGLE_CONTROLS,
            PlayerFloatingSubtitleAction.PICK_COLOR,
            PlayerFloatingSubtitleAction.PICK_BACKGROUND_COLOR -> Unit
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshSubtitleWindows()
        statusBarLyricsWindow.refreshLayout()
    }

    private fun dispatch(action: String) {
        current?.let { PlayerMediaNotifications.dispatch(it.sessionId, action) }
    }

    private fun update(state: PlayerMediaNotificationState) {
        if (mediaSessionId != state.sessionId) {
            mediaSession.release()
            mediaSession = createMediaSession(state.sessionId)
            metadataKey = null
        }
        val changedTrack = current?.sessionId != state.sessionId || current?.track?.path != state.track.path
        current = state
        mediaSession.isActive = true
        if (changedTrack) {
            details = null
            floatingTrackTitle.value = null
            metadataJob?.cancel()
            metadataJob = scope.launch {
                observePlayerStoredMedia(state.track.path, MediaLibraryRepository.getInstance(applicationContext))
                    .collectLatest {
                        val loaded = withContext(Dispatchers.IO) {
                            PlayerMediaNotificationMetadata.load(applicationContext, state.track, state.durationMs)
                        }
                        if (current?.sessionId == state.sessionId && current?.track?.path == loaded.path) {
                            details = loaded
                            floatingTrackTitle.value = loaded.path to loaded.title
                            updateMetadata()
                            publishNotification()
                        }
                    }
            }
        }
        updateMetadata()
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (state.hasPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (state.hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (state.ready && state.error == null) actions = actions or PlaybackState.ACTION_SEEK_TO
        val playbackState = when {
            state.error != null -> PlaybackState.STATE_ERROR
            !state.ready -> PlaybackState.STATE_BUFFERING
            state.playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        mediaSession.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(playbackState, state.positionMs, if (state.playing) state.speed else 0f,
                SystemClock.elapsedRealtime()).apply { state.error?.let(::setErrorMessage) }.build())
        publishNotification()
    }

    private var metadataKey: List<Any?>? = null
    private fun updateMetadata() {
        val state = current ?: return
        val info = details
        val duration = state.durationMs.takeIf { it > 0L } ?: info?.durationMs ?: 0L
        val lyric = state.lyricText.forMediaControls()
        val key = listOf(state.track.path, duration, info, lyric)
        if (metadataKey == key) return
        metadataKey = key
        val title = info?.title ?: state.track.name
        val artist = info?.artist.orEmpty()
        mediaSession.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, state.track.path)
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            // Android SystemUI uses the artist field for the secondary text of its media card.
            .putString(MediaMetadata.METADATA_KEY_ARTIST, lyric ?: artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, info?.album.orEmpty())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
            .apply {
                if (lyric != null) {
                    putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                    putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, lyric)
                    if (artist.isNotBlank()) putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, artist)
                    putString(ORIGINAL_ARTIST_KEY, artist)
                }
                info?.artwork?.let {
                    putBitmap(MediaMetadata.METADATA_KEY_ART, it)
                    putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                }
            }.build())
    }

    private fun publishNotification() {
        val state = current ?: return
        if (notificationKey == notificationKeyFor(state) || notificationJob?.isActive == true) return
        // Coalesce loading/metadata/transport bursts so Android never drops the final button state.
        // MediaSession progress and controls above remain immediate.
        notificationJob = scope.launch {
            delay((lastNotificationAtMs + 500L - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            val latest = current ?: return@launch
            if (!foreground) return@launch
            val key = notificationKeyFor(latest)
            if (notificationKey != key) {
                notificationKey = key
                lastNotificationAtMs = SystemClock.elapsedRealtime()
                manager.notify(PlayerMediaNotifications.NOTIFICATION_ID, buildNotification(latest))
            }
        }
    }

    private fun notificationKeyFor(state: PlayerMediaNotificationState): List<Any?> = listOf(
        state.sessionId, state.track.path, state.playing, state.ready, state.error,
        state.hasPrevious, state.hasNext, state.lyricText.forMediaControls(), details)

    private fun buildNotification(state: PlayerMediaNotificationState?): Notification {
        val info = details?.takeIf { it.path == state?.track?.path }
        val playing = state?.playing == true
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(info?.title ?: state?.track?.name ?: getString(R.string.app_name))
            .setContentText(state?.error ?: state?.lyricText.forMediaControls() ?: info?.artist?.takeIf { it.isNotBlank() }
                ?: info?.album?.takeIf { it.isNotBlank() } ?: getString(R.string.player_media_notification_channel))
            .setContentIntent(openPlayerIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false).setOnlyAlertOnce(true).setOngoing(playing)
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken).apply {
                if (state != null) setShowActionsInCompactView(0, 1, 2)
            })
        info?.artwork?.let(builder::setLargeIcon)
        if (state != null) {
            builder.setDeleteIntent(actionIntent(PlayerMediaNotifications.ACTION_STOP, state))
                .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_skip_previous), getString(R.string.player_previous),
                    actionIntent(PlayerMediaNotifications.ACTION_PREVIOUS, state).takeIf { state.hasPrevious }).build())
                .addAction(Notification.Action.Builder(Icon.createWithResource(this,
                    if (playing) R.drawable.ic_video_pause else R.drawable.ic_video_play),
                    getString(if (playing) R.string.player_pause else R.string.player_play),
                    actionIntent(if (playing) PlayerMediaNotifications.ACTION_PAUSE else PlayerMediaNotifications.ACTION_PLAY, state)).build())
                .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_skip_next), getString(R.string.player_next),
                    actionIntent(PlayerMediaNotifications.ACTION_NEXT, state).takeIf { state.hasNext }).build())
                .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), getString(R.string.player_end_stop),
                    actionIntent(PlayerMediaNotifications.ACTION_STOP, state)).build())
        }
        return builder.build()
    }

    private fun actionIntent(action: String, state: PlayerMediaNotificationState): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, PlayerMediaNotificationService::class.java).setAction(action)
            .setData(android.net.Uri.parse("subtitleedit://media/${state.sessionId}/${action.substringAfterLast('.')}"))
            .putExtra(PlayerMediaNotifications.EXTRA_SESSION_ID, state.sessionId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun openPlayerIntent(): PendingIntent = PendingIntent.getActivity(this, 3401,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(PlayerMediaNotifications.EXTRA_OPEN_PLAYER, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun clearNotification() {
        floatingSubtitleWindow.hide()
        statusBarLyricsWindow.hide()
        notificationJob?.cancel()
        metadataJob?.cancel()
        mediaSession.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        manager.cancel(PlayerMediaNotifications.NOTIFICATION_ID)
        foreground = false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        current?.let { PlayerMediaNotifications.closeRemovedTask(it.sessionId) }
        clearNotification()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        appOps.stopWatchingMode(overlayPermissionListener)
        scope.cancel()
        clearNotification()
        mediaSession.release()
        PlayerMediaNotifications.serviceDestroyed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun String?.forMediaControls(): String? = this?.take(MAX_LYRIC_CHARS)?.lineSequence()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.joinToString(" ")
        ?.takeIf(String::isNotEmpty)

    private companion object {
        const val CHANNEL_ID = "audio_mode_playback"
        const val ORIGINAL_ARTIST_KEY = "com.subtitleedit.player.metadata.ORIGINAL_ARTIST"
        const val MAX_LYRIC_CHARS = 500
    }
}
