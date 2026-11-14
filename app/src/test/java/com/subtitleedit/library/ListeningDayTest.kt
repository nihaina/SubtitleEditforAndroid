package com.subtitleedit.library

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningDayTest {
    @Test
    fun fiveAmBoundaryAssignsBeforeFiveAmToPreviousDay() {
        val tz = TimeZone.getDefault()
        val cal =
            Calendar.getInstance(tz).apply {
                set(2026, Calendar.NOVEMBER, 14, 3, 30, 0)
                set(Calendar.MILLISECOND, 0)
            }
        assertEquals("2026-11-13", ListeningDay.dateOf(cal.timeInMillis))

        cal.set(Calendar.HOUR_OF_DAY, 5)
        cal.set(Calendar.MINUTE, 1)
        assertEquals("2026-11-14", ListeningDay.dateOf(cal.timeInMillis))
    }

    @Test
    fun datesBetweenGeneratesInclusiveOrderedDates() {
        val dates = ListeningDay.datesBetween("2026-11-12", "2026-11-14")
        assertEquals(listOf("2026-11-12", "2026-11-13", "2026-11-14"), dates)
        assertTrue(ListeningDay.datesBetween("2026-11-15", "2026-11-14").isEmpty())
    }

    @Test
    fun intervalEndingExactlyAtFiveAmDoesNotCreateAnEmptyNextDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val boundary = ZonedDateTime.of(2026, 11, 14, 5, 0, 0, 0, zone).toInstant().toEpochMilli()
        val slices = ListeningDay.split(boundary - 1_000L, 1_000L, zone)
        assertEquals(listOf("2026-11-13"), slices.map { it.date })
        assertEquals(1_000L, slices.single().durationMs)
        assertEquals("2026-11-14", ListeningDay.dateOf(boundary, zone))
    }

    @Test
    fun daylightSavingChangeStillResetsAtFiveLocalTime() {
        val zone = ZoneId.of("America/New_York")
        val before = ZonedDateTime.of(2026, 3, 8, 4, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("2026-03-07", ListeningDay.dateOf(before, zone))
        val slices = ListeningDay.split(before, 60 * 60 * 1_000L, zone)
        assertEquals(listOf("2026-03-07", "2026-03-08"), slices.map { it.date })
        assertEquals(listOf(30 * 60 * 1_000L, 30 * 60 * 1_000L), slices.map { it.durationMs })
    }
}
