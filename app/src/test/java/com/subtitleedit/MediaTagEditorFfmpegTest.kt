package com.subtitleedit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real stream-copy checks; opt in with the same host FFmpeg used by the conversion tests. */
class MediaTagEditorFfmpegTest {
    @Test fun wavEditsReachInfoAndId3ReadersWithoutLosingLyricsOrAudio() = withFfmpeg { host ->
        val source = host.file("source with lyrics.wav")
        val original = MediaConvertMetadata(title = "Original title", artist = "Original singer", album = "Original album",
            genre = "Rock", year = "2001")
        host.run(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.2",
            "-metadata", "title=${original.title}", "-metadata", "artist=${original.artist}",
            "-metadata", "album=${original.album}", "-metadata", "genre=${original.genre}", "-metadata", "date=${original.year}", source.path))
        val lyrics = listOf(MediaConvertLyric("Original", "[00:00.125]First lyric 🌊\n"),
            MediaConvertLyric("Translation", "[00:00.125]第二份歌词\n"))
        MediaConvertId3Lyrics.writeWav(source, lyrics)
        val rawLyrics = WavTagFixture.readTag(source).frames.filter { it.id == "USLT" }
        val edited = host.file("edited.wav")
        val changes = MediaConvertMetadata()
            .withValue(MediaConvertMetadataField.TITLE, "Edited title")
            .withValue(MediaConvertMetadataField.ARTIST, "Edited singer")
            .withValue(MediaConvertMetadataField.ALBUM, "Edited album")
            .withValue(MediaConvertMetadataField.GENRE, "Jazz")
            .withValue(MediaConvertMetadataField.YEAR, "2026")
        host.run(MediaTagEditorCommand.build(source.path, edited.path, "wav", host.streams(source), changes, true))
        MediaConvertId3Lyrics.restoreWavMetadata(source, edited, changes, original)
        val tags = host.tags(edited)
        assertTrue(tags, "title=edited title" in tags)
        assertTrue(tags, "artist=edited singer" in tags)
        assertTrue(tags, "album=edited album" in tags)
        assertTrue(tags, "genre=jazz" in tags)
        assertTrue(tags, "date=2026" in tags)
        val frames = WavTagFixture.readTag(edited).frames
        assertEquals("Edited title", WavTagFixture.text(frames.single { it.id == "TIT2" }))
        assertEquals("Edited singer", WavTagFixture.text(frames.single { it.id == "TPE1" }))
        assertEquals("Edited album", WavTagFixture.text(frames.single { it.id == "TALB" }))
        assertEquals("Jazz", WavTagFixture.text(frames.single { it.id == "TCON" }))
        assertEquals("2026", WavTagFixture.text(frames.single { it.id == "TYER" }))
        assertWavInfo(edited, mapOf("INAM" to "Edited title", "IART" to "Edited singer", "IPRD" to "Edited album",
            "IGNR" to "Jazz", "ICRD" to "2026"))
        val actualLyrics = frames.filter { it.id == "USLT" }
        assertEquals(2, actualLyrics.size)
        rawLyrics.zip(actualLyrics).forEach { (expected, actual) ->
            org.junit.Assert.assertArrayEquals(expected.flags, actual.flags)
            org.junit.Assert.assertArrayEquals(expected.body, actual.body)
        }
        assertEquals(host.audioHash(source), host.audioHash(edited))

