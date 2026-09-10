package com.subtitleedit.task

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Shared execution/cancellation lifecycle for a page's long task. The caller
 * supplies the owning scope; starting again is forbidden until cleanup finishes.
 */
internal class LongTaskController(
    private val store: TaskStateStore,
    private val type: String
) {
    @Volatile private var execution: LongTaskExecution? = null

    val isRunning: Boolean get() = execution?.job?.isCompleted == false
    val isCancellationRequested: Boolean get() = execution?.isCancellationRequested == true

    @Synchronized
    fun launch(scope: CoroutineScope, block: suspend (LongTaskExecution) -> Unit): Job? {
        if (isRunning) return null
        val task = LongTaskExecution(store, UUID.randomUUID().toString(), type)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            task.reporter.running()
            try {
                block(task)
                task.ensureActive()
                task.finish()
            } catch (error: CancellationException) {
                task.reporter.cancelled()
                throw error
            } catch (error: Exception) {
                task.reporter.failed(error)
            } finally {
                task.release()
            }
        }
        task.job = job
        execution = task
        job.invokeOnCompletion { error ->
            // Also covers cancellation before the lazy coroutine starts.
            if (error is CancellationException) task.reporter.cancelled()
        }
        job.start()
        return job
    }

    fun cancel() = execution?.cancel() ?: Unit

    fun progress(progress: TaskProgress) {
        execution?.reporter?.running(progress)
    }
}

internal class LongTaskExecution(
    private val store: TaskStateStore,
    val id: String,
    type: String
) {
    internal val reporter = TaskReporter(store, id, type)
    @Volatile internal var job: Job? = null
    private val cancellationRequested = AtomicBoolean(false)
    private val cancellationActions = mutableListOf<() -> Unit>()
    private var failure: Throwable? = null

    val isCancellationRequested: Boolean
        get() = cancellationRequested.get() || job?.isCancelled == true

    fun onCancel(action: () -> Unit) {
        synchronized(cancellationActions) { cancellationActions += action }
        if (isCancellationRequested) action()
    }

    fun progress(progress: TaskProgress) = reporter.running(progress)

    fun recordFailure(error: Throwable) { failure = error }

    suspend fun ensureActive() {
        currentCoroutineContext().ensureActive()
        if (isCancellationRequested) throw CancellationException("任务已取消")
    }

    internal fun cancel() {
        if (job?.isCompleted != false || !cancellationRequested.compareAndSet(false, true)) return
        store.update(id, TaskStatus.CANCELLING, TaskProgress("正在取消，等待当前处理结束"))
        release()
        job?.cancel(CancellationException("用户取消任务"))
    }

    internal fun finish() {
        failure?.let { reporter.failed(it) } ?: reporter.succeeded()
    }

    internal fun release() {
        val actions = synchronized(cancellationActions) { cancellationActions.toList() }
        actions.forEach { runCatching(it) }
    }
}
