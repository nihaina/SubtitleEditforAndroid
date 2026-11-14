package com.subtitleedit.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlayerPlaylistOrderTest {
    private val shuffle = listOf(2, 0, 1)

    @Test fun sequenceStopsAtTheLastTrack() {
        assertEquals(1, PlayerPlaylistOrder.afterEnd(0, 3, PlayerPlayMode.SEQUENCE, shuffle))
        assertNull(PlayerPlaylistOrder.afterEnd(2, 3, PlayerPlayMode.SEQUENCE, shuffle))
    }

    @Test fun repeatOneKeepsTheTrackAndRepeatAllWraps() {
        assertEquals(2, PlayerPlaylistOrder.afterEnd(2, 3, PlayerPlayMode.REPEAT_ONE, shuffle))
        assertEquals(0, PlayerPlaylistOrder.afterEnd(2, 3, PlayerPlayMode.REPEAT_ALL, shuffle))
    }

    @Test fun shuffleFollowsThePermutationAndWraps() {
        assertEquals(0, PlayerPlaylistOrder.afterEnd(2, 3, PlayerPlayMode.SHUFFLE, shuffle))
        assertEquals(1, PlayerPlaylistOrder.afterEnd(0, 3, PlayerPlayMode.SHUFFLE, shuffle))
        assertEquals(2, PlayerPlaylistOrder.afterEnd(1, 3, PlayerPlayMode.SHUFFLE, shuffle))
    }

    @Test fun manualNavigationRespectsBounds() {
        assertNull(PlayerPlaylistOrder.manualNext(2, 3, PlayerPlayMode.SEQUENCE, shuffle))
        assertEquals(0, PlayerPlaylistOrder.manualNext(2, 3, PlayerPlayMode.REPEAT_ALL, shuffle))
        assertNull(PlayerPlaylistOrder.manualPrevious(0, 3, PlayerPlayMode.SEQUENCE, shuffle))
        assertEquals(2, PlayerPlaylistOrder.manualPrevious(0, 3, PlayerPlayMode.REPEAT_ALL, shuffle))
        assertEquals(1, PlayerPlaylistOrder.manualPrevious(2, 3, PlayerPlayMode.SHUFFLE, shuffle))
        assertEquals(2, PlayerPlaylistOrder.manualPrevious(0, 3, PlayerPlayMode.SHUFFLE, shuffle))
    }

    @Test fun shuffleOrderStartsAtThePlayedTrackAndCoversTheQueue() {
        val order = PlayerPlaylistOrder.newShuffleOrder(8, 3, Random(7))
        assertEquals(8, order.size)
        assertEquals(3, order.first())
        assertEquals((0 until 8).toList(), order.sorted())
    }

    @Test fun manualNavigationLeavesRepeatOneAndWrapsAtQueueBoundaries() {
        assertEquals(1, PlayerPlaylistOrder.manualNext(0, 3, PlayerPlayMode.REPEAT_ONE, shuffle))
        assertEquals(0, PlayerPlaylistOrder.manualNext(2, 3, PlayerPlayMode.REPEAT_ONE, shuffle))
        assertEquals(2, PlayerPlaylistOrder.manualPrevious(0, 3, PlayerPlayMode.REPEAT_ONE, shuffle))
        assertEquals(0, PlayerPlaylistOrder.afterEnd(0, 3, PlayerPlayMode.REPEAT_ONE, shuffle))
    }

    @Test fun emptyQueueHasNoNextTrack() {
        assertNull(PlayerPlaylistOrder.afterEnd(0, 0, PlayerPlayMode.REPEAT_ALL, emptyList()))
        assertNull(PlayerPlaylistOrder.manualNext(0, 0, PlayerPlayMode.SHUFFLE, emptyList()))
    }

    @Test fun outOfRangeCurrentIndexIsIgnored() {
        assertNull(PlayerPlaylistOrder.afterEnd(5, 3, PlayerPlayMode.REPEAT_ALL, shuffle))
        assertTrue(PlayerPlaylistOrder.newShuffleOrder(0, 0).isEmpty())
    }

    @Test fun shuffleNormalizesAnInvalidInitialIndex() {
        assertEquals(listOf(0), PlayerPlaylistOrder.newShuffleOrder(1, -1, Random(7)))
        val order = PlayerPlaylistOrder.newShuffleOrder(3, 100, Random(7))
        assertEquals(2, order.first())
        assertEquals(listOf(0, 1, 2), order.sorted())
    }

    @Test fun transportButtonsReflectTheModeRatherThanOnlyThePhysicalIndex() {
        val tracks = (0..2).map { PlayerTrack("/media/$it.wav", "$it.wav", false) }
        val first = PlayerUiState(playlist = tracks, currentIndex = 0, mode = PlayerPlayMode.SEQUENCE)
        assertTrue(first.hasNext)
        assertTrue(!first.hasPrevious)
        assertTrue(first.copy(positionMs = 6_000L).hasPrevious)
        assertTrue(first.copy(mode = PlayerPlayMode.REPEAT_ALL).hasPrevious)
        val last = first.copy(currentIndex = 2)
        assertTrue(!last.hasNext)
        assertTrue(last.copy(mode = PlayerPlayMode.REPEAT_ALL).hasNext)
        assertTrue(last.copy(mode = PlayerPlayMode.SHUFFLE, shuffleOrder = shuffle).hasNext)
        assertTrue(last.copy(mode = PlayerPlayMode.SHUFFLE, shuffleOrder = shuffle).hasPrevious)
    }
}
