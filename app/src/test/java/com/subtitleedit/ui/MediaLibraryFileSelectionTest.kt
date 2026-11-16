package com.subtitleedit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLibraryFileSelectionTest {
    private val files = listOf("a.mp3", "b.mp4", "c.flac", "d.wav", "e.m4a")

    @Test fun selectAllAddsOnlyFilesInThisListAndPreservesOtherSelections() {
        val result = updateMediaLibrarySelection(
            files,
            setOf("outside.mp3", "b.mp4"),
            MediaLibrarySelectionAction.SELECT_ALL
        )

        assertEquals(files.toSet() + "outside.mp3", result)
    }

    @Test fun rangeUsesDisplayedOrderRatherThanSelectionOrder() {
        val result = updateMediaLibrarySelection(
            files,
            linkedSetOf("d.wav", "outside.mp3", "b.mp4"),
            MediaLibrarySelectionAction.SELECT_RANGE
        )

        assertEquals(setOf("outside.mp3", "b.mp4", "c.flac", "d.wav"), result)
    }

    @Test fun rangeIncludesAllFilesBetweenTheOutermostSelectedItems() {
        val result = updateMediaLibrarySelection(
            files,
            setOf("a.mp3", "c.flac", "e.m4a"),
            MediaLibrarySelectionAction.SELECT_RANGE
        )

        assertEquals(files.toSet(), result)
    }

    @Test fun rangeWithFewerThanTwoVisibleSelectionsLeavesSelectionUnchanged() {
        for (selected in listOf(emptySet(), setOf("outside.mp3"), setOf("outside.mp3", "c.flac"))) {
            assertEquals(
                selected,
                updateMediaLibrarySelection(files, selected, MediaLibrarySelectionAction.SELECT_RANGE)
            )
        }
    }

    @Test fun invertTogglesVisibleFilesAndPreservesOtherSelections() {
        val result = updateMediaLibrarySelection(
            files,
            setOf("outside.mp3", "b.mp4", "d.wav"),
            MediaLibrarySelectionAction.INVERT
        )

        assertEquals(setOf("outside.mp3", "a.mp3", "c.flac", "e.m4a"), result)
    }

    @Test fun duplicateEntriesAreNotInvertedTwice() {
        val result = updateMediaLibrarySelection(
            listOf("a.mp3", "b.mp4", "a.mp3"),
            setOf("a.mp3"),
            MediaLibrarySelectionAction.INVERT
        )

        assertEquals(setOf("b.mp4"), result)
    }

    @Test fun emptyListDoesNotChangeSelectionForAnyAction() {
        val selected = setOf("outside.mp3")
        for (action in MediaLibrarySelectionAction.entries) {
            assertEquals(selected, updateMediaLibrarySelection(emptyList(), selected, action))
        }
    }
}