        val cleared = host.file("cleared.wav")
        val clear = MediaConvertMetadata.audioFields.fold(MediaConvertMetadata()) { value, field -> value.withValue(field, "") }
        host.run(MediaTagEditorCommand.build(edited.path, cleared.path, "wav", host.streams(edited), clear, true))
        MediaConvertId3Lyrics.restoreWavMetadata(edited, cleared, clear, changes)
        val clearedTags = host.tags(cleared)
        for (key in listOf("title", "artist", "album", "genre", "date", "year")) assertFalse(clearedTags, "$key=" in clearedTags)
        val clearedFrames = WavTagFixture.readTag(cleared).frames
        assertFalse(clearedFrames.any { it.id in setOf("TIT2", "TPE1", "TALB", "TCON", "TYER", "TDRC") })
        val remainingLyrics = clearedFrames.filter { it.id == "USLT" }
        assertEquals(2, remainingLyrics.size)
        rawLyrics.zip(remainingLyrics).forEach { (expected, actual) -> org.junit.Assert.assertArrayEquals(expected.body, actual.body) }
        assertEquals(host.audioHash(source), host.audioHash(cleared))
    }

    @Test fun oggAndOpusCommentsCanBeChangedAndClearedWithoutLosingUneditedTags() = withFfmpeg { host ->
        for ((extension, codec) in listOf("ogg" to "libvorbis", "opus" to "libopus")) {
            val source = host.file("source.$extension")
            host.run(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=48000", "-t", "0.3",
                "-c:a", codec, "-metadata:s:a:0", "title=Original track", "-metadata:s:a:0", "author=Original singer",
                "-metadata:s:a:0", "album=Original album", source.path))
            val edited = host.file("edited.$extension")
            host.run(MediaTagEditorCommand.build(source.path, edited.path, "ogg", host.streams(source),
                MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, "Edited track"), true))
            assertTrue(extension, "edited track" in host.inputInfo(edited))
            assertFalse(extension, "original track" in host.inputInfo(edited))
            val cleared = host.file("cleared.$extension")
            host.run(MediaTagEditorCommand.build(edited.path, cleared.path, "ogg", host.streams(edited),
                MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, "")
                    .withValue(MediaConvertMetadataField.ARTIST, ""), true))
            val clearedInfo = host.inputInfo(cleared)
            assertFalse(extension, "edited track" in clearedInfo)
            assertFalse(extension, "original singer" in clearedInfo)
            assertTrue(extension, "original album" in clearedInfo)
            assertEquals(extension, host.audioHash(source), host.audioHash(cleared))
        }
    }

    @Test fun clearingContainerTitleAlsoClearsPrimaryFallbacksAndKeepsAlternateTrackNames() = withFfmpeg { host ->
        val source = host.file("stream titles.mkv")
        host.run(listOf("-y", "-f", "lavfi", "-i", "color=size=64x64:rate=5",
            "-f", "lavfi", "-i", "sine=sample_rate=48000", "-t", "0.3",
            "-map", "0:v:0", "-map", "1:a:0", "-map", "1:a:0", "-c:v", "libx264", "-c:a", "aac",
            "-metadata:s:v:0", "title=Original stream title", "-metadata:s:a:0", "title=Original audio title",
            "-metadata:s:a:1", "title=Alternate commentary", "-metadata:s:v:0", "comment=Unedited comment", source.path))
        val cleared = host.file("cleared.mkv")
        host.run(MediaTagEditorCommand.build(source.path, cleared.path, "matroska", host.streams(source),
            MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, ""), false))
        val info = host.inputInfo(cleared)
        assertFalse("original stream title" in info)
        assertFalse("original audio title" in info)
        assertTrue("alternate commentary" in info)
        assertTrue("unedited comment" in info)
    }

    @Test fun artworkAndUneditedAudioTagsSurviveEverySupportedAudioContainer() = withFfmpeg { host ->
        val cover = host.cover()
        for ((extension, muxer, codec) in listOf(
            Triple("mp3", "mp3", "libmp3lame"), Triple("m4a", "ipod", "aac"), Triple("flac", "flac", "flac")
        )) {
            val source = host.file("source.$extension")
            host.run(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.6",
                "-c:a", codec, "-metadata", "title=Original title", "-metadata", "album=Original album", source.path))
            val custom = host.file("custom.$extension")
            val originalAudio = host.streams(source)
            host.run(MediaTagEditorCommand.build(source.path, custom.path, muxer, originalAudio,
                MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = cover.path)
                    .withValue(MediaConvertMetadataField.TITLE, "Edited title"), true))
            assertTrue(extension, "title=edited title" in host.tags(custom))
            assertTrue(extension, "album=original album" in host.tags(custom))
            assertTrue(extension, "attached pic" in host.inspect(custom))
            assertEquals("Audio packets changed in $extension", host.audioHash(source), host.audioHash(custom))

            val withPicture = host.streams(custom)
            val preserved = host.file("preserved.$extension")
            host.run(MediaTagEditorCommand.build(custom.path, preserved.path, muxer, withPicture,
                MediaConvertMetadata().withValue(MediaConvertMetadataField.ARTIST, "Singer"), true))
            assertTrue(extension, "attached pic" in host.inspect(preserved))
            assertTrue(extension, "album=original album" in host.tags(preserved))
            val cleared = host.file("cleared.$extension")
            host.run(MediaTagEditorCommand.build(preserved.path, cleared.path, muxer, withPicture,
                MediaConvertMetadata(cover = MediaConvertCoverMode.NONE).withValue(MediaConvertMetadataField.TITLE, ""), true))
            assertFalse(extension, "attached pic" in host.inspect(cleared))
            assertFalse(extension, "title=" in host.tags(cleared))
            assertTrue(extension, "album=original album" in host.tags(cleared))
        }
    }

    @Test fun tagUpdatesAndCoverReplacementKeepExtraAudioSubtitlesChaptersAndFonts() = withFfmpeg { host ->
        val cover = host.cover()
        val subtitles = host.file("captions.srt").apply { writeText("1\n00:00:00,000 --> 00:00:00,500\nHello\n") }
        val chapters = host.file("chapters.txt").apply {
            writeText(";FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=500\ntitle=Original chapter\n")
        }
        val font = host.file("font.ttf").apply { writeText("font attachment bytes") }
        for ((extension, muxer) in listOf("mp4" to "mp4", "mkv" to "matroska")) {
            val source = host.file("source.$extension")
            host.run(buildList {
                addAll(listOf("-y", "-f", "lavfi", "-i", "testsrc2=size=160x128:rate=25",
                    "-f", "lavfi", "-i", "sine=sample_rate=44100",
                    "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100", "-i", subtitles.path,
                    "-f", "ffmetadata", "-i", chapters.path, "-t", "0.6",
                    "-map", "0:v:0", "-map", "1:a:0", "-map", "2:a:0", "-map", "3:s:0",
                    "-map_chapters", "4", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac",
                    "-c:s", if (extension == "mp4") "mov_text" else "srt",
                    "-metadata", "title=Original film", "-metadata", "comment=Original comment",
                    "-metadata:s:a:1", "language=eng"))
                if (extension == "mkv") addAll(listOf("-attach", font.path,
                    "-metadata:s:t:0", "mimetype=application/x-truetype-font", "-metadata:s:t:0", "filename=font.ttf"))
                add(source.path)
            })
            val streams = host.streams(source)
            val tagged = host.file("tagged.$extension")
            host.run(MediaTagEditorCommand.build(source.path, tagged.path, muxer, streams,
                MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = cover.path)
                    .withValue(MediaConvertMetadataField.TITLE, "Edited film"), false))
            val info = host.inspect(tagged)
            assertTrue(extension, "title=edited film" in host.tags(tagged))
            assertTrue(extension, "comment=original comment" in host.tags(tagged))
            assertTrue(extension, "original chapter" in info)
            assertTrue(extension, "subtitle:" in info)
            assertTrue(extension, "audio:" in host.run(listOf("-i", tagged.path, "-map", "0:a:1", "-f", "null", "-")))
            assertEquals(extension, host.audioHash(source), host.audioHash(tagged))
            if (extension == "mkv") assertTrue("Font attachment was lost", "font.ttf" in info)
            assertTrue("Cover was lost in $extension", "attached pic" in info)

            val replaceStreams = host.streams(tagged)
            val replaced = host.file("replaced.$extension")
            host.run(MediaTagEditorCommand.build(tagged.path, replaced.path, muxer, replaceStreams,
                MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = cover.path), false))
            val replacedInfo = host.inspect(replaced)
            assertEquals("Duplicate covers in $extension", 1, Regex("attached pic").findAll(replacedInfo).count())
            assertTrue(extension, "subtitle:" in replacedInfo)
            if (extension == "mkv") assertTrue("Font attachment was lost on replacement", "font.ttf" in replacedInfo)
        }
    }

    private fun assertWavInfo(file: File, expected: Map<String, String>) {
        val bytes = WavTagFixture.chunks(file.readBytes()).single { it.first == "LIST" }.second
        assertEquals("INFO", String(bytes, 8, 4, Charsets.US_ASCII))
        val info = mutableMapOf<String, String>()
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = java.nio.ByteBuffer.wrap(bytes, offset + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            info[name] = String(bytes, offset + 8, size, Charsets.UTF_8).trimEnd('\u0000')
            offset += 8 + size + (size and 1)
        }
        expected.forEach { (name, value) -> assertEquals(name, value, info[name]) }
    }

    private fun withFfmpeg(block: (Host) -> Unit) {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG to run real tag remuxing checks", !executable.isNullOrBlank())
        val directory = Files.createTempDirectory("media tag editor ").toFile()
        try { block(Host(executable!!, directory)) } finally { directory.deleteRecursively() }
    }

    private class Host(private val executable: String, private val directory: File) {
        fun file(name: String) = File(directory, name)
        fun run(arguments: List<String>): String {
            val log = file("ffmpeg.log")
            val process = ProcessBuilder(listOf(executable, "-hide_banner", "-threads", "1", "-filter_threads", "1") + arguments)
                .redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("FFmpeg timed out: $arguments")
            }
            val output = log.readText().replace("\r\n", "\n")
            assertEquals("$arguments\n$output", 0, process.exitValue())
            return output
        }
        fun cover(): File = file("cover image.jpg").also {
            run(listOf("-y", "-f", "lavfi", "-i", "color=c=blue:size=64x64", "-frames:v", "1", "-threads", "1", it.path))
        }
        fun tags(file: File): String {
            val output = this.file("tags.txt")
            run(listOf("-y", "-i", file.path, "-map_metadata", "0", "-f", "ffmetadata", output.path))
            return output.readText().lowercase()
        }
        fun inspect(file: File): String = run(listOf("-i", file.path, "-map", "0:a:0", "-f", "null", "-"))
            .replace(Regex("\\s+"), " ").lowercase()
        fun inputInfo(file: File): String = run(listOf("-i", file.path, "-map", "0:a:0", "-f", "null", "-"))
            .substringBefore("Stream mapping:").lowercase()
        fun streams(file: File): List<MediaTagStream> {
            val input = run(listOf("-i", file.path, "-map", "0:a:0", "-f", "null", "-")).substringBefore("Stream mapping:")
            return Regex("(?m)^\\s*Stream #0:(\\d+)[^:]*: (Video|Audio|Subtitle|Attachment|Data):[^\\n]*")
                .findAll(input).map { match -> MediaTagStream(match.groupValues[1].toLong(),
                    match.groupValues[2].lowercase(), attachedPicture = "(attached pic)" in match.value) }.toList()
        }
        fun audioHash(file: File): String = run(listOf("-i", file.path, "-map", "0:a:0", "-c", "copy", "-f", "hash", "-hash", "sha256", "-"))
            .lineSequence().first { it.startsWith("SHA256=") }
    }
}
