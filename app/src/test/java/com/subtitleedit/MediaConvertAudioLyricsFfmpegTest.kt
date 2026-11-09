package com.subtitleedit

import com.subtitleedit.util.SubtitleParser
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MediaConvertAudioLyricsFfmpegTest {
    @Test fun nativeLyricFieldsRoundTripWithoutChangingAudioOrUneditedTags() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG to verify native lyric fields", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("native lyric tags ").toFile()
        fun run(arguments: List<String>): String {
            val log = File(root, "ffmpeg.log")
            val process = ProcessBuilder(listOf(executable!!, "-hide_banner", "-threads", "1", "-filter_threads", "1") + arguments)
                .redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw AssertionError("FFmpeg timed out") }
            val text = log.readText().replace("\r\n", "\n")
            assertEquals("$arguments\n$text", 0, process.exitValue())
            return text
        }
        fun inspect(file: File) = run(listOf("-i", file.path, "-map", "0:a:0", "-f", "null", "-")).substringBefore("Stream mapping:")
        fun hash(file: File) = run(listOf("-i", file.path, "-map", "0:a:0", "-c", "copy", "-f", "hash", "-hash", "sha256", "-"))
            .lineSequence().first { it.startsWith("SHA256=") }
        try {
            val lrc = "[00:00.00]First lyric\n[00:00.25]Second lyric"
            val lyric = MediaConvertLyric("English", lrc)
            val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders,
                MediaConvertAudioCodecs.baselineEncoders + setOf("wavpack", "libspeex"))
            for ((extension, codec) in listOf("flac" to "flac", "ogg" to "libvorbis", "opus" to "libopus",
                "spx" to "libspeex", "m4a" to "aac", "m4b" to "aac", "wma" to "wmav2", "wv" to "wavpack")) {
                val format = formats.first { it.extension == extension }
                val source = File(root, "source.$extension")
                run(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=32000", "-t", "0.5", "-c:a", codec,
                    "-metadata", "title=Unchanged title", "-metadata", "album=Unchanged album", "-f", format.formatName, source.path))
                val output = File(root, "tagged.$extension")
                run(MediaConvertCommand.build(source.path, output.path, format,
                    MediaConvertOptions(selectedAudioCodec = "copy"), MediaConvertSource(false, 0.5),
                    lyrics = listOf(lyric)))
                val info = inspect(output)
                assertTrue("$extension\n$info", "First lyric" in info && "Second lyric" in info)
                assertTrue(extension, "Unchanged title" in info && "Unchanged album" in info)
                assertEquals("Audio packets changed in $extension", hash(source), hash(output))
                val bytes = output.readBytes()
                val ascii = bytes.toString(Charsets.ISO_8859_1)
                when (extension) {
                    "m4a", "m4b" -> {
                        assertTrue(extension, "\u00a9lyr" in ascii)
                        assertFalse(extension, "[00:00.00]First lyric" in ascii)
                    }
                    "wma" -> assertTrue(extension, "WM/Lyrics" in bytes.toString(Charsets.UTF_16LE))
                    "wv" -> assertTrue(extension, "APETAGEX" in ascii)
                    else -> assertTrue(extension, "LYRICS=$lrc" in ascii)
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun mp3AacAndWavNativeFramesPreserveAudioAndMetadataThroughConversion() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG to verify MP3/AAC/WAV lyric writes", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("id3 lyric conversion ").toFile()
        fun run(arguments: List<String>): String {
            val log = File(root, "ffmpeg.log")
            val process = ProcessBuilder(listOf(executable!!, "-hide_banner", "-threads", "1", "-filter_threads", "1") + arguments)
                .redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw AssertionError("FFmpeg timed out") }
            val text = log.readText().replace("\r\n", "\n")
            assertEquals("$arguments\n$text", 0, process.exitValue())
            return text
        }
        fun hash(file: File) = run(listOf("-i", file.path, "-map", "0:a:0", "-c", "copy", "-f", "hash", "-hash", "sha256", "-"))
            .lineSequence().first { it.startsWith("SHA256=") }
        fun littleEndian(bytes: ByteArray, start: Int) = (3 downTo 0).fold(0L) { value, index ->
            (value shl 8) or (bytes[start + index].toLong() and 255)
        }
        fun frameIds(bytes: ByteArray, start: Int): List<String> {
            assertEquals("ID3", String(bytes, start, 3, Charsets.US_ASCII))
            val version = bytes[start + 3].toInt()
            assertTrue(version in 3..4)
            val size = (6..9).fold(0) { value, index -> (value shl 7) or (bytes[start + index].toInt() and 127) }
            var cursor = start + 10
            val ids = mutableListOf<String>()
            while (cursor < start + 10 + size && bytes[cursor] != 0.toByte()) {
                ids += String(bytes, cursor, 4, Charsets.US_ASCII)
                val length = (4..7).fold(0) { value, index ->
                    (value shl (if (version == 4) 7 else 8)) or
                        (bytes[cursor + index].toInt() and (if (version == 4) 127 else 255))
                }
                cursor += 10 + length
            }
            assertEquals(start + 10 + size, cursor)
            return ids
        }
        try {
            val input = File(root, "source.wav")
            run(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.5",
                "-metadata", "title=Original title", input.path))
            val lyrics = listOf(MediaConvertLyric("中文", "[00:00.00]海风 🌊\n[00:00.25]Second line\n"),
                MediaConvertLyric("English", "[00:00.00]Sea breeze\n"))
            val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders)
            for (extension in listOf("mp3", "aac", "wav")) {
                val output = File(root, "tagged.$extension")
                run(MediaConvertCommand.build(input.path, output.path, formats.first { it.extension == extension },
                    MediaConvertOptions(), MediaConvertSource(false, 0.5, audioSampleRate = 44100, audioChannels = 1),
                    lyrics = lyrics))
                assertTrue(extension, "Original title" in output.readBytes().toString(Charsets.ISO_8859_1))
                val before = hash(output)
                when (extension) {
                    "mp3" -> MediaConvertId3Lyrics.writeMp3(output, lyrics)
                    "aac" -> MediaConvertId3Lyrics.writeAac(output, lyrics)
                    else -> MediaConvertId3Lyrics.writeWav(output, lyrics)
                }
                assertEquals(extension, before, hash(output))
                val info = run(listOf("-i", output.path, "-map", "0:a:0", "-f", "null", "-"))
                val bytes = output.readBytes()
                // Some host FFmpeg builds fail to read their own Latin-1 ID3 text frames.
                // Verify the untouched title bytes as well as decoded Unicode lyric tags.
                assertTrue(extension, "Original title" in bytes.toString(Charsets.ISO_8859_1))
                assertTrue("$extension\n$info", "海风" in info && "Sea breeze" in info)
                val id3Offset = if (extension != "wav") 0 else {
                    assertEquals(bytes.size - 8L, littleEndian(bytes, 4))
                    var cursor = 12
                    while (String(bytes, cursor, 4, Charsets.US_ASCII) != "id3 ") {
                        val size = littleEndian(bytes, cursor + 4).toInt()
                        cursor += 8 + size + (size and 1)
                        assertTrue(cursor + 8 <= bytes.size)
                    }
                    cursor + 8
                }
                val frames = frameIds(bytes, id3Offset)
                assertEquals(extension, 2, frames.count { it == "USLT" })
                assertEquals(extension, if (extension != "wav") 2 else 0, frames.count { it == "SYLT" })
                val detected = output.inputStream().use { MediaConvertId3LyricsReader.read(it, wav = extension == "wav") }
                assertEquals(lyrics.map { it.lrc }, detected.filter { it.field == "USLT" }.map { it.text })
                assertEquals(extension, if (extension != "wav") 2 else 0, detected.count { it.field == "SYLT" })
                assertTrue(detected.all { it.synced })
                assertTrue("Detection must not change the source", bytes.contentEquals(output.readBytes()))
                if (extension == "aac") {
                    assertTrue(info, "Audio: aac" in info)
                    assertFalse("AAC must remain ADTS rather than MP4", bytes.toString(Charsets.ISO_8859_1).contains("ftyp"))
                    val tagSize = (6..9).fold(0) { value, index -> (value shl 7) or (bytes[index].toInt() and 127) }
                    val audioStart = 10 + tagSize
                    assertEquals(255, bytes[audioStart].toInt() and 255)
                    assertEquals(0xf0, bytes[audioStart + 1].toInt() and 0xf0)
                    assertEquals(listOf(0L, 250L), SubtitleParser.parseLRC(detected.first { it.field == "SYLT" }.text).map { it.startTime })

                    val replacement = listOf(MediaConvertLyric("Replacement", "[00:00.125]新歌词 🎵\n"))
                    MediaConvertId3Lyrics.writeAac(output, replacement)
                    assertEquals("AAC packets changed while replacing lyrics", before, hash(output))
                    val replaced = output.inputStream().use { MediaConvertId3LyricsReader.read(it, wav = false) }
                    assertEquals(listOf("USLT", "SYLT"), replaced.map { it.field })
                    assertTrue(replaced.all { it.title == "Replacement" })
                    assertEquals(replacement.single().lrc, replaced.first().text)
                    assertEquals(listOf(125L), SubtitleParser.parseLRC(replaced.last().text).map { it.startTime })
                    assertTrue("Replacement must preserve the title", "Original title" in output.readBytes().toString(Charsets.ISO_8859_1))
                    val replacementInfo = run(listOf("-i", output.path, "-map", "0:a:0", "-f", "null", "-"))
                    assertTrue(replacementInfo, "新歌词" in replacementInfo)
                }
            }
        } finally { root.deleteRecursively() }
    }
}
