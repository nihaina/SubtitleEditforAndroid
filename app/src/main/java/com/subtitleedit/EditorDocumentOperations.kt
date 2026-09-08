package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry

/** Small mutation boundary for the editor's existing mutable subtitle list. */
internal object EditorDocumentOperations {
    fun removeAt(entries: MutableList<SubtitleEntry>, position: Int): SubtitleEntry {
        require(position in entries.indices) { "字幕位置无效：$position" }
        return entries.removeAt(position)
    }

    fun addAt(
        entries: MutableList<SubtitleEntry>,
        position: Int,
        entry: SubtitleEntry
    ) {
        require(position in 0..entries.size) { "字幕插入位置无效：$position" }
        entries.add(position, entry)
    }

    fun addAllAt(
        entries: MutableList<SubtitleEntry>,
        position: Int,
        inserted: List<SubtitleEntry>
    ) {
        require(position in 0..entries.size) { "字幕插入位置无效：$position" }
        entries.addAll(position, inserted)
    }

    fun replaceEntries(entries: List<SubtitleEntry>): MutableList<SubtitleEntry> =
        entries.toMutableList()

    fun clear(entries: MutableList<SubtitleEntry>) {
        entries.clear()
    }

    fun updateFields(target: SubtitleEntry, source: SubtitleEntry) {
        target.index = source.index
        target.startTime = source.startTime
        target.endTime = source.endTime
        target.text = source.text
        target.endTimeModified = source.endTimeModified
        target.cueIdentifier = source.cueIdentifier
        target.cueSettings = source.cueSettings
    }

    fun renumber(entries: List<SubtitleEntry>) {
        entries.forEachIndexed { index, entry ->
            entry.index = index + 1
        }
    }
}
