package com.subtitleedit.editor

import com.subtitleedit.EditorTimingDrag
import com.subtitleedit.EditorTimingDragSession
import com.subtitleedit.EditorTimingRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Coalesces previews within a gesture, but commits ended gestures in order. */
internal class EditorSourceWaveformSyncController(
    private val scope: CoroutineScope,
    private val sourceContent: () -> String,
    private val editGeneration: () -> Long,
    private val isCurrent: (EditorTimingDrag) -> Boolean,
    private val applySource: (EditorTimingDrag, String, String, Boolean) -> Boolean,
    private val onSourceUpdated: (String, EditorSourceTextEdit) -> Unit,
    private val debounceMillis: Long = 40L,
    private val buildSource: (EditorTimingDrag, EditorTimingRange, String) -> String = EditorTimingDragSession::sourceFor
) {
    private data class Request(val drag: EditorTimingDrag, val range: EditorTimingRange, val final: Boolean)
    private data class Result(val source: String, val edit: EditorSourceTextEdit)

    private val commits = ArrayDeque<Request>()
    private var preview: Request? = null
    private val baselines = mutableMapOf<Long, String>()
    private var job: Job? = null
    private var epoch = 0L
    private var visiblePreview: Request? = null

    val hasPending: Boolean get() = commits.isNotEmpty() || preview != null || job?.isActive == true

    fun preview(drag: EditorTimingDrag, range: EditorTimingRange) {
        preview = Request(drag, range, false)
        start()
    }

    fun commit(drag: EditorTimingDrag) {
        if (preview?.drag === drag) preview = null
        commits.addLast(Request(drag, drag.range, true))
        start()
    }

    private fun start() {
        if (job?.isActive == true) return
        val scheduledEpoch = epoch
        job = scope.launch {
            val running = currentCoroutineContext()[Job]
            try {
                while (isActive && scheduledEpoch == epoch) {
                    delay(debounceMillis)
                    val request = commits.firstOrNull() ?: preview ?: break
                    if (!isCurrent(request.drag)) {
                        remove(request)
                        continue
                    }
                    val source = sourceContent()
                    val generation = editGeneration()
                    val base = baselines.getOrPut(request.drag.key) { source }
                    val result = withContext(Dispatchers.Default) { calculate(request, base, source) }
                    currentCoroutineContext().ensureActive()
                    if (scheduledEpoch != epoch) break
                    if (!isCurrent(request.drag) || generation != editGeneration()) {
                        remove(request)
                        continue
                    }
                    // A newer preview/final supersedes only this gesture's preview.
                    // Never discard a final because another gesture was queued.
                    if (!request.final && (preview !== request || commits.any { it.drag === request.drag })) continue
                    apply(request, base, result)
                    remove(request)
                }
            } finally {
                if (job === running) job = null
            }
        }
    }

    private fun calculate(request: Request, base: String, visible: String): Result {
        val updated = buildSource(request.drag, request.range, base)
        return Result(updated, EditorSourceTextEdit.between(visible, updated))
    }

    private fun apply(request: Request, base: String, result: Result) {
        if (applySource(request.drag, base, result.source, request.final)) {
            visiblePreview = request.takeUnless { it.final }
            onSourceUpdated(result.source, result.edit)
        }
    }

    private fun remove(request: Request) {
        if (request.final) {
            if (commits.firstOrNull() === request) commits.removeFirst()
            baselines.remove(request.drag.key)
        } else if (preview === request) preview = null
    }

    /** Lifecycle/mode/save barriers finish queued gestures before leaving their document. */
    fun flushNow() {
        epoch++
        job?.cancel()
        job = null
        while (commits.isNotEmpty()) {
            val request = commits.first()
            if (isCurrent(request.drag)) {
                val source = sourceContent()
                val base = baselines.getOrPut(request.drag.key) { source }
                apply(request, base, calculate(request, base, source))
            }
            remove(request)
        }
        preview = null
    }

    suspend fun flushAndJoin() {
        val running = job
        flushNow()
        running?.cancelAndJoin()
    }

    fun finishVisibleAndCancel() {
        visiblePreview?.takeIf { isCurrent(it.drag) }?.let { request ->
            baselines[request.drag.key]?.let { base ->
                applySource(request.drag, base, sourceContent(), true)
            }
        }
        cancel()
    }

    /** External source edits/document replacement take ownership over queued drag results. */
    fun cancel() {
        epoch++
        job?.cancel()
        job = null
        commits.clear()
        preview = null
        baselines.clear()
        visiblePreview = null
    }
}
