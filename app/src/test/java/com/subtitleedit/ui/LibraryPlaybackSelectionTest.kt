package com.subtitleedit.ui

import org.junit.Assert.*
import org.junit.Test

class LibraryPlaybackSelectionTest {
    @Test fun missingClickedFileCannotPlayAnotherTrack() {
        assertNull(LibraryPlaybackSelection.resolve(listOf("missing", "valid"), 0, false) { it == "valid" })
    }

    @Test fun playAllSkipsMissingFilesAndKeepsAvailableQueueOrder() {
        val selected = LibraryPlaybackSelection.resolve(listOf("missing", "b", "c", "b"), 0, true) { it != "missing" }
        assertEquals(LibraryPlaybackSelection.Selection(listOf("b", "c"), 0), selected)
    }

    @Test fun clickedPositionIsRecomputedAfterFiltering() {
        val selected = LibraryPlaybackSelection.resolve(listOf("missing", "b", "c"), 2, false) { it != "missing" }
        assertEquals(LibraryPlaybackSelection.Selection(listOf("b", "c"), 1), selected)
    }

    @Test fun invalidSelectionOrEmptyQueueDoesNotLaunchPlayback() {
        assertNull(LibraryPlaybackSelection.resolve(emptyList(), 0, true) { true })
        assertNull(LibraryPlaybackSelection.resolve(listOf("a"), 3, false) { true })
        assertNull(LibraryPlaybackSelection.resolve(listOf("a"), 0, true) { false })
    }

    @Test fun playAllCanResumeTheCurrentAvailableItem() {
        assertEquals(1, LibraryPlaybackSelection.resolve(listOf("a", "b"), 1, true) { true }?.startIndex)
    }
}
