package com.subtitleedit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MediaConvertSubtitlesFfmpegTest {
    @Test fun multipleVideoSubtitlesAndAudioLyricsSurviveAndExistingTracksAreRetained() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG for subtitle integration checks", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("multiple subtitle checks ").toFile()
        fun run(program: String, arguments: List<String>): String {
            val log = File(root, "process.log")
            val process = ProcessBuilder(listOf(program) + arguments).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw AssertionError("Timed out: $arguments") }
            val text = log.readText().replace("\r\n", "\n")
            assertEquals("$arguments\n$text", 0, process.exitValue())
            return text
        }
        fun ffmpeg(arguments: List<String>) = run(executable!!, listOf("-v", "error", "-threads", "1", "-filter_threads", "1") + arguments)
        fun inspect(file: File) = run(executable!!, listOf("-hide_banner", "-threads", "1", "-i", file.path, "-f", "null", "-")).substringBefore("Stream mapping:")
        fun subtitleCount(file: File) = Regex("(?m)^\\s*Stream #\\d+:\\d+[^\\r\\n]*: Subtitle:").findAll(inspect(file)).count()
        fun hasVideo(file: File) = Regex("(?m)^\\s*Stream #\\d+:\\d+[^\\r\\n]*: Video:").containsMatchIn(inspect(file))
        fun decoded(file: File, index: Int): String {
            val output = File(root, "decoded-$index.srt")
            ffmpeg(listOf("-y", "-i", file.path, "-map", "0:s:$index", "-c:s", "srt", output.path))
            return output.readText()
        }
        try {
            val input = File(root, "source.mkv")
            ffmpeg(listOf("-y", "-f", "lavfi", "-i", "color=c=black:s=160x128:r=25", "-f", "lavfi", "-i", "sine=sample_rate=48000",
                "-t", "1", "-c:v", "libx264", "-c:a", "aac", input.path))
            val first = File(root, "First captions.srt").apply { writeText("1\n00:00:00,000 --> 00:00:00,900\nFirst caption\n") }
            val second = File(root, "Second captions.srt").apply { writeText("1\n00:00:00,000 --> 00:00:00,900\nSecond caption\n") }
            val lrc = File(root, "Timed lyrics.lrc").apply { writeText("[00:00.00]First lyric\n[00:00.80]Second lyric\n[00:00.90]\n") }
            val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders)
            val source = MediaConvertSource(true, 1.0, 25.0, audioSampleRate = 48000, audioChannels = 1,
                videoCodecName = "h264", videoPixelFormat = "yuv420p", audioCodecName = "aac")
            val added = listOf(first, second).map { MediaConvertSubtitleInput(it.path, it.name, MediaConvertSubtitleType.SOFT) }
            for (extension in listOf("mp4", "mkv", "webm")) {
                val output = File(root, "multi.$extension")
                ffmpeg(MediaConvertCommand.build(input.path, output.path, formats.first { it.extension == extension }, MediaConvertOptions(), source, subtitles = added))
                assertEquals(extension, 2, Regex("(?m)^\\s*Stream #\\d+:\\d+[^\\r\\n]*: Subtitle:").findAll(inspect(output)).count())
                assertTrue(extension, "First caption" in decoded(output, 0))
                assertTrue(extension, "Second caption" in decoded(output, 1))
            }
            val withTracks = File(root, "multi.mkv")
            val retainedSource = source.copy(subtitleStreams = listOf(
                MediaConvertSourceSubtitle(2, "subrip", "eng", "Original English", true),
                MediaConvertSourceSubtitle(3, "subrip", "fra", "Original French")))
            val appended = File(root, "retained.mp4")
            ffmpeg(MediaConvertCommand.build(withTracks.path, appended.path, formats.first { it.extension == "mp4" },
                MediaConvertOptions(), retainedSource, subtitles = listOf(added.first())))
            assertEquals(3, Regex("(?m)^\\s*Stream #\\d+:\\d+[^\\r\\n]*: Subtitle:").findAll(inspect(appended)).count())
            assertTrue("First caption" in decoded(appended, 0))
            assertTrue("Second caption" in decoded(appended, 1))
            assertTrue("First caption" in decoded(appended, 2))
            for (extension in listOf("mka")) {
                val output = File(root, "lyrics.$extension")
                val lyrics = listOf(lrc, second).mapIndexed { index, picked ->
                    val normalized = MediaConvertLyricText.toLrc(picked.readText(), picked.name, null)
                    val prepared = File(root, "prepared-lyrics-$index.srt").apply {
                        writeText(MediaConvertLyricText.lrcToSrt(normalized, 1.0))
                    }
                    MediaConvertSubtitleInput(prepared.path, prepared.name, MediaConvertSubtitleType.SOFT)
                }
                ffmpeg(MediaConvertCommand.build(input.path, output.path, formats.first { it.extension == extension }, MediaConvertOptions(), source, subtitles = lyrics))
                assertEquals(extension, 2, Regex("(?m)^\\s*Stream #\\d+:\\d+[^\\r\\n]*: Subtitle:").findAll(inspect(output)).count())
                assertFalse(extension, Regex("(?m)^\\s*Stream #\\d+:\\d+[^\\r\\n]*: Video:").containsMatchIn(inspect(output)))
                assertTrue(extension, "First lyric" in decoded(output, 0))
                assertTrue(extension, "Second caption" in decoded(output, 1))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun offeredTextSubtitlesMuxAndBurnWithEscapedPaths() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG for subtitle integration checks", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("subtitle checks ").toFile()
        fun run(program: String, arguments: List<String>): String {
            val log = File(root, "process.log")
            val process = ProcessBuilder(listOf(program) + arguments).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw AssertionError("Timed out: $arguments") }
            val text = log.readText()
            assertEquals("$arguments\n$text", 0, process.exitValue())
            return text
        }
        fun ffmpeg(arguments: List<String>) = run(executable!!, listOf("-v", "error", "-threads", "1", "-filter_threads", "1") + arguments)
        fun inspect(file: File) = run(executable!!, listOf("-hide_banner", "-threads", "1", "-i", file.path, "-f", "null", "-")).substringBefore("Stream mapping:")
        try {
            val input = File(root, "source.mkv")
            ffmpeg(listOf("-y", "-f", "lavfi", "-i", "color=c=black:s=320x240:r=25", "-f", "lavfi", "-i", "sine=sample_rate=48000",
                "-t", "1", "-c:v", "libx264", "-c:a", "aac", input.path))
            val srt = File(root, "subtitle [x],semi;quote'.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:00,900\nCaption\n", Charsets.UTF_8)
            }
            val preparedLrc = File(root, "from lyrics [x],semi;quote'.srt").apply {
                writeText(MediaConvertLyricText.lrcToSrt("[00:00.00]Caption\n[00:00.90]\n", 1.0))
            }
            val subtitles = listOf(srt, preparedLrc) + listOf("ass", "ssa", "vtt").map { ext ->
                val file = File(root, "subtitle [x],semi;quote'.$ext")
                ffmpeg(listOf("-y", "-i", srt.path, "-c:s", if (ext == "vtt") "webvtt" else "ass", "-f", if (ext == "vtt") "webvtt" else "ass", file.path))
                file
            }
            val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders)
            val source = MediaConvertSource(true, 1.0, 25.0, audioSampleRate = 48000, audioChannels = 1)
            for (ext in listOf("mp4", "mov", "m4v", "3gp", "3g2", "mkv", "webm")) {
                val format = formats.first { it.extension == ext }
                for (subtitle in subtitles) {
                    val output = File(root, "muxed.$ext")
                    val options = MediaConvertOptions(selectedVideoCodec = if (ext == "mkv") "copy" else "auto")
                    ffmpeg(MediaConvertCommand.build(input.path, output.path, format, options, source,
                        subtitle = MediaConvertSubtitleInput(subtitle.path, subtitle.name, MediaConvertSubtitleType.SOFT)))
                    assertTrue(inspect(output), Regex("(?m)^\\s*Stream #\\d+:\\d+[^\\r\\n]*: Subtitle:").containsMatchIn(inspect(output)))
                    val decoded = File(root, "decoded.srt")
                    ffmpeg(listOf("-y", "-i", output.path, "-map", "0:s:0", "-c:s", "srt", decoded.path))
                    assertTrue("$ext/${subtitle.extension}", decoded.readText().contains("Caption"))
                }
            }
            for (subtitle in subtitles) {
                val output = File(root, "burn.mp4")
                ffmpeg(MediaConvertCommand.build(input.path, output.path, formats.first { it.extension == "mp4" }, MediaConvertOptions(), source,
                    subtitle = MediaConvertSubtitleInput(subtitle.path, subtitle.name, MediaConvertSubtitleType.BURN_IN)))
                assertFalse(inspect(output), Regex("(?m)^\\s*Stream #\\d+:\\d+[^\\r\\n]*: Subtitle:").containsMatchIn(inspect(output)))
                val pixels = File(root, "frame.raw")
                ffmpeg(listOf("-y", "-ss", "0.3", "-i", output.path, "-map", "0:v:0", "-vf", "format=gray", "-frames:v", "1", "-f", "rawvideo", pixels.path))
                assertTrue("No rendered caption for ${subtitle.extension}", pixels.readBytes().any { (it.toInt() and 255) > 200 })
            }
        } finally { root.deleteRecursively() }
    }
}
