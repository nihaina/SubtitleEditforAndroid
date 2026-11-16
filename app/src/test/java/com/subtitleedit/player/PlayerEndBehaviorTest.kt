package com.subtitleedit.player

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PlayerEndBehaviorTest {
    private val order = listOf(2, 0, 1)
    private val advanced = PlayerEndBehavior(enabled = true, reshuffle = false)

    @Test fun simpleModeMappingsKeepTheirExistingEndOfFileDecisions() {
        PlayerPlayMode.entries.forEach { mode ->
            val behavior = PlayerEndBehavior.fromMode(mode)
            assertFalse(behavior.enabled)
            (0..2).forEach { current ->
                val expected = PlayerPlaylistOrder.afterEnd(current, 3, mode, order)
                val decision = decide(current, behavior, mode)
                assertEquals("$mode at $current", expected, decision.targetIndex)
                assertEquals(expected != null, decision.autoplay)
                assertFalse(decision.moveToNextQueue)
                assertNull(decision.newShuffleOrder)
            }
        }
    }

    @Test fun trackStopOverridesEveryModeAndQueueRule() {
        PlayerPlayMode.entries.forEach { mode ->
            PlayerQueueEndAction.entries.forEach { queueAction ->
                (0..2).forEach { current ->
                    assertEquals(PlayerEndDecision(), decide(current, advanced.copy(
                        trackAction = PlayerTrackEndAction.STOP, queueAction = queueAction), mode))
                }
            }
        }
    }

    @Test fun followingTheExistingSingleTrackModeRepeatsWithoutApplyingQueueRules() {
        PlayerQueueEndAction.entries.forEach { queueAction ->
            (0..2).forEach { current ->
                assertEquals(PlayerEndDecision(current, autoplay = true), decide(current,
                    advanced.copy(queueAction = queueAction), PlayerPlayMode.REPEAT_ONE))
            }
        }
    }

    @Test fun loadingNextPausedOverridesSingleTrackRepeatAndNeverAutoplays() {
        PlayerPlayMode.entries.forEach { mode ->
            val current = if (mode == PlayerPlayMode.SHUFFLE) 2 else 0
            val next = if (mode == PlayerPlayMode.SHUFFLE) 0 else 1
            assertEquals(PlayerEndDecision(next), decide(current,
                advanced.copy(trackAction = PlayerTrackEndAction.LOAD_NEXT_PAUSED), mode))
        }
    }

    @Test fun followingExistingSequenceAndShuffleModesLoadsTheNextTrack() {
        assertEquals(PlayerEndDecision(1, autoplay = true), decide(0, advanced))
        assertEquals(PlayerEndDecision(0, autoplay = true), decide(2, advanced, PlayerPlayMode.SHUFFLE))
    }

    @Test fun stoppingAtQueueEndKeepsTheFinalSelection() {
        assertEquals(PlayerEndDecision(), decide(2, advanced))
        assertEquals(PlayerEndDecision(), decide(1, advanced, PlayerPlayMode.SHUFFLE))
    }

    @Test fun queueRepeatStartsAtTheFirstItemOfTheSameTraversalWithoutReshuffling() {
        val options = advanced.copy(queueAction = PlayerQueueEndAction.REPEAT_QUEUE)
        assertEquals(PlayerEndDecision(0, autoplay = true), decide(2, options))
        assertEquals(PlayerEndDecision(2, autoplay = true), decide(1, options, PlayerPlayMode.SHUFFLE))
        assertEquals(PlayerEndDecision(2), decide(1,
            options.copy(trackAction = PlayerTrackEndAction.LOAD_NEXT_PAUSED), PlayerPlayMode.SHUFFLE))
    }

    @Test fun reshufflingProducesACompleteDeterministicTraversalOnlyAtAShuffledQueueBoundary() {
        val options = advanced.copy(queueAction = PlayerQueueEndAction.REPEAT_QUEUE, reshuffle = true)
        val first = PlayerEndBehaviorPolicy.decide(1, 3, order, PlayerPlayMode.SHUFFLE, options, Random(31))
        val second = PlayerEndBehaviorPolicy.decide(1, 3, order, PlayerPlayMode.SHUFFLE, options, Random(31))
        assertEquals(first, second)
        assertEquals(listOf(0, 1, 2), first.newShuffleOrder!!.sorted())
        assertEquals(first.newShuffleOrder.first(), first.targetIndex)
        assertTrue(first.autoplay)
        assertNull(decide(2, options, PlayerPlayMode.SHUFFLE).newShuffleOrder)
        assertNull(decide(2, options).newShuffleOrder)
        val stopped = PlayerEndBehaviorPolicy.decide(1, 3, order, PlayerPlayMode.SHUFFLE,
            options.copy(queueAction = PlayerQueueEndAction.STOP), Random(31))
        assertNull(stopped.targetIndex)
        assertFalse(stopped.autoplay)
        assertEquals(listOf(0, 1, 2), stopped.newShuffleOrder!!.sorted())
    }

    @Test fun nextQueueIsRequestedOnlyAfterTheLastTrackAndRetainsAutoplay() {
        val options = advanced.copy(queueAction = PlayerQueueEndAction.NEXT_QUEUE, reshuffle = true)
        assertEquals(PlayerEndDecision(autoplay = true, moveToNextQueue = true), decide(2, options))
        assertEquals(PlayerEndDecision(moveToNextQueue = true), decide(2,
            options.copy(trackAction = PlayerTrackEndAction.LOAD_NEXT_PAUSED)))
        assertFalse(decide(0, options).moveToNextQueue)
        assertFalse(decide(0, options, PlayerPlayMode.SHUFFLE).moveToNextQueue)
        val shuffled = decide(1, options, PlayerPlayMode.SHUFFLE)
        assertTrue(shuffled.moveToNextQueue)
        assertNull("A new queue replaces the outgoing traversal", shuffled.newShuffleOrder)
    }

    @Test fun invalidQueuesNeverStartOrRequestAnotherQueue() {
        for (size in listOf(-1, 0, 3)) for (current in listOf(-1, 3, 100)) {
            PlayerTrackEndAction.entries.forEach { action ->
                assertEquals(PlayerEndDecision(), PlayerEndBehaviorPolicy.decide(current, size, order,
                    PlayerPlayMode.SHUFFLE,
                    advanced.copy(trackAction = action, queueAction = PlayerQueueEndAction.NEXT_QUEUE)))
            }
        }
    }

    @Test fun malformedShuffleOrderIsCompletedWithoutSkippingTracks() {
        val malformed = listOf(2, 2, 10, -1)
        assertEquals(PlayerEndDecision(0, autoplay = true),
            PlayerEndBehaviorPolicy.decide(2, 3, malformed, PlayerPlayMode.SHUFFLE, advanced))
        assertEquals(PlayerEndDecision(1, autoplay = true),
            PlayerEndBehaviorPolicy.decide(0, 3, malformed, PlayerPlayMode.SHUFFLE, advanced))
        assertEquals(PlayerEndDecision(),
            PlayerEndBehaviorPolicy.decide(1, 3, malformed, PlayerPlayMode.SHUFFLE, advanced))
    }

    @Test fun manualTraversalDoesNotWrapInsideTheQueue() {
        assertNull(PlayerEndBehaviorPolicy.adjacentIndex(2, 3, order, false, true))
        assertNull(PlayerEndBehaviorPolicy.adjacentIndex(0, 3, order, false, false))
        assertNull(PlayerEndBehaviorPolicy.adjacentIndex(1, 3, order, true, true))
        assertNull(PlayerEndBehaviorPolicy.adjacentIndex(2, 3, order, true, false))
        assertEquals(0, PlayerEndBehaviorPolicy.adjacentIndex(1, 3, order, true, false))
    }

    @Test fun oneTrackQueuesSupportEachQueueAction() {
        fun end(action: PlayerQueueEndAction) = PlayerEndBehaviorPolicy.decide(0, 1, listOf(0),
            PlayerPlayMode.SHUFFLE, advanced.copy(queueAction = action))
        assertNull(end(PlayerQueueEndAction.STOP).targetIndex)
        assertEquals(0, end(PlayerQueueEndAction.REPEAT_QUEUE).targetIndex)
        assertTrue(end(PlayerQueueEndAction.REPEAT_QUEUE).autoplay)
        assertTrue(end(PlayerQueueEndAction.NEXT_QUEUE).moveToNextQueue)
    }

    private fun decide(current: Int, behavior: PlayerEndBehavior, mode: PlayerPlayMode = PlayerPlayMode.SEQUENCE) =
        PlayerEndBehaviorPolicy.decide(current, 3, order, mode, behavior)
}
