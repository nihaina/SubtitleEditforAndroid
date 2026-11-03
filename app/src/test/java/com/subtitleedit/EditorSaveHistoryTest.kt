package com.subtitleedit

import com.subtitleedit.editor.EditorSourceTextEdit
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Disk formatting must not replace the raw text addressed by existing history patches. */
class EditorSaveHistoryTest {
    @Test fun savingNormalizedSrtToDiskKeepsTwoEditsUndoableAndRedoable() {
        val model = loaded(SRT, "test.srt")
        val first = model.subtitleEntries.first()
        editText(model, 0, "edited")
        val afterText = model.originalFileContent
        model.commitSingleEdit(EditorEntryEdit.Time(first.stableId, 1_100, 2_200), emptySet())!!
        val beforeSave = model.originalFileContent
        val top = model.peekUndo()
        val document = model.document.value
        val diskContent = model.buildSaveContent()!!
        assertNotEquals(beforeSave, diskContent)
        val file = Files.createTempFile("editor-save-history-", ".srt").toFile()
        try {
            EditorDocumentWriter.write(diskContent, Charsets.UTF_8) { file.outputStream() }
            model.markDocumentSaved()

            assertEquals(diskContent, file.readText())
            assertEquals(beforeSave, model.originalFileContent)
            assertEquals(beforeSave, model.sourceViewContent)
            assertEquals(beforeSave, model.sourceHistoryTextSnapshot)
            assertSame(top, model.peekUndo())
            assertSame(document, model.document.value)
            assertFalse(model.hasUnsavedChanges)
            move(model, undo = true)
            assertEquals(afterText, model.originalFileContent)
            move(model, undo = true)
            assertEquals(SRT, model.originalFileContent)
            assertEquals("first", first.text)
            assertNull(model.peekUndo())
            repeat(2) { move(model, undo = false) }
            assertEquals(beforeSave, model.originalFileContent)
            assertEquals(diskContent, model.buildSaveContent())
            assertTrue(model.hasUnsavedChanges)
        } finally { file.delete() }
    }

    @Test fun saveBetweenUndoAndRedoKeepsTheRedoPatchOnItsOriginalBaseline() {
        val model = loaded(SRT, "test.srt")
        editText(model, 0, "first edit")
        val afterFirst = model.originalFileContent
        editText(model, 1, "second edit")
        val afterSecond = model.originalFileContent
        move(model, undo = true)
        val redo = model.peekRedo()

        model.buildSaveContent()!!
        model.markDocumentSaved()

        assertSame(redo, model.peekRedo())
        assertEquals(afterFirst, model.originalFileContent)
        move(model, undo = false)
        assertEquals(afterSecond, model.originalFileContent)
        assertEquals("second edit", model.subtitleEntries[1].text)
        move(model, undo = true)
        move(model, undo = true)
        assertEquals(SRT, model.originalFileContent)
    }

    @Test fun editingAfterSaveCanUndoAcrossTheSaveWithoutCreatingAnExtraHistoryItem() {
        val model = loaded(SRT, "test.srt")
        editText(model, 0, "saved edit")
        val savedRaw = model.originalFileContent
        model.buildSaveContent()!!
        model.markDocumentSaved()
        editText(model, 1, "later edit")

        move(model, undo = true)
        assertEquals(savedRaw, model.originalFileContent)
        move(model, undo = true)
        assertEquals(SRT, model.originalFileContent)
        assertNull(model.peekUndo())
        repeat(2) { move(model, undo = false) }
        assertEquals(listOf("saved edit", "later edit"), model.subtitleEntries.map { it.text })
        assertNull(model.peekRedo())
    }

    @Test fun savingVttColorAndTimeEditsPreservesRawSectionsAndTheirUndoOrder() {
        val model = loaded(VTT, "test.vtt")
        model.applySubtitleColor(setOf(0), 0xFF123456.toInt(), emptySet())
        val afterColor = model.originalFileContent
        val coloredHeader = model.documentHeader
        model.commitSingleEdit(EditorEntryEdit.Time(model.subtitleEntries[1].stableId, 3_250, 4_500), emptySet())!!
        val afterTime = model.originalFileContent
        val footer = model.documentFooter
        val disk = model.buildSaveContent()!!
        assertNotEquals(afterTime, disk)

        model.markDocumentSaved()

        assertEquals(afterTime, model.originalFileContent)
        assertEquals(coloredHeader, model.documentHeader)
        assertEquals(footer, model.documentFooter)
        assertTrue(model.originalFileContent.startsWith("\uFEFFWEBVTT\r\n"))
        assertTrue(model.originalFileContent.endsWith("NOTE retained footer\r\n"))
        move(model, undo = true)
        assertEquals(afterColor, model.originalFileContent)
        move(model, undo = true)
        assertEquals(VTT, model.originalFileContent)
        repeat(2) { move(model, undo = false) }
        assertEquals(afterTime, model.originalFileContent)
        assertEquals(disk, model.buildSaveContent())
    }

