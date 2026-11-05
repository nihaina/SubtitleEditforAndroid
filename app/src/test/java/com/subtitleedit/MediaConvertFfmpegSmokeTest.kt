package com.subtitleedit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional host integration check; Android native execution is verified separately on a device. */
class MediaConvertFfmpegSmokeTest {
    @Test
    fun convertEveryContainerAndVideoEncoderCombination() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG to a host FFmpeg executable", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("media convert smoke ").toFile()
        fun run(args: List<String>): String {
            val log = File(root, "ffmpeg.log")
            val process = ProcessBuilder(listOf(executable!!, "-loglevel", "error", "-threads", "1", "-filter_threads", "1") + args)
                .redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("FFmpeg timed out: $args")
            }
            val output = log.readText()
            assertEquals("$args\n$output", 0, process.exitValue())
            return output
        }
        try {
            val encoders = MediaConvertFormats.parseEncoders(run(listOf("-hide_banner", "-loglevel", "info", "-encoders")))
            val formats = MediaConvertFormats.create(encoders)
            val input = File(root, "source with spaces.mkv")
            run(listOf("-y", "-f", "lavfi", "-i", "testsrc2=size=160x128:rate=25", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "0.24", "-c:v", "ffv1", "-c:a", "pcm_s16le", input.path))
            val source = MediaConvertSource(true, 0.24, 25.0)
            var count = 0
            for (format in formats) {
                val codecs = if (format.isAudioOnly) listOf("auto") else format.videoCodecs.filter { it != "copy" }
                for (codec in codecs) {
                    val target = File(root, "output.${format.extension}")
                    val options = MediaConvertOptions(selectedVideoCodec = codec, selectedQualityId = "23")
                    run(MediaConvertCommand.build(input.path, target.path, format, options, source))
                    assertTrue("Empty output: ${format.extension}/$codec", target.length() > 0)
                    count++
                }
            }
            assertTrue("Expected to exercise all 17 video containers and 9 audio containers", count >= 40)
            println("Converted $count container/encoder combinations using host FFmpeg")
        } finally {
            root.deleteRecursively()
        }
    }
}
