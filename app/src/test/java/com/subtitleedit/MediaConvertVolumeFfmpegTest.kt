package com.subtitleedit

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MediaConvertVolumeFfmpegTest {
    @Test
    fun volumeChangesDecodedSamplesForVideoAndAudioOutputs() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG for volume checks", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("audio volume checks ").toFile()
        fun run(args: List<String>) {
            val log = File(root, "process.log")
            val process = ProcessBuilder(listOf(executable!!, "-loglevel", "error", "-filter_threads", "1", "-threads", "1") + args)
                .redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw AssertionError("Timeout: $args") }
            assertEquals(log.readText(), 0, process.exitValue())
        }
        fun rms(file: File): Double {
            val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            var sum = 0.0
            val count = buffer.remaining() / 2
            assertTrue(count > 1000)
            while (buffer.remaining() >= 2) { val sample = buffer.short.toDouble(); sum += sample * sample }
            return sqrt(sum / count)
        }
        try {
            val input = File(root, "source.mkv")
            run(listOf("-y", "-f", "lavfi", "-i", "testsrc2=size=160x128:rate=25", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000,volume=0.1",
                "-t", "0.6", "-c:v", "ffv1", "-c:a", "pcm_s16le", input.path))
            val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders)
            var count = 0
            for ((ext, codec) in listOf("wav" to "pcm_s16le", "flac" to "flac", "mkv" to "pcm_s16le")) {
                val volumes = mutableMapOf<Int, Double>()
                for (percent in listOf(0, 50, 100, 200, 1000)) {
                    val output = File(root, "output.$ext")
                    val command = MediaConvertCommand.build(input.path, output.path, formats.first { it.extension == ext },
                        MediaConvertOptions(selectedVideoCodec = "copy", selectedAudioCodec = codec, audioVolumePercent = percent),
                        MediaConvertSource(true, 0.6, audioSampleRate = 48000, audioChannels = 1, audioChannelLayout = "mono"))
                    run(command)
                    val pcm = File(root, "decoded.pcm")
                    run(listOf("-y", "-i", output.path, "-map", "0:a:0", "-vn", "-c:a", "pcm_s16le", "-f", "s16le", pcm.path))
                    volumes[percent] = rms(pcm)
                    count++
                }
                assertEquals("$ext mute", 0.0, volumes.getValue(0), 0.0)
                for (percent in listOf(50, 200, 1000)) {
                    assertEquals("$ext $percent%", percent / 100.0, volumes.getValue(percent) / volumes.getValue(100), 0.02)
                }
            }
            val silentVideo = File(root, "video-only.mkv")
            run(listOf("-y", "-i", input.path, "-map", "0:v:0", "-c:v", "copy", silentVideo.path))
            run(MediaConvertCommand.build(silentVideo.path, File(root, "no-audio.mkv").path, formats.first { it.extension == "mkv" },
                MediaConvertOptions(selectedVideoCodec = "copy", audioVolumePercent = 200), MediaConvertSource(true, 0.6)))
            println("Verified decoded volume for $count video/audio conversions and a video without an audio track")
        } finally { root.deleteRecursively() }
    }
}
