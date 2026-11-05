package com.subtitleedit

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaConvertNativeTest {
    @Test
    fun bundledEncodersConvertAllCommonVideoContainers() {
        val encoders = MediaConvertFormats.parseEncoders(FFmpegKit.execute("-hide_banner -encoders").getAllLogsAsString())
        assertTrue("Missing rebuilt software encoders", encoders.containsAll(setOf("libx264", "libx265", "libxvid", "libaom-av1", "libtheora")))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "media-native-${System.nanoTime()}").apply { mkdirs() }
        fun run(arguments: List<String>) {
            val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
            assertTrue(session.getAllLogsAsString().takeLast(3000), ReturnCode.isSuccess(session.getReturnCode()))
        }
        try {
            val input = File(root, "source with spaces.mkv")
            run(listOf("-y", "-f", "lavfi", "-i", "testsrc2=size=160x128:rate=25", "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.24", "-c:v", "ffv1", "-c:a", "pcm_s16le", input.path))
            for (format in MediaConvertFormats.create(encoders).filterNot { it.isAudioOnly }) {
                for (codec in format.videoCodecs.filter { it != "copy" }) {
                    val output = File(root, "output.${format.extension}")
                    run(MediaConvertCommand.build(input.path, output.path, format,
                        MediaConvertOptions(selectedVideoCodec = codec, selectedQualityId = "23"), MediaConvertSource(true, 0.24, 25.0)))
                    assertTrue("Empty ${format.extension}/$codec", output.length() > 0)
                    val info = FFprobeKit.getMediaInformation(output.path).getMediaInformation()
                        ?: throw AssertionError("Unrecognizable ${format.extension}/$codec")
                    assertTrue(info.getStreams().any { it.getType() == "video" })
                    assertTrue(info.getStreams().any { it.getType() == "audio" })
                }
            }
        } finally { root.deleteRecursively() }
    }
}
