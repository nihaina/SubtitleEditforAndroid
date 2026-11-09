package com.subtitleedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaConvertAudioLyricsTest {
    private val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders,
        MediaConvertAudioCodecs.baselineEncoders + setOf("libspeex", "wavpack"))
    private fun format(extension: String) = formats.first { it.extension == extension }
    private val lyric = MediaConvertLyric("Chinese", "[ti:Song]\n[00:01.00]海风 🌊\n[00:03.00]Next line\n")

    @Test fun onlyNativeContainersAreOfferedAndSingleValueTagsLimitTheSelection() {
        for (extension in listOf("mp3", "wav", "mka")) assertEquals(extension, Int.MAX_VALUE, MediaConvertAudioLyrics.maxFiles(format(extension)))
        for (extension in listOf("m4a", "m4b", "flac", "ogg", "oga", "opus", "wma", "wv", "spx")) {
            assertEquals(extension, 1, MediaConvertAudioLyrics.maxFiles(format(extension)))
        }
        for (extension in listOf("aac", "ac3", "w64", "mp4")) assertEquals(extension, 0, MediaConvertAudioLyrics.maxFiles(format(extension)))
        assertEquals(0, MediaConvertAudioLyrics.maxFiles(null))
    }

    @Test fun tagArgumentsPreserveLrcForCommentContainersAndUseTheirNativeKeys() {
        assertEquals(listOf("-metadata", "LYRICS=${lyric.lrc}"), MediaConvertAudioLyrics.arguments(format("flac"), listOf(lyric)))
        assertEquals(listOf("-metadata", "WM/Lyrics=${lyric.lrc}"), MediaConvertAudioLyrics.arguments(format("wma"), listOf(lyric)))
        assertEquals(listOf("-metadata", "Lyrics=${lyric.lrc}"), MediaConvertAudioLyrics.arguments(format("wv"), listOf(lyric)))
        for (extension in listOf("ogg", "opus", "spx")) {
            assertEquals(listOf("-metadata", "LYRICS=${lyric.lrc}", "-metadata:s:a:0", "LYRICS=${lyric.lrc}"),
                MediaConvertAudioLyrics.arguments(format(extension), listOf(lyric)))
        }
    }

    @Test fun appleLyricsUsePlainTextInTheCopyrightLyrAtom() {
        assertEquals("海风 🌊\nNext line", MediaConvertAudioLyrics.plainText(lyric.lrc))
        for (extension in listOf("m4a", "m4b")) {
            assertEquals(listOf("-metadata", "lyrics=海风 🌊\nNext line"), MediaConvertAudioLyrics.arguments(format(extension), listOf(lyric)))
        }
    }

    @Test fun mp3AndWaveHaveDedicatedFrameWritersAndMkaRetainsItsTimedTrackPath() {
        assertTrue(MediaConvertAudioLyrics.needsFileRewrite(format("mp3")))
        assertTrue(MediaConvertAudioLyrics.needsFileRewrite(format("wav")))
        assertFalse(MediaConvertAudioLyrics.needsFileRewrite(format("flac")))
        assertEquals(listOf("-id3v2_version", "3"), MediaConvertAudioLyrics.arguments(format("mp3"), listOf(lyric, lyric.copy(title = "English"))))
        assertTrue(MediaConvertAudioLyrics.arguments(format("wav"), listOf(lyric)).isEmpty())
        assertTrue(MediaConvertAudioLyrics.usesSubtitleTracks(format("mka")))
        assertTrue(MediaConvertAudioLyrics.arguments(format("mka"), listOf(lyric, lyric)).isEmpty())
    }

    @Test fun unsupportedAndOverLimitSelectionsFailInsteadOfDroppingLyricVersions() {
        for ((extension, selected) in listOf("aac" to listOf(lyric), "flac" to listOf(lyric, lyric))) {
            try {
                MediaConvertAudioLyrics.arguments(format(extension), selected)
                throw AssertionError("Expected $extension lyric selection to be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        assertTrue(MediaConvertAudioLyrics.arguments(format("aac"), emptyList()).isEmpty())
    }
}
