package com.subtitleedit

import com.subtitleedit.editor.EditorSourceTextEdit
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import org.junit.Assert.*
import org.junit.Test

class EditorVttSourceMetadataTest {
    @Test fun firstCueIdentifierUndoAndRedoNeverAddItsIdentifierToTheHeader() {
        val source = document("first-id")
        val changed = source.replace("first-id", "changed-id")
        val model = EditorViewModel().apply {
            loadSubtitleContent(source, "test.vtt")
            setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), source, true)
            isSourceViewMode = true
        }
        assertTrue(model.recordSourceHistory(EditorSourceTextEdit.between(source, changed), "标识"))
        model.setSourceDocumentContent(changed)
        model.sourceHistoryTextSnapshot = changed
        model.subtitleEntries.single().cueIdentifier = "changed-id"

        assertTrue(model.undo(true) { operation, undo -> model.executeHistoryCommand(operation, undo) })
        model.subtitleEntries.single().cueIdentifier = "first-id" // Existing block-local installation.
        assertEquals("WEBVTT", model.documentHeader)
        assertSingleSavedIdentifier(model, "first-id")

        assertTrue(model.redo(true) { operation, undo -> model.executeHistoryCommand(operation, undo) })
        model.subtitleEntries.single().cueIdentifier = "changed-id"
        assertEquals("WEBVTT", model.documentHeader)
        assertSingleSavedIdentifier(model, "changed-id")
    }

    @Test fun restoringADeletedFirstIdentifierDoesNotDuplicateItOnSave() {
        val source = document("first-id")
        val withoutId = source.replace("first-id\n", "")
        val state = loaded(withoutId)

        state.updateVttMetadataForSourceEdit(source, 2)
        state.subtitleEntries.single().cueIdentifier = "first-id"

        assertEquals("WEBVTT", state.documentHeader)
        val serialized = SubtitleParser.serialize(state.subtitleDocument)
        assertEquals(1, serialized.lineSequence().count { it == "first-id" })
    }

    @Test fun identifiersThatResembleSignatureOrMapFieldsStayOutOfTheHeader() {
        listOf("WEBVTT speaker", "X-TIMESTAMP-MAP label", "X-TIMESTAMP-MAP=LOCAL:00:00:00.000").forEach { id ->
            val source = document(id)
            val state = loaded(source)
            val updated = source.replace("WEBVTT\n\n", "WEBVTT\n\nNOTE changed header\n\n")

            state.updateVttMetadataForSourceEdit(updated, 2)

            assertEquals(parsed(updated).header, state.documentHeader)
            assertFalse(state.documentHeader.contains(id))
            assertEquals(id, state.subtitleEntries.single().cueIdentifier)
        }
    }

    @Test fun headerChangesMatchTheFullParserWithBomCrLfAndTimestampMap() {
        val source = "\uFEFFWEBVTT\r\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000\r\n\r\n" +
            "STYLE\r\n::cue { color:red; }\r\n\r\n" +
            "first-id\r\n00:00:01.000 --> 00:00:02.000\r\nbody\r\n"
        val state = loaded(source)
        val updated = source.replace("color:red", "color:blue")
        val entries = state.subtitleEntries
        val row = entries.single()

        state.updateVttMetadataForSourceEdit(updated, 4)

        assertEquals(parsed(updated).header, state.documentHeader)
        assertFalse(state.documentHeader.contains("first-id"))
        assertFalse(state.documentHeader.contains("X-TIMESTAMP-MAP"))
        assertSame(entries, state.subtitleEntries)
        assertSame(row, entries.single())
        assertEquals(11_000L, row.startTime)
        assertEquals(source, state.originalFileContent)
    }

    @Test fun timeLinesInsideMetadataBlocksCannotBecomeTheFirstCueBoundary() {
        listOf("NOTE metadata", "STYLE", "REGION").forEach { block ->
            val source = "WEBVTT\n\n$block\n00:00:10.000 --> 00:00:20.000\nretained\n\n" +
                "first-id\n00:00:01.000 --> 00:00:02.000\nbody\n"
            val state = loaded(source)
            val updated = source.replace("retained", "changed")

            state.updateVttMetadataForSourceEdit(updated, 4)

            assertEquals(parsed(updated).header, state.documentHeader)
            assertTrue(state.documentHeader.contains("00:00:10.000 --> 00:00:20.000"))
            assertTrue(state.documentHeader.contains("changed"))
            assertFalse(state.documentHeader.contains("first-id"))
        }
    }

    @Test fun sourceBodyChangesKeepTheExistingHeaderAndRowsUntouched() {
        val source = document("first-id")
        val state = loaded(source)
        val header = state.documentHeader
        val entries = state.subtitleEntries
        val row = entries.single()
        val before = row.copy()
        state.documentFooter = "NOTE footer"
        state.historyEntriesSnapshot = listOf(row.copy())
        state.sourceHistoryTextSnapshot = source
        val history = state.historyEntriesSnapshot

        state.updateVttMetadataForSourceEdit(source.replace("body", "edited body"), 4)

        assertSame(header, state.documentHeader)
        assertSame(entries, state.subtitleEntries)
        assertSame(row, entries.single())
        assertEquals(before, row)
        assertSame(history, state.historyEntriesSnapshot)
        assertEquals(source, state.originalFileContent)
        assertEquals(source, state.sourceViewContent)
        assertEquals(source, state.sourceHistoryTextSnapshot)
        assertEquals("NOTE footer", state.documentFooter)
        assertFalse(state.hasUnsavedChanges)
    }

    @Test fun aCueWithoutAnIdentifierStillSeparatesTheHeaderFromItsTimeLine() {
        val source = "WEBVTT\n\nNOTE header\nretained\n\n00:00:01.000 --> 00:00:02.000\nbody\n"
        val state = loaded(source)
        val updated = source.replace("retained", "changed")

        state.updateVttMetadataForSourceEdit(updated, 3)

        assertEquals(parsed(updated).header, state.documentHeader)
        assertFalse(state.documentHeader.contains("-->"))
    }

    @Test fun aHeaderOnlyDocumentCanUpdateItsStyleWithoutInventingCueRows() {
        val source = "\uFEFFWEBVTT\r\n\r\nSTYLE\r\n::cue { color:red; }\r\n"
        val state = loaded(source)
        val updated = source.replace("color:red", "color:blue")

        state.updateVttMetadataForSourceEdit(updated, 3)

        assertEquals(parsed(updated).header, state.documentHeader)
        assertTrue(state.documentHeader.contains("color:blue"))
        assertTrue(state.subtitleEntries.isEmpty())
    }

    @Test fun aTemporarilyMalformedFirstCueDoesNotBecomeHeaderMetadata() {
        val source = document("first-id")
        val state = loaded(source)
        val header = state.documentHeader

        state.updateVttMetadataForSourceEdit(source.replace("00:00:01.000 --> 00:00:02.000", "00:00:"), 3)

        assertSame(header, state.documentHeader)
        assertFalse(state.documentHeader.contains("first-id"))
    }

    private fun loaded(source: String) = EditorDocumentState().apply {
        replaceDocument(parsed(source))
        originalFileContent = source
        sourceViewContent = source
    }

    private fun parsed(source: String) = SubtitleParser.parseDocument(source, format = SubtitleFormat.VTT)

    private fun document(identifier: String) = "WEBVTT\n\n$identifier\n00:00:01.000 --> 00:00:02.000\nbody\n"

    private fun assertSingleSavedIdentifier(model: EditorViewModel, identifier: String) {
        model.isSourceViewMode = false
        val saved = model.buildSaveContent()!!
        assertEquals(1, saved.lineSequence().count { it == identifier })
        assertEquals(identifier, SubtitleParser.parseVTT(saved).single().cueIdentifier)
        model.isSourceViewMode = true
    }
}
