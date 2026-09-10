package com.subtitleedit.nativebridge

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun interface NativeCommandSession {
    fun cancel()
}

internal fun interface NativeCommandExecutor {
    fun start(arguments: Array<String>, onComplete: (Boolean) -> Unit): NativeCommandSession
}

/** One owner, one native session at a time. Cancellation is sticky for this operation. */
internal class NativeCommandOperation(private val executor: NativeCommandExecutor) {
    private val cancelled = AtomicBoolean(false)
    private val activeSession = AtomicReference<NativeCommandSession?>()
    private val executionMutex = Mutex()

    suspend fun execute(arguments: Array<String>): Boolean = executionMutex.withLock {
        ensureActive()
        val completion = CompletableDeferred<Boolean>()
        val session = executor.start(arguments) { completion.complete(it) }
        activeSession.set(session)
        if (cancelled.get()) session.cancel()
        try {
            val successful = completion.await()
            ensureActive()
            successful
        } catch (error: CancellationException) {
            cancel()
            throw error
        } finally {
            // The caller may delete input/output files in its finally block. Do not
            // return until native code has actually stopped using those files.
            withContext(NonCancellable) { completion.await() }
            activeSession.compareAndSet(session, null)
        }
    }

    fun cancel() {
        cancelled.set(true)
        activeSession.get()?.cancel()
    }

    private suspend fun ensureActive() {
        currentCoroutineContext().ensureActive()
        if (cancelled.get()) throw CancellationException("媒体任务已取消")
    }
}
