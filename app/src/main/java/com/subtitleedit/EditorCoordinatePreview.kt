package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleCoordinate
import com.subtitleedit.util.SubtitleCoordinateOps
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.WebVttSubtitleFormatHandler

/** A playback-only overlay. No document fields need to be restored when picking ends. */
internal class EditorCoordinatePreview(
    private val documentEntries: List<SubtitleEntry>,
    private val format: SubtitleParser.SubtitleFormat,
    positions: Set<Int>,
    revision: Any
) {
    private val targets = positions.mapNotNull { position ->
        documentEntries.getOrNull(position)?.let { position to it.stableId }
    }
    private var snapshotRevision = revision
    private var snapshot = documentEntries.toList()
    private var targetPositions = targets.map { it.first }

    fun positions(entries: List<SubtitleEntry>, currentFormat: SubtitleParser.SubtitleFormat, revision: Any): Set<Int>? {
        if (!refresh(entries, currentFormat, revision)) return null
        return targetPositions.toSet()
    }

    /** The returned list has fixed structure and owns only the affected row copies. */
    fun entries(
        entries: List<SubtitleEntry>,
        currentFormat: SubtitleParser.SubtitleFormat,
        revision: Any,
        coordinate: SubtitleCoordinate
    ): List<SubtitleEntry>? {
        if (!refresh(entries, currentFormat, revision)) return null
        val base = snapshot
        val overlays = targetPositions.associateWith { position ->
            base[position].copy().also { entry ->
                if (format == SubtitleParser.SubtitleFormat.VTT) {
                    WebVttSubtitleFormatHandler.updateCueCoordinate(entry, coordinate.xPercent, coordinate.yPercent)
                } else {
                    entry.text = SubtitleCoordinateOps.set(entry.text, coordinate)
                }
            }
        }
        return object : AbstractList<SubtitleEntry>() {
            override val size: Int get() = base.size
            override fun get(index: Int): SubtitleEntry = overlays[index] ?: base[index]
        }
    }

    private fun refresh(entries: List<SubtitleEntry>, currentFormat: SubtitleParser.SubtitleFormat, revision: Any): Boolean {
        if (entries !== documentEntries || currentFormat != format) return false
        if (revision !== snapshotRevision) {
            snapshot = entries.toList()
            snapshotRevision = revision
            val shifted = targets.any { (position, id) -> snapshot.getOrNull(position)?.stableId != id }
            val positionsById = if (shifted) snapshot.withIndex().associate { it.value.stableId to it.index } else emptyMap()
            targetPositions = targets.mapNotNull { (position, id) ->
                if (shifted) positionsById[id] else position
            }
        }
        return true
    }
}
