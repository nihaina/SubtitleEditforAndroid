package com.subtitleedit

import com.subtitleedit.editor.EditorHistoryCoordinator
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleEntryOps
import org.junit.Assert.*
import org.junit.Test

class EditorListEditSessionTest {
    @Test fun batchTextCommitUpdatesRowsRawSourceHistoryAndSnapshotTogether() {
        val model = loaded()
        val liveList = model.subtitleEntries
        val rows = liveList.toList()
        val before = model.document.value
        val selection = setOf(rows[1].stableId)
        model.setHistoryBaseline(EditorEditHistory.ListState(liveList, selection), SRT, true)

        val change = model.commitListEdit(
            EditorCommand.UpdateTexts(listOf(0 to "first edited", 2 to "third edited"), false), selection
        )!!

        assertEquals(setOf(0, 2), change.result.changedPositions)
        assertFalse(change.result.structureChanged)
        assertEquals(selection, change.selectedIds)
        assertSame(liveList, model.subtitleEntries)
        rows.indices.forEach { assertSame(rows[it], model.subtitleEntries[it]) }
        assertEquals(SRT.replace("first", "first edited").replace("third", "third edited"), model.originalFileContent)
        assertEquals("first", before.entries.first().text)
        assertCommitted(model)
        assertEquals(2, (model.peekUndo() as EditorEditHistory.Operation.ListChange).updated.size)

        assertTrue(undo(model))
        assertEquals(SRT, model.originalFileContent)
        assertEquals(selection, model.historySelectionSnapshot)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals("third edited", rows[2].text)
        assertNull(model.peekRedo())
    }

    @Test fun noOpBatchAndInvalidTargetsDoNotDirtyPublishOrRecordHistory() {
        val model = loaded()
        val before = model.document.value
        val baseline = model.historyEntriesSnapshot
        val noOps = listOf(
            EditorCommand.UpdateTexts(listOf(0 to "first", 2 to "third"), false),
            EditorCommand.UpdateTexts(listOf(-1 to "outside", 10 to "outside"), false),
            EditorCommand.ApplyOffset(setOf(0, 1, 2), 0L),
            EditorCommand.Delete(setOf(-1, 10)),
            EditorCommand.Insert(0, emptyList()),
            EditorCommand.Merge(listOf(0, 2)),
            EditorCommand.Split(0, 1_000L, "left", "right")
        )
        noOps.forEach { assertNull(model.commitListEdit(it, emptySet())) }
        assertSame(before, model.document.value)
        assertSame(baseline, model.historyEntriesSnapshot)
        assertFalse(model.hasUnsavedChanges)
        assertEquals(SRT, model.originalFileContent)
        assertNull(model.peekUndo())
    }

    @Test fun noOpAfterUndoPreservesTheExistingRedoOperation() {
        val model = loaded()
        model.commitListEdit(EditorCommand.ApplyOffset(setOf(0), 200L), emptySet())
        assertTrue(undo(model))
        val redoOperation = model.peekRedo()
        val before = model.document.value
        assertNull(model.commitListEdit(EditorCommand.UpdateTexts(listOf(0 to "first"), false), emptySet()))
        assertSame(redoOperation, model.peekRedo())
        assertSame(before, model.document.value)
        assertTrue(redo(model))
        assertEquals(1_200L, model.subtitleEntries.first().startTime)
    }

    @Test fun negativeOffsetUsesTheExistingUnclampedTimingRuleAndKeepsEndFlags() {
        val model = loaded()
        val first = model.subtitleEntries.first()
        val originalFlag = first.endTimeModified
        val change = model.commitListEdit(EditorCommand.ApplyOffset(setOf(0, 2), -1_500L), emptySet())!!
        assertEquals(setOf(0, 2), change.result.changedPositions)
        assertEquals(-500L, first.startTime)
        assertEquals(500L, first.endTime)
        assertEquals(originalFlag, first.endTimeModified)
        assertEquals(3_000L, model.subtitleEntries[1].startTime)
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(SRT, model.originalFileContent)
        assertEquals(1_000L, first.startTime)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(-500L, first.startTime)
    }

