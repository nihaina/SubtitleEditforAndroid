package com.subtitleedit

import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.util.SubtitleParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPreparedDocumentLoadTest {
    @Test
    fun preparedTextDraftKeepsExplicitFormatAndOpenedMediaContext() {
        val source = "[00:01.00]This is literal text in a TXT draft"
        val model = EditorViewModel()
        val media = File("/media/song.wav")
        val subtitle = File("/media/song.srt")
        model.mediaType = EditorMediaType.AUDIO
        model.currentFile = media
        model.filePath = media.path
        model.subtitleFile = subtitle
        model.subtitleFilePath = subtitle.path
        model.hasUnsavedChanges = true
        model.sourceViewNeedsListSync = true

        // This part can run on a worker and must honor the draft's original extension.
        val prepared = model.subtitleRepository.load(source, "song.txt")
        val installed = model.loadPreparedSubtitleContent(source, prepared)

        assertSame(prepared, installed)
        assertSame(prepared, model.document.value)
        assertEquals(SubtitleParser.SubtitleFormat.TXT, model.currentFormat)
        assertEquals(source, model.subtitleEntries.single().text)
        assertEquals(source, model.originalFileContent)
        assertEquals(source, model.sourceViewContent)
        assertEquals(source, model.sourceHistoryTextSnapshot)
        assertFalse(model.sourceViewNeedsListSync)
        assertFalse(model.hasUnsavedChanges)
        assertTrue(model.documentLoaded)
        assertSame(media, model.currentFile)
        assertSame(subtitle, model.subtitleFile)
        assertEquals(media.path, model.filePath)
        assertEquals(subtitle.path, model.subtitleFilePath)

        // Subsequent editor changes must not mutate the parser result used for publication.
        model.subtitleEntries.single().text = "edited"
        assertEquals(source, prepared.entries.single().text)
    }
}
