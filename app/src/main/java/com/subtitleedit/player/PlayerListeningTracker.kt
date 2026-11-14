package com.subtitleedit.player

import java.util.UUID
import kotlin.math.abs

internal data class PlayerListeningInterval(
    val path: String,
    val playbackId: String,
    val startedAtMs: Long,
    val durationMs: Long
)

/** Main-thread state machine. Media position and playback speed never determine listening time. */
internal class PlayerListeningTracker(private val createId: () -> String = { UUID.randomUUID().toString() }) {
    var path: String = ""
        private set
    var playbackId: String = ""
        private set
    var hasPlayed: Boolean = false
        private set
    var playing: Boolean = false
        private set
    private var lastElapsedMs: Long? = null
    private var lastWallMs = 0L
    private var intervalStartMs = 0L
    private var pendingMs = 0L

    /** Preserve a run for surface reattachment/tag editing; repeat or a new selection gets a new id. */
    fun select(path: String, preservePlayback: Boolean, elapsedMs: Long, wallMs: Long): List<PlayerListeningInterval> {
        val completed = update(false, elapsedMs, wallMs)
        if (!preservePlayback || this.path != path || playbackId.isBlank()) {
            playbackId = createId()
            hasPlayed = false
        }
        this.path = path
        return completed
    }

    fun update(active: Boolean, elapsedMs: Long, wallMs: Long): List<PlayerListeningInterval> {
        val result = ArrayList<PlayerListeningInterval>(1)
        val enabled = active && path.isNotBlank() && playbackId.isNotBlank()
        val previousElapsed = lastElapsedMs
        if (playing && previousElapsed != null) {
            val delta = elapsedMs - previousElapsed
            if (delta > 0L) pendingMs += delta
            // Keep elapsed time when the user changes the wall clock, but begin a new
            // wall-time segment so later listening is filed under the correct date.
            if (!enabled || delta < 0L || abs((wallMs - lastWallMs) - delta) > 2_000L) {
                drain()?.let(result::add)
                intervalStartMs = wallMs
            }
        }
        if (enabled) {
            if (!playing) intervalStartMs = wallMs
            lastElapsedMs = elapsedMs
            lastWallMs = wallMs
            hasPlayed = true
        } else {
            lastElapsedMs = null
        }
        playing = enabled
        return result
    }

    /** Persists the tail without pausing or counting the same interval twice. */
    fun flush(elapsedMs: Long, wallMs: Long): List<PlayerListeningInterval> {
        val result = update(playing, elapsedMs, wallMs).toMutableList()
        drain()?.let(result::add)
        intervalStartMs = wallMs
        return result
    }

    private fun drain(): PlayerListeningInterval? {
        if (pendingMs <= 0L) return null
        return PlayerListeningInterval(path, playbackId, intervalStartMs, pendingMs).also { pendingMs = 0L }
    }
}
