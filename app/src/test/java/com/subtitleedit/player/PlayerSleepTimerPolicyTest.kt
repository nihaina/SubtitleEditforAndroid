package com.subtitleedit.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PlayerSleepTimerPolicyTest {
    @Test fun countdownExpiresAtTheDeadlineAndNeverHasNegativeRemainingTime() {
        val timer = PlayerSleepTimerPolicy.countdown(1_000L, 30)
        assertEquals(30, timer.durationMinutes)
        assertEquals(30 * 60_000L, PlayerSleepTimerPolicy.remainingMs(timer, 1_000L))
        assertFalse(PlayerSleepTimerPolicy.isExpired(timer, 1_000L + 30 * 60_000L - 1L))
        assertTrue(PlayerSleepTimerPolicy.isExpired(timer, 1_000L + 30 * 60_000L))
        assertEquals(0L, PlayerSleepTimerPolicy.remainingMs(timer, Long.MAX_VALUE))
    }

    @Test fun disabledTimerCannotExpire() {
        val timer = PlayerSleepTimer()
        assertFalse(timer.active)
        assertFalse(PlayerSleepTimerPolicy.isExpired(timer, Long.MAX_VALUE))
        assertEquals(0L, PlayerSleepTimerPolicy.remainingMs(timer, Long.MAX_VALUE))
    }

    @Test fun customMinutesAcceptWholeMinutesWithinTheDocumentedRange() {
        assertEquals(listOf(10, 20, 30, 45, 60), PlayerSleepTimerPolicy.COUNTDOWN_MINUTES)
        assertEquals(1, PlayerSleepTimerPolicy.parseMinutes("1"))
        assertEquals(75, PlayerSleepTimerPolicy.parseMinutes(" 75 "))
        assertEquals(1440, PlayerSleepTimerPolicy.parseMinutes("1440"))
        listOf("", " ", "0", "-1", "1441", "1.5", "ten", "2147483648").forEach {
            assertNull("Invalid custom input: $it", PlayerSleepTimerPolicy.parseMinutes(it))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun countdownRejectsNonPositiveMinutes() {
        PlayerSleepTimerPolicy.countdown(0L, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun countdownRejectsMinutesBeyondTheSupportedRange() {
        PlayerSleepTimerPolicy.countdown(0L, 1441)
    }

    @Test fun customCountdownUsesAnAbsoluteDeadlineRatherThanPlaybackSamples() {
        val timer = PlayerSleepTimerPolicy.countdown(42_000L, 75)
        assertEquals(42_000L + 75 * 60_000L, timer.endsAtElapsedMs)
        assertEquals(37 * 60_000L, PlayerSleepTimerPolicy.remainingMs(timer, 42_000L + 38 * 60_000L))
    }

    @Test fun remainingLabelRoundsUpAndUsesClockStyleFormatting() {
        assertEquals("04:05", PlayerSleepTimerPolicy.formatRemaining(245_000L))
        assertEquals("1:00:00", PlayerSleepTimerPolicy.formatRemaining(3_600_000L))
        assertEquals("00:01", PlayerSleepTimerPolicy.formatRemaining(1L))
        assertEquals("00:00", PlayerSleepTimerPolicy.formatRemaining(0L))
        assertEquals("00:00", PlayerSleepTimerPolicy.formatRemaining(-100L))
    }
}

class PlayerBookmarkStoreTest {
    @Test fun bookmarksRoundTripPerMediaFile() {
        val file = Files.createTempFile("player-bookmarks", ".json").toFile()
        try {
            val store = PlayerBookmarkStore(file)
            val marks = listOf(PlayerBookmark(60_000L, 1L), PlayerBookmark(10_000L, 2L, "耳搔段落"))
            store.save("/media/a.mp3", marks)
            store.save("/media/b.mp3", listOf(PlayerBookmark(5_000L, 3L)))
            val loaded = store.load("/media/a.mp3")
            assertEquals(listOf(10_000L, 60_000L), loaded.map { it.positionMs })
            assertEquals("耳搔段落", loaded.first().label)
            assertEquals(listOf(5_000L), store.load("/media/b.mp3").map { it.positionMs })
            store.save("/media/a.mp3", emptyList())
            assertTrue(store.load("/media/a.mp3").isEmpty())
            assertEquals(listOf(5_000L), store.load("/media/b.mp3").map { it.positionMs })
        } finally {
            file.delete()
        }
    }

    @Test fun missingOrMalformedStoreFileLoadsEmpty() {
        val directory = Files.createTempDirectory("player-bookmarks").toFile()
        try {
            val file = directory.resolve("none.json")
            val store = PlayerBookmarkStore(file)
            assertTrue(store.load("/media/a.mp3").isEmpty())
            file.writeText("{incomplete")
            assertTrue(store.load("/media/a.mp3").isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
