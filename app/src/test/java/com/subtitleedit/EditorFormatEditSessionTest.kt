package com.subtitleedit

import com.subtitleedit.editor.EditorHistoryCoordinator
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.SubtitleDocument
import com.subtitleedit.util.subtitle.WebVttSubtitleFormatHandler
import org.junit.Assert.*
import org.junit.Test

/** Formatting must share commit ownership without copying or rebinding untouched rows. */
class EditorFormatEditSessionTest {
    @Test fun srtColorCommitsSourceDirtyHistorySelectionAndSnapshotTogether() {
        val model = loaded(SRT, "test.srt")
        val rows = model.subtitleEntries.toList()
        val selected = setOf(rows[1].stableId)
        model.setHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, selected), SRT, true)
        val before = model.document.value
        val result = model.applySubtitleColor(setOf(0), 0xFF123456.toInt(), selected)

        assertEquals(setOf(0), result.changedPositions)
        assertFalse(result.documentMetadataChanged)
        assertEquals("<font color=\"#123456\">first</font>", rows[0].text)
        assertSame(rows[0], model.subtitleEntries[0])
        assertSame(rows[1], model.subtitleEntries[1])
        assertSame(before.entries[1], model.document.value.entries[1])
        assertEquals("first", before.entries[0].text)
        assertEquals(selected, model.historySelectionSnapshot)
        assertCommitted(model)
        val operation = model.peekUndo() as EditorEditHistory.Operation.ListChange
        assertEquals("颜色", operation.description)
        assertEquals(1, operation.updated.size)
        val coloredSource = model.sourceViewContent

        assertTrue(undo(model))
        assertEquals(SRT, model.sourceViewContent)
        assertEquals(selected, model.historySelectionSnapshot)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(coloredSource, model.originalFileContent)
        assertEquals(selected, model.historySelectionSnapshot)
        assertNull(model.peekRedo())
    }

    @Test fun lrcColorKeepsRawMetadataAndEndMarkerWhileRetainingExistingHtmlBehavior() {
        val source = "[ar:artist]\r\n[offset:15]\r\n\r\n[00:01.00]first\r\n[00:02.00]\r\n"
        val model = loaded(source, "test.lrc")
        val row = model.subtitleEntries.single()
        val original = row.copy()
        val result = model.applySubtitleColor(setOf(0), 0xFFFF0000.toInt(), emptySet())

        assertEquals(setOf(0), result.changedPositions)
        assertFalse(result.documentMetadataChanged)
        assertEquals("<font color=\"#FF0000\">first</font>", row.text)
        assertEquals(original.startTime, row.startTime)
        assertEquals(original.endTime, row.endTime)
        assertEquals(original.endTimeModified, row.endTimeModified)
        // LRC output intentionally strips formatting; the in-memory edit is still undoable.
        assertEquals(source, model.originalFileContent)
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(original, row)
        assertEquals(source, model.originalFileContent)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals("<font color=\"#FF0000\">first</font>", row.text)
    }

    @Test fun vttColorCommitsCssAndCueTextAsOneEditWithoutChangingOtherRawBlocks() {
        val model = loaded(VTT, "test.vtt")
        val before = model.document.value
        val rows = model.subtitleEntries.toList()
        val historyRows = model.historyEntriesSnapshot.toList()
        val selected = setOf(rows[0].stableId)
        model.setHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, selected), VTT, true)

        val result = model.applySubtitleColor(setOf(0), 0x80FF0000.toInt(), selected)

        assertEquals(setOf(0), result.changedPositions)
        assertTrue(result.documentMetadataChanged)
        assertTrue(model.documentHeader.contains("::cue(.ff000080)"))
        assertTrue(model.subtitleEntries[0].text.contains("<c.ff000080>"))
        assertTrue(model.sourceViewContent.startsWith("\uFEFFWEBVTT\r\n"))
        assertTrue(model.sourceViewContent.contains("NOTE untouched prefix\r\nmetadata\r\n"))
        assertTrue(model.sourceViewContent.contains(SECOND_VTT_BLOCK))
        assertTrue(model.sourceViewContent.endsWith(VTT_FOOTER))
        assertFalse(model.sourceViewContent.replace("\r\n", "").contains('\n'))
        rows.indices.forEach { assertSame(rows[it], model.subtitleEntries[it]) }
        assertSame(before.entries[1], model.document.value.entries[1])
        // The baseline created for this selected state is independent from the old one too.
        assertEquals(historyRows[1], model.historyEntriesSnapshot[1])
        assertEquals(before.footer, model.documentFooter)
        assertCommitted(model)
        val after = model.subtitleDocument
        val afterSource = model.sourceViewContent
        val operation = model.peekUndo() as EditorEditHistory.Operation.ListChange
        assertEquals(1, operation.updated.size)
        assertEquals(before.header, operation.beforeHeader)
        assertEquals(model.documentHeader, operation.afterHeader)

        assertTrue(undo(model))
        assertEquals(VTT, model.sourceViewContent)
        assertEquals(before, model.subtitleDocument)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(after, model.subtitleDocument)
        assertEquals(afterSource, model.originalFileContent)
    }

    @Test fun blankAndMissingColorTargetsAreNoOpsForRowsHistoryDirtyAndPublication() {
        val source = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n \n"
        val model = loaded(source, "test.vtt")
        val before = model.document.value
        val history = model.historyEntriesSnapshot
        val result = model.applySubtitleColor(setOf(0, 99), 0xFFFF0000.toInt(), emptySet())

        assertTrue(result.changedPositions.isEmpty())
        assertFalse(result.documentMetadataChanged)
        assertSame(before, model.document.value)
        assertSame(history, model.historyEntriesSnapshot)
        assertFalse(model.hasUnsavedChanges)
        assertEquals(source, model.sourceViewContent)
        assertNull(model.peekUndo())
    }

    @Test fun repeatedColorDoesNotRecordOrPublishAnotherCommit() {
        val model = loaded(VTT, "test.vtt")
        model.applySubtitleColor(setOf(0), 0xFFFFFF00.toInt(), emptySet())
        val operation = model.peekUndo()
        val document = model.document.value
        val source = model.sourceViewContent

        val result = model.applySubtitleColor(setOf(0), 0xFFFFFF00.toInt(), emptySet())

        assertTrue(result.changedPositions.isEmpty())
        assertFalse(result.documentMetadataChanged)
        assertSame(operation, model.peekUndo())
        assertSame(document, model.document.value)
        assertEquals(source, model.sourceViewContent)
    }

    @Test fun vttHeaderOnlyEditDoesNotPretendEveryCueChanged() {
        val model = loaded(VTT, "test.vtt")
        val before = model.document.value
        val history = model.historyEntriesSnapshot.toList()
        val rows = model.subtitleEntries.toList()
        val updated = model.subtitleDocument.copy(header = model.documentHeader + "\n\nSTYLE\n::cue { font-weight:bold; }")

        val result = model.applyVttDocument(updated, emptySet(), "修改样式")

        assertTrue(result.changedPositions.isEmpty())
        assertTrue(result.documentMetadataChanged)
        assertSame(before.entries, model.document.value.entries)
        assertEquals(updated.header, model.documentHeader)
        assertTrue(model.sourceViewContent.contains("::cue { font-weight:bold; }"))
        assertTrue(model.sourceViewContent.contains(SECOND_VTT_BLOCK))
        assertTrue(model.sourceViewContent.endsWith(VTT_FOOTER))
        rows.indices.forEach {
            assertSame(rows[it], model.subtitleEntries[it])
            assertSame(before.entries[it], model.document.value.entries[it])
            assertSame(history[it], model.historyEntriesSnapshot[it])
        }
        assertCommitted(model)
        val operation = model.peekUndo() as EditorEditHistory.Operation.ListChange
        assertTrue(operation.updated.isEmpty())
        assertFalse(operation.isSelectionOnly())
        assertEquals("修改样式", operation.description)
        val afterSource = model.sourceViewContent
        assertTrue(undo(model))
        assertEquals(VTT, model.sourceViewContent)
        assertEquals(before.header, model.documentHeader)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(afterSource, model.sourceViewContent)
        assertEquals(updated.header, model.documentHeader)
    }

    @Test fun colorStyleAppendPreservesCueIdentifiersThatStartLikeHeaderFields() {
        listOf("WEBVTT speaker", "X-TIMESTAMP-MAP label").forEach { identifier ->
            val source = headerLikeIdentifierSource(identifier, withStyle = false)
            val model = loaded(source, "identifier.vtt")
            assertEquals(identifier, model.subtitleEntries.single().cueIdentifier)

            val result = model.applySubtitleColor(setOf(0), 0xFFFF0000.toInt(), emptySet())

            assertEquals(setOf(0), result.changedPositions)
            assertTrue(result.documentMetadataChanged)
            assertIdentifierAndStyleRoundTrip(model, identifier, source)
        }
    }

    @Test fun typedStyleReplacementPreservesCueIdentifiersThatStartLikeHeaderFields() {
        listOf("WEBVTT speaker", "X-TIMESTAMP-MAP label").forEach { identifier ->
            val source = headerLikeIdentifierSource(identifier, withStyle = true)
            val model = loaded(source, "identifier.vtt")
            val row = model.subtitleEntries.single()
            val before = model.document.value

            val result = model.applyVttEdit(
                EditorVttEdit.SaveStyle("accent", "accent", mapOf("color" to "blue")), emptySet()
            )

            assertTrue(result.changedPositions.isEmpty())
            assertTrue(result.documentMetadataChanged)
            assertSame(row, model.subtitleEntries.single())
            assertSame(before.entries, model.document.value.entries)
            assertTrue(model.sourceViewContent.contains("color:blue;"))
            assertIdentifierAndStyleRoundTrip(model, identifier, source)
        }
    }

    @Test fun vttFooterOnlyEditIsUndoableAndLeavesCueRowsUntouched() {
        val model = loaded(VTT, "test.vtt")
        val before = model.document.value
        val newFooter = "NOTE replaced footer\nnew metadata"
        val result = model.applyVttDocument(model.subtitleDocument.copy(footer = newFooter), emptySet(), "修改尾注")

        assertTrue(result.changedPositions.isEmpty())
        assertTrue(result.documentMetadataChanged)
        assertEquals(newFooter, model.documentFooter)
        assertTrue(model.originalFileContent.contains("NOTE replaced footer\r\nnew metadata"))
        assertFalse(model.originalFileContent.contains("NOTE untouched footer"))
        assertTrue(model.originalFileContent.contains(SECOND_VTT_BLOCK))
        before.entries.indices.forEach { assertSame(before.entries[it], model.document.value.entries[it]) }
        val operation = model.peekUndo() as EditorEditHistory.Operation.ListChange
        assertEquals(before.footer, operation.beforeFooter)
        assertEquals(newFooter, operation.afterFooter)
        assertFalse(operation.isSelectionOnly())
        assertCommitted(model)
        val afterSource = model.sourceViewContent

        assertTrue(undo(model))
        assertEquals(before.footer, model.documentFooter)
        assertEquals(VTT, model.sourceViewContent)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(newFooter, model.documentFooter)
        assertEquals(afterSource, model.sourceViewContent)
    }

    @Test fun vttCueIdentifierPlacementAndTextCommitAsOneRowEdit() {
        val model = loaded(VTT, "test.vtt")
        val before = model.subtitleDocument
        val first = model.subtitleEntries.first()
        val secondSnapshot = model.document.value.entries[1]
        val updatedFirst = first.copy(text = "updated body", cueIdentifier = "new-id")
        WebVttSubtitleFormatHandler.updateCueSettings(updatedFirst, "line:60% position:40% align:start region:r1")
        val updated = before.copy(entries = listOf(updatedFirst, before.entries[1]))

        val result = model.applyVttDocument(updated, emptySet(), "排列和文本")

        assertEquals(setOf(0), result.changedPositions)
        assertFalse(result.documentMetadataChanged)
        assertSame(first, model.subtitleEntries.first())
        assertEquals(updatedFirst, first)
        assertSame(secondSnapshot, model.document.value.entries[1])
        assertTrue(model.sourceViewContent.contains("new-id\r\n"))
        assertTrue(model.sourceViewContent.contains("line:60% position:40% align:start region:r1"))
        assertTrue(model.sourceViewContent.contains("updated body"))
        assertTrue(model.sourceViewContent.endsWith(VTT_FOOTER))
        assertEquals(1, (model.peekUndo() as EditorEditHistory.Operation.ListChange).updated.size)
        assertCommitted(model)
        val afterSource = model.sourceViewContent

        assertTrue(undo(model))
        assertEquals(before, model.subtitleDocument)
        assertEquals(VTT, model.sourceViewContent)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(updatedFirst, first)
        assertEquals(afterSource, model.originalFileContent)
    }

    @Test fun unchangedVttDocumentKeepsDirtyHistoryAndSnapshotUntouched() {
        val model = loaded(VTT, "test.vtt")
        val before = model.document.value
        val result = model.applyVttDocument(model.subtitleDocument, emptySet(), "无变更")
        assertTrue(result.changedPositions.isEmpty())
        assertFalse(result.documentMetadataChanged)
        assertSame(before, model.document.value)
        assertFalse(model.hasUnsavedChanges)
        assertNull(model.peekUndo())
    }

    @Test fun metadataOnlyStateChangeRemainsUndoableWhenItsRawSourceIsAlreadyNormalized() {
        val source = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nbody\n"
        val model = loaded(source, "test.vtt")
        val header = model.documentHeader

        val result = model.applyVttDocument(model.subtitleDocument.copy(header = header + "\n"), emptySet(), "样式")

        assertTrue(result.documentMetadataChanged)
        assertEquals(source, model.originalFileContent)
        val operation = model.peekUndo() as EditorEditHistory.Operation.ListChange
        assertNull(operation.sourceEdit)
        assertFalse(operation.isSelectionOnly())
        assertSame(operation, model.peekUndoWithoutSelection())
        assertTrue(undo(model))
        assertEquals(header, model.documentHeader)
        assertEquals(source, model.originalFileContent)
    }

    @Test fun styleHeaderCanBeCommittedToAnEmptyVttWithoutLosingBomOrNewlineConvention() {
        val source = "\uFEFFWEBVTT\r\n\r\n"
        val model = loaded(source, "empty.vtt").apply {
            // An empty file has no cues for automatic format detection. Exercise
            // the format boundary with an explicitly selected VTT document.
            replaceDocument(SubtitleParser.parseDocument(source, format = SubtitleParser.SubtitleFormat.VTT))
            setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), source, true)
        }
        val header = "WEBVTT\n\nSTYLE\n::cue { color:red; }"
        val result = model.applyVttDocument(model.subtitleDocument.copy(header = header), emptySet(), "样式")
        assertTrue(result.changedPositions.isEmpty())
        assertTrue(result.documentMetadataChanged)
        assertTrue(model.subtitleEntries.isEmpty())
        assertEquals(header, model.documentHeader)
        assertTrue(model.sourceViewContent.startsWith("\uFEFFWEBVTT\r\n"))
        assertTrue(model.sourceViewContent.contains("STYLE\r\n::cue { color:red; }"))
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(source, model.sourceViewContent)
        assertNull(model.peekUndo())
    }

    @Test fun noOpFormatAfterUndoKeepsTheRedoOperation() {
        val model = loaded(VTT, "test.vtt")
        model.applySubtitleColor(setOf(0), 0xFF123456.toInt(), emptySet())
        assertTrue(undo(model))
        val redo = model.peekRedo()
        val document = model.document.value
        val result = model.applyVttDocument(model.subtitleDocument, emptySet(), "无变更")
        assertTrue(result.changedPositions.isEmpty())
        assertFalse(result.documentMetadataChanged)
        assertSame(redo, model.peekRedo())
        assertSame(document, model.document.value)
        assertTrue(redo(model))
        assertTrue(model.subtitleEntries.first().text.contains("<c."))
    }

    @Test fun sourceModeAndViewTransitionsRejectFormattingBeforeMutation() {
        listOf(true to false, false to true).forEach { (sourceMode, transitioning) ->
            val model = loaded(VTT, "test.vtt")
            val before = model.document.value
            val updated = model.subtitleDocument.copy(header = "WEBVTT\n\nSTYLE\n::cue { color:red; }")
            model.isSourceViewMode = sourceMode
            model.isSourceViewTransitioning = transitioning
            val color = model.applySubtitleColor(setOf(0), 0xFFFF0000.toInt(), emptySet())
            val format = model.applyVttDocument(updated, emptySet(), "样式")
            assertTrue(color.changedPositions.isEmpty())
            assertFalse(color.documentMetadataChanged)
            assertTrue(format.changedPositions.isEmpty())
            assertFalse(format.documentMetadataChanged)
            assertEquals(VTT, model.sourceViewContent)
            assertEquals(before.entries, model.subtitleEntries)
            assertSame(before, model.document.value)
            assertFalse(model.hasUnsavedChanges)
            assertNull(model.peekUndo())
        }
    }

    @Test fun specialVttEntryPointRejectsStructuralOrDifferentFormatDocuments() {
        val model = loaded(VTT, "test.vtt")
        val before = model.document.value
        val documents = listOf(
            model.subtitleDocument.copy(entries = model.subtitleEntries.drop(1)),
            model.subtitleDocument.copy(entries = model.subtitleEntries.reversed()),
            model.subtitleDocument.copy(format = SubtitleParser.SubtitleFormat.SRT)
        )
        documents.forEach { updated ->
            val result = model.applyVttDocument(updated, emptySet(), "不支持")
            assertTrue(result.changedPositions.isEmpty())
            assertFalse(result.documentMetadataChanged)
        }
        assertEquals(VTT, model.originalFileContent)
        assertSame(before, model.document.value)
        assertFalse(model.hasUnsavedChanges)
        assertNull(model.peekUndo())
    }

    @Test fun formatCommitDoesNotGetRecordedAgainByTheLegacyHistoryCoordinator() {
        val model = loaded(VTT, "test.vtt")
        model.applySubtitleColor(setOf(0), 0xFF123456.toInt(), emptySet())
        val operation = model.peekUndo()
        EditorHistoryCoordinator(model, model.documentState, { emptySet() }, { false }, { _, _ -> }, {}).recordList()
        assertSame(operation, model.peekUndo())
        assertTrue(undo(model))
        assertNull(model.peekUndo())
    }

    @Test fun pureVttTransformsBorrowTheCurrentRowsUntilTheChangedRowIsPrepared() {
        val model = loaded(VTT, "test.vtt")
        val rows = model.subtitleEntries
        val first = rows.first()
        val second = rows[1]
        val result = model.applyVttDocumentEdit(emptySet(), "说话人") { borrowed ->
            assertSame(rows, borrowed.entries)
            assertSame(first, borrowed.entries.first())
            assertSame(second, borrowed.entries[1])
            borrowed.copy(entries = listOf(first.copy(text = "<v Speaker>first body</v>"), second))
        }
        assertEquals(setOf(0), result.changedPositions)
        assertFalse(result.documentMetadataChanged)
        assertSame(first, model.subtitleEntries.first())
        assertSame(second, model.subtitleEntries[1])
        assertEquals("<v Speaker>first body</v>", first.text)
        assertEquals(1, (model.peekUndo() as EditorEditHistory.Operation.ListChange).updated.size)
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(VTT, model.originalFileContent)
        assertNull(model.peekUndo())
    }

    @Test fun ordinaryAndFormatEditsShareTheSameUndoOrder() {
        val model = loaded(SRT, "test.srt")
        val first = model.subtitleEntries.first()
        model.commitSingleEdit(EditorEntryEdit.Text(first.stableId, "edited"), emptySet())!!
        val afterText = model.originalFileContent
        model.applySubtitleColor(setOf(0), 0xFFFF0000.toInt(), emptySet())
        val afterColor = model.originalFileContent
        model.commitListEdit(EditorCommand.ApplyOffset(setOf(0), 100), emptySet())!!
        assertTrue(undo(model))
        assertEquals(afterColor, model.originalFileContent)
        assertTrue(undo(model))
        assertEquals(afterText, model.originalFileContent)
        assertTrue(undo(model))
        assertEquals(SRT, model.originalFileContent)
        assertNull(model.peekUndo())
        repeat(3) { assertTrue(redo(model)) }
        assertEquals(1_100L, first.startTime)
        assertTrue(first.text.contains("#FF0000"))
    }

    @Test fun largeColorEditReusesAllUnchangedLiveHistoryAndPublishedRows() {
        val source = buildString {
            repeat(5_000) { index ->
                append(index + 1).append("\n00:00:01,000 --> 00:00:02,000\nrow ").append(index).append("\n\n")
            }
        }
        val model = loaded(source, "large.srt")
        val rows = model.subtitleEntries.toList()
        val list = model.subtitleEntries
        val history = model.historyEntriesSnapshot.toList()
        val document = model.document.value
        val target = 2_500
        val result = model.applySubtitleColor(setOf(target), 0xFF123456.toInt(), emptySet())

        assertEquals(setOf(target), result.changedPositions)
        assertSame(list, model.subtitleEntries)
        rows.indices.forEach { index ->
            assertSame(rows[index], model.subtitleEntries[index])
            if (index == target) {
                assertNotSame(history[index], model.historyEntriesSnapshot[index])
                assertNotSame(document.entries[index], model.document.value.entries[index])
            } else {
                assertSame(history[index], model.historyEntriesSnapshot[index])
                assertSame(document.entries[index], model.document.value.entries[index])
            }
        }
        assertEquals("row $target", document.entries[target].text)
        assertEquals(1, (model.peekUndo() as EditorEditHistory.Operation.ListChange).updated.size)
    }

    @Test fun sourcePatchFailureCannotCommitPreparedColorOrHistory() {
        val model = loaded(SRT, "test.srt")
        val state = model.documentState
        val list = state.subtitleEntries
        val rows = list.map { it.copy() }
        val history = state.historyEntriesSnapshot
        val document = model.document.value
        var historyCalls = 0
        var synchronizeCalls = 0
        val session = EditorFormatEditSession(state, { historyCalls++ },
            synchronizeSource = { _, _, before, after ->
                synchronizeCalls++
                assertSame(list, before)
                assertNotSame(list[0], after[0])
                assertSame(list[1], after[1])
                assertEquals(rows, list)
                assertEquals(SRT, state.sourceViewContent)
                error("source patch failed")
            })

        val failure = runCatching { session.applyColor(setOf(0), 0xFFFF0000.toInt(), emptySet()) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("source patch failed", failure?.message)
        assertEquals(1, synchronizeCalls)
        assertEquals(0, historyCalls)
        assertSame(list, state.subtitleEntries)
        assertEquals(rows, list)
        assertEquals(SRT, state.originalFileContent)
        assertEquals(SRT, state.sourceViewContent)
        assertEquals(SRT, state.sourceHistoryTextSnapshot)
        assertSame(history, state.historyEntriesSnapshot)
        assertSame(document, model.document.value)
        assertFalse(state.hasUnsavedChanges)
        assertNull(model.peekUndo())
    }

    @Test fun metadataWriterFailureCannotCommitPreparedCueFieldsOrSections() {
        val model = loaded(VTT, "test.vtt")
        val state = model.documentState
        val before = model.subtitleDocument
        val list = state.subtitleEntries
        val first = list.first()
        val document = model.document.value
        val history = state.historyEntriesSnapshot
        var historyCalls = 0
        var metadataCalls = 0
        val session = EditorFormatEditSession(state, { historyCalls++ },
            rewriteVttSections = { _, _, _, _, _ ->
                metadataCalls++
                assertEquals(before.entries, list)
                assertEquals(before.header, state.documentHeader)
                assertEquals(before.footer, state.documentFooter)
                error("metadata patch failed")
            })
        val updated = before.copy(
            entries = listOf(before.entries[0].copy(text = "prepared text"), before.entries[1]),
            header = before.header + "\n\nSTYLE\n::cue { color:red; }",
            footer = "NOTE changed footer"
        )

        val failure = runCatching { session.applyVtt(updated, emptySet(), "样式") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("metadata patch failed", failure?.message)
        assertEquals(1, metadataCalls)
        assertEquals(0, historyCalls)
        assertSame(list, state.subtitleEntries)
        assertSame(first, list.first())
        assertEquals(before, model.subtitleDocument)
        assertEquals(VTT, state.originalFileContent)
        assertEquals(VTT, state.sourceViewContent)
        assertSame(history, state.historyEntriesSnapshot)
        assertSame(document, model.document.value)
        assertFalse(state.hasUnsavedChanges)
        assertNull(model.peekUndo())
    }

    private fun loaded(source: String, fileName: String) = EditorViewModel().apply {
        loadSubtitleContent(source, fileName)
        setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), source, true)
    }

    private fun headerLikeIdentifierSource(identifier: String, withStyle: Boolean): String =
        "\uFEFFWEBVTT\r\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:0\r\n\r\n" +
            (if (withStyle) "STYLE\r\n::cue(.accent) { color:red; }\r\n\r\n" else "") +
            "$identifier\r\n00:00:01.000 --> 00:00:02.000\r\n<c.accent>body</c>\r\n"

    private fun assertIdentifierAndStyleRoundTrip(model: EditorViewModel, identifier: String, source: String) {
        val afterSource = model.sourceViewContent
        val cueStart = afterSource.indexOf("$identifier\r\n00:00:01.000 --> 00:00:02.000")
        assertTrue("Cue identifier must stay immediately before its time line", cueStart >= 0)
        assertTrue("STYLE must precede the cue identifier", afterSource.indexOf("STYLE") in 0 until cueStart)
        assertTrue(afterSource.startsWith("\uFEFFWEBVTT\r\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:0\r\n"))
        val reparsed = SubtitleParser.parseDocument(afterSource, format = SubtitleParser.SubtitleFormat.VTT)
        assertEquals(identifier, reparsed.entries.single().cueIdentifier)
        assertEquals(model.subtitleEntries.single().text, reparsed.entries.single().text)
        assertCommitted(model)
        assertTrue(undo(model))
        assertEquals(source, model.sourceViewContent)
        assertEquals(identifier, model.subtitleEntries.single().cueIdentifier)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(afterSource, model.sourceViewContent)
        assertEquals(identifier, model.subtitleEntries.single().cueIdentifier)
        assertNull(model.peekRedo())
    }

    private fun assertCommitted(model: EditorViewModel) {
        assertTrue(model.hasUnsavedChanges)
        assertFalse(model.sourceViewNeedsListSync)
        assertEquals(model.originalFileContent, model.sourceViewContent)
        assertEquals(model.originalFileContent, model.sourceHistoryTextSnapshot)
        assertEquals(model.subtitleEntries, model.document.value.entries)
    }

    private fun undo(model: EditorViewModel) = moveHistory(model, true)
    private fun redo(model: EditorViewModel) = moveHistory(model, false)

    private fun moveHistory(model: EditorViewModel, undo: Boolean): Boolean {
        var result: EditorHistoryCommandResult? = null
        val apply: (EditorEditHistory.Operation, Boolean) -> Unit = { operation, reversed ->
            result = model.executeHistoryCommand(operation, reversed)
        }
        val moved = if (undo) model.undo(false, apply) else model.redo(false, apply)
        result?.let {
            model.syncHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, it.selectedIds), model.sourceViewContent, false)
        }
        return moved
    }

    private companion object {
        const val SRT = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,000\r\nfirst\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nsecond\r\n"
        const val SECOND_VTT_BLOCK = "second-id\r\n00:00:03.000 --> 00:00:04.000\r\nsecond body\r\n\r\n"
        const val VTT_FOOTER = "NOTE untouched footer\r\nretained\r\n"
        const val VTT = "\uFEFFWEBVTT\r\n\r\nNOTE untouched prefix\r\nmetadata\r\n\r\n" +
            "first-id\r\n00:00:01.000 --> 00:00:02.000\r\nfirst body\r\n\r\n" + SECOND_VTT_BLOCK + VTT_FOOTER
    }
}
