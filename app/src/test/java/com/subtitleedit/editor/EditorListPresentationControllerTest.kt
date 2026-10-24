package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorListPresentationControllerTest {
    @Test
    fun defaultTargetSelectionKeepsStableIdsWhenRowsAreInsertedBeforeLaterSelections() {
        val entries = mutableListOf(
            SubtitleEntry(text = "1"),
            SubtitleEntry(text = "2"),
            SubtitleEntry(text = "4"),
            SubtitleEntry(text = "5")
        )
        val selectedIds = entries.mapTo(mutableSetOf()) { it.stableId }
        val controller = EditorListPresentationController(
            adapter = { error("adapter is not needed for target selection") },
            entries = { entries },
            selectedIds = { selectedIds },
            updateSelectedCount = {}
        )

        entries.add(2, SubtitleEntry(text = "3"))

        assertEquals(
            selectedIds,
            controller.targetSelection(
                selectedIndices = null,
                selectedStableIds = null,
                clearSelection = false
            )
        )
    }

    @Test
    fun defaultTargetSelectionDropsOnlyTheIdOfADeletedRow() {
        val entries = mutableListOf(
            SubtitleEntry(text = "1"),
            SubtitleEntry(text = "2"),
            SubtitleEntry(text = "3"),
            SubtitleEntry(text = "4")
        )
        val selectedIds = entries.mapTo(mutableSetOf()) { it.stableId }
        val deletedId = entries.removeAt(1).stableId
        val controller = EditorListPresentationController(
            adapter = { error("adapter is not needed for target selection") },
            entries = { entries },
            selectedIds = { selectedIds },
            updateSelectedCount = {}
        )

        assertEquals(
            selectedIds - deletedId,
            controller.targetSelection(
                selectedIndices = null,
                selectedStableIds = null,
                clearSelection = false
            )
        )
    }

    @Test
    fun explicitClearSelectionStillWinsWhenRequested() {
        val entries = mutableListOf(SubtitleEntry(text = "1"))
        val controller = EditorListPresentationController(
            adapter = { error("adapter is not needed for target selection") },
            entries = { entries },
            selectedIds = { setOf(entries.single().stableId) },
            updateSelectedCount = {}
        )

        assertEquals(
            emptySet<Long>(),
            controller.targetSelection(
                selectedIndices = null,
                selectedStableIds = null,
                clearSelection = true
            )
        )
    }
}
