package com.subtitleedit

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.util.FfprobeMediaInformation
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaTagEditorWorkflowTest {
    @Test fun wavTagEditsAndClearsPreserveLyricsThroughTheActualSaveWorkflow() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        wavTagEditRoundTrip(app.cacheDir)
    }

    @Test fun sharedStorageWavCanBeSavedAgainWithoutAFalseSourceChangedFailure() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        wavTagEditRoundTrip(requireNotNull(app.getExternalFilesDir(null)))
    }

    @Suppress("DEPRECATION")
    @Test fun publicDownloadsWavCanBeSavedAgainWithoutAFalseSourceChangedFailure() {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        assumeTrue("This regression needs public Downloads access", downloads.isDirectory && downloads.canWrite())
        // Public media uses a different FUSE path from app-specific external storage.
        wavTagEditRoundTrip(downloads)
    }

    private fun wavTagEditRoundTrip(directory: File) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = File(directory, "wav-tag-workflow-${System.nanoTime()}").apply { mkdirs() }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            val source = File(root, "source.wav")
            val session = FFmpegKit.executeWithArguments(arrayOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=48000",
                "-t", "0.3", "-metadata", "title=Original title", "-metadata", "artist=Original singer",
                "-metadata", "album=Original album", source.path))
            assertTrue(session.getAllLogsAsString(), ReturnCode.isSuccess(session.getReturnCode()))
            MediaConvertId3Lyrics.writeWav(source, listOf(
                MediaConvertLyric("原文", "[00:00.00]海风 🌊\n"),
                MediaConvertLyric("Translation", "[00:00.00]Sea breeze\n")
            ))
            val lyrics = source.inputStream().buffered().use(MediaConvertEmbeddedLyricReader::read)
            val provider = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))
            lateinit var editor: MediaTagEditorViewModel
            lateinit var converter: MediaConvertViewModel
            instrumentation.runOnMainSync {
                editor = provider[MediaTagEditorViewModel::class.java]
                converter = provider[MediaConvertViewModel::class.java]
                editor.initialize(listOf(source.path))
            }
            await { editor.state.value.selection != MediaConvertMetadataSelection.READING }
            assertTrue(editor.state.value.selection.editable)

            val changed = editor.state.value.metadata
                .withValue(MediaConvertMetadataField.TITLE, "新标题 🌊")
                .withValue(MediaConvertMetadataField.ARTIST, "新歌手")
                .withValue(MediaConvertMetadataField.ALBUM, "新专辑")
            instrumentation.runOnMainSync { editor.setMetadata(changed); editor.save() }
            await { !editor.state.value.isSaving }
            assertEquals(editor.state.value.message, "新标题 🌊", tags(source).title)
            assertEquals("新歌手", tags(source).artist)
            assertEquals("新专辑", tags(source).album)
            assertEquals(lyrics, source.inputStream().buffered().use(MediaConvertEmbeddedLyricReader::read))

            instrumentation.runOnMainSync { converter.selectFiles(listOf(Uri.fromFile(source))) }
            await { !converter.state.value.isReadingSourceSubtitles }
            assertEquals(lyrics, converter.state.value.sourceEmbeddedLyrics)
            assertFalse(converter.state.value.sourceLyricsProbeFailed)

            val cleared = changed
                .withValue(MediaConvertMetadataField.TITLE, "")
                .withValue(MediaConvertMetadataField.ARTIST, "")
                .withValue(MediaConvertMetadataField.ALBUM, "")
            instrumentation.runOnMainSync { editor.setMetadata(cleared); editor.save() }
            await { !editor.state.value.isSaving }
            assertEquals(editor.state.value.message, "", tags(source).title)
            assertEquals("", tags(source).artist)
            assertEquals("", tags(source).album)
            assertEquals(lyrics, source.inputStream().buffered().use(MediaConvertEmbeddedLyricReader::read))
        } finally {
            instrumentation.runOnMainSync { owner.viewModelStore.clear() }
            root.deleteRecursively()
        }
    }

    @Test fun realSourceModificationIsRejectedWithoutOverwritingTheChangedFile() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        rejectSourceModification(app.cacheDir)
    }

    @Test fun publicSourceModificationIsRejectedWithoutOverwritingTheChangedFile() {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        assumeTrue("This regression needs public Downloads access", downloads.isDirectory && downloads.canWrite())
        rejectSourceModification(downloads)
    }

    private fun rejectSourceModification(directory: File) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = File(directory, "wav-tag-source-guard-${System.nanoTime()}").apply { mkdirs() }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            val source = File(root, "source.wav")
            val session = FFmpegKit.executeWithArguments(arrayOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=48000",
                "-t", "0.3", "-metadata", "title=Original title", source.path))
            assertTrue(session.getAllLogsAsString(), ReturnCode.isSuccess(session.getReturnCode()))
            lateinit var editor: MediaTagEditorViewModel
            instrumentation.runOnMainSync {
                editor = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[
                    MediaTagEditorViewModel::class.java]
                editor.initialize(listOf(source.path))
            }
            await { editor.state.value.selection != MediaConvertMetadataSelection.READING }
            val originalModified = source.lastModified()
            // Simulate a different editor changing an audio sample while this editor remains open.
            val changedBytes = source.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            source.writeBytes(changedBytes)
            assertTrue(source.setLastModified(originalModified + 2_000L))
            instrumentation.runOnMainSync {
                editor.setMetadata(editor.state.value.metadata.withValue(MediaConvertMetadataField.TITLE, "Our edit"))
                editor.save()
            }
            await { !editor.state.value.isSaving }
            assertTrue(editor.state.value.message,
                editor.state.value.message.contains(app.getString(R.string.media_tag_editor_source_changed)))
            assertArrayEquals(changedBytes, source.readBytes())
            assertEquals("Original title", tags(source).title)
        } finally {
            instrumentation.runOnMainSync { owner.viewModelStore.clear() }
            root.deleteRecursively()
        }
    }

    private fun tags(file: File): MediaConvertMetadata {
        val info = FfprobeMediaInformation.read(file.path,
            ApplicationProvider.getApplicationContext<Application>().cacheDir)
        return MediaConvertMetadata.fromTags { info.getTags()?.optString(it) }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for tag workflow", predicate())
    }
}