    @Test fun aVttMetadataOnlyEditRemainsUndoableAfterSave() {
        val model = loaded(VTT, "test.vtt")
        val initial = model.subtitleDocument
        model.applyVttDocument(initial.copy(
            header = initial.header + "\n\nSTYLE\n::cue(.new) { font-weight:bold; }",
            footer = "NOTE changed footer"
        ), emptySet(), "样式和注释")
        val raw = model.originalFileContent
        val after = model.subtitleDocument
        model.buildSaveContent()!!

        model.markDocumentSaved()
        move(model, undo = true)

        assertEquals(VTT, model.originalFileContent)
        assertEquals(initial, model.subtitleDocument)
        move(model, undo = false)
        assertEquals(raw, model.originalFileContent)
        assertEquals(after, model.subtitleDocument)
    }

    @Test fun lrcListSaveNormalizesOutputWithoutChangingThePatchSource() {
        val model = loaded(LRC, "test.lrc")
        editText(model, 0, "<b>edited</b>")
        val afterText = model.originalFileContent
        model.commitSingleEdit(EditorEntryEdit.Time(model.subtitleEntries[0].stableId, 1_250, 2_200), emptySet())!!
        val raw = model.originalFileContent
        val disk = model.buildSaveContent()!!
        assertFalse(disk.contains("<b>"))
        assertNotEquals(raw, disk)

        model.markDocumentSaved()

        assertEquals(raw, model.originalFileContent)
        move(model, undo = true)
        assertEquals(afterText, model.originalFileContent)
        move(model, undo = true)
        assertEquals(LRC, model.originalFileContent)
        repeat(2) { move(model, undo = false) }
        assertEquals(raw, model.originalFileContent)
        assertEquals(disk, model.buildSaveContent())
    }

    @Test fun lrcSourceSaveKeepsVisibleMarkupAndSourceHistoryWhileWritingPlainText() {
        val model = loaded(LRC, "test.lrc")
        model.isSourceViewMode = true
        val changed = LRC.replace("first", "<b>edited</b>")
        recordSourceEdit(model, changed)
        val disk = model.buildSaveContent()!!
        assertTrue(changed.contains("<b>edited</b>"))
        assertFalse(disk.contains("<b>"))
        assertTrue(disk.contains("edited"))

        model.markDocumentSaved()

        assertEquals(changed, model.originalFileContent)
        assertEquals(changed, model.sourceViewContent)
        assertEquals(changed, model.sourceHistoryTextSnapshot)
        move(model, undo = true)
        assertEquals(LRC, model.originalFileContent)
        move(model, undo = false)
        assertEquals(changed, model.originalFileContent)
        assertEquals(disk, model.buildSaveContent())
    }

