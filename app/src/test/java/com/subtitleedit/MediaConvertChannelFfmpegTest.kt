package com.subtitleedit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MediaConvertChannelFfmpegTest {
    @Test
    fun offeredMultichannelLayoutsEncodeProbeAndDecode() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG for channel layout checks", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("channel layout matrix ").toFile()
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
            val generated = ffmpeg(listOf("-y", "-f", "lavfi", "-i", "testsrc2=size=160x128:rate=25", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=7.1", "-t", "1.2", "-c:v", "ffv1", "-c:a", "pcm_s16le", source.path))
            assertEquals(generated.second, 0, generated.first)
            val ffprobe = File(executable!!).resolveSibling(if (executable.endsWith(".exe")) "ffprobe.exe" else "ffprobe").path
            val failures = mutableListOf<String>()
            val probeSource = MediaConvertSource(true, 1.2, audioSampleRate = 48000, audioChannels = 8, audioChannelLayout = "7.1")
            var count = 0
            for (codec in mkv.audioCodecs.filter { it != "auto" && it != "copy" }) {
                val indices = listOf(0) + MediaConvertAudioChannels.available(codec, mkv.formatName).filter { it.index >= 3 }.map { it.index }
                for (index in indices) {
                    val options = MediaConvertOptions(selectedVideoCodec = "copy", selectedAudioCodec = codec, channelIndex = index)
                    val parameters = MediaConvertAudioCodecs.parameters(codec, mkv.formatName, options, probeSource)
                    val output = File(root, "output.mkv")
                    val encoded = ffmpeg(MediaConvertCommand.build(source.path, output.path, mkv, options, probeSource))
                    if (encoded.first != 0) { failures += "$codec/$index encode: ${encoded.second.takeLast(700)}"; continue }
                    val probed = run(listOf(ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=channels,channel_layout", "-of", "default=nw=1", output.path))
                    val properties = probed.second.lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').trim() }
                    val expectedCount = parameters.channels ?: probeSource.audioChannels
                    if (probed.first != 0 || properties["channels"]?.toIntOrNull() != expectedCount) {
                        failures += "$codec/$index channels: ${probed.second}"; continue
                    }
                    val actualLayout = properties["channel_layout"]
                    if (!MediaConvertAudioChannels.recordsLayout(codec, mkv.formatName)) {
                        if (actualLayout != null && actualLayout != "unknown") { failures += "$codec/$index unexpected PCM layout: $actualLayout"; continue }
                    } else if (parameters.channelLayout != null && actualLayout != parameters.channelLayout) {
                        failures += "$codec/$index layout expected ${parameters.channelLayout}: ${probed.second}"; continue
                    }
                    val decoded = ffmpeg(listOf("-i", output.path, "-map", "0:a:0", "-vn", "-f", "null", "-"))
                    if (decoded.first != 0) { failures += "$codec/$index decode: ${decoded.second.takeLast(500)}"; continue }
                    count++
                }
            }
            println("Encoded/probed/decoded $count channel layout/codec combinations")
            assertTrue("Expected broad channel layout coverage", count >= 50)
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
