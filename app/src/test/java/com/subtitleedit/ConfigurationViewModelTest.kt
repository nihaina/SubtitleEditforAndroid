package com.subtitleedit

import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationViewModelTest {
    @Test
    fun mainStateKeepsNavigationAndDestinationSelection() {
        val model = MainViewModel()
        val download = File("/storage/emulated/0/Download")
        val parent = File("/storage/emulated/0")

        model.currentDirectory = download
        model.directoryHistory += parent
        model.selectedPaths += File(download, "example.srt").path
        model.pendingFileOperation = FileOperation.COPY

        assertEquals(download, model.currentDirectory)
        assertEquals(listOf(parent), model.directoryHistory)
        assertEquals(FileOperation.COPY, model.pendingFileOperation)
        assertEquals(1, model.selectedPaths.size)
    }

    @Test
    fun editorStateKeepsUnsavedDocumentAndUiPosition() {
        val model = EditorViewModel()
        val entry = SubtitleEntry(1, 100L, 900L, "未保存内容")

        model.documentLoaded = true
        model.subtitleEntries += entry
        model.currentFormat = SubtitleParser.SubtitleFormat.SRT
        model.hasUnsavedChanges = true
        model.isSourceViewMode = false
        model.selectedIndices = setOf(0)
        model.savedFirstVisibleItemPosition = 0
        model.savedScrollPosition = -12

        assertSame(entry, model.subtitleEntries.single())
        assertTrue(model.hasUnsavedChanges)
        assertEquals(setOf(0), model.selectedIndices)
        assertEquals(-12, model.savedScrollPosition)
    }

    @Test
    fun editorStateKeepsSourceDocumentAndCreatedUri() {
        val model = EditorViewModel()
        model.documentLoaded = true
        model.isSourceViewMode = true
        model.sourceViewContent = "line 1\nline 2"
        model.documentUri = "content://documents/subtitle.srt"
        model.isNewFile = false

        assertEquals("line 1\nline 2", model.sourceViewContent)
        assertEquals("content://documents/subtitle.srt", model.documentUri)
        assertTrue(!model.isNewFile)
    }

    @Test
    fun newSubtitleDocumentKeepsOpenedMediaContext() {
        val model = EditorViewModel()
        val mediaFile = File("/storage/emulated/0/Movies/example.mp4")
        model.mediaType = EditorMediaType.VIDEO
        model.filePath = mediaFile.path
        model.currentFile = mediaFile
        model.subtitleFilePath = "/storage/emulated/0/Movies/example.srt"
        model.subtitleFile = File(model.subtitleFilePath)
        model.documentUri = "content://documents/example.srt"

        model.startNewSubtitleDocument()

        assertSame(mediaFile, model.currentFile)
        assertEquals(mediaFile.path, model.filePath)
        assertEquals(EditorMediaType.VIDEO, model.mediaType)
        assertEquals("example.mp4", model.documentTitle)
        assertEquals("", model.subtitleFilePath)
        assertNull(model.subtitleFile)
        assertNull(model.documentUri)
        assertTrue(model.isNewFile)
    }

    @Test
    fun uriSubtitleTitleDoesNotReplaceOpenedMediaTitle() {
        val model = EditorViewModel()
        val mediaFile = File("/storage/emulated/0/Music/example.flac")
        model.mediaType = EditorMediaType.AUDIO
        model.filePath = mediaFile.path
        model.currentFile = mediaFile

        model.openUriSubtitleDocument(
            uri = "content://documents/translated.srt",
            subtitleTitle = "translated.srt"
        )

        assertSame(mediaFile, model.currentFile)
        assertEquals("example.flac", model.documentTitle)
        assertEquals("content://documents/translated.srt", model.documentUri)
        assertTrue(!model.isNewFile)

        model.saveUriSubtitleDocument(
            uri = "content://documents/final.srt",
            subtitleTitle = "final.srt"
        )

        assertSame(mediaFile, model.currentFile)
        assertEquals("example.flac", model.documentTitle)
        assertEquals("content://documents/final.srt", model.documentUri)
    }

    @Test
    fun newSubtitleOnlyDocumentClearsPreviousFileContext() {
        val model = EditorViewModel()
        model.mediaType = EditorMediaType.SUBTITLE_ONLY
        model.filePath = "/storage/emulated/0/Download/example.srt"
        model.currentFile = File(model.filePath)
        model.documentTitle = "example.srt"

        model.startNewSubtitleDocument()

        assertEquals("", model.filePath)
        assertNull(model.currentFile)
        assertEquals("未命名", model.documentTitle)
        assertTrue(model.isNewFile)
    }
}
