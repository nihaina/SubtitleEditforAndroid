package com.subtitleedit.player

import org.junit.Assert.*
import org.junit.Test

class PlayerQueueEditsTest {
    private val tracks = listOf("a", "b", "c", "d", "e").map { PlayerTrack("/$it.wav", it, false) }
    private val order = listOf(2, 4, 0, 3, 1)

    @Test fun insertingNextPreservesCurrentAndTheRemainingShuffleTraversal() {
        val added = listOf(tracks[0].newQueueEntry(), tracks[0].newQueueEntry(), tracks[3].newQueueEntry())
        val edit = PlayerQueueEdits.insertNext(tracks, 2, order, added)
        assertEquals(tracks.take(3) + added + tracks.drop(3), edit.playlist)
        assertEquals(tracks[2], edit.playlist[edit.currentIndex])
        assertEquals(listOf(tracks[2]) + added + order.drop(1).map { tracks[it] },
            edit.shuffleOrder.map { edit.playlist[it] })
        assertEquals(edit.playlist.indices.toList(), edit.shuffleOrder.sorted())
    }

    @Test fun duplicateOccurrencesMoveAndRemoveIndependentlyWithoutRetargetingCurrent() {
        val first = tracks[0]
        val duplicate = first.newQueueEntry()
        val queue = listOf(first, tracks[1], duplicate)
        assertNotEquals(first.queueEntryId, duplicate.queueEntryId)
        assertNotEquals("Occurrences must remain distinct to state observers", first, duplicate)
        val moved = requireNotNull(PlayerQueueEdits.moveEntries(queue, 2, listOf(2, 0, 1),
            first.queueEntryId, duplicate.queueEntryId))
        assertEquals(duplicate.queueEntryId, moved.playlist[moved.currentIndex].queueEntryId)
        assertEquals(listOf(duplicate.queueEntryId, first.queueEntryId, tracks[1].queueEntryId),
            moved.shuffleOrder.map { moved.playlist[it].queueEntryId })
        val removed = requireNotNull(PlayerQueueEdits.removeEntries(moved.playlist, moved.currentIndex,
            moved.shuffleOrder, setOf(first.queueEntryId)))
        assertEquals(2, removed.playlist.size)
        assertEquals(duplicate.queueEntryId, removed.playlist[removed.currentIndex].queueEntryId)
        val allCopies = requireNotNull(PlayerQueueEdits.remove(removed.playlist, removed.currentIndex,
            removed.shuffleOrder, setOf(first.path)))
        assertEquals(listOf(tracks[1]), allCopies.playlist)
    }

    @Test fun movingAcrossThePlayingRowPreservesItsIdentityAndTheShuffleTraversal() {
        val edit = requireNotNull(PlayerQueueEdits.move(tracks, 2, order, tracks[0].path, tracks[3].path))
        assertEquals(listOf("b", "c", "d", "a", "e"), edit.playlist.map { it.name })
        assertEquals(tracks[2], edit.playlist[edit.currentIndex])
        assertEquals(order.map { tracks[it].path }, edit.shuffleOrder.map { edit.playlist[it].path })
        assertEquals(listOf(1, 4, 3, 2, 0), edit.shuffleOrder)
        assertEquals(listOf("a", "b", "c", "d", "e"), tracks.map { it.name })
    }

    @Test fun movingThePlayingRowUpAndDownKeepsItsNewIndex() {
        val down = requireNotNull(PlayerQueueEdits.move(tracks, 0, order, tracks[0].path, tracks[4].path))
        assertEquals(4, down.currentIndex)
        assertEquals(tracks[0], down.playlist[down.currentIndex])
        val up = requireNotNull(PlayerQueueEdits.move(down.playlist, down.currentIndex, down.shuffleOrder,
            tracks[0].path, tracks[1].path))
        assertEquals(tracks, up.playlist)
        assertEquals(0, up.currentIndex)
        assertEquals(order, up.shuffleOrder)
    }

    @Test fun removingOtherRowsRetainsCurrentTrackAndRelativeShuffleOrder() {
        val edit = requireNotNull(PlayerQueueEdits.remove(tracks, 2, order, setOf(tracks[0].path, tracks[3].path)))
        assertEquals(listOf(tracks[1], tracks[2], tracks[4]), edit.playlist)
        assertEquals(1, edit.currentIndex)
        assertEquals(listOf(1, 2, 0), edit.shuffleOrder)
    }

    @Test fun removingCurrentAndFollowingRowsChoosesTheNextSurvivorThenPreviousAtTheEnd() {
        val next = requireNotNull(PlayerQueueEdits.remove(tracks, 2, order, setOf(tracks[2].path, tracks[3].path)))
        assertEquals(tracks[4], next.playlist[next.currentIndex])
        val previous = requireNotNull(PlayerQueueEdits.remove(tracks, 2, order,
            setOf(tracks[2].path, tracks[3].path, tracks[4].path)))
        assertEquals(tracks[1], previous.playlist[previous.currentIndex])
    }

    @Test fun removingAllRowsHasNoCurrentIndexOrShuffleEntries() {
        val edit = requireNotNull(PlayerQueueEdits.remove(tracks, 2, order, tracks.mapTo(hashSetOf()) { it.path }))
        assertTrue(edit.playlist.isEmpty())
        assertEquals(-1, edit.currentIndex)
        assertTrue(edit.shuffleOrder.isEmpty())
    }

    @Test fun staleTargetsAndEmptyEditsAreIgnored() {
        assertNull(PlayerQueueEdits.move(tracks, 2, order, tracks[0].path, tracks[0].path))
        assertNull(PlayerQueueEdits.move(tracks, 2, order, "/missing", tracks[0].path))
        assertNull(PlayerQueueEdits.move(tracks, 2, order, tracks[0].path, "/missing"))
        assertNull(PlayerQueueEdits.remove(tracks, 2, order, emptySet()))
        assertNull(PlayerQueueEdits.remove(tracks, 2, order, setOf("/missing")))
    }

    @Test fun malformedShuffleOrdersAreRepairedWithoutReintroducingRemovedRows() {
        val edit = requireNotNull(PlayerQueueEdits.remove(tracks, 2, listOf(2, 2, -1, 40, 0), setOf(tracks[0].path)))
        assertEquals(listOf(1, 0, 2, 3), edit.shuffleOrder)
        assertEquals(edit.playlist.indices.toList(), edit.shuffleOrder.sorted())
    }
}
