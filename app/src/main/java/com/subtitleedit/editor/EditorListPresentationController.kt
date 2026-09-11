package com.subtitleedit.editor

import com.subtitleedit.adapter.SubtitleAdapter
import com.subtitleedit.model.SubtitleEntry

/** Keeps RecyclerView payload and selection bookkeeping out of the Activity. */
internal class EditorListPresentationController(
    private val adapter: () -> SubtitleAdapter,
    private val entries: () -> List<SubtitleEntry>,
    private val selectedIds: () -> Set<Long>,
    private val updateSelectedCount: () -> Unit
) {
    private companion object {
        const val BULK_NOTIFY_THRESHOLD = 200
    }

    fun targetSelection(
        selectedIndices: Set<Int>?,
        selectedStableIds: Set<Long>?,
        clearSelection: Boolean
    ): Set<Long> {
        val currentIds = entries().mapTo(mutableSetOf()) { it.stableId }
        return when {
            selectedStableIds != null -> selectedStableIds.filterTo(mutableSetOf()) { it in currentIds }
            selectedIndices != null -> selectedIndices.mapNotNullTo(mutableSetOf()) { entries().getOrNull(it)?.stableId }
            clearSelection -> emptySet()
            else -> selectedIds()
        }
    }

    fun notifyPositions(positions: Iterable<Int>, includeNeighbors: Boolean = true) {
        val list = positions.toList()
        if (list.size > BULK_NOTIFY_THRESHOLD) {
            adapter().refreshAllItems()
        } else if (includeNeighbors) {
            val affected = buildSet {
                list.forEach { position ->
                    if (position in entries().indices) add(position)
                    if (position - 1 in entries().indices) add(position - 1)
                    if (position + 1 in entries().indices) add(position + 1)
                }
            }
            affected.sorted().forEach { adapter().notifyItemChanged(it) }
        } else {
            list.filter { it in entries().indices }.distinct().sorted()
                .forEach { adapter().notifyItemChanged(it) }
        }
    }

    fun bindSelection(ids: Set<Long>, clear: Boolean = false) {
        if (clear) adapter().clearSelection()
        adapter().setSelectionByStableIds(ids)
        updateSelectedCount()
    }
}
