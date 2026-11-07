package com.subtitleedit

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.util.ModelDirectoryManager
import com.subtitleedit.util.SettingsManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the three pages migrated after VocalSeparation.
 * Input selections and queued work state belong to the retained ViewModel,
 * so recreating the Activity must not clear them.
 */
@RunWith(AndroidJUnit4::class)
class RemainingRecreationTest {

    @Test
    fun subtitleToolsExposeResolvedDefaultOutputDirectories() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsManager.getInstance(context)
        val previousPersistenceSetting = settings.isOutputDirectoryPersistenceEnabled()
        settings.setOutputDirectoryPersistenceEnabled(false)
        try {
            ActivityScenario.launch(BatchConvertActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val viewModel = ViewModelProvider(activity)[BatchConvertViewModel::class.java]
                    val expected = File(ModelDirectoryManager.softwareDirectory(), "Convert").absolutePath
                    assertEquals(expected, viewModel.state.value.outputDirectoryLabel)
                }
            }
            ActivityScenario.launch(AutoTranslateActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val viewModel = ViewModelProvider(activity)[AutoTranslateViewModel::class.java]
                    val expected = File(ModelDirectoryManager.softwareDirectory(), "Translate").absolutePath
                    assertEquals(expected, viewModel.state.value.outputDirectory)
                }
            }
        } finally {
            settings.setOutputDirectoryPersistenceEnabled(previousPersistenceSetting)
        }
    }

    @Test
    fun mediaConvertSelectionSurvivesRecreation() {
        ActivityScenario.launch(MediaConvertActivity::class.java).use { scenario ->
            lateinit var before: MediaConvertViewModel
            scenario.onActivity { activity ->
                before = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
                before.selectFiles(listOf(Uri.parse("file:///nonexistent/video.mp4")))
                before.selectFormat("mp4")
                before.setAudioBitrate("192")
                before.setAudioVolume(200)
                before.setResolution(MediaConvertResolutionSettings(presetIndex = 3))
                before.setVideoBitrate(MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "3000"))
                before.setVideoSettings(MediaConvertVideoSettings(rotation = MediaConvertRotation.CLOCKWISE, flipHorizontal = true,
                    pixelFormat = "yuv420p10le", sizeLimitPercent = 50, filter = MediaConvertVideoFilter.GOLD))
                before.selectFormat("mp3")
                before.setAudioBitrate("128")
                before.setAudioVolume(50)
                before.selectFormat("mp4")
                assertEquals("192", before.state.value.options.audioBitrate)
                assertEquals(200, before.state.value.options.audioVolumePercent)
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                val after = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
                assertSame(before, after)
                assertEquals(1, after.state.value.selectedFiles.size)
                assertTrue(after.state.value.selectedFiles.single().fileName.contains("video.mp4"))
                assertEquals("mp4", after.state.value.selectedFormat)
                assertEquals(3, after.state.value.options.resolution.presetIndex)
                assertEquals(MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "3000"), after.state.value.options.videoBitrate)
                assertEquals(MediaConvertRotation.CLOCKWISE, after.state.value.options.videoSettings.rotation)
                assertTrue(after.state.value.options.videoSettings.flipHorizontal)
                assertEquals(50, after.state.value.options.videoSettings.sizeLimitPercent)
                assertEquals(MediaConvertVideoFilter.GOLD, after.state.value.options.videoSettings.filter)
                after.selectFormat("mp3")
                assertEquals("128", after.state.value.options.audioBitrate)
                assertEquals(50, after.state.value.options.audioVolumePercent)
                assertEquals(0, after.state.value.options.resolution.presetIndex)
                assertEquals(MediaConvertVideoBitrateSettings(), after.state.value.options.videoBitrate)
                assertEquals(MediaConvertVideoSettings(), after.state.value.options.videoSettings)
                after.selectFormat("mp4")
                assertEquals("192", after.state.value.options.audioBitrate)
                assertEquals(200, after.state.value.options.audioVolumePercent)
                assertEquals("yuv420p10le", after.state.value.options.videoSettings.pixelFormat)
            }
        }
    }

    @Test
    fun conversionSubtitlesAreClearedForBatchSourcesAndAudioOutput() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val subtitle = File(context.cacheDir, "convert-subtitle-${System.nanoTime()}.srt").apply {
            writeText("1\n00:00:00,000 --> 00:00:01,000\nCaption\n")
        }
        try {
            ActivityScenario.launch(MediaConvertActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
                    val input = Uri.parse("file:///nonexistent/video.mp4")
                    model.selectFiles(listOf(input))
                    model.selectFormat("mp4")
                    model.setSubtitleType(MediaConvertSubtitleType.SOFT)
                    model.selectSubtitle(Uri.fromFile(subtitle))
                    assertEquals(subtitle.name, model.state.value.subtitleFile?.fileName)
                    model.selectFormat("mp3")
                    assertEquals(null, model.state.value.subtitleFile)
                    model.selectSubtitle(Uri.fromFile(subtitle))
                    assertEquals(null, model.state.value.subtitleFile)
                    model.selectFiles(listOf(input, Uri.parse("file:///nonexistent/second.mp4")))
                    model.selectFormat("mp4")
                    model.selectSubtitle(Uri.fromFile(subtitle))
                    assertEquals(null, model.state.value.subtitleFile)
                    assertTrue(model.allowedSubtitleExtensions.isEmpty())
                }
            }
        } finally { subtitle.delete() }
    }

    @Test
    fun transcriptMatchSelectionsSurviveRecreation() {
        ActivityScenario.launch(TranscriptMatchActivity::class.java).use { scenario ->
            lateinit var before: TranscriptMatchViewModel
            scenario.onActivity { activity ->
                before = ViewModelProvider(activity)[TranscriptMatchViewModel::class.java]
                before.selectText(Uri.parse("file:///nonexistent/script.srt"))
                before.selectAudio(Uri.parse("file:///nonexistent/audio.wav"))
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                val after = ViewModelProvider(activity)[TranscriptMatchViewModel::class.java]
                assertSame(before, after)
                assertTrue(after.state.value.textFileName.contains("script.srt"))
                assertTrue(after.state.value.audioFileName.contains("audio.wav"))
                assertTrue(after.state.value.pendingFiles.contains("script.srt"))
            }
        }
    }

    @Test
    fun autoTranslateQueueSurvivesRecreation() {
        ActivityScenario.launch(AutoTranslateActivity::class.java).use { scenario ->
            lateinit var before: AutoTranslateViewModel
            scenario.onActivity { activity ->
                before = ViewModelProvider(activity)[AutoTranslateViewModel::class.java]
                before.addFiles(listOf(Uri.parse("file:///nonexistent/subtitles.srt")))
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                val after = ViewModelProvider(activity)[AutoTranslateViewModel::class.java]
                assertSame(before, after)
                assertEquals(1, after.state.value.files.size)
                assertTrue(after.state.value.files.single().fileName.contains("subtitles.srt"))
            }
        }
    }
}
