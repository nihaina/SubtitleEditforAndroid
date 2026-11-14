package com.subtitleedit.player

import kotlin.random.Random

/** Queue navigation rules for every play mode. Pure so the order can be unit tested. */
internal object PlayerPlaylistOrder {
    /** Index to play once the current track ends, or null when the queue is exhausted. */
    fun afterEnd(current: Int, size: Int, mode: PlayerPlayMode, shuffleOrder: List<Int>): Int? {
        if (size <= 0 || current !in 0 until size) return null
        return when (mode) {
            PlayerPlayMode.REPEAT_ONE -> current
            PlayerPlayMode.REPEAT_ALL -> (current + 1) % size
            PlayerPlayMode.SEQUENCE -> (current + 1).takeIf { it < size }
            PlayerPlayMode.SHUFFLE -> {
                val position = shuffleOrder.indexOf(current)
                val next = shuffleOrder.getOrNull(position + 1)
                next ?: shuffleOrder.firstOrNull()
            }
        }
    }

    /** Explicit "next" button: wraps in loop modes, stops at the end in sequence mode. */
    fun manualNext(current: Int, size: Int, mode: PlayerPlayMode, shuffleOrder: List<Int>): Int? {
        if (size <= 0 || current !in 0 until size) return null
        return when (mode) {
            PlayerPlayMode.SHUFFLE -> afterEnd(current, size, mode, shuffleOrder)
            PlayerPlayMode.REPEAT_ALL, PlayerPlayMode.REPEAT_ONE -> (current + 1) % size
            PlayerPlayMode.SEQUENCE -> (current + 1).takeIf { it < size }
        }
    }

    fun manualPrevious(current: Int, size: Int, mode: PlayerPlayMode, shuffleOrder: List<Int>): Int? {
        if (size <= 0 || current !in 0 until size) return null
        return when (mode) {
            PlayerPlayMode.SHUFFLE -> {
                val position = shuffleOrder.indexOf(current)
                if (position < 0) null else shuffleOrder.getOrNull(position - 1) ?: shuffleOrder.lastOrNull()
            }
            PlayerPlayMode.REPEAT_ALL, PlayerPlayMode.REPEAT_ONE -> (current - 1 + size) % size
            else -> (current - 1).takeIf { it >= 0 }
        }
    }

    /** A fresh shuffle permutation that always starts at the track being played. */
    fun newShuffleOrder(size: Int, first: Int, random: Random = Random): List<Int> =
        if (size <= 0) emptyList() else {
            val start = first.coerceIn(0, size - 1)
            listOf(start) + (0 until size).filter { it != start }.shuffled(random)
        }
}
