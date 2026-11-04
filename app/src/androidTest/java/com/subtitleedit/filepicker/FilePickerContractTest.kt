package com.subtitleedit.filepicker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilePickerContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun everyContractTargetsTheAppActivityWithoutASafAction() {
        val initial = File(context.cacheDir, "picker initial #1")
        val intents = listOf(
            AppFilePickerContracts.OpenDocument().createIntent(context, arrayOf("text/*")),
            AppFilePickerContracts.OpenMultipleDocuments().createIntent(context, arrayOf("audio/*", "video/*")),
            AppFilePickerContracts.OpenDocumentTree().createIntent(context, Uri.fromFile(initial)),
            AppFilePickerContracts.CreateDocument("application/json").createIntent(context, "backup.json")
        )
        intents.forEach {
            assertEquals(FilePickerActivity::class.java.name, it.component!!.className)
            assertEquals(context.packageName, it.component!!.packageName)
            assertNull(it.action)
            assertNull(it.data)
            assertEquals(0, it.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        assertEquals(listOf(FilePickerMode.OPEN_FILE, FilePickerMode.OPEN_FILES,
            FilePickerMode.DIRECTORY, FilePickerMode.SAVE_FILE),
            intents.map { FilePickerRequest.fromIntent(it).mode })
        assertEquals(listOf("audio/*", "video/*"), FilePickerRequest.fromIntent(intents[1]).mimeTypes)
        assertEquals(initial.path, FilePickerRequest.fromIntent(intents[2]).initialPath)
        assertEquals("backup.json", FilePickerRequest.fromIntent(intents[3]).suggestedName)
    }

    @Test fun selectedPathsRoundTripUnicodeAndPreserveOrderWithoutExposingFileUris() {
        val first = File(context.cacheDir, "字幕 #1.srt")
        val second = File(context.cacheDir, ".hidden.txt")
        val result = AppFilePickerContracts.result(listOf(first, second, first))
        assertNull(result.data)
        assertNull(result.clipData)
        val uris = AppFilePickerContracts.OpenMultipleDocuments().parseResult(Activity.RESULT_OK, result)
        assertEquals(listOf(Uri.fromFile(first), Uri.fromFile(second)), uris)
        assertEquals(Uri.fromFile(first),
            AppFilePickerContracts.OpenDocument().parseResult(Activity.RESULT_OK, result))
        assertEquals(Uri.fromFile(first),
            AppFilePickerContracts.CreateDocument("*/*").parseResult(Activity.RESULT_OK, result))
    }

    @Test fun cancellingAnyPickerDiscardsItsPendingSelection() {
        val result = AppFilePickerContracts.result(listOf(File(context.cacheDir, "selected.txt")))
        assertNull(AppFilePickerContracts.OpenDocument().parseResult(Activity.RESULT_CANCELED, result))
        assertNull(AppFilePickerContracts.OpenDocumentTree().parseResult(Activity.RESULT_CANCELED, result))
        assertNull(AppFilePickerContracts.CreateDocument("text/plain").parseResult(Activity.RESULT_CANCELED, result))
        assertTrue(AppFilePickerContracts.OpenMultipleDocuments().parseResult(Activity.RESULT_CANCELED, result).isEmpty())
        assertTrue(AppFilePickerContracts.OpenMultipleDocuments().parseResult(Activity.RESULT_OK, null).isEmpty())
    }

    @Test fun pickerMimeRulesKeepSubtitleMediaModelAndBackupSelectionsAvailable() {
        val textAndApplications = listOf("text/*", "application/*")
        for (name in listOf("sample.SRT", "lyrics.lrc", "note.txt", "captions.vtt", "backup.json")) {
            assertTrue(name, FilePickerPolicy.matchesMimeType(File(context.cacheDir, name), textAndApplications))
        }
        for (name in listOf("audio.mp3", "audio.flac", "audio.ape", "video.mkv", "video.mp4")) {
            assertTrue(name, FilePickerPolicy.matchesMimeType(File(context.cacheDir, name), listOf("audio/*", "video/*")))
        }
        assertFalse(FilePickerPolicy.matchesMimeType(File(context.cacheDir, "captions.srt"), listOf("audio/*", "video/*")))
        assertTrue(FilePickerPolicy.matchesMimeType(File(context.cacheDir, "model.onnx"), listOf("application/octet-stream")))
        assertTrue(FilePickerPolicy.matchesMimeType(File(context.cacheDir, "arbitrary.unknown_extension"), listOf("*/*")))
    }

    @Test fun hiddenNamesAreValidButTraversalAndEmptyNamesAreRejected() {
        for (name in listOf(".txt", ".hidden", "字幕 #1.srt")) {
            assertTrue(name, FilePickerPolicy.validateFileName(name))
        }
        for (name in listOf("", " ", ".", "..", "../escape.srt", "nested/file.srt", "bad\u0000name")) {
            assertFalse(name, FilePickerPolicy.validateFileName(name))
        }
    }
}
