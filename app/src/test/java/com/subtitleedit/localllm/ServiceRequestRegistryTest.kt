package com.subtitleedit.localllm

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.resumeWithException

class ServiceRequestRegistryTest {
    @Test fun disconnectFailsBothLoadAndGenerationWaiters() = runBlocking {
        val registry = ServiceRequestRegistry()
        val connection = Any()
        registry.connected(connection)
        val requests = List(2) {
            async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        registry.register(connection) { continuation.resumeWithException(it) }
                    }
                }.exceptionOrNull()
            }
        }
        val error = IllegalStateException("service died")
        registry.disconnected(connection, error)
        withTimeout(1_000) {
            requests.forEach { request ->
                val failure = request.await()
                assertTrue(failure is IllegalStateException)
                assertEquals(error.message, failure?.message)
            }
        }
    }

    @Test fun requestRegisteredAfterDisconnectFailsImmediately() {
        val registry = ServiceRequestRegistry()
        val connection = Any()
        registry.connected(connection)
        registry.disconnected(connection, IllegalStateException("died"))
        var failure: Throwable? = null
        registry.register(connection) { failure = it }
        assertNotNull(failure)
    }

    @Test fun completedOrCancelledRequestsAreNotFailedAgain() {
        val registry = ServiceRequestRegistry()
        val connection = Any()
        registry.connected(connection)
        var failures = 0
        val registration = registry.register(connection) { failures++ }
        registration.close()
        registration.close()
        registry.disconnected(connection, IllegalStateException("died"))
        assertEquals(0, failures)
    }

    @Test fun reconnectFailsOldRequestsButIgnoresLateOldDisconnect() {
        val registry = ServiceRequestRegistry()
        val first = Any()
        val second = Any()
        registry.connected(first)
        var firstFailures = 0
        registry.register(first) { firstFailures++ }
        registry.connected(second)
        var secondFailures = 0
        registry.register(second) { secondFailures++ }
        registry.disconnected(first, IllegalStateException("late"))
        assertEquals(1, firstFailures)
        assertEquals(0, secondFailures)
        registry.disconnected(second, IllegalStateException("died"))
        registry.disconnected(second, IllegalStateException("duplicate"))
        assertEquals(1, secondFailures)
    }
}
