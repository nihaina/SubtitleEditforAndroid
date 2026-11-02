package com.subtitleedit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class AutoTranslateRunConfiguration(
    val translation: PersistedTranslationConfig?,
    val batchSize: Int?,
    val punctuationProvider: String?,
    val punctuationEnabled: Boolean,
    val translationEnabled: Boolean,
    val overwriteOutput: Boolean
)

/** Single owner of durable queue mutations and the jobs actually processing each file. */
internal class AutoTranslateQueueController(
    read: () -> AutoTranslateQueueSnapshot?,
    private val write: (AutoTranslateQueueSnapshot) -> Unit
) {
    private val lock = Any()
    private val workerMutex = Mutex()
    private var state = read() ?: AutoTranslateQueueSnapshot(false, true, emptyList())
    private var workerActive = false
    private val executions = mutableMapOf<String, Execution>()

    enum class StopReason { PAUSED, REMOVED }

    class Execution internal constructor(
        val initial: AutoTranslateFileSnapshot,
        internal val job: Job,
        internal val onStop: (StopReason) -> Unit
    ) {
        @Volatile internal var stopReason: StopReason? = null
    }

    fun snapshot(): AutoTranslateQueueSnapshot = synchronized(lock) { state }

    fun add(file: AutoTranslateFileSnapshot): Boolean = synchronized(lock) {
        if (state.files.any { it.uri == file.uri || it.sessionId == file.sessionId }) return false
        commit(state.copy(files = state.files + file))
        true
    }

    fun setOptions(punctuationEnabled: Boolean? = null, translationEnabled: Boolean? = null) =
        synchronized(lock) {
            commit(state.copy(
                punctuationPredictionEnabled = punctuationEnabled ?: state.punctuationPredictionEnabled,
                translationEnabled = translationEnabled ?: state.translationEnabled
            ))
        }

    /** Changes run configuration, never the progress held by the authoritative queue. */
    fun prepare(sessionIds: Set<String>, configuration: AutoTranslateRunConfiguration, first: String) =
        synchronized(lock) {
            val files = state.files.map { file ->
                if (file.sessionId !in sessionIds || file.sessionId in executions) return@map file
                val resuming = file.status == "PAUSED"
                file.copy(
                    status = if (file.sessionId == first) "RUNNING" else file.status,
                    processingStage = if (file.sessionId == first && !resuming) "READING" else file.processingStage,
                    translationConfig = if (resuming) file.translationConfig else configuration.translation,
                    translationBatchSize = if (resuming) file.translationBatchSize else configuration.batchSize,
                    punctuationProvider = if (resuming) file.punctuationProvider else configuration.punctuationProvider,
                    punctuationEnabled = if (resuming) file.punctuationEnabled else configuration.punctuationEnabled,
                    translationEnabled = if (resuming) file.translationEnabled else configuration.translationEnabled,
                    overwriteOutput = if (resuming) file.overwriteOutput else configuration.overwriteOutput
                )
            }
            commit(state.copy(files = files, workRunning = workerActive || files.any { it.status == "RUNNING" }))
        }

    fun isExecuting(sessionId: String): Boolean = synchronized(lock) { sessionId in executions }

    fun pause(sessionId: String) = stop(setOf(sessionId), StopReason.PAUSED)

    fun pauseAll() = stop(snapshot().files.filter { it.status == "RUNNING" }.mapTo(mutableSetOf()) { it.sessionId }, StopReason.PAUSED)

    fun remove(sessionId: String) = stop(setOf(sessionId), StopReason.REMOVED)

    private fun stop(sessionIds: Set<String>, reason: StopReason) {
        val active = synchronized(lock) {
            val files = if (reason == StopReason.REMOVED) {
                state.files.filterNot { it.sessionId in sessionIds }
            } else {
                state.files.map { if (it.sessionId in sessionIds) it.copy(status = "PAUSED") else it }
            }
            commit(state.copy(files = files, workRunning = workerActive || files.any { it.status == "RUNNING" }))
            sessionIds.mapNotNull { executions[it] }.onEach { it.stopReason = reason }
        }
        active.forEach { execution ->
            // Cancel the real API/native request before cancellation unwinds its awaiting coroutine.
            try {
                execution.onStop(reason)
            } finally {
                execution.job.cancel(CancellationException("字幕任务已${if (reason == StopReason.REMOVED) "移除" else "暂停"}"))
            }
        }
    }

    suspend fun <T> withWorker(block: suspend () -> T): T = workerMutex.withLock {
        synchronized(lock) {
            commit(state.copy(workRunning = true))
            workerActive = true
        }
        try {
            block()
        } finally {
            synchronized(lock) {
                workerActive = false
                commit(state.copy(workRunning = false))
            }
        }
    }

    /** A removed child is cancelled independently so later queued files can still run. */
    suspend fun runFile(
        sessionId: String,
        resumePaused: Boolean = false,
        onStop: (StopReason) -> Unit,
        block: suspend (Execution) -> Unit
    ): Boolean = supervisorScope {
        var execution: Execution? = null
        val task = async(start = CoroutineStart.LAZY) {
            currentCoroutineContext().ensureActive()
            val job = currentCoroutineContext().job
            val owned = synchronized(lock) {
                val initial = state.files.firstOrNull { it.sessionId == sessionId } ?: return@async
                if (initial.status != "WAITING" && initial.status != "RUNNING" &&
                    !(resumePaused && initial.status == "PAUSED")) return@async
                if (sessionId in executions) return@async
                Execution(initial, job, onStop).also { executions[sessionId] = it }
            }
            execution = owned
            try {
                block(owned)
            } catch (error: CancellationException) {
                synchronized(lock) {
                    val files = state.files.map {
                        if (it.sessionId == sessionId) it.copy(status = "PAUSED") else it
                    }
                    commit(state.copy(files = files))
                }
                throw error
            } catch (error: Exception) {
                synchronized(lock) {
                    commit(state.copy(files = state.files.map {
                        if (it.sessionId == sessionId) it.copy(status = "STOPPED", message = error.message.orEmpty()) else it
                    }))
                }
                throw error
            } finally {
                synchronized(lock) { executions.remove(sessionId) }
            }
        }
        try {
            task.await()
            execution != null
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (execution?.stopReason != StopReason.REMOVED) throw error
            false
        }
    }

    fun progress(execution: Execution, file: AutoTranslateFileSnapshot) = synchronized(lock) {
        val id = execution.initial.sessionId
        if (executions[id] !== execution || state.files.none { it.sessionId == id }) return
        require(file.sessionId == id)
        val updated = if (execution.stopReason == StopReason.PAUSED) file.copy(status = "PAUSED") else file
        val files = if (updated.status == "COMPLETED") state.files.filterNot { it.sessionId == id }
            else state.files.map { if (it.sessionId == id) updated else it }
        commit(state.copy(files = files))
    }

    /** Linearizes publishing output with removal: a removed task cannot start a later write. */
    fun <T> writeOutput(execution: Execution, block: () -> T): T = synchronized(lock) {
        val id = execution.initial.sessionId
        if (executions[id] !== execution || execution.stopReason != null || state.files.none { it.sessionId == id }) {
            throw CancellationException("字幕任务已停止")
        }
        block()
    }

    private fun commit(snapshot: AutoTranslateQueueSnapshot) {
        write(snapshot)
        state = snapshot
    }
}
