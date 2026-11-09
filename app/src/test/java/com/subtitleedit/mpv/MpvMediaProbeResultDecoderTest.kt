package com.subtitleedit.mpv

import org.junit.Assert.*
import org.junit.Test

class MpvMediaProbeResultDecoderTest {
    @Test fun keepsTagsSeparateFromSubtitleStreamIndices() {
        val result = MpvMediaProbeResultDecoder.decode(arrayOf("mpv-media-1", "1", "2",
            "3", "subrip", "zho", "Subtitle", "1", "LYRICS", "[00:01.00]海风 🌊", "WM/Lyrics", "Plain lyrics"))
        assertEquals(listOf(MpvSubtitleTrack(3, "subrip", "zho", "Subtitle", true)), result.subtitleTracks)
        assertEquals(listOf("LYRICS" to "[00:01.00]海风 🌊", "WM/Lyrics" to "Plain lyrics"), result.lyricMetadata)
        val onlyLyrics = MpvMediaProbeResultDecoder.decode(arrayOf("mpv-media-1", "0", "1", "lyrics", "Text"))
        assertTrue(onlyLyrics.subtitleTracks.isEmpty())
        assertEquals(1, onlyLyrics.lyricMetadata.size)
    }

    @Test fun invalidCountsAndIncompleteResultsAreRejected() {
        for (fields in listOf(emptyArray(), arrayOf("mpv-media-1", "0", "-1"),
            arrayOf("mpv-media-1", "2147483647", "0"), arrayOf("mpv-media-1", "0", "1", "lyrics"),
            arrayOf("old", "0", "0"))) {
            try {
                MpvMediaProbeResultDecoder.decode(fields)
                fail("Incomplete native probe result was accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
