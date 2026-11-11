package com.subtitleedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.util.FfprobeMediaInformation
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaMuxWorkflowTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun splitThenMergeSharesTheQueueAndFolderUntilVideoIsRemoved() = withFixture { root, video, audio ->
        val videoBytes = video.readBytes()
        val audioBytes = audio.readBytes()
        withModel(root) { model ->
            onMain { model.addFiles(listOf(Uri.fromFile(video))) }
            await { !model.state.value.isImporting }
            val originalId = model.state.value.rows.single().video!!.id
            onMain { model.setMode(MediaMuxMode.SPLIT); model.startProcessing() }
            await { !model.state.value.isRunning }
            val split = model.state.value.rows.single()
            assertNotNull(model.state.value.log, split.audio)
            assertNotNull(model.state.value.log, split.output)
            val splitVideo = File(Uri.parse(split.output!!.uri).path!!)
            val splitAudio = File(Uri.parse(split.audio!!.uri).path!!)
            assertEquals(File(root, "test.mp4/分离/test.mp4"), splitVideo)
            assertEquals(File(root, "test.mp4/分离/test.m4a"), splitAudio)
            assertEquals(1 to 0, counts(splitVideo))
            assertEquals(0 to 1, counts(splitAudio))
            assertEquals(videoHash(video, root), videoHash(splitVideo, root))
            assertEquals(audioHash(video, root), audioHash(splitAudio, root))
            assertFalse(split.eligible(MediaMuxMode.SPLIT))
            assertTrue(split.eligible(MediaMuxMode.MERGE))

            val generatedVideoBytes = splitVideo.readBytes()
            val generatedAudioBytes = splitAudio.readBytes()
            onMain { model.removeOutput(split.id) }
            val cleared = model.state.value.rows.single()
            assertNull("Output removal must clear the result shown in the queue", cleared.output)
            assertEquals(split.video, cleared.video)
            assertEquals(split.audio, cleared.audio)
            assertTrue(splitVideo.isFile)
            assertTrue(splitAudio.isFile)
            assertArrayEquals(generatedVideoBytes, splitVideo.readBytes())
            assertArrayEquals(generatedAudioBytes, splitAudio.readBytes())

            onMain { model.setMode(MediaMuxMode.MERGE); model.startProcessing() }
            await { model.state.value.conflict != null || !model.state.value.isRunning }
            assertNotNull(model.state.value.log, model.state.value.conflict)
            onMain { model.resolveAudioConflict(MediaMuxAudioAction.ADD, true) }
            await { !model.state.value.isRunning }
            var output = File(Uri.parse(model.state.value.rows.single().output!!.uri).path!!)
            assertEquals(File(root, "test.mp4/合并/test.mp4"), output)
            assertEquals(1 to 2, counts(output))
            assertEquals(videoHash(video, root), videoHash(output, root))

            // Replacing the paired audio leaves the video identity and its folder intact.
            onMain { model.remove(split.id, MediaMuxColumn.AUDIO); model.addFiles(listOf(Uri.fromFile(audio))) }
            await { !model.state.value.isImporting }
            onMain { model.startProcessing() }
            await { model.state.value.conflict != null || !model.state.value.isRunning }
            assertNotNull("Apply-to-all must reset for the next run", model.state.value.conflict)
            onMain { model.resolveAudioConflict(MediaMuxAudioAction.REPLACE, false) }
            await { !model.state.value.isRunning }
            output = File(Uri.parse(model.state.value.rows.single().output!!.uri).path!!)
            assertEquals(File(root, "test.mp4/合并/test.mp4"), output)
            assertEquals(1 to 1, counts(output))
            assertEquals(audioHash(audio, root), audioHash(output, root))
            assertEquals(originalId, model.state.value.rows.single().video!!.id)

            onMain { model.remove(split.id, MediaMuxColumn.VIDEO); model.addFiles(listOf(Uri.fromFile(video))) }
            await { !model.state.value.isImporting }
            assertNotEquals(originalId, model.state.value.rows.single().video!!.id)
            onMain { model.startProcessing() }
            await { model.state.value.conflict != null || !model.state.value.isRunning }
            onMain { model.resolveAudioConflict(MediaMuxAudioAction.REPLACE, false) }
            await { !model.state.value.isRunning }
            output = File(Uri.parse(model.state.value.rows.single().output!!.uri).path!!)
            assertEquals(File(root, "test.mp4（1）/合并/test.mp4"), output)
        }
        assertArrayEquals(videoBytes, video.readBytes())
        assertArrayEquals(audioBytes, audio.readBytes())
    }

    @Test fun duplicateInputsGetSeparateFoldersAndAudioConflictAppliesOnlyToOneRun() = withFixture { root, video, audio ->
        withModel(root) { model ->
            onMain { model.addFiles(listOf(video, audio, video, audio).map(Uri::fromFile)) }
            await { !model.state.value.isImporting }
            assertEquals(2, model.state.value.rows.size)
            assertEquals(2, model.state.value.rows.map { it.video!!.id }.distinct().size)
            onMain { model.startProcessing() }
            await { model.state.value.conflict != null || !model.state.value.isRunning }
            onMain { model.resolveAudioConflict(MediaMuxAudioAction.ADD, true) }
            await { !model.state.value.isRunning || model.state.value.conflict != null }
            assertFalse("Apply-to-all must handle both eligible rows: ${model.state.value.log}", model.state.value.isRunning)
            val files = model.state.value.rows.map { File(Uri.parse(it.output!!.uri).path!!) }
            assertEquals(setOf(File(root, "test.mp4/合并/test.mp4"), File(root, "test.mp4（1）/合并/test.mp4")), files.toSet())
            files.forEach { assertEquals(1 to 2, counts(it)) }
            val snapshots = files.associateWith { it.readBytes() }

            onMain { model.startProcessing() }
            await { model.state.value.conflict != null || !model.state.value.isRunning }
            assertNotNull(model.state.value.conflict)
            onMain { model.resolveAudioConflict(null, false) }
            await { !model.state.value.isRunning }
            snapshots.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
            assertFalse(File(root, "test.mp4（2）").exists())
        }
    }

    @Test fun incompatibleMergeCreatesNoOutputFoldersAndSkipsUnpairedRows() = withFixture { root, _, audio ->
        val video = File(root, "webm-source.webm")
        ffmpeg(listOf("-y", "-f", "lavfi", "-i", "color=size=32x32:rate=5", "-t", "0.3", "-an", "-c:v", "libvpx", video.path))
        withModel(root) { model ->
            onMain { model.addFiles(listOf(Uri.fromFile(video), Uri.fromFile(audio))) }
            await { !model.state.value.isImporting }
            onMain { model.addEmptyRow(); model.startProcessing() }
            await { !model.state.value.isRunning }
            assertNull(model.state.value.log, model.state.value.rows.first().output)
            assertFalse(File(root, video.name).exists() && File(root, video.name).isDirectory)
            assertTrue(model.state.value.log, model.state.value.log.contains("失败 1"))
            assertEquals(2, model.state.value.rows.size)
        }
    }

    @Test fun outputOwnersReuseOnlyTheirOwnFilesAndRespectExistingFolderConflicts() = withFixture { root, _, _ ->
        val outputRoot = File(root, "store").apply { mkdirs() }
        val occupied = File(outputRoot, "test.mp4").apply { mkdirs() }
        File(occupied, "keep.txt").writeText("Keep me")
        val prepared = File(root, "prepared.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val file = MediaMuxFile("owner-one", "file:///source/test.mp4", "test.mp4", MediaMuxColumn.VIDEO)
        val store = MediaMuxOutputStore(app)
        runBlocking {
            val merged = store.publish(Uri.fromFile(outputRoot), file, MediaMuxMode.MERGE, listOf("test.mp4" to prepared))
            assertEquals(File(outputRoot, "test.mp4（1）/合并/test.mp4").path, Uri.parse(merged.single().uri).path)
            prepared.writeBytes(byteArrayOf(4, 5))
            val repeated = store.publish(Uri.fromFile(outputRoot), file, MediaMuxMode.MERGE, listOf("test.mp4" to prepared))
            assertEquals(merged, repeated)
            assertArrayEquals(prepared.readBytes(), File(Uri.parse(repeated.single().uri).path!!).readBytes())
            store.publish(Uri.fromFile(outputRoot), file, MediaMuxMode.SPLIT,
                listOf("test.mp4" to prepared, "test.m4a" to prepared))
            assertTrue(File(outputRoot, "test.mp4（1）/分离/test.m4a").isFile)
            store.forget(file.id)
            val readded = store.publish(Uri.fromFile(outputRoot), file.copy(id = "new-owner"), MediaMuxMode.MERGE,
                listOf("test.mp4" to prepared))
            assertEquals(File(outputRoot, "test.mp4（2）/合并/test.mp4").path, Uri.parse(readded.single().uri).path)
        }
        assertEquals("Keep me", File(occupied, "keep.txt").readText())
    }

    @Test fun bigEndianPcmIsSeparatedIntoACopyCompatibleAudioContainer() = withFixture { root, _, _ ->
        val video = File(File(root, "sources"), "big-endian.mov")
        ffmpeg(listOf("-y", "-f", "lavfi", "-i", "color=size=32x32:rate=10", "-f", "lavfi", "-i", "sine",
            "-t", "0.3", "-c:v", "mpeg4", "-c:a", "pcm_s16be", video.path))
        withModel(root) { model ->
            onMain { model.addFiles(listOf(Uri.fromFile(video))) }
            await { !model.state.value.isImporting }
            onMain { model.setMode(MediaMuxMode.SPLIT); model.startProcessing() }
            await { !model.state.value.isRunning }
            val result = model.state.value.rows.single()
            assertNotNull(model.state.value.log, result.audio)
            val audio = File(Uri.parse(result.audio!!.uri).path!!)
            assertEquals("big-endian.mka", audio.name)
            assertEquals(0 to 1, counts(audio))
            assertEquals(audioHash(video, root), audioHash(audio, root))
            assertEquals(1 to 0, counts(File(Uri.parse(result.output!!.uri).path!!)))
        }
    }

    @Test fun muxShortcutOpensSecondPageAndKeepsQueueIdentityAcrossRecreation() = withFixture { _, video, audio ->
        val intent = Intent(app, MediaConvertActivity::class.java)
            .putExtra(MediaConvertActivity.EXTRA_PAGE, MediaProcessingPage.MUX.name)
            .putStringArrayListExtra(MediaConvertActivity.EXTRA_FILE_PATHS, arrayListOf(video.path, audio.path))
        ActivityScenario.launch<MediaConvertActivity>(intent).use { scenario ->
            lateinit var model: MediaMuxViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MediaMuxViewModel::class.java] }
            await { !model.state.value.isImporting }
            val ids = model.state.value.rows.map { it.video?.id to it.audio?.id }
            assertEquals(1, ids.size)
            scenario.recreate()
            scenario.onActivity {
                val retained = ViewModelProvider(it)[MediaMuxViewModel::class.java]
                assertEquals(ids, retained.state.value.rows.map { row -> row.video?.id to row.audio?.id })
                assertTrue(ViewModelProvider(it)[MediaConvertViewModel::class.java].state.value.selectedFiles.isEmpty())
            }
            if (InstrumentationRegistry.getArguments().getString("muxScreenshot") == "true") {
                instrumentation.waitForIdleSync()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                if (screenshot != null) {
                    File(app.getExternalFilesDir(null), "media-mux-preview.png").outputStream().use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
            }
        }
    }

    private fun withFixture(block: (File, File, File) -> Unit) {
        val root = File(app.cacheDir, "mux-workflow-${System.nanoTime()}").apply { mkdirs() }
        try {
            val sources = File(root, "sources").apply { mkdirs() }
            val video = File(sources, "test.mp4")
            val audio = File(sources, "test2.mp3")
            ffmpeg(listOf("-y", "-f", "lavfi", "-i", "color=size=32x32:rate=10", "-f", "lavfi", "-i", "sine=frequency=440",
                "-t", "0.4", "-c:v", "libx264", "-c:a", "aac", video.path))
            ffmpeg(listOf("-y", "-f", "lavfi", "-i", "sine=frequency=880", "-t", "0.4", "-c:a", "libmp3lame", audio.path))
            block(root, video, audio)
        } finally { root.deleteRecursively() }
    }

    private fun withModel(output: File, block: (MediaMuxViewModel) -> Unit) {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        lateinit var model: MediaMuxViewModel
        lateinit var originalRoot: Uri
        try {
            onMain {
                model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MediaMuxViewModel::class.java]
                originalRoot = requireNotNull(model.currentOutputDirectoryUri)
                model.selectOutputDirectory(Uri.fromFile(output))
            }
            block(model)
        } finally {
            onMain { model.cancel() }
            await { !model.state.value.isRunning && !model.state.value.isImporting }
            onMain { model.selectOutputDirectory(originalRoot); owner.viewModelStore.clear() }
        }
    }

    private fun counts(file: File): Pair<Int, Int> {
        val streams = FfprobeMediaInformation.read(file.path, app.cacheDir).getStreams()
        return streams.count { it.getType() == "video" } to streams.count { it.getType() == "audio" }
    }
    private fun audioHash(file: File, root: File): String {
        val out = File(root, "hash.txt")
        ffmpeg(listOf("-y", "-i", file.path, "-map", "0:a:0", "-c", "copy", "-f", "hash", "-hash", "sha256", out.path))
        return out.readText().trim()
    }
    private fun videoHash(file: File, root: File): String {
        val out = File(root, "video-hash.txt")
        ffmpeg(listOf("-y", "-i", file.path, "-map", "0:v:0", "-c", "copy", "-f", "hash", "-hash", "sha256", out.path))
        return out.readText().trim()
    }
    private fun ffmpeg(arguments: List<String>) {
        val result = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        assertTrue(result.getAllLogsAsString().takeLast(2000), ReturnCode.isSuccess(result.getReturnCode()))
    }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 45_000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25)
        assertTrue("Timed out waiting for media mux workflow", condition())
    }
}
