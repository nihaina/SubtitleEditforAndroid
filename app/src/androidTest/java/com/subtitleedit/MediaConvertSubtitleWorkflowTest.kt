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
import com.subtitleedit.util.FfprobeMediaInformation
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise subtitle preparation through the ViewModel, rather than bypassing it in command tests. */
@RunWith(AndroidJUnit4::class)
class MediaConvertSubtitleWorkflowTest {
    @Test fun appExportedWavLyricsAreDetectedAfterReselectionAndReopeningThePage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "wav-lyrics-workflow-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(root, "source audio.wav")
            ffmpeg(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "1", source.path))
            val original = File(root, "中文歌词.lrc").apply {
                writeText("[ti:海风]\n[00:00.125]海风 🌊\n[00:00.500]第二行\n")
            }
            val translated = File(root, "English lyrics.srt").apply {
                writeText("1\n00:00:00,125 --> 00:00:00,450\nSea breeze\n\n2\n00:00:00,500 --> 00:00:00,900\nSecond line\n")
            }
            val directory = File(root, "exported").apply { mkdirs() }
            lateinit var output: File
            ActivityScenario.launch(MediaConvertActivity::class.java).use { scenario ->
                lateinit var originalDirectory: Uri
                var originalSaveToSource = false
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
                    originalDirectory = requireNotNull(model.currentOutputDirectoryUri)
                    originalSaveToSource = model.state.value.saveToSourceDirectory
                    model.setSaveToSourceDirectory(false)
                    model.selectOutputDirectory(Uri.fromFile(directory))
                    model.selectFiles(listOf(Uri.fromFile(source)))
                    model.selectFormat("wav")
                    model.selectSubtitles(listOf(Uri.fromFile(original), Uri.fromFile(translated)))
                    assertEquals(listOf(original.name, translated.name), model.state.value.subtitleFiles.map { it.fileName })
                    model.startConversion()
                }
                try {
                    val completed = awaitState(scenario) { !it.isConverting }
                    assertEquals(completed.log, 1, completed.outputUris.size)
                    output = File(requireNotNull(completed.outputUris.single().path))
                    assertEquals(directory.canonicalFile, requireNotNull(output.parentFile).canonicalFile)
                    val tags = output.inputStream().buffered().use(MediaConvertEmbeddedLyricReader::read)
                    assertEquals(listOf("USLT", "USLT"), tags.map { it.field })
                    assertEquals(listOf("中文歌词", "English lyrics"), tags.map { it.title })
                    assertEquals(original.readText(), tags.first().text)
                    assertTrue(tags.last().text.contains("Sea breeze"))
                    val bytes = output.readBytes()
                    repeat(2) {
                        assertWavLyricsAfterSelection(scenario, output)
                        assertArrayEquals("Selecting the exported WAV modified it", bytes, output.readBytes())
                    }
                } finally {
                    scenario.onActivity { activity ->
                        val model = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
                        model.cancelConversion()
                        model.selectOutputDirectory(originalDirectory)
                        model.setSaveToSourceDirectory(originalSaveToSource)
                    }
                }
            }
            ActivityScenario.launch(MediaConvertActivity::class.java).use { scenario ->
                assertWavLyricsAfterSelection(scenario, output)
            }
        } finally { root.deleteRecursively() }
    }

    private fun assertWavLyricsAfterSelection(scenario: ActivityScenario<MediaConvertActivity>, file: File) {
        scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
            model.selectFiles(listOf(Uri.fromFile(file)))
            assertTrue(model.state.value.isReadingSourceSubtitles)
            assertTrue(model.state.value.sourceEmbeddedLyrics.isEmpty())
        }
        val detected = awaitState(scenario) { !it.isReadingSourceSubtitles }
        assertFalse(detected.log, detected.sourceLyricsProbeFailed)
        assertEquals(listOf("USLT", "USLT"), detected.sourceEmbeddedLyrics.map { it.field })
        assertEquals(listOf("中文歌词", "English lyrics"), detected.sourceEmbeddedLyrics.map { it.title })
        assertTrue(detected.sourceEmbeddedLyrics.all { it.synced })
        assertTrue(detected.sourceEmbeddedLyrics.first().text.contains("海风 🌊"))
        assertTrue(detected.sourceEmbeddedLyrics.last().text.contains("Sea breeze"))
        scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
            assertTrue("The WAV must display the existing-lyrics section", model.selectedSourceIsAudio)
        }
    }

    @Test fun selectedSubtitlesReachTheMuxerAndRendererThroughTheRealConversionWorkflow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "subtitle-workflow-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(root, "source with spaces ☕.mp4")
            val base = File(root, "base.mp4")
            val cover = File(root, "source cover.png")
            ffmpeg(listOf("-y", "-f", "lavfi", "-i", "color=c=black:s=320x240:r=25",
                "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.8", "-c:v", "libx264", "-c:a", "aac", base.path))
            ffmpeg(listOf("-y", "-f", "lavfi", "-i", "color=c=blue:s=64x64", "-frames:v", "1", cover.path))
            ffmpeg(listOf("-y", "-i", base.path, "-i", cover.path, "-map", "0", "-map", "1:v:0", "-c", "copy",
                "-disposition:v:1", "attached_pic", source.path))
            val srt = File(root, "字幕 with spaces.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:00,700\nCaption\n", Charsets.UTF_8)
            }
            val lrc = File(root, "歌词.lrc").apply { writeText("[00:00.00]Caption\n[00:00.70]\n") }
            val subtitles = listOf(srt, lrc) + listOf("vtt", "ass", "ssa").map { extension ->
                File(root, "caption.$extension").also { file ->
                    ffmpeg(listOf("-y", "-i", srt.path, "-c:s", if (extension == "vtt") "webvtt" else "ass",
                        "-f", if (extension == "vtt") "webvtt" else "ass", file.path))
                }
            }
            // Prove that the source and the simple cached subtitle both have usable probe output.
            assertTrue(FfprobeMediaInformation.read(source.path, context.cacheDir).getStreams().any { it.getType() == "video" })
            assertEquals("subrip", FfprobeMediaInformation.read(srt.path, context.cacheDir).getStreams().single().getCodec())

            ActivityScenario.launch(MediaConvertActivity::class.java).use { scenario ->
                lateinit var originalDirectory: Uri
                var originalSaveToSource = false
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
                    originalDirectory = requireNotNull(model.currentOutputDirectoryUri)
                    originalSaveToSource = model.state.value.saveToSourceDirectory
                    model.selectFiles(listOf(Uri.fromFile(source)))
                    model.selectFormat("mp4")
                    model.setSaveToSourceDirectory(false)
                }
                try {
                    awaitState(scenario) { !it.isReadingSourceSubtitles && it.subtitleBurnInAvailable }
                    for (type in MediaConvertSubtitleType.entries) for (subtitle in subtitles) {
                        val directory = File(root, "${type.name}-${subtitle.extension}").apply { mkdirs() }
                        scenario.onActivity { activity ->
                            val model = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
                            model.state.value.subtitleFiles.indices.reversed().forEach(model::removeSubtitle)
                            model.selectOutputDirectory(Uri.fromFile(directory))
                            model.setSubtitleType(type)
                            model.selectSubtitles(listOf(Uri.fromFile(subtitle)))
                            assertEquals(listOf(subtitle.name), model.state.value.subtitleFiles.map { it.fileName })
                            model.startConversion()
                        }
                        val state = awaitState(scenario) { !it.isConverting }
                        assertEquals("${type.name}/${subtitle.extension}\n${state.log}", 1, state.outputUris.size)
                        val output = File(requireNotNull(state.outputUris.single().path))
                        val streams = FfprobeMediaInformation.read(output.path, context.cacheDir).getStreams()
                        if (type == MediaConvertSubtitleType.SOFT) {
                            assertTrue(streams.any { it.getType() == "subtitle" && it.getCodec() == "mov_text" })
                            val decoded = File(directory, "decoded.srt")
                            ffmpeg(listOf("-y", "-i", output.path, "-map", "0:s:0", "-c:s", "srt", decoded.path))
                            assertTrue(decoded.readText().contains("Caption"))
                        } else {
                            assertFalse(streams.any { it.getType() == "subtitle" })
                            val pixels = File(directory, "frame.raw")
                            ffmpeg(listOf("-y", "-ss", "0.3", "-i", output.path, "-vf", "format=gray", "-frames:v", "1",
                                "-f", "rawvideo", pixels.path))
                            assertTrue("Caption was not burned in for ${subtitle.extension}", pixels.readBytes().any { (it.toInt() and 255) > 200 })
                        }
                    }
                    val invalid = File(root, "invalid.srt").apply { writeText("This is not a timed subtitle") }
                    val directory = File(root, "invalid-output").apply { mkdirs() }
                    scenario.onActivity { activity ->
                        val model = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
                        model.state.value.subtitleFiles.indices.reversed().forEach(model::removeSubtitle)
                        model.selectOutputDirectory(Uri.fromFile(directory))
                        model.setSubtitleType(MediaConvertSubtitleType.SOFT)
                        model.selectSubtitles(listOf(Uri.fromFile(invalid)))
                        model.startConversion()
                    }
                    assertTrue(awaitState(scenario) { !it.isConverting }.outputUris.isEmpty())
                    assertTrue(directory.listFiles().orEmpty().isEmpty())
                } finally {
                    scenario.onActivity { activity ->
                        val model = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
                        model.cancelConversion()
                        model.selectOutputDirectory(originalDirectory)
                        model.setSaveToSourceDirectory(originalSaveToSource)
                    }
                }
            }
        } finally { root.deleteRecursively() }
    }

    private fun awaitState(scenario: ActivityScenario<MediaConvertActivity>, condition: (MediaConvertUiState) -> Boolean): MediaConvertUiState {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        var latest: MediaConvertUiState? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { latest = ViewModelProvider(it)[MediaConvertViewModel::class.java].state.value }
            latest?.takeIf(condition)?.let { return it }
            SystemClock.sleep(25)
        }
        throw AssertionError("Timed out waiting for media conversion: ${latest?.log}")
    }

    private fun ffmpeg(arguments: List<String>) {
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        assertTrue(session.getAllLogsAsString().takeLast(3000), ReturnCode.isSuccess(session.getReturnCode()))
    }
}
