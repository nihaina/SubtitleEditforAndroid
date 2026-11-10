package com.subtitleedit

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaConvertSubtitleRendererTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun actualRenderResultDeterminesAvailabilityWithoutAnyListingLogs() {
        val cache = temporary.newFolder("cache 'with spaces'")
        var checked = false
        assertTrue(MediaConvertSubtitleRenderer.isAvailable(cache) { arguments ->
            checked = true
            val fixture = cache.listFiles()!!.single()
            assertTrue(fixture.readText().contains("00:00:00,000 --> 00:00:01,000"))
            assertTrue(arguments.toList().windowed(2).contains(listOf("-frames:v", "1")))
            assertTrue(arguments.toList().windowed(2).contains(listOf("-f", "null")))
            val filter = arguments[arguments.indexOf("-vf") + 1]
            assertTrue(filter.startsWith("subtitles=filename="))
            assertTrue(filter.contains("charenc=UTF-8"))
            true
        })
        assertTrue(checked)
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun unsuccessfulRendererProbeIsUnavailableAndCleansItsFixture() {
        val cache = temporary.newFolder()
        assertFalse(MediaConvertSubtitleRenderer.isAvailable(cache) { false })
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun failedExecutionAlsoCleansItsFixture() {
        val cache = temporary.newFolder()
        val failure = IllegalStateException("Native library unavailable")
        try {
            MediaConvertSubtitleRenderer.isAvailable(cache) { throw failure }
            fail("Expected native execution failure")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }
}
