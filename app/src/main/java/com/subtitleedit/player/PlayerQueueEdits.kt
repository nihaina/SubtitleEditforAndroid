package com.subtitleedit.player

/** Current queue edits never alter the directory, album or user playlist that supplied it. */
internal data class PlayerQueueEdit(
    val playlist: List<PlayerTrack>,
    val currentIndex: Int,
    val shuffleOrder: List<Int>
)

internal object PlayerQueueEdits {
    /** Insert fresh occurrences after the current song in both the visible and shuffled queue. */
    fun insertNext(
        playlist: List<PlayerTrack>, currentIndex: Int, shuffleOrder: List<Int>, added: List<PlayerTrack>
    ): PlayerQueueEdit {
        if (added.isEmpty()) return PlayerQueueEdit(playlist, currentIndex, shuffleOrder)
        val insertion = (currentIndex + 1).coerceIn(0, playlist.size)
        val changed = playlist.toMutableList().apply { addAll(insertion, added) }
        val currentEntry = playlist.getOrNull(currentIndex)?.queueEntryId
        val nextCurrent = changed.indexOfFirst { it.queueEntryId == currentEntry }.takeIf { it >= 0 } ?: 0
        val addedRange = insertion until insertion + added.size
        val addedIndices = addedRange.toList()
        val retainedOrder = remapShuffle(playlist, changed, shuffleOrder).filterNot { it in addedRange }
        val nextInOrder = (retainedOrder.indexOf(nextCurrent) + 1).coerceAtLeast(0)
        val order = retainedOrder.toMutableList().apply { addAll(nextInOrder, addedIndices) }
        return PlayerQueueEdit(changed, nextCurrent, order)
    }

    /** Move to the target's original index, as a dragged row crosses the target row. */
    fun move(
        playlist: List<PlayerTrack>,
        currentIndex: Int,
        shuffleOrder: List<Int>,
        fromPath: String,
        toPath: String
    ): PlayerQueueEdit? {
        val from = playlist.indexOfFirst { it.path == fromPath }
        val to = playlist.indexOfFirst { it.path == toPath }
        return moveIndices(playlist, currentIndex, shuffleOrder, from, to)
    }

    fun moveEntries(
        playlist: List<PlayerTrack>, currentIndex: Int, shuffleOrder: List<Int>,
        fromEntryId: String, toEntryId: String
    ): PlayerQueueEdit? = moveIndices(playlist, currentIndex, shuffleOrder,
        playlist.indexOfFirst { it.queueEntryId == fromEntryId }, playlist.indexOfFirst { it.queueEntryId == toEntryId })

    private fun moveIndices(
        playlist: List<PlayerTrack>, currentIndex: Int, shuffleOrder: List<Int>, from: Int, to: Int
    ): PlayerQueueEdit? {
        if (from < 0 || to < 0 || from == to) return null
        val changed = playlist.toMutableList().apply { add(to, removeAt(from)) }
        val currentEntry = playlist.getOrNull(currentIndex)?.queueEntryId
        return PlayerQueueEdit(changed, changed.indexOfFirst { it.queueEntryId == currentEntry },
            remapShuffle(playlist, changed, shuffleOrder))
    }

    /** Retain the playing path, or choose its next surviving row, then its previous row. */
    fun remove(
        playlist: List<PlayerTrack>,
        currentIndex: Int,
        shuffleOrder: List<Int>,
        paths: Set<String>
    ): PlayerQueueEdit? {
        if (paths.isEmpty()) return null
        return removeEntries(playlist, currentIndex, shuffleOrder,
            playlist.filter { it.path in paths }.mapTo(hashSetOf()) { it.queueEntryId })
    }

    fun removeEntries(
        playlist: List<PlayerTrack>, currentIndex: Int, shuffleOrder: List<Int>, entryIds: Set<String>
    ): PlayerQueueEdit? {
        if (entryIds.isEmpty()) return null
        val changed = playlist.filterNot { it.queueEntryId in entryIds }
        if (changed.size == playlist.size) return null
        val currentEntry = playlist.getOrNull(currentIndex)?.queueEntryId
        val replacement = currentEntry?.takeUnless { it in entryIds }
            ?: playlist.drop((currentIndex + 1).coerceAtLeast(0)).firstOrNull { it.queueEntryId !in entryIds }?.queueEntryId
            ?: playlist.take(currentIndex.coerceAtLeast(0)).lastOrNull { it.queueEntryId !in entryIds }?.queueEntryId
        return PlayerQueueEdit(changed, changed.indexOfFirst { it.queueEntryId == replacement },
            remapShuffle(playlist, changed, shuffleOrder))
    }

    private fun remapShuffle(
        before: List<PlayerTrack>,
        after: List<PlayerTrack>,
        shuffleOrder: List<Int>
    ): List<Int> {
        val newIndices = after.withIndex().associate { it.value.queueEntryId to it.index }
        val retained = shuffleOrder.mapNotNull { before.getOrNull(it)?.queueEntryId?.let(newIndices::get) }.distinct()
        val included = retained.toHashSet()
        return retained + after.indices.filterNot { it in included }
    }
}
