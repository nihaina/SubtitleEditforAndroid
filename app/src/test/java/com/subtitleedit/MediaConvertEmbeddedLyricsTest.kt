package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaConvertEmbeddedLyricsTest {
    @Test fun readsNativeMetadataKeysAndIgnoresOtherTags() {
        for ((extension, key, field) in listOf(Triple("flac", "LYRICS", "LYRICS"),
            Triple("ogg", "lyrics", "LYRICS"), Triple("opus", "Lyrics", "LYRICS"),
            Triple("wv", "Lyrics", "LYRICS"), Triple("m4a", "lyrics", "©lyr"),
            Triple("wma", "WM/Lyrics", "WM/Lyrics"))) {
            val tags = MediaConvertEmbeddedLyrics.fromMetadata(listOf("title" to "Song", key to "[00:01.25]海风 🌊"), extension)
            assertEquals(extension, 1, tags.size)
            assertEquals(field, tags.single().field)
            assertTrue(tags.single().synced)
            assertEquals("[00:01.25]海风 🌊", tags.single().text)
        }
        assertTrue(MediaConvertEmbeddedLyrics.fromMetadata(listOf("artist" to "Singer", "lyrics" to " "), "flac").isEmpty())
    }

    @Test fun id3KeysRetainHyphenatedDescriptionsAndEmptyDescriptions() {
        val tags = MediaConvertEmbeddedLyrics.fromMetadata(listOf("lyrics-中文-原文-zho" to "[00:00.00]你好",
            "lyrics-eng" to "Hello", "lyrics-und" to "Untitled", "UNSYNCEDLYRICS" to "Plain"), "mp3")
        assertEquals(listOf("中文-原文", "", "", ""), tags.map { it.title })
        assertEquals(listOf("zho", "eng", "und", ""), tags.map { it.language })
        assertTrue(tags.all { it.field == "USLT" })
        assertFalse(tags[1].synced)
    }

    @Test fun directFramesTakePrecedenceAndSynchronizedFrameRemainsSeparate() {
        val metadata = MediaConvertEmbeddedLyrics.fromMetadata(listOf("lyrics-中文-zho" to "LRC", "lyrics-Other-eng" to "Other"), "mp3")
        val frames = listOf(MediaConvertEmbeddedLyric("USLT", "中文", "zho", "[00:00.00]LRC", true),
            MediaConvertEmbeddedLyric("SYLT", "中文", "zho", "[00:00.00]LRC", true))
        val merged = MediaConvertEmbeddedLyrics.merge(metadata, frames)
        assertEquals(3, merged.size)
        assertEquals(frames, merged.take(2))
        assertEquals("Other", merged.last().title)
    }
}
