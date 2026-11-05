package com.subtitleedit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MediaConvertSampleRateFfmpegTest {
    @Test
    fun addedSamplingRatesEncodeAndMatchProbedAudioRate() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG for sample rate checks", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("sample rate matrix ").toFile()
        fun run(command: List<String>): Pair<Int, String> {
            val log = File(root, "process.log")
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw AssertionError("Timeout: $command") }
            return process.exitValue() to log.readText()
        }
        fun ffmpeg(args: List<String>) = run(listOf(executable!!, "-loglevel", "error", "-filter_threads", "1", "-threads", "1") + args)
        try {
            val encoders = ffmpeg(listOf("-hide_banner", "-loglevel", "info", "-encoders")).second
            val mkv = MediaConvertFormats.create(MediaConvertFormats.parseEncoders(encoders), MediaConvertAudioCodecs.parseEncoders(encoders)).first { it.extension == "mkv" }
            val source = File(root, "source.mkv")
            val generated = ffmpeg(listOf("-y", "-f", "lavfi", "-i", "testsrc2=size=160x128:rate=25", "-f", "lavfi", "-i", "sine=sample_rate=48000", "-t", "1.2", "-c:v", "ffv1", "-c:a", "pcm_s16le", "-ac", "2", source.path))
            assertEquals(generated.second, 0, generated.first)
            val ffprobe = File(executable!!).resolveSibling(if (executable.endsWith(".exe")) "ffprobe.exe" else "ffprobe").path
            val failures = mutableListOf<String>()
            var count = 0
            for (codec in mkv.audioCodecs.filter { it != "auto" && it != "copy" }) {
                for (index in MediaConvertAudioCodecs.sampleRateIndices(codec, mkv.formatName).filter { it >= 14 }) {
                    val rate = MediaConvertCommand.sampleRates[index]
                    val output = File(root, "output.mkv")
                    val encoded = ffmpeg(MediaConvertCommand.build(source.path, output.path, mkv,
                        MediaConvertOptions(selectedVideoCodec = "copy", selectedAudioCodec = codec, sampleRateIndex = index),
                        MediaConvertSource(true, 1.2, audioSampleRate = 48000, audioChannels = 2)))
                    if (encoded.first != 0) { failures += "$codec/$rate encode: ${encoded.second.takeLast(400)}"; continue }
                    val probed = run(listOf(ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate", "-of", "csv=p=0", output.path))
                    if (probed.first != 0 || probed.second.lineSequence().firstOrNull()?.trim()?.toIntOrNull() != rate) {
                        failures += "$codec/$rate probe: ${probed.second}"; continue
                    }
                    val decoded = ffmpeg(listOf("-i", output.path, "-map", "0:a:0", "-vn", "-f", "null", "-"))
                    if (decoded.first != 0) { failures += "$codec/$rate decode: ${decoded.second.takeLast(400)}"; continue }
                    count++
                }
            }
            println("Encoded/probed/decoded $count new sampling rate/codec combinations")
            assertTrue("Expected broad sample rate coverage", count >= 50)
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
