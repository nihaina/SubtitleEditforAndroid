package com.subtitleedit.player

/** The same validated countdown rules are used for stopping and starting playback. */
internal object PlayerSleepTimerPolicy {
    val COUNTDOWN_MINUTES: List<Int> = listOf(10, 20, 30, 45, 60)
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 1440

    fun parseMinutes(text: String): Int? = text.trim().toIntOrNull()
        ?.takeIf { it in MIN_MINUTES..MAX_MINUTES }

    fun countdown(nowElapsedMs: Long, minutes: Int): PlayerSleepTimer {
        require(minutes in MIN_MINUTES..MAX_MINUTES)
        return PlayerSleepTimer(
            mode = PlayerSleepTimer.Mode.COUNTDOWN,
            endsAtElapsedMs = nowElapsedMs + minutes * 60_000L,
            durationMinutes = minutes
        )
    }

    fun remainingMs(timer: PlayerSleepTimer, nowElapsedMs: Long): Long =
        if (timer.active) (timer.endsAtElapsedMs - nowElapsedMs).coerceAtLeast(0L) else 0L

    fun isExpired(timer: PlayerSleepTimer, nowElapsedMs: Long): Boolean =
        timer.active && nowElapsedMs >= timer.endsAtElapsedMs

    fun formatRemaining(remainingMs: Long): String {
        val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }
}
