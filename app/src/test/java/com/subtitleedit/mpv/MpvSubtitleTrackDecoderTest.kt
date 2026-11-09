package com.subtitleedit.mpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvSubtitleTrackDecoderTest {
    @Test fun preservesActualStreamIndicesAndUnicodeMetadata() {
        val tracks = MpvSubtitleTrackDecoder.decode(arrayOf(
            "3", "subrip", "zho", "中文字幕 🌊", "1",
            "8", "hdmv_pgs_subtitle", "jpn", "", "0"
        ))
        assertEquals(listOf(3L, 8L), tracks.map { it.ffIndex })
        assertEquals(MpvSubtitleTrack(3L, "subrip", "zho", "中文字幕 🌊", true), tracks.first())
        assertEquals(MpvSubtitleTrack(8L, "hdmv_pgs_subtitle", "jpn", "", false), tracks.last())
    }

    @Test fun noSubtitleTracksProducesAnEmptyList() {
        assertTrue(MpvSubtitleTrackDecoder.decode(emptyArray()).isEmpty())
    }

    @Test fun missingOrNegativeStreamIndicesAndIncompleteTracksAreRejected() {
        for (fields in listOf(arrayOf("1", "srt"), arrayOf("", "srt", "", "", "0"),
            arrayOf("-1", "srt", "", "", "0"), arrayOf("2", "srt", "", "", "yes"))) {
            try {
                MpvSubtitleTrackDecoder.decode(fields)
                throw AssertionError("Invalid track should have been rejected")
            } catch (_: IllegalArgumentException) {
                // An unavailable stream index must not accidentally map a different stream.
            }
        }
    }
}
