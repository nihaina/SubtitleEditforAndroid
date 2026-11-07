package com.subtitleedit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MediaConvertSubtitlesFfmpegTest {
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
        val probe = File(File(executable!!).parentFile, if (executable.endsWith(".exe")) "ffprobe.exe" else "ffprobe").path
        fun inspect(file: File) = run(probe, listOf("-v", "error", "-show_entries", "stream=codec_type,codec_name", "-of", "json", file.path))
        try {
            val input = File(root, "source.mkv")
            ffmpeg(listOf("-y", "-f", "lavfi", "-i", "color=c=black:s=320x240:r=25", "-f", "lavfi", "-i", "sine=sample_rate=48000",
                "-t", "1", "-c:v", "libx264", "-c:a", "pcm_s16le", input.path))
            val srt = File(root, "subtitle [x],semi;quote'.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:00,900\nCaption\n", Charsets.UTF_8)
            }
            val subtitles = listOf(srt) + listOf("ass", "ssa", "vtt").map { ext ->
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
                    assertTrue(inspect(output).contains("\"codec_type\": \"subtitle\""))
                    val decoded = File(root, "decoded.srt")
                    ffmpeg(listOf("-y", "-i", output.path, "-map", "0:s:0", "-c:s", "srt", decoded.path))
                    assertTrue("$ext/${subtitle.extension}", decoded.readText().contains("Caption"))
                }
            }
            for (subtitle in subtitles) {
                val output = File(root, "burn.mp4")
                ffmpeg(MediaConvertCommand.build(input.path, output.path, formats.first { it.extension == "mp4" }, MediaConvertOptions(), source,
                    subtitle = MediaConvertSubtitleInput(subtitle.path, subtitle.name, MediaConvertSubtitleType.BURN_IN)))
                assertFalse(inspect(output).contains("\"codec_type\": \"subtitle\""))
                val pixels = File(root, "frame.raw")
                ffmpeg(listOf("-y", "-ss", "0.3", "-i", output.path, "-map", "0:v:0", "-vf", "format=gray", "-frames:v", "1", "-f", "rawvideo", pixels.path))
                assertTrue("No rendered caption for ${subtitle.extension}", pixels.readBytes().any { (it.toInt() and 255) > 200 })
            }
        } finally { root.deleteRecursively() }
    }
}
