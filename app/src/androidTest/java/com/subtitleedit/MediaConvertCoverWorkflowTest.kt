package com.subtitleedit

import android.content.Context
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.library.SharedMediaCache
import com.subtitleedit.util.FfprobeMediaInformation
import com.subtitleedit.util.SharedFileCache
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Check both the saved picture bytes and the artwork path used by library/player screens. */
@RunWith(AndroidJUnit4::class)
class MediaConvertCoverWorkflowTest {
    @Test fun mp4CoverRemainsReadableAfterRemuxingToMatroskaWithExternalSubtitles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "convert-cover-workflow-${System.nanoTime()}").apply { check(mkdirs()) }
        val source = File(root, "source with cover.mp4")
        var output: File? = null
        try {
            val base = File(root, "black-video.mp4")
            val picture = File(root, "blue-cover.png")
            val captions = File(root, "external captions.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:00,700\nCover regression caption\n", Charsets.UTF_8)
            }
            ffmpeg(listOf("-y", "-f", "lavfi", "-i", "color=c=black:s=160x120:r=10",
                "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.8",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", base.path))
            ffmpeg(listOf("-y", "-f", "lavfi", "-i", "color=c=blue:s=64x64", "-frames:v", "1", picture.path))
            ffmpeg(listOf("-y", "-i", base.path, "-i", picture.path, "-map", "0", "-map", "1:v:0",
                "-c", "copy", "-disposition:v:1", "attached_pic", "-metadata", "title=Cover regression film", source.path))

            assertBlueArtwork(source)
            val directory = File(root, "output").apply { check(mkdir()) }
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
                    model.selectFormat("mkv")
                    model.setVideoCodec("copy")
                    model.setAudioCodec("copy")
                    model.setSubtitleType(MediaConvertSubtitleType.SOFT)
                    model.selectSubtitles(listOf(Uri.fromFile(captions)))
                }
                try {
                    val ready = awaitState(scenario) { !it.isReadingSourceSubtitles }
                    assertFalse(ready.log, ready.sourceSubtitleProbeFailed)
                    assertEquals(listOf(captions.name), ready.subtitleFiles.map { it.fileName })
                    scenario.onActivity { ViewModelProvider(it)[MediaConvertViewModel::class.java].startConversion() }
                    val completed = awaitState(scenario) { !it.isConverting }
                    assertEquals(completed.log, 1, completed.outputUris.size)
                    output = File(requireNotNull(completed.outputUris.single().path))
                } finally {
                    scenario.onActivity { activity ->
                        val model = ViewModelProvider(activity)[MediaConvertViewModel::class.java]
                        model.cancelConversion()
                        model.selectOutputDirectory(originalDirectory)
                        model.setSaveToSourceDirectory(originalSaveToSource)
                    }
                }
            }

            val converted = requireNotNull(output)
            val info = FfprobeMediaInformation.read(converted.path, context.cacheDir)
            val streams = info.getStreams()
            assertEquals("Cover regression film", info.getTags()?.optString("title"))
            assertEquals(1, streams.count { it.getType() == "subtitle" && it.getCodec() == "subrip" })
            val decoded = File(root, "decoded.srt")
            ffmpeg(listOf("-y", "-i", converted.path, "-map", "0:s:0", "-c:s", "srt", decoded.path))
            assertTrue(decoded.readText().contains("Cover regression caption"))

            val cover = streams.firstNotNullOfOrNull { stream ->
                val tags = stream.getTags()
                val filename = tags?.optString("filename").orEmpty().lowercase(Locale.ROOT)
                val imageAttachment = stream.getType() == "attachment" &&
                    tags?.optString("mimetype").orEmpty().startsWith("image/") &&
                    filename.substringBeforeLast('.') in setOf("cover", "cover_land", "small_cover", "small_cover_land")
                MediaTagCoverExport.cover(
                    MediaTagStream(requireNotNull(stream.getIndex()), stream.getType().orEmpty(),
                        attachedPicture = stream.getAllProperties()?.optJSONObject("disposition")?.optInt("attached_pic", 0) == 1,
                        coverAttachment = imageAttachment), stream.getCodec(), filename)
            }
            assertNotNull("The converted Matroska file must contain an identifiable cover picture", cover)
            val embeddedCover = requireNotNull(cover)
            val extracted = File(root, "extracted.${embeddedCover.extension}")
            ffmpeg(MediaTagCoverExport.arguments(converted.path, extracted.path, embeddedCover))
            assertArrayEquals("Remuxing must preserve the original picture packet", picture.readBytes(), extracted.readBytes())

            // Some Android versions do not expose Matroska attachments through this API.
            // Record that platform result separately; application artwork must still work.
            val retriever = MediaMetadataRetriever()
            try {
                val pictureSize = runCatching {
                    retriever.setDataSource(converted.path)
                    retriever.embeddedPicture?.size ?: 0
                }.getOrDefault(0)
                Log.i("CoverWorkflowTest", "Android Matroska embedded picture bytes=$pictureSize")
            } finally { runCatching { retriever.release() } }
            assertBlueArtwork(converted)
            val metadata = requireNotNull(SharedFileCache.attributes.get(converted))
            val thumbnail = requireNotNull(SharedMediaCache.artwork(metadata, 64, allowVideoFrame = true))
            assertTrue("A cached embedded cover must also win over the black movie thumbnail",
                Color.blue(thumbnail.getPixel(thumbnail.width / 2, thumbnail.height / 2)) > 180)
        } finally {
            listOfNotNull(source, output).forEach {
                SharedMediaCache.invalidate(it.path)
                SharedFileCache.attributes.invalidate(it.path)
            }
            root.deleteRecursively()
        }
    }

    private fun assertBlueArtwork(file: File) {
        val metadata = requireNotNull(SharedFileCache.attributes.refresh(file))
        val artwork = SharedMediaCache.artwork(metadata, 128, allowVideoFrame = false)
        assertNotNull("Embedded artwork must be available for ${file.name} without using a movie frame", artwork)
        val bitmap = requireNotNull(artwork)
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        assertTrue("Expected the blue cover, not the black video frame in ${file.name}",
            Color.blue(pixel) > 180 && Color.red(pixel) < 60 && Color.green(pixel) < 60)
    }

    private fun awaitState(scenario: ActivityScenario<MediaConvertActivity>, condition: (MediaConvertUiState) -> Boolean): MediaConvertUiState {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        var latest: MediaConvertUiState? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { latest = ViewModelProvider(it)[MediaConvertViewModel::class.java].state.value }
            latest?.takeIf(condition)?.let { return it }
            SystemClock.sleep(25)
        }
        throw AssertionError("Timed out waiting for cover conversion: ${latest?.log}")
    }

    private fun ffmpeg(arguments: List<String>) {
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        assertTrue("$arguments\n${session.getAllLogsAsString().takeLast(3000)}", ReturnCode.isSuccess(session.getReturnCode()))
    }
}
