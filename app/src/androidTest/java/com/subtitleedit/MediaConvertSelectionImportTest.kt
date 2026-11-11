package com.subtitleedit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Directory shortcuts prefill the existing tool without starting a conversion. */
@RunWith(AndroidJUnit4::class)
class MediaConvertSelectionImportTest {
    @Test fun videoShortcutImportsAllSelectedSubtitlesBeforeAnOutputFormatIsChosen() = withSources { source ->
        ActivityScenario.launch<MediaConvertActivity>(shortcut(source.video, source.subtitles)).use { scenario ->
            scenario.withModel { model ->
                assertEquals(listOf(Uri.fromFile(source.video)), model.state.value.selectedFiles.map { it.uri })
                assertEquals(source.subtitles.map { it.name }, model.state.value.subtitleFiles.map { it.fileName })
                assertEquals(source.subtitles.map(Uri::fromFile), model.state.value.subtitleFiles.map { it.uri })
                assertNull(model.state.value.selectedFormat)
                assertFalse(model.isRunning)
                assertTrue(model.state.value.outputUris.isEmpty())
            }
            awaitProbe(scenario)
            scenario.withModel { model ->
                assertEquals(source.subtitles.map { it.name }, model.state.value.subtitleFiles.map { it.fileName })
                assertFalse(model.selectedSourceIsAudio)
                model.selectFormat("mkv")
                assertEquals(source.subtitles.map { it.name }, model.state.value.subtitleFiles.map { it.fileName })
                assertEquals(MediaConvertSubtitleType.SOFT, model.state.value.subtitleType)
            }
        }
    }

    @Test fun audioShortcutUsesTheExistingLyricCapacityOfTheChosenOutputFormat() = withSources { source ->
        ActivityScenario.launch<MediaConvertActivity>(shortcut(source.audio, source.subtitles)).use { scenario ->
            awaitProbe(scenario)
            scenario.withModel { model ->
                assertTrue(model.selectedSourceIsAudio)
                assertNull(model.state.value.selectedFormat)
                assertEquals(source.subtitles.map { it.name }, model.state.value.subtitleFiles.map { it.fileName })
                model.selectFormat("wav")
                assertTrue(model.state.value.isAudioOutput)
                assertEquals(source.subtitles.map { it.name }, model.state.value.subtitleFiles.map { it.fileName })
                model.selectFormat("flac")
                assertEquals(1, model.maxSubtitleFiles)
                assertEquals(source.subtitles.take(1).map { it.name }, model.state.value.subtitleFiles.map { it.fileName })
                assertFalse(model.isRunning)
                assertTrue(model.state.value.outputUris.isEmpty())
            }
        }
    }

    @Test fun recreatingTheToolDoesNotReplayItsShortcutOverManualSelections() = withSources { source ->
        ActivityScenario.launch<MediaConvertActivity>(shortcut(source.video, source.subtitles)).use { scenario ->
            scenario.withModel { model ->
                model.selectFormat("mkv")
                model.removeSubtitle(0)
            }
            scenario.recreate()
            scenario.withModel { model ->
                assertEquals("mkv", model.state.value.selectedFormat)
                assertEquals(source.subtitles.drop(1).map { it.name }, model.state.value.subtitleFiles.map { it.fileName })
                model.selectFiles(listOf(Uri.fromFile(source.audio)))
                model.selectFormat("wav")
                model.selectSubtitle(Uri.fromFile(source.manualSubtitle))
            }
            awaitProbe(scenario)
            scenario.recreate()
            scenario.withModel { model ->
                assertEquals(listOf(Uri.fromFile(source.audio)), model.state.value.selectedFiles.map { it.uri })
                assertEquals("wav", model.state.value.selectedFormat)
                assertEquals(listOf(source.manualSubtitle.name), model.state.value.subtitleFiles.map { it.fileName })
                assertFalse(model.isRunning)
            }
        }
    }

