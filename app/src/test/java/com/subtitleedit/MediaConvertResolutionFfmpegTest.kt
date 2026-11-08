package com.subtitleedit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MediaConvertResolutionFfmpegTest {
    @Test
    fun renderedFramesMatchFitFillStretchAndOriginalSizePlacement() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG to run rendered-frame checks", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("resolution frames ").toFile()
        fun frame(mode: MediaConvertFitMode, width: Int = 100, height: Int = 100, sar: Int = 1): ByteArray {
            val output = File(root, "frame.rgb")
            val log = File(root, "ffmpeg.log")
            val source = "color=c=blue:s=200x100:r=1,drawbox=x=60:y=10:w=80:h=80:color=red:t=fill,setsar=$sar"
            val filter = MediaConvertResolution.filter(MediaConvertResolutionSettings(-1, width, height, fitMode = mode), null, null)!!
            val process = ProcessBuilder(executable!!, "-y", "-loglevel", "error", "-filter_threads", "1", "-f", "lavfi", "-i", source,
                "-vf", filter, "-frames:v", "1", "-pix_fmt", "rgb24", "-f", "rawvideo", output.path)
                .redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw AssertionError("Filter timed out: $filter") }
            assertEquals(log.readText(), 0, process.exitValue())
            return output.readBytes().also { assertEquals(width * height * 3, it.size) }
        }
        fun ByteArray.pixel(x: Int, y: Int, width: Int = 100) = (0..2).map { this[(y * width + x) * 3 + it].toInt() and 255 }
        fun black(rgb: List<Int>) = rgb.all { it < 20 }
        fun red(rgb: List<Int>) = rgb[0] > 200 && rgb[1] < 20 && rgb[2] < 20
        fun blue(rgb: List<Int>) = rgb[2] > 200 && rgb[0] < 20 && rgb[1] < 20
        try {
            val fit = frame(MediaConvertFitMode.FIT)
            assertTrue(black(fit.pixel(50, 5)))
            assertTrue(red(fit.pixel(50, 50)))
            assertTrue(blue(fit.pixel(5, 50)))
            val fill = frame(MediaConvertFitMode.FILL)
            assertTrue(red(fill.pixel(15, 50)))
            assertTrue(blue(fill.pixel(5, 50)))
            val stretch = frame(MediaConvertFitMode.STRETCH)
            assertTrue(blue(stretch.pixel(15, 50)))
            assertTrue(red(stretch.pixel(50, 50)))
            val centered = frame(MediaConvertFitMode.CENTER, 300, 200)
            assertTrue(black(centered.pixel(5, 100, 300)))
            assertTrue(blue(centered.pixel(100, 100, 300)))
            assertTrue(red(centered.pixel(150, 100, 300)))
            val nonSquarePixels = frame(MediaConvertFitMode.FIT, sar = 2)
            assertTrue(black(nonSquarePixels.pixel(50, 30)))
            assertTrue(red(nonSquarePixels.pixel(50, 50)))
            // Portrait targets also need valid pad/crop dimensions for every mode.
            MediaConvertFitMode.entries.forEach { frame(it, 100, 200) }
        } finally { root.deleteRecursively() }
    }
}
