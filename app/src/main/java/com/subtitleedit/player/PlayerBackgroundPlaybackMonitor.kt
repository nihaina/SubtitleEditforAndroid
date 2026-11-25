package com.subtitleedit.player

/** Detects a missed player heartbeat and insufficient playback progress in the same interval. */
internal class PlayerBackgroundPlaybackMonitor {
    data class Playback(
        val loadId: Long,
        val positionMs: Long,
        val durationMs: Long,
        val speed: Float,
    ) {
        val canContinue: Boolean
            get() = loadId > 0L && speed.isFinite() && speed > 0f &&
                (durationMs <= 0L || durationMs - positionMs > 1_000L * speed)
    }

    private data class Sample(val elapsedMs: Long, val playback: Playback)

    private var background = false
    val isInBackground: Boolean get() = background
    private var last: Sample? = null
    private var suspectedLoadId: Long? = null

    fun enterBackground(elapsedMs: Long, playback: Playback?) {
        background = true
        recordHeartbeat(elapsedMs, playback)
    }

    fun recordHeartbeat(elapsedMs: Long, playback: Playback?) {
        if (!background) return
        if (playback == null || !playback.canContinue) {
            resetPlayback()
            return
        }
        val previous = last
        last = Sample(elapsedMs, playback)
        if (previous == null || previous.playback.loadId != playback.loadId || previous.playback.speed != playback.speed) {
            suspectedLoadId = null
            return
        }
        val gapMs = elapsedMs - previous.elapsedMs
        val advancedMs = playback.positionMs - previous.playback.positionMs
        val expectedMs = gapMs * playback.speed
        if (gapMs >= MIN_HEARTBEAT_GAP_MS && advancedMs >= 0 && advancedMs <= expectedMs * MAX_PROGRESS_FRACTION) {
            suspectedLoadId = playback.loadId
        }
    }

    fun returnToForeground(elapsedMs: Long, playback: Playback?): Boolean {
        recordHeartbeat(elapsedMs, playback)
        val suspected = suspectedLoadId != null
        background = false
        resetPlayback()
        return suspected
    }

    fun resetPlayback() {
        last = null
        suspectedLoadId = null
    }

    private companion object {
        const val MIN_HEARTBEAT_GAP_MS = 3_000L
        const val MAX_PROGRESS_FRACTION = 0.25
    }
}
