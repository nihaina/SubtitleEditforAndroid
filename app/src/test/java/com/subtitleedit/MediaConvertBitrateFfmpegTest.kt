package com.subtitleedit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MediaConvertBitrateFfmpegTest {
    @Test
    fun differentBitrateTargetsAffectRealEncodingEvenWithASavedQualitySetting() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG to run bitrate encoding checks", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("bitrate encode ").toFile()
        fun run(args: List<String>) {
            val log = File(root, "ffmpeg.log")
            val process = ProcessBuilder(listOf(executable!!, "-loglevel", "error", "-filter_threads", "1") + args)
                .redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw AssertionError("FFmpeg timed out") }
            assertEquals(log.readText(), 0, process.exitValue())
        }
        try {
            val input = File(root, "source.mkv")
            run(listOf("-y", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30", "-t", "3", "-c:v", "ffv1", input.path))
            val format = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders).first { it.extension == "mp4" }
            fun encode(kbps: Int): File = File(root, "target-$kbps.mp4").also { output ->
                run(MediaConvertCommand.build(input.path, output.path, format,
                    MediaConvertOptions(videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.PRESET, presetKbps = kbps), selectedQualityId = "18"),
                    MediaConvertSource(true, 3.0, 30.0)))
            }
            val low = encode(250)
            val high = encode(2000)
            assertTrue("Expected a larger output at 2000 kbps; low=${low.length()}, high=${high.length()}", high.length() > low.length() * 1.5)
            println("250 kbps: ${low.length()} bytes; 2000 kbps: ${high.length()} bytes")
            val original = File(root, "original-rate.mp4")
            run(MediaConvertCommand.build(input.path, original.path, format,
                MediaConvertOptions(videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.ORIGINAL)),
                MediaConvertSource(true, 3.0, 30.0, videoBitrateBps = 500000)))
            assertTrue(original.length() > 0)
        } finally { root.deleteRecursively() }
    }
}
