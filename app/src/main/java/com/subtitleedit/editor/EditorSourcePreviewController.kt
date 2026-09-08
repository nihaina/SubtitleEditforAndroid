package com.subtitleedit.editor

import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.SubtitleDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class EditorSourcePreviewController(
    private val scope: CoroutineScope,
    private val isSourceViewMode: () -> Boolean,
    private val suppressSourceViewChanges: () -> Boolean,
    private val editGeneration: () -> Long,
    private val currentFormat: () -> SubtitleParser.SubtitleFormat,
    private val snapshotContent: () -> String,
    private val onParsed: (Long, String, SubtitleDocument) -> Unit,
    private val parseDocument: suspend (String, SubtitleParser.SubtitleFormat) -> SubtitleDocument =
        { content, format ->
            withContext(Dispatchers.Default) {
                SubtitleParser.parseDocument(content, format = format)
            }
        }
) {
    private var previewJob: Job? = null

    val isActive: Boolean
        get() = previewJob?.isActive == true

    fun schedule() {
        previewJob?.cancel()
        val scheduledGeneration = editGeneration()
        previewJob = scope.launch {
            delay(350L)
            if (
                !isSourceViewMode() ||
                suppressSourceViewChanges() ||
                scheduledGeneration != editGeneration()
            ) {
                return@launch
            }
            val sourceSnapshot = snapshotContent()
            val parsedDocument = parseDocument(sourceSnapshot, currentFormat())
            if (!coroutineContext.isActive || !isSourceViewMode() || scheduledGeneration != editGeneration()) {
                return@launch
            }
            onParsed(scheduledGeneration, sourceSnapshot, parsedDocument)
        }
    }

    fun scheduleListPreview(onReady: () -> Unit) {
        previewJob?.cancel()
        previewJob = scope.launch {
            delay(350L)
            if (!coroutineContext.isActive || isSourceViewMode()) return@launch
            onReady()
        }
    }

    fun cancel() {
        previewJob?.cancel()
        previewJob = null
    }

    suspend fun cancelAndJoin() {
        val job = previewJob ?: return
        job.cancelAndJoin()
        if (previewJob === job) previewJob = null
    }
}
