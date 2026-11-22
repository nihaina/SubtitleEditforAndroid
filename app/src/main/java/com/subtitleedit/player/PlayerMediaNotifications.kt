package com.subtitleedit.player

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal data class PlayerMediaNotificationState(
    val sessionId: Long,
    val track: PlayerTrack,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val ready: Boolean,
    val error: String?,
    val speed: Float,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val lyricText: String? = null,
    val floatingSubtitleText: String? = null,
    val floatingSubtitleAvailable: Boolean = false,
)

/** One main-window session owns the notification; no Activity or second mpv engine is created. */
internal object PlayerMediaNotifications {
    const val NOTIFICATION_ID = 3401
    const val EXTRA_OPEN_PLAYER = "open_audio_player"
    const val EXTRA_SESSION_ID = "media_session_id"
    const val ACTION_PLAY = "com.subtitleedit.player.media.PLAY"
    const val ACTION_PAUSE = "com.subtitleedit.player.media.PAUSE"
    const val ACTION_PREVIOUS = "com.subtitleedit.player.media.PREVIOUS"
    const val ACTION_NEXT = "com.subtitleedit.player.media.NEXT"
    const val ACTION_STOP = "com.subtitleedit.player.media.STOP"

    private val mutableState = MutableStateFlow<PlayerMediaNotificationState?>(null)
    val state = mutableState.asStateFlow()
    private var owner: PlayerMediaNotificationConnection? = null
    private var model: PlayerViewModel? = null
    private var serviceRequested = false
    private var serviceContext: Context? = null
    private var retryAfterMs = 0L
    private var nextSessionId = 0L

    fun newSessionId(): Long = ++nextSessionId

    fun publish(connection: PlayerMediaNotificationConnection, viewModel: PlayerViewModel,
        snapshot: PlayerMediaNotificationState?, context: Context) {
        if (snapshot == null) {
            remove(connection)
            return
        }
        owner = connection
        model = viewModel
        serviceContext = context.applicationContext
        mutableState.value = snapshot
        ensureService()
    }

    private fun ensureService() {
        val context = serviceContext ?: return
        if (state.value == null) return
        if (!serviceRequested && SystemClock.elapsedRealtime() >= retryAfterMs) {
            serviceRequested = true
            try {
                ContextCompat.startForegroundService(context, Intent(context, PlayerMediaNotificationService::class.java))
            } catch (error: RuntimeException) {
                serviceRequested = false
                retryAfterMs = SystemClock.elapsedRealtime() + 30_000L
                Log.w("PlayerMediaNotifications", "Unable to start media notification", error)
            }
        }
    }

    fun remove(connection: PlayerMediaNotificationConnection) {
        if (owner !== connection) return
        owner = null
        model = null
        mutableState.value = null
    }

    fun serviceDestroyed() {
        serviceRequested = false
        // A handoff may resume while the old service is still completing stopSelf().
        ensureService()
    }

    fun dispatch(sessionId: Long, action: String, seekMs: Long? = null) {
        val snapshot = state.value ?: return
        val current = model ?: return
        if (snapshot.sessionId != sessionId || current.outputSuspended.value ||
            !PlayerPreferences.getInstance(current.getApplication()).playerModeEnabled) return
        when (action) {
            ACTION_PLAY -> current.play()
            ACTION_PAUSE -> current.pause()
            ACTION_PREVIOUS -> if (current.state.value.hasPrevious) current.previous()
            ACTION_NEXT -> if (current.state.value.hasNext) current.next()
            ACTION_STOP -> current.closeSession()
            else -> if (seekMs != null && current.state.value.ready) current.seekTo(seekMs)
        }
    }
}

/** Lives with the ViewModel, so recreation never registers or tears down a second session. */
internal class PlayerMediaNotificationConnection(
    context: Context,
    private val model: PlayerViewModel,
    scope: CoroutineScope
) {
    private val app = context.applicationContext
    private val preferences = PlayerPreferences.getInstance(app)
    private var active = false
    private var sessionId = 0L
    private val job: Job = scope.launch {
        combine(
            model.state, model.outputSuspended, preferences.notificationLyricsChanges, model.detailedLyricsInForeground,
        ) { _, _, showLyrics, _ ->
            val state = model.state.value
            val suspended = model.outputSuspended.value
            val track = state.currentTrack
            if (suspended || track == null || !preferences.playerModeEnabled) {
                active = false
                null
            } else {
                if (!active) sessionId = PlayerMediaNotifications.newSessionId()
                active = true
                val subtitle = state.subtitleText.takeIf {
                    !track.isVideo && state.ready && state.error == null &&
                        state.selectedSubtitleId != "none" && it.isNotBlank()
                }
                val floatingSubtitleAvailable = !track.isVideo && state.error == null &&
                    !(state.lyricsVisible && model.detailedLyricsInForeground.value)
                PlayerMediaNotificationState(sessionId, track, state.positionMs,
                    state.durationMs, state.playing, state.ready, state.error, state.speed,
                    state.hasPrevious, state.hasNext,
                    lyricText = subtitle.takeIf { showLyrics },
                    floatingSubtitleText = subtitle.takeIf { floatingSubtitleAvailable },
                    floatingSubtitleAvailable = floatingSubtitleAvailable,
                )
            }
        }.distinctUntilChanged { old, new ->
            old != null && new != null && old.copy(positionMs = 0L) == new.copy(positionMs = 0L) &&
                old.positionMs / 1_000L == new.positionMs / 1_000L || old == new
        }.collect { snapshot ->
            PlayerMediaNotifications.publish(this@PlayerMediaNotificationConnection, model, snapshot, app)
        }
    }

    fun close() {
        job.cancel()
        invalidate()
    }

    fun invalidate() {
        active = false
        PlayerMediaNotifications.remove(this)
    }
}
