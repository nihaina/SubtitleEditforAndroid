package com.subtitleedit.editor

import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.SubtitleDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
    private val debounceMillis: Long = 350L,
    private val parseDocument: suspend (String, SubtitleParser.SubtitleFormat) -> SubtitleDocument =
        { content, format -> SubtitleParser.parseDocument(content, format = format) }
) {
    private var previewJob: Job? = null
    // SubtitleParser is synchronous and does not observe coroutine cancellation while it is
    // walking a large document. Serializing preview parses prevents a typing burst from running
    // several full parses concurrently and multiplying GC pressure.
    private val parseDispatcher = Dispatchers.Default.limitedParallelism(1)

    val isActive: Boolean
        get() = previewJob?.isActive == true

    fun schedule(onFinished: ((Long, Boolean) -> Unit)? = null) {
        previewJob?.cancel()
        val scheduledGeneration = editGeneration()
        previewJob = scope.launch {
            try {
                delay(debounceMillis)
                if (
                    !isSourceViewMode() ||
                    suppressSourceViewChanges() ||
                    scheduledGeneration != editGeneration()
                ) {
                    if (isSourceViewMode() && scheduledGeneration != editGeneration()) {
                        onFinished?.invoke(scheduledGeneration, false)
                    }
                    return@launch
                }
                val sourceSnapshot = snapshotContent()
                val parsedDocument = withContext(parseDispatcher) {
                    parseDocument(sourceSnapshot, currentFormat())
                }
                if (!coroutineContext.isActive || !isSourceViewMode() || scheduledGeneration != editGeneration()) {
                    if (coroutineContext.isActive &&
                        isSourceViewMode() &&
                        scheduledGeneration != editGeneration()
                    ) {
                        onFinished?.invoke(scheduledGeneration, false)
                    }
                    return@launch
                }
                onParsed(scheduledGeneration, sourceSnapshot, parsedDocument)
                onFinished?.invoke(scheduledGeneration, true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // A synchronous parser may finish with an error after its coroutine was
                // cancelled. Do not let that stale job unlock a newer full-parse request.
                if (coroutineContext.isActive &&
                    isSourceViewMode() &&
                    scheduledGeneration == editGeneration()
                ) {
                    onFinished?.invoke(scheduledGeneration, false)
                }
                // Parsing is a best-effort background refresh.  Re-throwing here escapes the
                // lifecycleScope coroutine and can crash the editor after a malformed source
                // edit or parser allocation failure.  The caller keeps the last valid row model
                // and decides whether to retry or unlock the editor.
            }
        }
    }

    fun scheduleListPreview(onReady: () -> Unit) {
        previewJob?.cancel()
        previewJob = scope.launch {
            delay(debounceMillis)
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