    @Test fun mixedSourceAndListHistoryCanCrossAListSaveInBothDirections() {
        val model = loaded(SRT, "test.srt")
        val changedSource = SRT.replace("first", "from source")
        model.isSourceViewMode = true
        recordSourceEdit(model, changedSource)
        // Simulate the existing block-local installation and view-switch baseline.
        model.subtitleEntries[0].text = "from source"
        model.syncHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, emptySet()), changedSource, true)
        model.sourceViewNeedsListSync = false
        model.isSourceViewMode = false
        editText(model, 1, "from list")
        val finalSource = model.originalFileContent
        model.buildSaveContent()!!

        model.markDocumentSaved()

        move(model, undo = true)
        assertEquals(changedSource, model.originalFileContent)
        move(model, undo = true)
        assertEquals(SRT, model.originalFileContent)
        move(model, undo = false)
        assertEquals(changedSource, model.originalFileContent)
        move(model, undo = false)
        assertEquals(finalSource, model.originalFileContent)
    }

    @Test fun defaultNewSubtitleCanBeSavedAfterUndoWithoutInvalidatingRedo() {
        val source = SubtitleParser.toSRT(listOf(SubtitleEntry(index = 1, startTime = 0, endTime = 3_000, text = "请输入文本")))
        val model = loaded(source, "new.srt")
        assertEquals(source, model.buildSaveContent())
        editText(model, 0, "new text")
        val editedSource = model.originalFileContent
        move(model, undo = true)
        val redo = model.peekRedo()
        model.buildSaveContent()!!

        model.markDocumentSaved()

        assertEquals(source, model.sourceViewContent)
        assertSame(redo, model.peekRedo())
        move(model, undo = false)
        assertEquals(editedSource, model.originalFileContent)
        assertEquals("new text", model.subtitleEntries.single().text)
    }

    @Test fun savingAGenuinelyEmptyDocumentKeepsItsInsertionRedoAvailable() {
        val model = loaded("", "empty.srt").apply { currentFormat = SubtitleParser.SubtitleFormat.SRT }
        model.commitListEdit(EditorCommand.Insert(0,
            listOf(SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "inserted"))), emptySet())!!
        val insertedSource = model.originalFileContent
        val insertedId = model.subtitleEntries.single().stableId
        move(model, undo = true)
        assertTrue(model.subtitleEntries.isEmpty())
        assertEquals("", model.originalFileContent)
        val redo = model.peekRedo()
        // The existing SRT writer emits terminal blank lines even with no cues.
        assertTrue(SubtitleParser.parseSRT(model.buildSaveContent()!!).isEmpty())

        model.markDocumentSaved()

        assertEquals("", model.originalFileContent)
        assertEquals("", model.sourceViewContent)
        assertEquals("", model.sourceHistoryTextSnapshot)
        assertSame(redo, model.peekRedo())
        assertFalse(model.hasUnsavedChanges)
        move(model, undo = false)
        assertEquals(insertedSource, model.originalFileContent)
        assertEquals(insertedId, model.subtitleEntries.single().stableId)
        assertEquals("inserted", model.subtitleEntries.single().text)
    }

    @Test fun structuralEditsKeepTheirSourcePatchesAndSurvivingRowsAcrossSave() {
        val model = loaded(SRT, "test.srt")
        val first = model.subtitleEntries[0]
        val second = model.subtitleEntries[1]
        model.commitListEdit(EditorCommand.Delete(setOf(0)), emptySet())!!
        val afterDeletion = model.originalFileContent
        model.commitListEdit(EditorCommand.Insert(1,
            listOf(SubtitleEntry(startTime = 5_000, endTime = 6_000, text = "inserted"))), emptySet())!!
        val finalSource = model.originalFileContent
        val finalIds = model.subtitleEntries.map { it.stableId }
        model.buildSaveContent()!!

        model.markDocumentSaved()
        move(model, undo = true)

        assertEquals(afterDeletion, model.originalFileContent)
        assertSame(second, model.subtitleEntries.single())
        move(model, undo = true)
        assertEquals(SRT, model.originalFileContent)
        assertEquals(first.stableId, model.subtitleEntries[0].stableId)
        assertSame(second, model.subtitleEntries[1])
        repeat(2) { move(model, undo = false) }
        assertEquals(finalSource, model.originalFileContent)
        assertEquals(finalIds, model.subtitleEntries.map { it.stableId })
        assertSame(second, model.subtitleEntries[0])
    }

    @Test fun saveCompletionDoesNotCopyOrVisitTwentyThousandRowsOrPublishTheirSnapshot() {
        val source = buildString {
            repeat(20_000) { index -> append(index + 1).append("\n00:00:01,000 --> 00:00:02,000\nrow ").append(index).append("\n\n") }
        }
        val model = loaded(source, "large.srt")
        editText(model, 10_000, "edited")
        val live = CountingRows(model.subtitleEntries)
        model.documentState.subtitleEntries = live
        val baseline = model.historyEntriesSnapshot
        val document = model.document.value
        val sourceText = model.originalFileContent
        val operation = model.peekUndo()

        model.markDocumentSaved()

        assertEquals(0, live.reads)
        assertSame(live, model.subtitleEntries)
        assertSame(baseline, model.historyEntriesSnapshot)
        assertSame(document, model.document.value)
        assertSame(sourceText, model.originalFileContent)
        assertSame(operation, model.peekUndo())
        assertFalse(model.hasUnsavedChanges)
    }

    @Test fun saveCompletionDoesNotClaimPendingSourceParsingHasFinished() {
        val model = loaded(SRT, "test.srt")
        model.setSourceDocumentContent(SRT.replace("first", "pending"))
        model.sourceHistoryTextSnapshot = model.sourceViewContent
        model.documentState.sourceViewEditGeneration = 4
        model.documentState.sourceViewEntriesGeneration = 3
        val document = model.document.value

        model.markDocumentSaved()

        assertTrue(model.sourceViewNeedsListSync)
        assertEquals(4L, model.documentState.sourceViewEditGeneration)
        assertEquals(3L, model.documentState.sourceViewEntriesGeneration)
        assertSame(document, model.document.value)
        assertEquals(SRT.replace("first", "pending"), model.sourceViewContent)
        assertFalse(model.hasUnsavedChanges)
    }

    @Test fun failedDiskWriteLeavesTheDirtyDocumentAndItsHistoryUntouched() {
        val model = loaded(SRT, "test.srt")
        editText(model, 0, "edited")
        val source = model.originalFileContent
        val operation = model.peekUndo()
        val baseline = model.historyEntriesSnapshot
        val document = model.document.value

        val failure = runCatching {
            EditorDocumentWriter.write(model.buildSaveContent()!!, Charsets.UTF_8) {
                object : OutputStream() { override fun write(value: Int) { throw IOException("disk failure") } }
            }
            model.markDocumentSaved()
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("disk failure", failure?.message)
        assertTrue(model.hasUnsavedChanges)
        assertEquals(source, model.originalFileContent)
        assertSame(operation, model.peekUndo())
        assertSame(baseline, model.historyEntriesSnapshot)
        assertSame(document, model.document.value)
        move(model, undo = true)
        assertEquals(SRT, model.originalFileContent)
    }

    private fun loaded(source: String, fileName: String) = EditorViewModel().apply {
        loadSubtitleContent(source, fileName)
        setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), source, true)
    }

    private fun editText(model: EditorViewModel, position: Int, text: String) {
        checkNotNull(model.commitSingleEdit(EditorEntryEdit.Text(model.subtitleEntries[position].stableId, text), emptySet()))
    }

    private fun recordSourceEdit(model: EditorViewModel, source: String) {
        val before = model.sourceViewContent
        assertTrue(model.recordSourceHistory(EditorSourceTextEdit.between(before, source), "源码编辑"))
        model.setSourceDocumentContent(source)
        model.sourceHistoryTextSnapshot = source
    }

    private fun move(model: EditorViewModel, undo: Boolean) {
        val apply: (EditorEditHistory.Operation, Boolean) -> Unit = { command, reversed ->
            model.executeHistoryCommand(command, reversed)
        }
        assertTrue(if (undo) model.undo(model.isSourceViewMode, apply) else model.redo(model.isSourceViewMode, apply))
    }

    private class CountingRows(private val backing: MutableList<SubtitleEntry>) : AbstractMutableList<SubtitleEntry>() {
        var reads = 0
        override val size: Int get() = backing.size
        override fun get(index: Int): SubtitleEntry { reads++; return backing[index] }
        override fun set(index: Int, element: SubtitleEntry): SubtitleEntry = backing.set(index, element)
        override fun add(index: Int, element: SubtitleEntry) = backing.add(index, element)
        override fun removeAt(index: Int): SubtitleEntry = backing.removeAt(index)
    }

    private companion object {
        const val SRT = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,000\r\nfirst\r\n\r\n" +
            "2\r\n00:00:03,000 --> 00:00:04,000\r\nsecond\r\n"
        const val VTT = "\uFEFFWEBVTT\r\n\r\nNOTE retained prefix\r\n\r\n" +
            "first-id\r\n00:00:01.000 --> 00:00:02.000\r\nfirst\r\n\r\n" +
            "second-id\r\n00:00:03.000 --> 00:00:04.000\r\nsecond\r\n\r\nNOTE retained footer\r\n"
        const val LRC = "\uFEFF[ar:artist]\r\n\r\n[00:01.00]first\r\n[00:02.00]\r\n" +
            "[00:03.00]second\r\n[00:04.00]\r\n"
    }
}
