package com.subtitleedit.util

import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.R
import org.junit.Assert.*
import org.junit.Test

class MediaTagPropertyDetailsTest {
    @Test fun standardTagPropertiesUseTheTagEditorFieldNamesAndOrder() {
        val details = MediaTagPropertyDetails.fromProperties(arrayOf(
            "TITLE", "曲名 🌊", "ARTIST", "歌手", "ALBUM", "专辑", "GENRE", "自定义流派",
            "DATE", "2026-11-10", "ALBUMARTIST", "专辑歌手", "COMPOSER", "作曲者",
            "TRACKNUMBER", "3/12", "DISCNUMBER", "1/2", "COMMENT", "第一行\n第二行",
            "LYRICIST", "作词者", "COPYRIGHT", "版权", "LANGUAGE", "zho",
            "LYRICS", "Do not expose the entire lyric document as a property"
        ))
        assertEquals(MediaConvertMetadata.audioFields.map { it.labelResource }, details.map { it.label })
        assertEquals("曲名 🌊", details.first { it.label == R.string.media_convert_info_title }.value)
        assertEquals("3/12", details.first { it.label == R.string.media_convert_info_track_number }.value)
        assertEquals("第一行\n第二行", details.first { it.label == R.string.media_convert_info_comment }.value)
        assertFalse(details.any { "Do not expose" in it.value })
    }

    @Test fun missingFieldsAreAbsentAndMultiValueFieldsAreNotLost() {
        assertTrue(MediaTagPropertyDetails.fromProperties(emptyArray()).isEmpty())
        assertEquals(listOf(PropertyDetail(R.string.media_convert_info_artist, "First / Second")),
            MediaTagPropertyDetails.fromProperties(arrayOf("ARTIST", "First", "ARTIST", "Second", "ALBUM", "")))
    }

    @Test fun videoAuthorUsesTheVideoTagEditorLabelAndValue() {
        val details = MediaTagPropertyDetails.fromMetadata(MediaConvertMetadata(author = "Director", artist = "Singer"), isVideo = true)
        assertEquals(listOf(PropertyDetail(R.string.media_convert_info_author, "Director")), details)
    }
}
