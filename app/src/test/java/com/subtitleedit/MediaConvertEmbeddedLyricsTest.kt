package com.subtitleedit

import com.arthenica.ffmpegkit.MediaInformationJsonParser
import org.junit.Assert.*
import org.junit.Test

class MediaConvertEmbeddedLyricsTest {
    @Test fun readsNativeMetadataKeysAndIgnoresOtherTags() {
        for ((extension, key, field) in listOf(Triple("flac", "LYRICS", "LYRICS"),
            Triple("ogg", "lyrics", "LYRICS"), Triple("opus", "Lyrics", "LYRICS"),
            Triple("wv", "Lyrics", "LYRICS"), Triple("m4a", "lyrics", "©lyr"),
            Triple("wma", "WM/Lyrics", "WM/Lyrics"), Triple("aac", "lyrics", "USLT"))) {
            val tags = MediaConvertEmbeddedLyrics.fromMetadata(listOf("title" to "Song", key to "[00:01.25]海风 🌊"), extension)
            assertEquals(extension, 1, tags.size)
            assertEquals(field, tags.single().field)
            assertTrue(tags.single().synced)
            assertEquals("[00:01.25]海风 🌊", tags.single().text)
        }
        assertTrue(MediaConvertEmbeddedLyrics.fromMetadata(listOf("artist" to "Singer", "lyrics" to " "), "flac").isEmpty())
    }

    @Test fun id3KeysRetainHyphenatedDescriptionsAndEmptyDescriptions() {
        for (extension in listOf("mp3", "aac")) {
            val tags = MediaConvertEmbeddedLyrics.fromMetadata(listOf("lyrics-中文-原文-zho" to "[00:00.00]你好",
                "lyrics-eng" to "Hello", "lyrics-und" to "Untitled", "UNSYNCEDLYRICS" to "Plain"), extension)
            assertEquals(extension, listOf("中文-原文", "", "", ""), tags.map { it.title })
            assertEquals(extension, listOf("zho", "eng", "und", ""), tags.map { it.language })
            assertTrue(extension, tags.all { it.field == "USLT" })
            assertFalse(extension, tags[1].synced)
        }
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

    @Test fun readsContainerAndAudioStreamLyricsWithoutDependingOnPlayerMetadata() {
        val info = MediaInformationJsonParser.fromWithError("""
            {"format":{"tags":{"lyrics-eng":"Container lyrics"}},
             "streams":[
               {"index":0,"codec_type":"audio","codec_name":"pcm_s16le",
                "tags":{"LYRICS":"[00:01.00]Stream lyrics","language":"zho"}},
               {"index":1,"codec_type":"video","codec_name":"mjpeg","tags":{"lyrics":"Not an audio lyric"}}
             ]}
        """.trimIndent())
        val detected = MediaConvertEmbeddedLyrics.fromMediaInformation(info, "wav")
        assertEquals(2, detected.size)
        assertEquals(listOf("Container lyrics", "[00:01.00]Stream lyrics"), detected.map { it.text })
        assertEquals(listOf("eng", "zho"), detected.map { it.language })
        assertTrue(detected.all { it.field == "USLT" })
        assertTrue(detected.last().synced)
    }

    @Test fun containerAndStreamCopiesUseTheRicherLanguageAndKeepTranslations() {
        val info = MediaInformationJsonParser.fromWithError("""
            {"format":{"tags":{"LYRICS":"Line one\r\nLine two"}},
             "streams":[
               {"index":0,"codec_type":"audio","tags":{"lyrics":"Line one\nLine two","language":"eng"}},
               {"index":1,"codec_type":"audio","tags":{"Lyrics":"Line one\nLine two","language":"zho"}}
             ]}
        """.trimIndent())
        val detected = MediaConvertEmbeddedLyrics.fromMediaInformation(info, "ogg")
        assertEquals(2, detected.size)
        assertEquals(listOf("eng", "zho"), detected.map { it.language })
        assertTrue(detected.all { it.field == "LYRICS" })
    }

    @Test fun metadataAliasesAndNewlineDifferencesDoNotDuplicateLyricsOrReadLinksAsLyrics() {
        val detected = MediaConvertEmbeddedLyrics.fromMetadata(listOf(
            "WM/Lyrics" to "Line one\r\nLine two", "wm/lyrics" to "Line one\nLine two",
            "lyrics-url" to "https://example.com/lyrics", "lyrics-link" to "https://example.com/lyrics",
            "lyrics_website" to "https://example.com", "lyrics_provider" to "Provider"
        ), "wma")
        assertEquals(listOf(MediaConvertEmbeddedLyric("WM/Lyrics", text = "Line one\r\nLine two")), detected)
        assertEquals("©lyr", MediaConvertEmbeddedLyrics.fromMetadata(listOf("©lyr" to "Plain lyrics"), "m4a").single().field)
    }

    @Test fun directLyricsSuppressAPlayerCopyThatLostItsDescriptionWithoutLosingOtherLanguages() {
        val frame = MediaConvertEmbeddedLyric("USLT", "Original", "eng", "[00:01.00]Line\r\n", true)
        val player = listOf(MediaConvertEmbeddedLyric("USLT", text = "[00:01.00]Line", synced = true),
            MediaConvertEmbeddedLyric("USLT", "Original", "zho", "[00:01.00]Line", true))
        assertEquals(listOf(frame, player.last()), MediaConvertEmbeddedLyrics.merge(player, listOf(frame)))
    }

    @Test fun renamedWavDoesNotDuplicateItsGenericMetadataAsASeparateLyric() {
        val frame = MediaConvertEmbeddedLyric("USLT", "Original", "eng", "[00:01.00]Line\r\n", true)
        val synced = frame.copy(field = "SYLT")
        val fallback = MediaConvertEmbeddedLyrics.fromMetadata(listOf("lyrics" to "[00:01.00]Line"), "bin")
        assertEquals("LYRICS", fallback.single().field)
        assertEquals(listOf(frame, synced), MediaConvertEmbeddedLyrics.merge(fallback, listOf(frame, synced)))
        val translated = fallback.single().copy(language = "zho")
        assertEquals(listOf(frame, translated), MediaConvertEmbeddedLyrics.merge(listOf(translated), listOf(frame)))
    }
}
