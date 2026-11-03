package com.subtitleedit.editor

import com.subtitleedit.EditorEditHistory
import com.subtitleedit.EditorEditHistory.Operation
import com.subtitleedit.EditorEntryEdit
import com.subtitleedit.EditorHistoryCommandResult
import com.subtitleedit.EditorViewModel
import org.junit.Assert.*
import org.junit.Test

class EditorHistoryCoordinatorTest {
    @Test fun listHistoryIsCommittedAndMovedBeforeItsResultIsRendered() {
        val model = loaded()
        val row = model.subtitleEntries.first()
        model.commitSingleEdit(EditorEntryEdit.Text(row.stableId, "changed"), emptySet())!!
        val editedSource = model.originalFileContent
        val operation = model.peekUndo()!!
        var renders = 0
        val coordinator = coordinator(model, render = { result, renderedOperation, undo ->
            renders++
            assertSame(operation, renderedOperation)
            assertSame(model.subtitleEntries, result.entries)
            assertEquals(if (undo) "first" else "changed", row.text)
            assertEquals(if (undo) SRT else editedSource, result.sourceText)
            assertEquals(result.sourceText, model.originalFileContent)
            assertEquals(result.sourceText, model.sourceViewContent)
            assertEquals(result.sourceText, model.sourceHistoryTextSnapshot)
            assertEquals(model.subtitleEntries, model.historyEntriesSnapshot)
            assertEquals(model.subtitleEntries, model.document.value.entries)
            assertEquals(result.selectedIds, model.historySelectionSnapshot)
            assertTrue(model.hasUnsavedChanges)
            assertFalse(model.sourceViewNeedsListSync)
            if (undo) {
                assertNull(model.peekUndo())
                assertSame(operation, model.peekRedo())
            } else {
                assertSame(operation, model.peekUndo())
                assertNull(model.peekRedo())
            }
        })

        assertTrue(coordinator.undo())
        assertTrue(coordinator.redo())
        assertEquals(2, renders)
    }

    @Test fun listHistoryDoesNotReadUiSelectionAgainAfterTheModelCommit() {
        val model = loaded()
        model.commitSingleEdit(EditorEntryEdit.Text(model.subtitleEntries.first().stableId, "changed"), emptySet())!!
        var renders = 0
        val coordinator = coordinator(
            model,
            selection = { error("List history must not resynchronize from the UI after committing") },
            render = { _, _, _ -> renders++ }
        )

        assertTrue(coordinator.undo())
        assertEquals("first", model.historyEntriesSnapshot.first().text)
        assertEquals(1, renders)
    }

    @Test fun renderFailureDoesNotRestoreAnAlreadyCommittedOperationToTheUndoStack() {
        val model = loaded()
        model.commitSingleEdit(EditorEntryEdit.Text(model.subtitleEntries.first().stableId, "changed"), emptySet())!!
        val operation = model.peekUndo()!!
        val coordinator = coordinator(model, render = { _, _, _ -> error("render failed") })

        val failure = runCatching { coordinator.undo() }.exceptionOrNull()

        assertEquals("render failed", failure?.message)
        assertEquals("first", model.subtitleEntries.first().text)
        assertEquals(SRT, model.originalFileContent)
        assertEquals(model.subtitleEntries, model.document.value.entries)
        assertEquals(model.subtitleEntries, model.historyEntriesSnapshot)
        assertNull(model.peekUndo())
        assertSame(operation, model.peekRedo())
    }

    @Test fun failedSourcePatchDoesNotRenderOrMoveListHistoryOrChangeTheModel() {
        val model = loaded()
        model.commitSingleEdit(EditorEntryEdit.Text(model.subtitleEntries.first().stableId, "changed"), emptySet())!!
        val operation = model.peekUndo()!!
        model.originalFileContent = "unrelated document"
        model.sourceViewContent = "also unrelated"
        model.hasUnsavedChanges = false
        val list = model.subtitleEntries
        val rows = list.map { it.copy() }
        val baseline = model.historyEntriesSnapshot
        val document = model.document.value
        val sourceHistory = model.sourceHistoryTextSnapshot
        var renders = 0
        val coordinator = coordinator(model, render = { _, _, _ -> renders++ })

        val failure = runCatching { coordinator.undo() }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertSame(operation, model.peekUndo())
        assertNull(model.peekRedo())
        assertEquals(0, renders)
        assertSame(list, model.subtitleEntries)
        assertEquals(rows, list)
        assertSame(baseline, model.historyEntriesSnapshot)
        assertSame(document, model.document.value)
        assertEquals("unrelated document", model.originalFileContent)
        assertEquals("also unrelated", model.sourceViewContent)
        assertEquals(sourceHistory, model.sourceHistoryTextSnapshot)
        assertFalse(model.hasUnsavedChanges)
    }

