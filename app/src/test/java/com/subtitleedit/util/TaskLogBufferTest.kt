package com.subtitleedit.util

import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLogBufferTest {

    @Test
    fun appendPrefixesTimestampAndReturnsFullText() {
        val buffer = TaskLogBuffer(clock = { Date(0) })

        buffer.append("first")
        val text = buffer.append("second")

        val lines = text.lines().filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
        assertTrue(lines[0].matches(Regex("""\[\d{2}:\d{2}:\d{2}] first""")))
        assertTrue(lines[1].endsWith("] second"))
        assertEquals(text, buffer.text())
    }

    @Test
    fun keepsOnlyTheNewestCharactersWhenOverCapacity() {
        val buffer = TaskLogBuffer(maxChars = 10)

        buffer.appendRaw("0123456789")
        val text = buffer.appendRaw("abc")

        assertEquals(10, text.length)
        assertTrue(text.endsWith("abc\n"))
    }

    @Test
    fun clearEmptiesTheBuffer() {
        val buffer = TaskLogBuffer()
        buffer.appendRaw("line")

        buffer.clear()

        assertEquals("", buffer.text())
    }

    @Test
    fun concurrentAppendsAreNotLost() {
        val buffer = TaskLogBuffer(maxChars = Int.MAX_VALUE)

        (1..4).map { worker ->
            thread { repeat(500) { buffer.appendRaw("w$worker") } }
        }.forEach { it.join() }

        assertEquals(2000, buffer.text().lines().count { it.isNotEmpty() })
    }
}

class ByteSizeFormatTest {

    @Test
    fun picksUnitByMagnitude() {
        assertEquals("0.5 KB", ByteSizeFormat.format(512, Locale.US))
        assertEquals("1.50 MB", ByteSizeFormat.format(1024L * 1024L * 3 / 2, Locale.US))
        assertEquals("2.00 GB", ByteSizeFormat.format(2L * 1024L * 1024L * 1024L, Locale.US))
    }
}
