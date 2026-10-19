package com.subtitleedit.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFormatConverterTest {
    @Test
    fun sourceFileNameFilterAcceptsSupportedSubtitleAndTextExtensions() {
        assertTrue(SubtitleFormatConverter.isSupportedSourceFileName("captions.SRT"))
        assertTrue(SubtitleFormatConverter.isSupportedSourceFileName("captions.lrc"))
        assertTrue(SubtitleFormatConverter.isSupportedSourceFileName("transcript.txt"))
        assertTrue(SubtitleFormatConverter.isSupportedSourceFileName("captions.vtt"))
    }

    @Test
    fun sourceFileNameFilterRejectsUnsupportedOrMissingExtensions() {
        assertFalse(SubtitleFormatConverter.isSupportedSourceFileName("captions.ass"))
        assertFalse(SubtitleFormatConverter.isSupportedSourceFileName("notes.md"))
        assertFalse(SubtitleFormatConverter.isSupportedSourceFileName("captions"))
    }
}
