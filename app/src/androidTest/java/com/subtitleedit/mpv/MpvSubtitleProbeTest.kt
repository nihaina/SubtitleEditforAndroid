package com.subtitleedit.mpv

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MpvSubtitleProbeTest {
    @Test fun audioLyricMetadataIsSeparateFromTracksAndRefreshesForEachFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "mpv-lyric-metadata-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(root, "source.wav")
            runFfmpeg(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.5", source.path))
            val lrc = "[00:00.10]海风 🌊\n[00:00.30]Second line"
            val samples = listOf(Triple("aac", "aac", "lyrics"), Triple("flac", "flac", "LYRICS"), Triple("ogg", "libvorbis", "LYRICS"),
                Triple("opus", "libopus", "LYRICS"), Triple("m4a", "aac", "lyrics"),
                Triple("wma", "wmav2", "WM/Lyrics"), Triple("wv", "wavpack", "Lyrics")).map { (ext, codec, key) ->
                val output = File(root, "with-lyrics.$ext")
                val id3 = if (ext == "aac") listOf("-write_id3v2", "1") else emptyList()
                runFfmpeg(listOf("-y", "-i", source.path, "-c:a", codec, "-metadata", "$key=$lrc") + id3 + output.path)
                output
            }
            runBlocking {
                repeat(2) {
                    for (file in samples) {
                        val result = MpvSubtitleProbe.readDetails(context, Uri.fromFile(file))
                        assertTrue("Tags must not create subtitle stream indices", result.subtitleTracks.isEmpty())
                        assertTrue("Missing lyrics in ${file.extension}: ${result.lyricMetadata}",
                            result.lyricMetadata.any { (_, value) -> value == lrc })
                        val plain = MpvSubtitleProbe.readDetails(context, Uri.fromFile(source))
                        assertTrue(plain.lyricMetadata.isEmpty())
                    }
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun readsActualStreamIndicesAndUnicodeMetadataFromTheBundledPlayer() = withMedia { context, root, source ->
        val unicodeSource = File(root, "视频 🌊.mkv").also { source.copyTo(it) }
        val tracks = runBlocking { MpvSubtitleProbe.read(context, Uri.fromFile(unicodeSource)) }

        // mpv's subtitle ids are 1/2, but FFmpeg maps the container streams 2/3.
        assertEquals(listOf(2L, 3L), tracks.map { it.ffIndex })
        assertEquals(listOf("subrip", "subrip"), tracks.map { it.codec })
        assertEquals(listOf("zho", "eng"), tracks.map { it.language })
        assertEquals(listOf("中文字幕 🌊", "English captions"), tracks.map { it.title })
        assertTrue(tracks.first().isDefault)
        assertFalse(tracks.last().isDefault)
    }

    @Test fun noSubtitleAndFailedLoadsReleaseTheProbeForTheNextRequest() = withMedia { context, root, source ->
        val withoutSubtitles = File(root, "without-subtitles.mkv")
        runFfmpeg(listOf("-y", "-i", source.path, "-map", "0:v:0", "-map", "0:a:0", "-c", "copy", "-sn", withoutSubtitles.path))

        runBlocking {
            assertTrue(MpvSubtitleProbe.read(context, Uri.fromFile(withoutSubtitles)).isEmpty())
            try {
                MpvSubtitleProbe.read(context, Uri.fromFile(File(root, "missing.mkv")))
                throw AssertionError("A failed media load must not be reported as an empty subtitle list")
            } catch (_: IOException) {
                // The failed instance must be released before the following request.
            }
            assertEquals(listOf(2L, 3L), MpvSubtitleProbe.read(context, Uri.fromFile(source)).map { it.ffIndex })
        }
    }

    @Test fun repeatedSelectionsReadEachSourcesCurrentTracksInsteadOfTheFirstResult() = withMedia { context, root, source ->
        val otherSource = File(root, "other-source.mkv")
        runFfmpeg(listOf("-y", "-i", source.path, "-map", "0:v:0", "-map", "0:a:0", "-map", "0:s:1",
            "-c", "copy", "-metadata:s:s:0", "title=Other source", "-disposition:s:0", "0", otherSource.path))

        runBlocking {
            repeat(3) {
                val firstTracks = MpvSubtitleProbe.read(context, Uri.fromFile(source))
                assertEquals(listOf(2L, 3L), firstTracks.map { it.ffIndex })
                assertEquals(listOf("中文字幕 🌊", "English captions"), firstTracks.map { it.title })

                val otherTracks = MpvSubtitleProbe.read(context, Uri.fromFile(otherSource))
                assertEquals(listOf(2L), otherTracks.map { it.ffIndex })
                assertEquals(listOf("Other source"), otherTracks.map { it.title })
                assertFalse(otherTracks.single().isDefault)

                assertEquals(firstTracks, MpvSubtitleProbe.read(context, Uri.fromFile(source)))
            }
        }
    }

    @Test fun probingDoesNotReplaceOrDestroyTheExistingEditorPlayer() = withMedia { context, _, source ->
        val loaded = CountDownLatch(1)
        val observer = object : MPVLib.EventObserver {
            override fun event(eventId: Int) {
                if (eventId == MPVLib.MpvEvent.FILE_LOADED) loaded.countDown()
            }
        }
        val created = MPVLib.create(context.applicationContext)
        assertTrue("The native test requires an unused editor playback session", created)
        try {
            for ((name, value) in listOf(
                "config" to "no", "vo" to "null", "ao" to "null", "pause" to "yes",
                "terminal" to "no", "idle" to "yes", "keep-open" to "yes", "sub-auto" to "no"
            )) assertTrue("Unable to set $name", MPVLib.setOptionString(name, value) >= 0)
            assertTrue("Editor player initialization failed", MPVLib.init() >= 0)
            MPVLib.addObserver(observer)
            MPVLib.command(arrayOf("loadfile", source.absolutePath, "replace"))
            assertTrue("Editor source did not finish loading", loaded.await(10, TimeUnit.SECONDS))
            MPVLib.setPropertyDouble("volume", 37.0)
            val path = MPVLib.getPropertyString("path")
            val trackCount = MPVLib.getPropertyInt("track-list/count")

            val tracks = runBlocking { MpvSubtitleProbe.read(context, Uri.fromFile(source)) }

            assertEquals(listOf(2L, 3L), tracks.map { it.ffIndex })
            assertEquals(path, MPVLib.getPropertyString("path"))
            assertEquals(trackCount, MPVLib.getPropertyInt("track-list/count"))
            assertEquals(true, MPVLib.getPropertyBoolean("pause"))
            assertEquals(37.0, MPVLib.getPropertyDouble("volume") ?: Double.NaN, 0.001)
        } finally {
            MPVLib.removeObserver(observer)
            if (created) MPVLib.destroy()
        }
    }

    private fun withMedia(block: (Context, File, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "mpv-probe-${System.nanoTime()}").apply { mkdirs() }
        try {
            val first = File(root, "first.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:00,600\n中文字幕\n", Charsets.UTF_8)
            }
            val second = File(root, "second.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:00,600\nEnglish captions\n", Charsets.UTF_8)
            }
            val source = File(root, "source.mkv")
            runFfmpeg(listOf(
                "-y", "-f", "lavfi", "-i", "color=c=black:s=64x64:r=10",
                "-f", "lavfi", "-i", "sine=sample_rate=48000", "-i", first.path, "-i", second.path,
                "-t", "0.7", "-map", "0:v:0", "-map", "1:a:0", "-map", "2:s:0", "-map", "3:s:0",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-c:s", "srt",
                "-metadata:s:s:0", "language=zho", "-metadata:s:s:0", "title=中文字幕 🌊",
                "-metadata:s:s:1", "language=eng", "-metadata:s:s:1", "title=English captions",
                "-disposition:s:0", "default", "-disposition:s:1", "0", source.path
            ))
            block(context, root, source)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun runFfmpeg(arguments: List<String>) {
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        assertTrue(session.getAllLogsAsString().takeLast(3000), ReturnCode.isSuccess(session.getReturnCode()))
    }
}
