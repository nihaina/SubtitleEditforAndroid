package com.subtitleedit.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Shares blocking file work without tying its lifetime to a recycled list row. */
internal class SharedFileResourceCache<T : Any>(
    private val maximumWeight: Int,
    private val maximumEntries: Int = 2048,
    private val weightOf: (T) -> Int = { 1 },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val permits: Semaphore = Semaphore(2)
) {
    private data class Key(val path: String, val version: String)
    private class Request<T> {
        lateinit var task: Deferred<T>
        var waiters = 0
        var started = false
    }

    private val lock = Any()
    private val values = LinkedHashMap<Key, T>(16, 0.75f, true)
    private val requests = mutableMapOf<Key, Request<T>>()
    private var weight = 0L

    init {
        require(maximumWeight > 0)
        require(maximumEntries > 0)
    }

    fun peek(path: String, version: String): T? = synchronized(lock) { values[Key(path, version)] }

    suspend fun get(path: String, version: String, load: suspend () -> T): T {
        val key = Key(path, version)
        val request = synchronized(lock) {
            values[key]?.let { return it }
            (requests[key] ?: newRequest(key, load).also { requests[key] = it }).also { it.waiters++ }
        }
        request.task.start()
        try {
            return request.task.await()
        } finally {
            synchronized(lock) {
                request.waiters--
                // A recycled row must not leave a long queue of obsolete
                // decodes. Already-started blocking work remains shared.
                if (request.waiters == 0 && !request.started && requests[key] === request) {
                    requests.remove(key)
                    request.task.cancel()
                }
            }
        }
    }

    private fun newRequest(key: Key, load: suspend () -> T): Request<T> {
        val request = Request<T>()
        request.task = scope.async(start = CoroutineStart.LAZY) {
            try {
                val result = permits.withPermit {
                    synchronized(lock) { request.started = true }
                    currentCoroutineContext().ensureActive()
                    load()
                }
                synchronized(lock) {
                    // invalidate() detaches old work. It may finish, but cannot
                    // replace the result of a newer request for this path.
                    if (requests[key] === request) {
                        requests.remove(key)
                        putLocked(key, result)
                    }
                }
                result
            } catch (error: Throwable) {
                synchronized(lock) {
                    if (requests[key] === request) requests.remove(key)
                }
                throw error
            }
        }
        return request
    }

    fun invalidate(path: String) = synchronized(lock) {
        val iterator = values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.path == path) {
                weight -= weightOf(entry.value).coerceAtLeast(1)
                iterator.remove()
            }
        }
        val pending = requests.iterator()
        while (pending.hasNext()) {
            val entry = pending.next()
            if (entry.key.path == path) {
                pending.remove()
                if (!entry.value.started) entry.value.task.cancel()
            }
        }
    }

    fun clear() = synchronized(lock) {
        values.clear()
        requests.values.forEach { if (!it.started) it.task.cancel() }
        requests.clear()
        weight = 0
    }

    private fun putLocked(key: Key, value: T) {
        values.put(key, value)?.let { weight -= weightOf(it).coerceAtLeast(1) }
        weight += weightOf(value).coerceAtLeast(1)
        val iterator = values.iterator()
        while ((weight > maximumWeight || values.size > maximumEntries) && iterator.hasNext()) {
            weight -= weightOf(iterator.next().value).coerceAtLeast(1)
            iterator.remove()
        }
    }
}
