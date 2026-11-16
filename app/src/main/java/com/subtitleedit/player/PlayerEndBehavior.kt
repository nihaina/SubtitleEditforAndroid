package com.subtitleedit.player

import kotlin.random.Random

internal enum class PlayerTrackEndAction { FOLLOW_MODE, STOP, LOAD_NEXT_PAUSED, REPEAT_TRACK }
internal enum class PlayerQueueEndAction { STOP, NEXT_QUEUE, REPEAT_QUEUE }

/** Advanced rules are opt-in and choose end actions independently of traversal order. */
internal data class PlayerEndBehavior(
    val enabled: Boolean = false,
    val trackAction: PlayerTrackEndAction = PlayerTrackEndAction.FOLLOW_MODE,
    val queueAction: PlayerQueueEndAction = PlayerQueueEndAction.STOP,
    val wrapQueues: Boolean = false,
    val reshuffle: Boolean = true
) {
    fun effectiveFor(mode: PlayerPlayMode): PlayerEndBehavior = if (enabled) this else fromMode(mode)

    companion object {
        fun fromMode(mode: PlayerPlayMode): PlayerEndBehavior = when (mode) {
            PlayerPlayMode.SEQUENCE -> PlayerEndBehavior()
            PlayerPlayMode.REPEAT_ONE -> PlayerEndBehavior(trackAction = PlayerTrackEndAction.REPEAT_TRACK)
            PlayerPlayMode.REPEAT_ALL -> PlayerEndBehavior(queueAction = PlayerQueueEndAction.REPEAT_QUEUE)
            PlayerPlayMode.SHUFFLE -> PlayerEndBehavior(queueAction = PlayerQueueEndAction.REPEAT_QUEUE)
        }.copy(reshuffle = false)
    }
}

/** A null target leaves the completed track selected; the host owns loading another queue. */
internal data class PlayerEndDecision(
    val targetIndex: Int? = null,
    val autoplay: Boolean = false,
    val moveToNextQueue: Boolean = false,
    val newShuffleOrder: List<Int>? = null
)

/** End-of-file decisions only. Manual previous/next buttons retain their own navigation rules. */
internal object PlayerEndBehaviorPolicy {
    fun decide(
        current: Int,
        size: Int,
        shuffleOrder: List<Int>,
        mode: PlayerPlayMode,
        behavior: PlayerEndBehavior,
        random: Random = Random
    ): PlayerEndDecision {
        if (size <= 0 || current !in 0 until size) return PlayerEndDecision()
        when (behavior.trackAction) {
            PlayerTrackEndAction.STOP -> return PlayerEndDecision()
            PlayerTrackEndAction.REPEAT_TRACK -> return PlayerEndDecision(current, autoplay = true)
            // The persisted FOLLOW_MODE value now means play next in the selected traversal.
            PlayerTrackEndAction.FOLLOW_MODE -> Unit
            PlayerTrackEndAction.LOAD_NEXT_PAUSED -> Unit
        }

        val autoplay = behavior.trackAction == PlayerTrackEndAction.FOLLOW_MODE
        val shuffle = mode == PlayerPlayMode.SHUFFLE
        val order = if (shuffle) completeShuffleOrder(shuffleOrder, size) else (0 until size).toList()
        val next = adjacentIndex(current, size, shuffleOrder, shuffle, forward = true)
        if (next != null) return PlayerEndDecision(next, autoplay)

        if (behavior.queueAction == PlayerQueueEndAction.NEXT_QUEUE) {
            return PlayerEndDecision(autoplay = autoplay, moveToNextQueue = true)
        }

        val newOrder = if (shuffle && behavior.reshuffle) (0 until size).shuffled(random) else null
        return when (behavior.queueAction) {
            PlayerQueueEndAction.STOP -> PlayerEndDecision(newShuffleOrder = newOrder)
            PlayerQueueEndAction.REPEAT_QUEUE -> PlayerEndDecision((newOrder ?: order).first(), autoplay,
                newShuffleOrder = newOrder)
            PlayerQueueEndAction.NEXT_QUEUE -> error("Next queue was handled before preparing the current queue")
        }
    }

    fun adjacentIndex(current: Int, size: Int, shuffleOrder: List<Int>, shuffle: Boolean, forward: Boolean): Int? {
        if (current !in 0 until size) return null
        val order = if (shuffle) completeShuffleOrder(shuffleOrder, size) else (0 until size).toList()
        return order.getOrNull(order.indexOf(current) + if (forward) 1 else -1)
    }

    private fun completeShuffleOrder(order: List<Int>, size: Int): List<Int> {
        val valid = order.filter { it in 0 until size }.distinct()
        if (valid.size == size) return valid
        val included = valid.toHashSet()
        return valid + (0 until size).filterNot { it in included }
    }
}
