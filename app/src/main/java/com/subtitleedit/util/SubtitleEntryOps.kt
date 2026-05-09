package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry

object SubtitleEntryOps {
    fun deepCopy(entry: SubtitleEntry): SubtitleEntry {
        return entry.copy()
    }

    fun deepCopy(entries: List<SubtitleEntry>): List<SubtitleEntry> {
        return entries.map { it.copy() }
    }

    fun copyContent(source: SubtitleEntry, target: SubtitleEntry) {
        target.startTime = source.startTime
        target.endTime = source.endTime
        target.text = source.text
    }

    fun applyOffset(entry: SubtitleEntry, offsetMs: Long) {
        entry.startTime = (entry.startTime + offsetMs).coerceAtLeast(0)
        entry.endTime = (entry.endTime + offsetMs).coerceAtLeast(entry.startTime + 1)
    }

    fun applyOffsetAll(entries: Iterable<SubtitleEntry>, offsetMs: Long) {
        entries.forEach { applyOffset(it, offsetMs) }
    }
}