    @Test fun selectionHistoryRendersSelectionWithoutPublishingOrMarkingTheDocumentDirty() {
        val model = loaded()
        val selected = setOf(model.subtitleEntries.first().stableId)
        model.recordListHistory(
            EditorEditHistory.ListState(model.subtitleEntries, emptySet()),
            EditorEditHistory.ListState(model.subtitleEntries, selected),
            "selection", null, null
        )
        val document = model.document.value
        var selection = selected
        var renders = 0
        val coordinator = coordinator(model, selection = { selection }, render = { result, operation, undo ->
            renders++
            assertTrue(operation.isSelectionOnly())
            assertEquals(if (undo) emptySet<Long>() else selected, result.selectedIds)
            assertEquals(result.selectedIds, model.historySelectionSnapshot)
            assertSame(document, model.document.value)
            assertFalse(model.hasUnsavedChanges)
            selection = result.selectedIds
        })

        assertTrue(coordinator.undo())
        assertTrue(selection.isEmpty())
        assertTrue(coordinator.redo())
        assertEquals(selected, selection)
        assertEquals(2, renders)
        assertEquals(SRT, model.originalFileContent)
    }

    @Test fun sourceModeSkipsSelectionHistoryWhileUsingTheListCommitForContentHistory() {
        val model = loaded()
        model.commitSingleEdit(EditorEntryEdit.Text(model.subtitleEntries.first().stableId, "changed"), emptySet())!!
        val content = model.peekUndo()!!
        val selected = setOf(model.subtitleEntries.last().stableId)
        model.recordListHistory(
            EditorEditHistory.ListState(model.subtitleEntries, emptySet()),
            EditorEditHistory.ListState(model.subtitleEntries, selected),
            "selection", null, null
        )
        val selection = model.peekUndo()!!
        model.isSourceViewMode = true
        var renders = 0
        val coordinator = coordinator(model, render = { result, operation, undo ->
            renders++
            assertSame(content, operation)
            assertTrue(undo)
            assertEquals(selected, result.selectedIds)
            assertEquals(SRT, result.sourceText)
        })

        assertTrue(coordinator.undo())
        assertSame(selection, model.peekUndo())
        assertSame(content, model.peekRedo())
        assertFalse(coordinator.undo())
        assertEquals(1, renders)
        assertEquals("first", model.document.value.entries.first().text)
    }

    @Test fun sourceHistoryStillUsesTheSourceCallbackAndSynchronizesItsResolvedRows() {
        val model = loaded()
        val changedSource = SRT.replace("first", "changed")
        val edit = EditorSourceTextEdit.between(SRT, changedSource)
        model.recordSourceHistory(edit, "source edit")
        val operation = model.peekUndo()!!
        model.subtitleEntries.first().text = "changed"
        model.originalFileContent = changedSource
        model.sourceViewContent = changedSource
        model.sourceHistoryTextSnapshot = changedSource
        model.syncHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, emptySet()), changedSource, true)
        model.isSourceViewMode = true
        var sourceCalls = 0
        val coordinator = coordinator(model, applySource = { applied, undo ->
            sourceCalls++
            assertSame(operation, applied)
            assertTrue(undo)
            val target = (applied as Operation.SourceChange).edit.applyTo(model.sourceViewContent, undo)
            // The Activity owns block-local parsing for source history. Simulate
            // its successful local result before the coordinator advances the baseline.
            model.originalFileContent = target
            model.sourceViewContent = target
            model.sourceHistoryTextSnapshot = target
            model.subtitleEntries.first().text = "first"
        }, render = { _, _, _ -> error("Source history must keep its existing rendering path") })

        assertTrue(coordinator.undo())
        assertEquals(1, sourceCalls)
        assertEquals(SRT, model.sourceHistoryTextSnapshot)
        assertEquals("first", model.historyEntriesSnapshot.first().text)
        assertNull(model.peekUndo())
        assertSame(operation, model.peekRedo())
    }

    @Test fun emptyHistoryDoesNotInvokeEitherApplicationPath() {
        val model = loaded()
        val document = model.document.value
        val coordinator = coordinator(model, render = { _, _, _ -> error("No history to render") })

        assertFalse(coordinator.undo())
        assertFalse(coordinator.redo())
        assertSame(document, model.document.value)
        assertFalse(model.hasUnsavedChanges)
    }

    private fun coordinator(
        model: EditorViewModel,
        selection: () -> Set<Long> = { model.historySelectionSnapshot },
        applySource: (Operation, Boolean) -> Unit = { _, _ -> error("List history must not call the source application path") },
        render: (EditorHistoryCommandResult, Operation.ListChange, Boolean) -> Unit = { _, _, _ -> }
    ) = EditorHistoryCoordinator(
        stateModel = model,
        documentState = model.documentState,
        selectedIds = selection,
        sourceMode = { model.isSourceViewMode },
        applyOperation = applySource,
        invalidateMenu = {},
        renderListResult = render
    )

    private fun loaded() = EditorViewModel().apply {
        loadSubtitleContent(SRT, "test.srt")
        setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), SRT, true)
    }

    private companion object {
        const val SRT = "1\n00:00:01,000 --> 00:00:02,000\nfirst\n\n2\n00:00:03,000 --> 00:00:04,000\nsecond\n"
    }
}
