package com.subtitleedit.adapter

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.model.SubtitleEntry
import java.util.Collections
import java.util.IdentityHashMap

/** Compose-observable subtitle list and selection state used by the editor. */
class SubtitleAdapter(
    @Suppress("unused") private val onItemClick: (SubtitleEntry, Int) -> Unit,
    @Suppress("unused") private val onItemLongClick: (SubtitleEntry, Int) -> Unit,
    @Suppress("unused") private val onTimeClick: (SubtitleEntry, Int, Boolean) -> Unit,
    @Suppress("unused") private val onTextClick: (SubtitleEntry, Int) -> Unit,
    @Suppress("unused") private val onJumpToTimeClick: (SubtitleEntry, Int) -> Unit,
    @Suppress("unused") private val onSetTimeClick: (SubtitleEntry, Int) -> Unit,
    private var hasPlayableMedia: Boolean = false,
    private val onSelectionChanged: (() -> Unit)? = null
) {
    companion object {
        const val PAYLOAD_SELECTION = "selection"
        const val PAYLOAD_PLAYING = "playing"
        const val PAYLOAD_TIME_CONFLICT = "time_conflict"
        private const val SELECTION_REFRESH_THRESHOLD = 200
    }

    private val _composeRevision = mutableIntStateOf(0)
    val composeRevision: State<Int> get() = _composeRevision

    private var currentListState by mutableStateOf<List<SubtitleEntry>>(emptyList())
    val currentList: List<SubtitleEntry> get() = currentListState
    val itemCount: Int get() = currentListState.size

    private val selectedEntries: MutableSet<SubtitleEntry> =
        Collections.newSetFromMap(IdentityHashMap())

    private var searchHighlightPosition = -1
    private var searchQuery = ""
    private var searchMatchCase = false
    private var searchWholeWord = false
    private var currentPlayingPosition = -1

    private fun invalidateCompose() {
        _composeRevision.intValue++
    }

    fun submitList(entries: List<SubtitleEntry>, commitCallback: (() -> Unit)? = null) {
        val selectedIds = selectedEntries.mapTo(mutableSetOf()) { it.stableId }
        currentListState = entries
        selectedEntries.clear()
        entries.filterTo(selectedEntries) { it.stableId in selectedIds }
        invalidateCompose()
        commitCallback?.invoke()
    }

    fun isPlayingPosition(position: Int): Boolean = position == currentPlayingPosition
    fun hasPlayableMedia(): Boolean = hasPlayableMedia
    fun searchHighlightPosition(): Int = searchHighlightPosition
    fun searchQuery(): String = searchQuery
    fun searchMatchCase(): Boolean = searchMatchCase
    fun searchWholeWord(): Boolean = searchWholeWord

    fun setHasPlayableMedia(value: Boolean) {
        if (hasPlayableMedia == value) return
        hasPlayableMedia = value
        invalidateCompose()
    }

    fun toggleSelection(position: Int) {
        val entry = currentList.getOrNull(position) ?: return
        if (selectedEntries.remove(entry).not()) selectedEntries.add(entry)
        invalidateCompose()
        onSelectionChanged?.invoke()
    }

    fun isSelected(position: Int): Boolean = currentList.getOrNull(position)?.let(selectedEntries::contains) == true

    fun getSelectedPositions(): Set<Int> = currentList.mapIndexedNotNullTo(mutableSetOf()) { index, entry ->
        index.takeIf { selectedEntries.contains(entry) }
    }

    fun getSelectedEntries(): List<Pair<SubtitleEntry, Int>> = currentList.mapIndexedNotNull { index, entry ->
        if (selectedEntries.contains(entry)) entry to index else null
    }

    fun clearSelection() {
        if (selectedEntries.isEmpty()) return
        selectedEntries.clear()
        invalidateCompose()
    }

    fun setSelectionByIndices(indices: Set<Int>) {
        selectedEntries.clear()
        indices.forEach { index -> currentList.getOrNull(index)?.let(selectedEntries::add) }
        invalidateCompose()
    }

    fun setSelectionByStableIds(ids: Set<Long>) {
        selectedEntries.clear()
        currentList.filterTo(selectedEntries) { it.stableId in ids }
        invalidateCompose()
    }

    fun setAllSelection(selected: Boolean) {
        selectedEntries.clear()
        if (selected) selectedEntries.addAll(currentList)
        invalidateCompose()
    }

    fun getSelectedCount(): Int = selectedEntries.size

    fun removeSelectionByEntry(entry: SubtitleEntry) {
        if (selectedEntries.remove(entry)) invalidateCompose()
    }

    fun refreshSelectionAfterDataChange() {
        val selectedData = selectedEntries.map { it.copy() }.toSet()
        selectedEntries.clear()
        currentList.filterTo(selectedEntries) { it.copy() in selectedData }
        invalidateCompose()
    }

    fun syncSelectionWithCurrentList() {
        val selectedData = selectedEntries.mapTo(mutableSetOf()) {
            Triple(it.startTime, it.endTime, it.text)
        }
        selectedEntries.clear()
        currentList.filterTo(selectedEntries) { Triple(it.startTime, it.endTime, it.text) in selectedData }
        invalidateCompose()
    }

    fun refreshAllItems() = invalidateCompose()

    fun notifyItemChanged(position: Int, payload: Any? = null) {
        if (position in currentList.indices) invalidateCompose()
    }

    fun notifyItemRangeChanged(start: Int, count: Int, payload: Any? = null) {
        if (count > 0 && start < itemCount && start + count > 0) invalidateCompose()
    }

    fun highlightSearchResult(
        position: Int,
        query: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ) {
        searchHighlightPosition = position
        searchQuery = query
        searchMatchCase = matchCase
        searchWholeWord = wholeWord
        invalidateCompose()
    }

    fun clearSearchHighlight() {
        searchHighlightPosition = -1
        searchQuery = ""
        searchMatchCase = false
        searchWholeWord = false
        invalidateCompose()
    }

    fun highlightCurrentPlaying(position: Int) {
        if (currentPlayingPosition == position) return
        currentPlayingPosition = position
        invalidateCompose()
    }

    fun clearPlayingHighlight() {
        if (currentPlayingPosition == -1) return
        currentPlayingPosition = -1
        invalidateCompose()
    }
}
