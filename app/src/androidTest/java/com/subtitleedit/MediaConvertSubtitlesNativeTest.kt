package com.subtitleedit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.subtitleedit.util.FfprobeMediaInformation
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.util.SubtitleFontManager
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaConvertSubtitlesNativeTest {
    @Test fun bundledSubtitleRendererAndMuxerProduceCaptions() {
        assertTrue("Missing libass subtitle filter", MediaConvertSubtitles.hasBurnInFilter(
            FFmpegKit.execute("-hide_banner -filters").getAllLogsAsString()))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "subtitle-native-${System.nanoTime()}").apply { mkdirs() }
        fun run(arguments: List<String>) {
            val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
            assertTrue(session.getAllLogsAsString().takeLast(3000), ReturnCode.isSuccess(session.getReturnCode()))
        }
        try {
            val input = File(root, "source.mkv")
            val subtitle = File(root, "caption [x],quote'.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:00,900\nCaption 字幕\n", Charsets.UTF_8)
            }
            run(listOf("-y", "-f", "lavfi", "-i", "color=c=black:s=320x240:r=25", "-t", "1", "-c:v", "libx264", input.path))
            val format = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders).first { it.extension == "mp4" }
            val source = MediaConvertSource(true, 1.0, 25.0)
            for (type in MediaConvertSubtitleType.entries) {
                val output = File(root, "output.mp4")
                run(MediaConvertCommand.build(input.path, output.path, format, MediaConvertOptions(), source,
                    subtitle = MediaConvertSubtitleInput(subtitle.path, subtitle.name, type,
                        if (type == MediaConvertSubtitleType.BURN_IN) SubtitleFontManager.writeFontConfig(context).path else null)))
                val streams = FfprobeMediaInformation.read(output.path, context.cacheDir).getStreams()
                if (type == MediaConvertSubtitleType.SOFT) {
                    assertTrue(streams.any { it.getType() == "subtitle" && it.getCodec() == "mov_text" })
                    val decoded = File(root, "decoded.srt")
                    run(listOf("-y", "-i", output.path, "-map", "0:s:0", "-c:s", "srt", decoded.path))
                    assertTrue(decoded.readText().contains("Caption 字幕"))
                } else {
                    assertFalse(streams.any { it.getType() == "subtitle" })
                    val pixels = File(root, "frame.raw")
                    run(listOf("-y", "-ss", "0.3", "-i", output.path, "-vf", "format=gray", "-frames:v", "1", "-f", "rawvideo", pixels.path))
                    assertTrue("Caption not rendered on black video", pixels.readBytes().any { (it.toInt() and 255) > 200 })
                }
            }
        } finally { root.deleteRecursively() }
    }
}
