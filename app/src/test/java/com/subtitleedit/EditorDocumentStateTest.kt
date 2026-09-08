package com.subtitleedit

import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDocumentStateTest {
    @Test
    fun newSubtitleDocumentClearsSubtitleReferenceWithoutChangingMediaFile() {
        val state = EditorDocumentState().apply {
            mediaType = EditorMediaType.VIDEO
            currentFile = java.io.File("/tmp/video.mp4")
            filePath = currentFile!!.path
            subtitleFilePath = "/tmp/old.srt"
            subtitleFile = java.io.File(subtitleFilePath)
            documentUri = "content://old-subtitle"
            documentTitle = "old.srt"
            isNewFile = false
        }

        state.startNewSubtitleDocument()

        assertEquals(state.currentFile?.path, state.filePath)
        assertEquals("video.mp4", state.currentFile?.name)
        assertEquals("", state.subtitleFilePath)
        assertNull(state.subtitleFile)
        assertNull(state.documentUri)
        assertEquals("video.mp4", state.documentTitle)
        assertTrue(state.isNewFile)
    }

    @Test
    fun openingUriSubtitleClearsStandaloneDocumentReference() {
        val state = EditorDocumentState().apply {
            filePath = "/tmp/old.srt"
            currentFile = java.io.File(filePath)
            subtitleFilePath = filePath
            subtitleFile = currentFile
            documentUri = "content://old"
            documentTitle = "old.srt"
            isNewFile = true
        }

        state.openUriSubtitleDocument("content://new", "new.srt")

        assertEquals("", state.filePath)
        assertNull(state.currentFile)
        assertEquals("", state.subtitleFilePath)
        assertNull(state.subtitleFile)
        assertEquals("content://new", state.documentUri)
        assertEquals("new.srt", state.documentTitle)
        assertFalse(state.isNewFile)
    }

    @Test
    fun compatibilityViewModelPropertiesShareDocumentState() {
        val viewModel = EditorViewModel()
        val entry = SubtitleEntry(text = "hello")

        viewModel.subtitleEntries.add(entry)
        viewModel.documentTitle = "sample.srt"

        assertEquals(listOf(entry), viewModel.documentState.subtitleEntries)
        assertEquals("sample.srt", viewModel.documentState.documentTitle)
    }
}
