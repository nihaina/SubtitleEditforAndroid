package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleSourceSynchronizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Coalesces waveform drags and writes timing changes back to source text off the UI thread. */
internal class EditorSourceWaveformSyncController(
    private val scope: CoroutineScope,
    private val isSourceViewMode: () -> Boolean,
    private val hasPendingSourceEdits: () -> Boolean,
    private val isPreviewActive: () -> Boolean,
    private val editGeneration: () -> Long,
    private val currentFormat: () -> SubtitleParser.SubtitleFormat,
    private val sourceContent: () -> String,
    private val snapshotSourceContent: () -> String,
    private val currentEntries: () -> List<SubtitleEntry>,
    private val cancelPreview: () -> Unit,
    private val schedulePreview: () -> Unit,
    private val recordHistory: (String, String, List<SubtitleEntry>?) -> Unit,
    private val onSourceUpdated: (String) -> Unit
) {
    private data class Request(
        val content: String,
        val entries: List<SubtitleEntry>,
        val generation: Long,
        val indices: IntArray,
        val starts: LongArray,
        val ends: LongArray,
        val modified: BooleanArray,
        val dragKey: Long?,
        val recordHistory: Boolean,
        val historyStart: String?,
        val historyEntries: List<SubtitleEntry>?
    )

    private var job: Job? = null
    private var pending: Request? = null
    private var historyKey: Long? = null
    private var historyStart: String? = null
    private var historyEntries: List<SubtitleEntry>? = null

    fun schedule(updated: List<SubtitleEntry>, dragKey: Long?, shouldRecord: Boolean, initialEntries: List<SubtitleEntry>?) {
        val inFlight = hasPendingSourceEdits() || isPreviewActive()
        val content = if (hasPendingSourceEdits()) snapshotSourceContent() else sourceContent()
        if (inFlight) cancelPreview()
        if (dragKey != null && historyKey != dragKey) {
            historyKey = dragKey
            historyStart = content
            historyEntries = initialEntries ?: currentEntries().map { it.copy() }
        }
        pending = Request(
            content = content,
            entries = (historyEntries ?: currentEntries()).map { it.copy() },
            generation = editGeneration(),
            indices = IntArray(updated.size) { updated[it].index },
            starts = LongArray(updated.size) { updated[it].startTime },
            ends = LongArray(updated.size) { updated[it].endTime },
            modified = BooleanArray(updated.size) { updated[it].endTimeModified },
            dragKey = dragKey,
            recordHistory = shouldRecord,
            historyStart = historyStart,
            historyEntries = historyEntries
        )
        if (job?.isActive == true) return
        job = scope.launch {
            val running = coroutineContext[Job]
            try {
                while (isActive) {
                    delay(40L)
                    val request = pending ?: break
                    pending = null
                    if (!isSourceViewMode() || request.generation != editGeneration()) continue
                    val result = withContext(Dispatchers.Default) {
                        if (request.entries.size != request.indices.size) null
                        else {
                            val updatedEntries = request.entries.mapIndexed { index, entry ->
                                entry.copy(
                                    index = request.indices[index],
                                    startTime = request.starts[index],
                                    endTime = request.ends[index],
                                    endTimeModified = request.modified[index]
                                )
                            }
                            SubtitleSourceSynchronizer.apply(
                                content = request.content,
                                format = currentFormat(),
                                oldEntries = request.entries,
                                newEntries = updatedEntries
                            )
                        }
                    }
                    if (!isActive || !isSourceViewMode() || request.generation != editGeneration()) continue
                    if (pending != null) continue
                    if (result == null) {
                        schedulePreview()
                        continue
                    }
                    if (request.recordHistory) {
                        recordHistory(request.historyStart ?: request.content, result, request.historyEntries)
                        if (request.dragKey != null && historyKey == request.dragKey) {
                            historyKey = null
                            historyStart = null
                            historyEntries = null
                        }
                    }
                    onSourceUpdated(result)
                }
            } finally {
                if (job === running) job = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        pending = null
    }

    suspend fun cancelAndJoin() {
        val active = job ?: return
        active.cancelAndJoin()
        if (job === active) job = null
        pending = null
    }
}
