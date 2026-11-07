package com.subtitleedit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MediaConvertVbrFfmpegTest {
    @Test
    fun allTenLameLevelsProduceDecodableVariableBitrateAudio() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG for VBR encoding checks", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("audio vbr checks ").toFile()
        fun run(program: String, args: List<String>): String {
            val log = File(root, "process.log")
            val process = ProcessBuilder(listOf(program) + args).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw AssertionError("Timeout: $args") }
            val output = log.readText()
            assertEquals("$args\n$output", 0, process.exitValue())
            return output
        }
        fun ffmpeg(args: List<String>) = run(executable!!, listOf("-loglevel", "error", "-filter_threads", "1", "-threads", "1") + args)
        try {
            val probe = File(File(executable!!).parentFile, if (executable.endsWith(".exe")) "ffprobe.exe" else "ffprobe").path
            val input = File(root, "source.wav")
            ffmpeg(listOf("-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "2", "-ac", "2", input.path))
            val format = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders).first { it.extension == "mp3" }
            for (quality in MediaConvertAudioVbr.qualities) {
                val output = File(root, "vbr-$quality.mp3")
                ffmpeg(MediaConvertCommand.build(input.path, output.path, format,
                    MediaConvertOptions(audioVbrQuality = quality, audioBitrate = MediaConvertAudioBitrate.ORIGINAL),
                    MediaConvertSource(false, 2.0, audioSampleRate = 44100, audioChannels = 2)))
                assertTrue(output.length() > 0)
                val packetSizes = run(probe, listOf("-v", "error", "-select_streams", "a:0", "-show_entries", "packet=size", "-of", "csv=p=0", output.path))
                    .lineSequence().mapNotNull { it.substringBefore(',').trim().toIntOrNull() }.toSet()
                assertTrue("q=$quality should use variable frame sizes: $packetSizes", packetSizes.size > 1)
                ffmpeg(listOf("-i", output.path, "-map", "0:a:0", "-f", "null", "-"))
            }
        } finally { root.deleteRecursively() }
    }
}
