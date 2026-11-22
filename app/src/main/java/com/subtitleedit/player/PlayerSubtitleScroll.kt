package com.subtitleedit.player

/** Media time, rather than animation time, keeps scrolling aligned during seeks and speed changes. */
internal fun subtitleScrollProgress(positionMs: Long, startMs: Long, endMs: Long): Float {
    val duration = endMs - startMs
    if (duration <= 0) return 0f
    val hold = (duration / 4).coerceAtMost(650L)
    val moving = duration - 2 * hold
    return ((positionMs - startMs - hold).toDouble() / moving).coerceIn(0.0, 1.0).toFloat()
}

internal data class PlayerOverlaySubtitleCue(
    val sessionId: Long,
    val text: String,
    val timing: PlayerSubtitleCueTiming?,
    val positionMs: Long,
    val sampledAtMs: Long,
    val playing: Boolean,
    val speed: Float,
) {
    fun progressAt(nowMs: Long): Float {
        val timing = timing ?: return 0f
        val elapsed = if (playing) (nowMs - sampledAtMs).coerceAtLeast(0L) else 0L
        val mediaPosition = positionMs + (elapsed * speed).toLong()
        return subtitleScrollProgress(mediaPosition, timing.startMs, timing.endMs)
    }
}
