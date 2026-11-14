package com.subtitleedit.library

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A listening day runs from local 05:00 until the following local 05:00. */
internal object ListeningDay {
    const val RESET_HOUR = 5

    data class Slice(val date: String, val startedAtMs: Long, val durationMs: Long)

    fun currentDate(nowMs: Long = System.currentTimeMillis()): String = dateOf(nowMs)

    fun dateOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = Instant.ofEpochMilli(epochMs).atZone(zone)
        return (if (local.hour < RESET_HOUR) local.toLocalDate().minusDays(1)
            else local.toLocalDate())
            .toString()
    }

    fun nextBoundary(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.parse(dateOf(epochMs, zone))
            .plusDays(1)
            .atTime(RESET_HOUR, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    fun split(
        startedAtMs: Long,
        durationMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Slice> {
        if (durationMs <= 0L) return emptyList()
        require(startedAtMs <= Long.MAX_VALUE - durationMs) {
            "Listening interval overflows epoch time"
        }
        val result = ArrayList<Slice>()
        val end = startedAtMs + durationMs
        var start = startedAtMs
        while (start < end) {
            val stop = minOf(end, nextBoundary(start, zone))
            result += Slice(dateOf(start, zone), start, stop - start)
            start = stop
        }
        return result
    }

    fun datesBetween(startDate: String, endDate: String): List<String> {
        val start = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return emptyList()
        val end = runCatching { LocalDate.parse(endDate) }.getOrNull() ?: return emptyList()
        if (start > end) return emptyList()
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { it <= end }
            .map { it.toString() }
            .toList()
    }

    fun dayOfWeekIndex(date: String): Int =
        runCatching { LocalDate.parse(date).dayOfWeek.value % 7 }.getOrDefault(0)
}