    @Test fun extendingAdjacentRowsUsesTimesFromBeforeTheBatchAndOneUndo() {
        val model = loaded()
        val before = model.subtitleEntries.map { it.copy() }
        model.commitListEdit(EditorCommand.ExtendToAdjacent(setOf(0, 1), false), emptySet())!!
        assertEquals(listOf(3_000L, 5_000L, 6_000L), model.subtitleEntries.map { it.endTime })
        assertTrue(model.subtitleEntries[0].endTimeModified)
        assertTrue(model.subtitleEntries[1].endTimeModified)
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(before, model.subtitleEntries)
        assertEquals(SRT, model.originalFileContent)
        assertNull(model.peekUndo())
    }

    @Test fun deleteRenumbersRowsAndFiltersSelectionWithoutReplacingSurvivors() {
        val model = loaded()
        val list = model.subtitleEntries
        val first = list[0]
        val third = list[2]
        val selection = list.mapTo(mutableSetOf()) { it.stableId }
        model.setHistoryBaseline(EditorEditHistory.ListState(list, selection), SRT, true)
        val change = model.commitListEdit(EditorCommand.Delete(setOf(1)), selection)!!
        assertTrue(change.result.structureChanged)
        assertEquals(setOf(first.stableId, third.stableId), change.selectedIds)
        assertSame(list, model.subtitleEntries)
        assertSame(first, list[0])
        assertSame(third, list[1])
        assertEquals(listOf(1, 2), list.map { it.index })
        assertFalse(model.originalFileContent.contains("second"))
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(SRT, model.originalFileContent)
        assertEquals(selection, model.historySelectionSnapshot)
        assertSame(third, model.subtitleEntries[2])
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(change.selectedIds, model.historySelectionSnapshot)
    }

