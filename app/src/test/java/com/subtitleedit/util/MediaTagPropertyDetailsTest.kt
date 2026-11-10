package com.subtitleedit.util

import org.junit.Assert.*
import org.junit.Test

class MediaTagPropertyDetailsTest {
    @Test fun standardTagPropertiesExposeAllCommonFieldsInTheExistingOrder() {
        val details = MediaTagPropertyDetails.fromProperties(arrayOf(
            "TITLE", "曲名 🌊", "ARTIST", "歌手", "ALBUM", "专辑", "GENRE", "自定义流派",
            "DATE", "2026-11-10", "ALBUMARTIST", "专辑歌手", "COMPOSER", "作曲者",
            "TRACKNUMBER", "3/12", "DISCNUMBER", "1/2", "COMMENT", "第一行\n第二行",
            "LYRICIST", "作词者", "COPYRIGHT", "版权", "LANGUAGE", "zho",
            "LYRICS", "Do not expose the entire lyric document as a property"
        ))
        assertEquals(listOf("歌曲名", "艺术家", "专辑", "专辑艺术家", "作曲", "流派", "年份",
            "音轨号", "碟号", "注释", "作词", "版权", "语言"), details.map { it.label })
        assertEquals("曲名 🌊", details.first().value)
        assertEquals("3/12", details.first { it.label == "音轨号" }.value)
        assertFalse(details.any { "Do not expose" in it.value })
    }

    @Test fun missingFieldsAreAbsentAndMultiValueFieldsAreNotLost() {
        assertTrue(MediaTagPropertyDetails.fromProperties(emptyArray()).isEmpty())
        assertEquals(listOf(PropertyDetail("艺术家", "First / Second")),
            MediaTagPropertyDetails.fromProperties(arrayOf("ARTIST", "First", "ARTIST", "Second", "ALBUM", "")))
    }
}