    @Test fun openingTheToolWithoutExtrasKeepsTheExistingManualPickerWorkflow() = withSources { source ->
        ActivityScenario.launch(MediaConvertActivity::class.java).use { scenario ->
            scenario.withModel { model ->
                assertTrue(model.state.value.selectedFiles.isEmpty())
                assertTrue(model.state.value.subtitleFiles.isEmpty())
                assertNull(model.state.value.selectedFormat)
                model.selectFiles(listOf(Uri.fromFile(source.audio)))
                model.selectFormat("wav")
                model.selectSubtitle(Uri.fromFile(source.manualSubtitle))
            }
            scenario.recreate()
            scenario.withModel { model ->
                assertEquals(listOf(Uri.fromFile(source.audio)), model.state.value.selectedFiles.map { it.uri })
                assertEquals(listOf(source.manualSubtitle.name), model.state.value.subtitleFiles.map { it.fileName })
                assertEquals("wav", model.state.value.selectedFormat)
                assertFalse(model.isRunning)
            }
        }
    }

    @Test fun multipleMediaExtrasCannotAttachOneSubtitleSelectionToTheWholeBatch() = withSources { source ->
        val intent = shortcut(source.video, source.subtitles).apply {
            putStringArrayListExtra(MediaConvertActivity.EXTRA_FILE_PATHS,
                arrayListOf(source.video.absolutePath, source.audio.absolutePath))
        }
        ActivityScenario.launch<MediaConvertActivity>(intent).use { scenario ->
            scenario.withModel { model ->
                assertEquals(listOf(source.video, source.audio).map(Uri::fromFile),
                    model.state.value.selectedFiles.map { it.uri })
                assertTrue("Batch imports must preserve the existing single-source subtitle restriction",
                    model.state.value.subtitleFiles.isEmpty())
                assertTrue(model.allowedSubtitleExtensions.isEmpty())
                assertFalse(model.isRunning)
            }
            awaitProbe(scenario)
            scenario.withModel { model -> assertTrue(model.state.value.subtitleFiles.isEmpty()) }
        }
    }

    private data class Sources(val video: File, val audio: File, val subtitles: List<File>, val manualSubtitle: File)

    private fun shortcut(media: File, subtitles: List<File>): Intent =
        Intent(ApplicationProvider.getApplicationContext<Context>(), MediaConvertActivity::class.java).apply {
            putStringArrayListExtra(MediaConvertActivity.EXTRA_FILE_PATHS, arrayListOf(media.absolutePath))
            putStringArrayListExtra(MediaConvertActivity.EXTRA_SUBTITLE_PATHS, ArrayList(subtitles.map { it.absolutePath }))
        }

    private fun withSources(block: (Sources) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "media-shortcut-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val audio = File(root, "selected audio.wav")
            ffmpeg(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.3", audio.path))
            val video = File(root, "selected video.mkv")
            ffmpeg(listOf("-y", "-f", "lavfi", "-i", "color=size=64x64:rate=10", "-i", audio.path,
                "-t", "0.3", "-c:v", "mpeg4", "-c:a", "aac", video.path))
            val subtitle = File(root, "selected 中文.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:00,250\nSelected subtitle\n\n")
            }
            val lyric = File(root, "selected English.lrc").apply { writeText("[00:00.00]Selected lyric\n") }
            val manual = File(root, "manually selected.lrc").apply { writeText("[00:00.00]Manual lyric\n") }
            block(Sources(video, audio, listOf(subtitle, lyric), manual))
        } finally { root.deleteRecursively() }
    }

    private fun ActivityScenario<MediaConvertActivity>.withModel(block: (MediaConvertViewModel) -> Unit) {
        onActivity { activity -> block(ViewModelProvider(activity)[MediaConvertViewModel::class.java]) }
    }

    private fun awaitProbe(scenario: ActivityScenario<MediaConvertActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var reading = true
            scenario.withModel { reading = it.state.value.isReadingSourceSubtitles }
            if (!reading) return
            SystemClock.sleep(20L)
        }
        fail("Timed out waiting for shortcut source probing")
    }

    private fun ffmpeg(arguments: List<String>) {
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        assertTrue(session.getAllLogsAsString().takeLast(2000), ReturnCode.isSuccess(session.getReturnCode()))
    }
}