    @Test fun insertedRowsAreOwnedAndRequestedSelectionIsFilteredToTheNewDocument() {
        val model = loaded()
        val surviving = model.subtitleEntries.last()
        val inserted = SubtitleEntry(startTime = 2_200L, endTime = 2_800L, text = "inserted")
        val selected = setOf(surviving.stableId)
        model.setHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, selected), SRT, true)
        val change = model.commitListEdit(
            EditorCommand.Insert(1, listOf(inserted)), selected,
            selectedAfter = selected + inserted.stableId + Long.MIN_VALUE
        )!!
        assertEquals(selected + inserted.stableId, change.selectedIds)
        assertEquals(listOf(1, 2, 3, 4), model.subtitleEntries.map { it.index })
        assertNotSame(inserted, model.subtitleEntries[1])
        inserted.text = "outside mutation"
        assertEquals("inserted", model.subtitleEntries[1].text)
        assertSame(surviving, model.subtitleEntries.last())
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(SRT, model.originalFileContent)
        assertEquals(selected, model.historySelectionSnapshot)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals("inserted", model.subtitleEntries[1].text)
        assertEquals(change.selectedIds, model.historySelectionSnapshot)
    }

    @Test fun mergeCreatesOneNewRowKeepsOtherIdentityAndDropsOnlyRemovedSelection() {
        val model = loaded()
        val old = model.subtitleEntries.map { it.copy() }
        val third = model.subtitleEntries.last()
        val selection = old.mapTo(mutableSetOf()) { it.stableId }
        model.setHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, selection), SRT, true)
        val change = model.commitListEdit(EditorCommand.Merge(listOf(0, 1)), selection)!!
        val merged = model.subtitleEntries.first()
        assertTrue(change.result.structureChanged)
        assertEquals("first\nsecond", merged.text)
        assertEquals(1_000L, merged.startTime)
        assertEquals(4_000L, merged.endTime)
        assertFalse(merged.stableId in old.map { it.stableId })
        assertEquals(setOf(third.stableId), change.selectedIds)
        assertSame(third, model.subtitleEntries.last())
        val source = model.originalFileContent
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(old, model.subtitleEntries)
        assertEquals(SRT, model.originalFileContent)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(source, model.originalFileContent)
        assertEquals(merged.stableId, model.subtitleEntries.first().stableId)
    }

    @Test fun splitKeepsEndMarkerSemanticsAndRestoresOriginalStableIdOnUndo() {
        val model = loaded()
        val original = model.subtitleEntries.first().copy()
        val selection = setOf(original.stableId, model.subtitleEntries.last().stableId)
        model.setHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, selection), SRT, true)
        val change = model.commitListEdit(EditorCommand.Split(0, 1_500L, "left", "right"), selection)!!
        val left = model.subtitleEntries[0]
        val right = model.subtitleEntries[1]
        assertNotEquals(original.stableId, left.stableId)
        assertNotEquals(original.stableId, right.stableId)
        assertNotEquals(left.stableId, right.stableId)
        assertEquals(1_500L, left.endTime)
        assertEquals(1_500L, right.startTime)
        assertTrue(left.endTimeModified)
        assertEquals(original.endTimeModified, right.endTimeModified)
        assertEquals(selection - original.stableId, change.selectedIds)
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(original, model.subtitleEntries.first())
        assertEquals(SRT, model.originalFileContent)
        assertEquals(selection, model.historySelectionSnapshot)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(listOf(left.stableId, right.stableId), model.subtitleEntries.take(2).map { it.stableId })
    }

    @Test fun vttDeleteAndEditNormalizesTheIntendedCueAfterIndicesShift() {
        val model = loaded(VTT, "sample.vtt")
        val before = model.subtitleEntries.map { it.copy() }
        val middle = model.subtitleEntries[1]
        val last = model.subtitleEntries[2]
        val header = model.documentHeader
        val footer = model.documentFooter
        val change = model.commitListEdit(EditorCommand.UpdateTexts(listOf(0 to "", 2 to "updated")), emptySet())!!
        assertTrue(change.result.structureChanged)
        assertEquals(1, change.result.removedCount)
        assertSame(middle, model.subtitleEntries[0])
        assertSame(last, model.subtitleEntries[1])
        assertEquals(before[1].cueSettings, middle.cueSettings)
        assertEquals("region:r3", last.cueSettings)
        assertEquals("updated", last.text)
        assertEquals(header, model.documentHeader)
        assertEquals(footer, model.documentFooter)
        assertTrue(model.originalFileContent.startsWith("\uFEFFWEBVTT\r\n"))
        assertTrue(model.originalFileContent.contains("STYLE\r\n::cue { color: lime; }"))
        assertTrue(model.originalFileContent.contains("NOTE footer\r\nretained"))
        assertFalse(model.originalFileContent.replace("\r\n", "").contains('\n'))
        assertFalse(model.originalFileContent.contains("position:30%"))
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(before, model.subtitleEntries)
        assertEquals(VTT, model.originalFileContent)
        assertNull(model.peekUndo())
    }

    @Test fun replacingRowsCanReorderSurvivorsWithoutAliasingTheInput() {
        val model = loaded()
        val rows = model.subtitleEntries.toList()
        val replacement = listOf(rows[2].copy(text = "third edited"), rows[0].copy())
        val change = model.commitListEdit(EditorCommand.ReplaceEntries(replacement), emptySet())!!
        assertTrue(change.result.structureChanged)
        assertEquals(listOf("third edited", "first"), model.subtitleEntries.map { it.text })
        assertSame(rows[2], model.subtitleEntries[0])
        assertSame(rows[0], model.subtitleEntries[1])
        assertEquals(listOf(1, 2), model.subtitleEntries.map { it.index })
        replacement.first().text = "external"
        assertEquals("third edited", model.subtitleEntries.first().text)
        val source = model.originalFileContent
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(SRT, model.originalFileContent)
        assertEquals(listOf("first", "second", "third"), model.subtitleEntries.map { it.text })
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(source, model.originalFileContent)
    }

    @Test fun singlePasteRetainsTargetTimingAndUsesOneReversibleHistoryRecord() {
        val model = loaded()
        val first = model.subtitleEntries.first()
        val original = first.copy()
        model.clipboardTexts = listOf("clipboard state")
        val change = model.commitListEdit(EditorCommand.PasteAt(0, listOf("pasted")), emptySet())!!
        assertFalse(change.result.structureChanged)
        assertSame(first, model.subtitleEntries.first())
        assertEquals(original.startTime, first.startTime)
        assertEquals(original.endTime, first.endTime)
        assertEquals(original.endTimeModified, first.endTimeModified)
        assertEquals(listOf("clipboard state"), model.clipboardTexts)
        assertEquals(SRT.replace("first", "pasted"), model.originalFileContent)
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(original, first)
        assertEquals(SRT, model.originalFileContent)
        assertNull(model.peekUndo())
    }

    @Test fun multiPasteUsesExistingGroupTimeAllocationAndRenumbersFollowingRows() {
        val model = loaded()
        val second = model.subtitleEntries[1]
        val texts = listOf("target", "extra one", "extra two")
        val change = model.commitListEdit(EditorCommand.PasteAt(0, texts), emptySet())!!
        assertTrue(change.result.structureChanged)
        assertEquals(listOf("target", "extra one", "extra two", "second", "third"), model.subtitleEntries.map { it.text })
        assertEquals(listOf(2_000L, 2_500L), model.subtitleEntries.slice(1..2).map { it.startTime })
        assertEquals(listOf(2_500L, 3_000L), model.subtitleEntries.slice(1..2).map { it.endTime })
        assertEquals((1..5).toList(), model.subtitleEntries.map { it.index })
        assertSame(second, model.subtitleEntries[3])
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(SRT, model.originalFileContent)
        assertSame(second, model.subtitleEntries[1])
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(5, model.subtitleEntries.size)
    }

    @Test fun selectionPasteFillsTargetsInOrderThenInsertsSurplusAfterTheLastTarget() {
        val model = loaded()
        val rows = model.subtitleEntries.toList()
        val selection = setOf(rows[0].stableId, rows[2].stableId)
        model.setHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, selection), SRT, true)
        val change = model.commitListEdit(
            EditorCommand.PasteToSelection(listOf(2, 0), listOf("first pasted", "third pasted", "surplus")), selection
        )!!
        assertTrue(change.result.structureChanged)
        assertEquals(listOf("first pasted", "second", "third pasted", "surplus"), model.subtitleEntries.map { it.text })
        assertEquals(selection, change.selectedIds)
        rows.indices.forEach { assertSame(rows[it], model.subtitleEntries[it]) }
        assertEquals(6_000L, model.subtitleEntries.last().startTime)
        assertEquals(9_000L, model.subtitleEntries.last().endTime)
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(SRT, model.originalFileContent)
        assertEquals(selection, model.historySelectionSnapshot)
        assertNull(model.peekUndo())
    }

    @Test fun invalidPasteTargetsAndInsufficientClipboardTextAreNoOps() {
        val model = loaded()
        val document = model.document.value
        assertNull(model.commitListEdit(EditorCommand.PasteAt(-1, listOf("pasted")), emptySet()))
        assertNull(model.commitListEdit(EditorCommand.PasteAt(0, emptyList()), emptySet()))
        assertNull(model.commitListEdit(EditorCommand.PasteToSelection(listOf(0, 2), listOf("one")), emptySet()))
        assertNull(model.commitListEdit(EditorCommand.PasteToSelection(emptyList(), listOf("one")), emptySet()))
        assertSame(document, model.document.value)
        assertEquals(SRT, model.originalFileContent)
        assertFalse(model.hasUnsavedChanges)
        assertNull(model.peekUndo())
    }

    @Test fun cutDeletionAndSubsequentPasteKeepTheirExistingTwoUndoSteps() {
        val model = loaded()
        model.commitListEdit(EditorCommand.Delete(setOf(1)), emptySet())!!
        val sourceAfterDeletion = model.originalFileContent
        model.commitListEdit(EditorCommand.PasteAt(1, listOf("second")), emptySet())!!
        assertEquals(listOf("first", "second"), model.subtitleEntries.map { it.text })
        assertTrue(undo(model))
        assertEquals(sourceAfterDeletion, model.originalFileContent)
        assertEquals(listOf("first", "third"), model.subtitleEntries.map { it.text })
        assertTrue(undo(model))
        assertEquals(SRT, model.originalFileContent)
        assertNull(model.peekUndo())
    }

    @Test fun relativeInsertionRetainsTheExistingBeforeReferenceTimeDistribution() {
        val model = loaded()
        val rows = model.subtitleEntries.toList()
        val texts = listOf("new one", "new two")
        val expected = SubtitleEntryOps.createInsertedEntries(
            false, rows[1], rows[0], rows[1], texts, SubtitleEntryOps.DEFAULT_INSERT_DURATION_MS
        )
        val change = model.commitListEdit(
            EditorCommand.Insert(1, expected), emptySet()
        )!!
        assertTrue(change.result.structureChanged)
        assertEquals(listOf("first", "new one", "new two", "second", "third"), model.subtitleEntries.map { it.text })
        val inserted = model.subtitleEntries.slice(1..2)
        assertEquals(expected.map { it.startTime to it.endTime }, inserted.map { it.startTime to it.endTime })
        assertSame(rows[1], model.subtitleEntries[3])
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(SRT, model.originalFileContent)
        assertNull(model.peekUndo())
    }

    @Test fun pastingAfterCuttingAllRowsRestoresTheCapturedTargetInThePasteCommit() {
        val model = loaded()
        val target = model.subtitleEntries.first().copy()
        model.commitListEdit(EditorCommand.Delete(model.subtitleEntries.indices.toSet()), emptySet())!!
        val sourceAfterDeletion = model.sourceViewContent
        assertTrue(model.subtitleEntries.isEmpty())
        val change = model.commitListEdit(
            EditorCommand.PasteAt(0, listOf("pasted"), fallbackEntry = target), emptySet()
        )!!
        assertTrue(change.result.structureChanged)
        assertEquals(target.stableId, model.subtitleEntries.single().stableId)
        assertEquals(target.startTime, model.subtitleEntries.single().startTime)
        assertEquals("pasted", model.subtitleEntries.single().text)
        assertCommitted(model)
        assertTrue(undo(model))
        assertTrue(model.subtitleEntries.isEmpty())
        assertEquals(sourceAfterDeletion, model.originalFileContent)
        assertTrue(undo(model))
        assertEquals(SRT, model.originalFileContent)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertTrue(redo(model))
        assertEquals("pasted", model.subtitleEntries.single().text)
    }

    @Test fun sourcePatchFailureLeavesRowsRawSourceDirtyAndHistoryUntouched() {
        val model = loaded()
        val state = model.documentState
        val liveList = state.subtitleEntries
        val row = liveList.first()
        val original = row.copy()
        val baseline = state.historyEntriesSnapshot
        val document = model.document.value
        var listHistoryCalls = 0
        var sourceHistoryCalls = 0
        val session = EditorListEditSession(
            state,
            { _, _, _, _ -> listHistoryCalls++ },
            { _, _ -> sourceHistoryCalls++ },
            { _, _, _, _ -> throw IllegalStateException("source patch failed") }
        )
        val error = runCatching {
            session.commit(EditorCommand.UpdateTexts(listOf(0 to "prepared only"), false), emptySet())
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("source patch failed", error?.message)
        assertSame(liveList, state.subtitleEntries)
        assertSame(row, state.subtitleEntries.first())
        assertEquals(original, row)
        assertEquals(SRT, state.originalFileContent)
        assertEquals(SRT, state.sourceViewContent)
        assertSame(baseline, state.historyEntriesSnapshot)
        assertSame(document, model.document.value)
        assertFalse(state.hasUnsavedChanges)
        assertEquals(0, listHistoryCalls)
        assertEquals(0, sourceHistoryCalls)
        assertNull(model.peekUndo())
    }

    @Test fun lrcTimeCommitPreservesMetadataAndUndoRestoresTheEndMarkerFlag() {
        val source = "[ar:artist]\r\n[offset:15]\r\n\r\n[00:01.00]first\r\n[00:03.00]second\r\n[00:04.00]\r\n"
        val model = loaded(source, "sample.lrc")
        val first = model.subtitleEntries.first()
        val initial = first.copy()
        model.commitListEdit(EditorCommand.UpdateTime(0, endTime = initial.endTime - 500L), emptySet())!!
        assertTrue(first.endTimeModified)
        assertTrue(model.originalFileContent.startsWith("[ar:artist]\r\n[offset:15]\r\n"))
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(initial, first)
        assertEquals(source, model.originalFileContent)
        assertNull(model.peekUndo())
    }

    @Test fun sourceModeAndModeTransitionRejectListCommandsByDefault() {
        val model = loaded()
        val before = model.document.value
        val command = EditorCommand.Delete(setOf(0))
        model.isSourceViewMode = true
        assertNull(model.commitListEdit(command, emptySet()))
        model.isSourceViewMode = false
        model.isSourceViewTransitioning = true
        assertNull(model.commitListEdit(command, emptySet()))
        assertSame(before, model.document.value)
        assertEquals(SRT, model.originalFileContent)
        assertFalse(model.hasUnsavedChanges)
        assertNull(model.peekUndo())
    }

    @Test fun explicitlyAllowedSourceInsertionRecordsSourceHistoryAndAdvancesGenerations() {
        val model = loaded()
        model.isSourceViewMode = true
        val generation = model.documentState.sourceViewEditGeneration
        model.commitListEdit(
            EditorCommand.Insert(1, listOf(SubtitleEntry(startTime = 2_100L, endTime = 2_900L, text = "timestamped"))),
            emptySet(), allowSourceMode = true
        )!!
        assertTrue(model.peekUndo() is EditorEditHistory.Operation.SourceChange)
        assertTrue(model.documentState.sourceViewEditGeneration > generation)
        assertEquals(model.documentState.sourceViewEditGeneration, model.documentState.sourceViewEntriesGeneration)
        assertTrue(model.originalFileContent.contains("timestamped"))
        assertCommitted(model)
        assertTrue(undo(model, sourceMode = true))
        assertEquals(SRT, model.originalFileContent)
        assertNull(model.peekUndo())
    }

    @Test fun legacyRecorderCannotRecordAnAlreadyCommittedBatchAgain() {
        val model = loaded()
        model.commitListEdit(EditorCommand.ApplyOffset(setOf(0, 2), 100L), emptySet())!!
        val operation = model.peekUndo()
        EditorHistoryCoordinator(model, model.documentState, { emptySet() }, { false }, { _, _ -> }, {}).recordList()
        assertSame(operation, model.peekUndo())
        assertTrue(undo(model))
        assertNull(model.peekUndo())
    }

    @Test fun unsupportedSourceInsertionLeavesRowsSourceHistoryAndGenerationsUntouched() {
        listOf(
            "plain text\n" to "sample.txt",
            "[Script Info]\nScriptType: v4.00+\n" to "sample.ass",
            "[Script Info]\nScriptType: v4.00\n" to "sample.ssa"
        ).forEach { (source, fileName) ->
            val model = loaded(source, fileName)
            model.isSourceViewMode = true
            val rows = model.subtitleEntries.toList()
            val snapshot = model.document.value
            val generation = model.documentState.sourceViewEditGeneration
            val entriesGeneration = model.documentState.sourceViewEntriesGeneration
            assertNull(model.commitListEdit(
                EditorCommand.Insert(0, listOf(SubtitleEntry(startTime = 1_000L, endTime = 2_000L, text = "timestamped"))),
                emptySet(), allowSourceMode = true
            ))
            assertEquals(rows, model.subtitleEntries)
            rows.indices.forEach { assertSame(rows[it], model.subtitleEntries[it]) }
            assertEquals(source, model.sourceViewContent)
            assertEquals(source, model.originalFileContent)
            assertEquals(generation, model.documentState.sourceViewEditGeneration)
            assertEquals(entriesGeneration, model.documentState.sourceViewEntriesGeneration)
            assertSame(snapshot, model.document.value)
            assertFalse(model.hasUnsavedChanges)
            assertNull(model.peekUndo())
        }
    }

    @Test fun firstSecondAndThirdRoundCommitsShareOneUndoOrder() {
        val model = loaded()
        val first = model.subtitleEntries.first()
        model.commitSingleEdit(EditorEntryEdit.Text(first.stableId, "single"), emptySet())!!
        val singleSource = model.originalFileContent
        assertTrue(model.beginWaveformDrag(300L, first.stableId, emptySet()))
        model.updateWaveformDrag(300L, EditorTimingRange(1_100L, 2_100L, false))
        model.finishWaveformDrag(300L, emptySet())!!
        val dragSource = model.originalFileContent
        model.commitListEdit(EditorCommand.UpdateTexts(listOf(1 to "batch"), false), emptySet())!!
        val batchSource = model.originalFileContent
        assertTrue(undo(model))
        assertEquals(dragSource, model.originalFileContent)
        assertTrue(undo(model))
        assertEquals(singleSource, model.originalFileContent)
        assertTrue(undo(model))
        assertEquals(SRT, model.originalFileContent)
        assertNull(model.peekUndo())
        repeat(3) { assertTrue(redo(model)) }
        assertEquals(batchSource, model.originalFileContent)
        assertNull(model.peekRedo())
    }

    @Test fun largeBatchRetainsUntouchedLiveRowsHistoryRowsAndPublishedSnapshots() {
        val source = buildString {
            repeat(2_000) { index ->
                append(index + 1).append("\n00:00:01,000 --> 00:00:02,000\nrow ").append(index).append("\n\n")
            }
        }
        val model = loaded(source)
        val liveList = model.subtitleEntries
        val rows = liveList.toList()
        val history = model.historyEntriesSnapshot.toList()
        val document = model.document.value
        val targets = setOf(100, 1_500)
        model.commitListEdit(EditorCommand.UpdateTexts(targets.map { it to "updated $it" }, false), emptySet())!!
        assertSame(liveList, model.subtitleEntries)
        rows.indices.forEach { position ->
            assertSame(rows[position], model.subtitleEntries[position])
            if (position in targets) {
                assertNotSame(history[position], model.historyEntriesSnapshot[position])
                assertNotSame(document.entries[position], model.document.value.entries[position])
            } else {
                assertSame(history[position], model.historyEntriesSnapshot[position])
                assertSame(document.entries[position], model.document.value.entries[position])
            }
        }
        assertEquals("row 100", history[100].text)
        assertEquals("row 1500", document.entries[1_500].text)
        assertEquals(2, (model.peekUndo() as EditorEditHistory.Operation.ListChange).updated.size)
        assertCommitted(model)
    }

    private fun loaded(source: String = SRT, fileName: String = "sample.srt") = EditorViewModel().apply {
        loadSubtitleContent(source, fileName)
        setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), source, true)
    }

    private fun assertCommitted(model: EditorViewModel) {
        assertTrue(model.hasUnsavedChanges)
        assertFalse(model.sourceViewNeedsListSync)
        assertEquals(model.originalFileContent, model.sourceViewContent)
        assertEquals(model.originalFileContent, model.sourceHistoryTextSnapshot)
        assertEquals(model.subtitleEntries, model.document.value.entries)
    }

    private fun undo(model: EditorViewModel, sourceMode: Boolean = false) = moveHistory(model, true, sourceMode)
    private fun redo(model: EditorViewModel) = moveHistory(model, false, false)

    private fun moveHistory(model: EditorViewModel, undo: Boolean, sourceMode: Boolean): Boolean {
        var result: EditorHistoryCommandResult? = null
        val apply: (EditorEditHistory.Operation, Boolean) -> Unit = { command, reversed ->
            result = model.executeHistoryCommand(command, reversed)
        }
        val moved = if (undo) model.undo(sourceMode, apply) else model.redo(sourceMode, apply)
        result?.let {
            model.syncHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, it.selectedIds), model.sourceViewContent, sourceMode)
        }
        return moved
    }

    private companion object {
        const val SRT = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,000\r\nfirst\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nsecond\r\n\r\n3\r\n00:00:05,000 --> 00:00:06,000\r\nthird\r\n"
        const val VTT = "\uFEFFWEBVTT\r\n\r\nSTYLE\r\n::cue { color: lime; }\r\n\r\none\r\n00:01.000 --> 00:02.000 position:10% line:10% region:r1\r\nfirst\r\n\r\ntwo\r\n00:03.000 --> 00:04.000 position:20% line:20% region:r2\r\nsecond\r\n\r\nthree\r\n00:05.000 --> 00:06.000 position:30% line:30% region:r3\r\nthird\r\n\r\nNOTE footer\r\nretained\r\n"
    }
}
