package com.subtitleedit.editor

/** The fixed screen anchor used while the user holds the timestamp button. */
internal data class EditorWaveformTimestampSession(
    val startMs: Long,
    val anchorX: Float
) {
    fun panViewport(
        visibleStartMs: Long,
        deltaMs: Long,
        visibleDurationMs: Long,
        durationMs: Long
    ): Long {
        val paddingMs = visibleDurationMs.coerceIn(250L, EditorWaveformState.MAX_VISIBLE_DURATION_MS)
        val normalMaxStart = (durationMs - visibleDurationMs).coerceAtLeast(0L)
        return (visibleStartMs + deltaMs).coerceIn(-paddingMs, normalMaxStart + paddingMs)
    }

    fun endTime(
        visibleStartMs: Long,
        visibleDurationMs: Long,
        viewportWidthPx: Int,
        durationMs: Long
    ): Long {
        if (viewportWidthPx <= 0) return 0L
        return (visibleStartMs + anchorX / viewportWidthPx.toFloat() * visibleDurationMs)
            .toLong().coerceIn(0L, durationMs)
    }

    companion object {
        fun start(
            startMs: Long,
            visibleStartMs: Long,
            visibleDurationMs: Long,
            viewportWidthPx: Int
        ): EditorWaveformTimestampSession {
            val anchorX = if (visibleDurationMs <= 0L) 0f else
                ((startMs - visibleStartMs).toFloat() / visibleDurationMs * viewportWidthPx)
                    .coerceAtLeast(0f)
            return EditorWaveformTimestampSession(startMs, anchorX)
        }
    }
}
