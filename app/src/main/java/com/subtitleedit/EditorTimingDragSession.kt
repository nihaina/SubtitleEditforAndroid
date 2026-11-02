package com.subtitleedit

import com.subtitleedit.editor.EditorSourceTextEdit
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleSourceSynchronizer

internal data class EditorTimingRange(val startTime: Long, val endTime: Long, val endTimeModified: Boolean) {
    fun applyTo(entry: SubtitleEntry) {
        entry.startTime = startTime
        entry.endTime = endTime
        entry.endTimeModified = endTimeModified
    }

    companion object {
        fun of(entry: SubtitleEntry) = EditorTimingRange(entry.startTime, entry.endTime, entry.endTimeModified)
    }
}

internal sealed interface EditorWaveformDragEvent {
    data class Begin(val key: Long, val stableId: Long) : EditorWaveformDragEvent
    data class Update(val key: Long, val range: EditorTimingRange) : EditorWaveformDragEvent
    data class Finish(val key: Long) : EditorWaveformDragEvent
}

internal class EditorTimingDrag internal constructor(
    val key: Long,
    val position: Int,
    val original: SubtitleEntry,
    val sourceMode: Boolean,
    val format: SubtitleParser.SubtitleFormat,
    val beforeEntries: List<SubtitleEntry>,
    internal val documentEntries: List<SubtitleEntry>,
    internal val beforeSource: String,
    internal val selectedBefore: Set<Long>
) {
    var range = EditorTimingRange.of(original)
        internal set
    internal var valid = true
}

internal data class EditorTimingDragEnd(val drag: EditorTimingDrag, val change: EditorEntryChange?)

/** One timing gesture owns its baseline; MOVE never records history or rebuilds source. */
internal class EditorTimingDragSession(
    private val state: EditorDocumentState,
    private val recordList: (EditorEditHistory.ListState, EditorEditHistory.ListState, String, String) -> Unit,
    private val recordSource: (EditorSourceTextEdit, String) -> Unit
) {
    private var active: EditorTimingDrag? = null
    private val sourceDrags = linkedMapOf<Long, EditorTimingDrag>()

    val hasPendingSource: Boolean get() = sourceDrags.isNotEmpty()

    fun begin(key: Long, stableId: Long, sourceMode: Boolean, selectedIds: Set<Long>): Boolean {
        if (active != null) return false
        val position = state.subtitleEntries.indexOfFirst { it.stableId == stableId }
        if (position < 0) return false
        val baseline = if (!sourceMode && state.historyBaselineInitialized &&
            state.historyEntriesSnapshot.size == state.subtitleEntries.size &&
            state.subtitleEntries.indices.all {
                state.historyEntriesSnapshot[it] == state.subtitleEntries[it] &&
                    state.historyEntriesSnapshot[it] !== state.subtitleEntries[it]
            }
        ) state.historyEntriesSnapshot else state.subtitleEntries.map { it.copy() }.toMutableList()
        val drag = EditorTimingDrag(
            key, position, state.subtitleEntries[position].copy(), sourceMode,
            state.currentFormat, baseline, state.subtitleEntries, state.originalFileContent,
            if (state.historyBaselineInitialized && !sourceMode) state.historySelectionSnapshot.toSet() else selectedIds.toSet()
        )
        active = drag
        if (sourceMode) sourceDrags[key] = drag
        return true
    }

    fun current(key: Long): EditorTimingDrag? = active?.takeIf { it.key == key && isCurrent(it) }

    fun isCurrent(drag: EditorTimingDrag): Boolean = drag.valid &&
        drag.documentEntries === state.subtitleEntries && drag.format == state.currentFormat &&
        state.subtitleEntries.getOrNull(drag.position)?.stableId == drag.original.stableId

    fun update(key: Long, range: EditorTimingRange): EditorEntryChange? {
        val drag = current(key) ?: return null
        // Returning to the initial range is a no-op, including a resize handle
        // whose clamp prevented any end-time change.
        val normalized = if (range.startTime == drag.original.startTime && range.endTime == drag.original.endTime) {
            EditorTimingRange.of(drag.original)
        } else range
        drag.range = normalized
        normalized.applyTo(state.subtitleEntries[drag.position])
        return EditorEntryChange(drag.position, drag.original.stableId)
    }

    fun finish(key: Long, selectedIds: Set<Long>): EditorTimingDragEnd? {
        val drag = active?.takeIf { it.key == key } ?: return null
        active = null
        if (!isCurrent(drag)) {
            discard(drag)
            return null
        }
        val changed = drag.range != EditorTimingRange.of(drag.original)
        if (drag.sourceMode) {
            if (changed) state.hasUnsavedChanges = true
            // Even a net no-op must restore source text shown by earlier previews.
            return EditorTimingDragEnd(drag, null)
        }
        if (!changed) return EditorTimingDragEnd(drag, null)
        val after = state.subtitleEntries
        val source = sourceFor(drag, drag.range, drag.beforeSource)
        recordList(
            EditorEditHistory.ListState(drag.beforeEntries, drag.selectedBefore),
            EditorEditHistory.ListState(after, selectedIds.toSet()), drag.beforeSource, source
        )
        installSource(source)
        state.historyBaselineInitialized = true
        state.hasUnsavedChanges = true
        return EditorTimingDragEnd(drag, EditorEntryChange(drag.position, drag.original.stableId))
    }

    fun applySource(drag: EditorTimingDrag, baselineSource: String, source: String, final: Boolean): Boolean {
        if (!isCurrent(drag) || sourceDrags[drag.key] !== drag) return false
        if (final) {
            val edit = EditorSourceTextEdit.between(baselineSource, source)
            if (!edit.isNoOp) {
                recordSource(edit, baselineSource)
                state.hasUnsavedChanges = true
            }
            sourceDrags.remove(drag.key)
        }
        installSource(source)
        state.sourceViewEditGeneration++
        state.sourceViewEntriesGeneration = state.sourceViewEditGeneration
        return true
    }

    fun discard(drag: EditorTimingDrag) {
        drag.valid = false
        sourceDrags.remove(drag.key)
        if (active === drag) active = null
    }

    fun discardSourceDrags(): Boolean {
        val pending = sourceDrags.isNotEmpty()
        sourceDrags.values.toList().forEach(::discard)
        active?.takeIf { it.sourceMode }?.let(::discard)
        return pending
    }

    fun discardAll() {
        active?.let(::discard)
        discardSourceDrags()
    }

    private fun installSource(source: String) {
        state.originalFileContent = source
        state.sourceViewContent = source
        state.sourceHistoryTextSnapshot = source
        state.sourceViewNeedsListSync = false
    }

    companion object {
        /** Background work reads only the gesture's owned baseline and immutable range. */
        fun sourceFor(drag: EditorTimingDrag, range: EditorTimingRange, source: String): String {
            val updated = drag.original.copy().also(range::applyTo)
            val after = object : AbstractList<SubtitleEntry>() {
                override val size: Int get() = drag.beforeEntries.size
                override fun get(index: Int) = if (index == drag.position) updated else drag.beforeEntries[index]
            }
            return SubtitleSourceSynchronizer.apply(source, drag.format, drag.beforeEntries, after)
        }
    }
}
