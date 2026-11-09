package com.subtitleedit

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reopening the source picker must start a fresh probe, even for the same path. */
@RunWith(AndroidJUnit4::class)
class MediaConvertRepeatedProbeTest {
    @Test fun selectingFirstSecondAndFirstAgainRefreshesTracksAndKeepsTheOutputFormat() = withSources { first, second ->
        ActivityScenario.launch(MediaConvertActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
                model.selectFiles(listOf(Uri.fromFile(first)))
                model.selectFormat("mp4")
            }
            awaitTracks(scenario, first, listOf("First source", "First source English"))

            for ((source, titles) in listOf(
                second to listOf("Second source"),
                first to listOf("First source", "First source English"),
                first to listOf("First source", "First source English")
            )) {
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
                    model.selectFiles(listOf(Uri.fromFile(source)))
                    assertTrue(model.state.value.isReadingSourceSubtitles)
                    assertTrue(model.state.value.sourceSubtitleTracks.isEmpty())
                    assertEquals("mp4", model.state.value.selectedFormat)
                }
                awaitTracks(scenario, source, titles)
            }
        }
    }

    @Test fun rapidReselectionsOnlyPublishTheFinalSourcesTracks() = withSources { first, second ->
        ActivityScenario.launch(MediaConvertActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
                model.selectFiles(listOf(Uri.fromFile(first)))
                model.selectFormat("mp4")
            }
            awaitTracks(scenario, first, listOf("First source", "First source English"))

            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
                model.selectFiles(listOf(Uri.fromFile(first)))
                model.selectFiles(listOf(Uri.fromFile(second)))
                model.selectFiles(listOf(Uri.fromFile(first)))
                assertTrue(model.state.value.isReadingSourceSubtitles)
                assertTrue(model.state.value.sourceSubtitleTracks.isEmpty())
            }
            awaitTracks(scenario, first, listOf("First source", "First source English"))

            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[MediaConvertViewModel::class.java].state.value
                assertEquals(listOf(2L, 3L), state.sourceSubtitleTracks.map { it.streamIndex })
                assertEquals("mp4", state.selectedFormat)
                assertFalse(state.sourceSubtitleProbeFailed)
            }
        }
    }

    @Test fun reselectingAudioRefreshesId3AndCommentLyricsWithoutKeepingThePreviousSourcesTags() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "media-convert-repeat-lyrics-${System.nanoTime()}").apply { mkdirs() }
        try {
            val plain = File(root, "no-lyrics.wav")
            runFfmpeg(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.7", plain.path))
            val mp3 = File(root, "two-lyrics.mp3")
            runFfmpeg(listOf("-y", "-i", plain.path, "-c:a", "libmp3lame", "-id3v2_version", "3", mp3.path))
            MediaConvertId3Lyrics.writeMp3(mp3, listOf(
                MediaConvertLyric("Chinese lyrics", "[00:00.10]海风\n[00:00.40]第二行\n"),
                MediaConvertLyric("English lyrics", "[00:00.10]Sea breeze\n")
            ))
            val flac = File(root, "comment-lyrics.flac")
            val flacLrc = "[00:00.20]Comment lyric\n[00:00.50]Final line\n"
            runFfmpeg(listOf("-y", "-i", plain.path, "-c:a", "flac", "-metadata", "LYRICS=$flacLrc", flac.path))

            ActivityScenario.launch(MediaConvertActivity::class.java).use { scenario ->
                for (source in listOf(mp3, plain, flac, mp3, mp3)) {
                    scenario.onActivity { activity ->
                        val model = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
                        model.selectFiles(listOf(Uri.fromFile(source)))
                        if (model.state.value.selectedFormat == null) model.selectFormat("mp3")
                        assertTrue(model.state.value.isReadingSourceSubtitles)
                        assertTrue(model.state.value.sourceEmbeddedLyrics.isEmpty())
                        assertEquals("mp3", model.state.value.selectedFormat)
                    }
                    val state = awaitLyrics(scenario, source)
                    assertTrue("Lyric metadata must not become subtitle streams", state.sourceSubtitleTracks.isEmpty())
                    when (source) {
                        mp3 -> {
                            assertEquals("The USLT metadata copy must not be listed twice", 4, state.sourceEmbeddedLyrics.size)
                            assertEquals(mapOf("USLT" to 2, "SYLT" to 2), state.sourceEmbeddedLyrics.groupingBy { it.field }.eachCount())
                            assertEquals(setOf("Chinese lyrics", "English lyrics"), state.sourceEmbeddedLyrics.map { it.title }.toSet())
                            assertTrue(state.sourceEmbeddedLyrics.all { it.synced })
                            assertTrue(state.sourceEmbeddedLyrics.any { "海风" in it.text })
                            assertTrue(state.sourceEmbeddedLyrics.any { "Sea breeze" in it.text })
                        }
                        flac -> {
                            assertEquals(1, state.sourceEmbeddedLyrics.size)
                            val lyric = state.sourceEmbeddedLyrics.single()
                            assertEquals("LYRICS", lyric.field)
                            assertEquals(flacLrc.trim(), lyric.text)
                            assertTrue(lyric.synced)
                        }
                        else -> assertTrue(state.sourceEmbeddedLyrics.isEmpty())
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun awaitLyrics(scenario: ActivityScenario<MediaConvertActivity>, source: File): MediaConvertUiState {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        var lastState: MediaConvertUiState? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity ->
                lastState = ViewModelProvider(activity)[MediaConvertViewModel::class.java].state.value
            }
            val state = checkNotNull(lastState)
            if (!state.isReadingSourceSubtitles && state.selectedFiles.singleOrNull()?.uri == Uri.fromFile(source)) {
                assertFalse("Lyric probe failed for ${source.name}: ${state.log}", state.sourceLyricsProbeFailed)
                assertEquals("mp3", state.selectedFormat)
                return state
            }
            SystemClock.sleep(20L)
        }
        throw AssertionError("Timed out reading embedded lyrics from ${source.name}: $lastState")
    }

    private fun awaitTracks(scenario: ActivityScenario<MediaConvertActivity>, source: File, titles: List<String>) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        var lastState: MediaConvertUiState? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity ->
                lastState = ViewModelProvider(activity)[MediaConvertViewModel::class.java].state.value
            }
            val state = checkNotNull(lastState)
            if (!state.isReadingSourceSubtitles && state.selectedFiles.singleOrNull()?.uri == Uri.fromFile(source)) {
                assertFalse("Probe failed for ${source.name}: ${state.log}", state.sourceSubtitleProbeFailed)
                assertEquals("Tracks from ${source.name} were not published", titles, state.sourceSubtitleTracks.map { it.title })
                return
            }
            // Activity and native work run independently; only the test thread waits.
            SystemClock.sleep(20L)
        }
        throw AssertionError("Timed out probing ${source.name}: $lastState")
    }

    private fun withSources(block: (File, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "media-convert-repeat-probe-${System.nanoTime()}").apply { mkdirs() }
        try {
            val subtitles = File(root, "captions.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:00,600\nCaption\n", Charsets.UTF_8)
            }
            val first = File(root, "first.mkv")
            runFfmpeg(listOf(
                "-y", "-f", "lavfi", "-i", "color=c=black:s=64x64:r=10",
                "-f", "lavfi", "-i", "sine=sample_rate=48000", "-i", subtitles.path,
                "-t", "0.7", "-map", "0:v:0", "-map", "1:a:0", "-map", "2:s:0", "-map", "2:s:0",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-c:s", "srt",
                "-metadata:s:s:0", "language=zho", "-metadata:s:s:0", "title=First source",
                "-metadata:s:s:1", "language=eng", "-metadata:s:s:1", "title=First source English",
                "-disposition:s:0", "default", "-disposition:s:1", "0", first.path
            ))
            val second = File(root, "second.mkv")
            runFfmpeg(listOf("-y", "-i", first.path, "-map", "0:v:0", "-map", "0:a:0", "-map", "0:s:1",
                "-c", "copy", "-metadata:s:s:0", "title=Second source", second.path))
            block(first, second)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun runFfmpeg(arguments: List<String>) {
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        assertTrue(session.getAllLogsAsString().takeLast(3000), ReturnCode.isSuccess(session.getReturnCode()))
    }
}
