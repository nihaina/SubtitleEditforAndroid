package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EditorDocumentOperationsTest {
    @Test
    fun listOperationsKeepExistingMutableListSemantics() {
        val first = SubtitleEntry(text = "first")
        val second = SubtitleEntry(text = "second")
        val entries = mutableListOf(first)

        EditorDocumentOperations.addAt(entries, 1, second)
        val removed = EditorDocumentOperations.removeAt(entries, 0)

        assertSame(first, removed)
        assertEquals(listOf(second), entries)
    }

    @Test
    fun replaceEntriesReturnsMutableListWithOriginalEntryReferences() {
        val entry = SubtitleEntry(text = "entry")

        val replacement = EditorDocumentOperations.replaceEntries(listOf(entry))
        replacement[0].text = "updated"

        assertEquals("updated", entry.text)
    }

    @Test
    fun updateFieldsPreservesTargetStableId() {
        val target = SubtitleEntry(text = "old")
        val source = SubtitleEntry(
            index = 4,
            startTime = 1000L,
            endTime = 2000L,
            text = "new",
            endTimeModified = true,
            cueIdentifier = "cue",
            cueSettings = "align:start"
        )
        val stableId = target.stableId

        EditorDocumentOperations.updateFields(target, source)

        assertEquals(stableId, target.stableId)
        assertEquals(4, target.index)
        assertEquals(1000L, target.startTime)
        assertEquals(2000L, target.endTime)
        assertEquals("new", target.text)
        assertEquals(true, target.endTimeModified)
        assertEquals("cue", target.cueIdentifier)
        assertEquals("align:start", target.cueSettings)
    }

    @Test
    fun renumberUpdatesOnlyDisplayIndexes() {
        val first = SubtitleEntry(index = 10, text = "first")
        val second = SubtitleEntry(index = 20, text = "second")
        val stableIds = listOf(first.stableId, second.stableId)

        EditorDocumentOperations.renumber(listOf(first, second))

        assertEquals(1, first.index)
        assertEquals(2, second.index)
        assertEquals(stableIds, listOf(first.stableId, second.stableId))
    }
}
