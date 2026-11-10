package com.subtitleedit

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.mpv.MPVLib
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Extracting all subtitle packets can pass even when they are physically behind the video. */
@RunWith(AndroidJUnit4::class)
class MediaConvertSubtitlesInterleavingTest {
    @Test fun mpvCanDisplayAndSeekLateSoftCaptionsWhenTheSourceHasACover() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "subtitle-interleaving-${System.nanoTime()}").apply { mkdirs() }
        var playerCreated = false
        try {
            val base = File(root, "base.mp4")
            val cover = File(root, "cover.png")
            val source = File(root, "source.mp4")
            val captions = File(root, "captions.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:01,000\nFirst\n\n" +
                    "2\n00:00:02,000 --> 00:00:04,000\nSecond\n\n" +
                    "3\n00:00:05,000 --> 00:00:07,000\nThird\n\n" +
                    "4\n00:00:20,000 --> 00:00:25,000\nLater caption\n\n" +
                    "5\n00:01:30,000 --> 00:01:35,000\nFinal caption\n")
            }
            run(listOf("-y", "-f", "lavfi", "-i", "color=c=black:s=160x120:r=5",
                "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "120",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", base.path))
            run(listOf("-y", "-f", "lavfi", "-i", "color=c=blue:s=64x64", "-frames:v", "1", cover.path))
            run(listOf("-y", "-i", base.path, "-i", cover.path, "-map", "0", "-map", "1:v:0",
                "-c", "copy", "-disposition:v:1", "attached_pic", source.path))
            val format = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders).first { it.extension == "mkv" }
            val output = File(root, "muxed.mkv")
            val preparedSource = MediaConvertSourceCover.prepare(source.path, root,
                MediaConvertSource(true, 120.0, 5.0, videoCodecName = "h264", videoStreamIndex = 0,
                    attachedPictureStreamIndex = 2, attachedPictureCodecName = "png"))
            run(MediaConvertCommand.build(source.path, output.path, format,
                MediaConvertOptions(selectedVideoCodec = "copy", selectedAudioCodec = "copy"),
                preparedSource,
                subtitle = MediaConvertSubtitleInput(captions.path, captions.name, MediaConvertSubtitleType.SOFT)))

            playerCreated = MPVLib.create(context)
            assertTrue(playerCreated)
            for ((name, value) in mapOf("vo" to "null", "ao" to "null", "pause" to "yes", "sid" to "1",
                "sub-auto" to "no", "terminal" to "no")) MPVLib.setOptionString(name, value)
            assertTrue(MPVLib.init() >= 0)
            MPVLib.command(arrayOf("loadfile", output.path))
            await { MPVLib.getPropertyDouble("duration") != null }
            for ((time, text) in listOf(22 to "Later caption", 92 to "Final caption", 22 to "Later caption")) {
                MPVLib.command(arrayOf("seek", time.toString(), "absolute+exact"))
                await { MPVLib.getPropertyString("sub-text") == text }
                assertEquals(text, MPVLib.getPropertyString("sub-text"))
            }
        } finally {
            if (playerCreated) MPVLib.destroy()
            root.deleteRecursively()
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue("mpv did not receive the caption at the seek position", predicate())
    }

    private fun run(arguments: List<String>) {
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        assertTrue(session.getAllLogsAsString().takeLast(2000), ReturnCode.isSuccess(session.getReturnCode()))
    }
}
