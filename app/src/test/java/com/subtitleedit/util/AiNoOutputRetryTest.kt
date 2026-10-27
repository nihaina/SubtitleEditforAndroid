package com.subtitleedit.util

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class AiNoOutputRetryTest {
    @Test
    fun retriesOnlyNoOutputAndFailsAfterThreeRetries() = runBlocking {
        val timeouts = AtomicInteger(0)
        val retries = mutableListOf<Int>()

        val failure = runCatching {
            retryAiRequestWhenNoOutput(
                idleTimeoutMs = 10,
                onTimeout = { timeouts.incrementAndGet() },
                onRetry = { retries += it }
            ) { _, _ ->
                delay(100)
                Unit
            }
        }.exceptionOrNull()

        assertEquals(AiNoOutputTimeoutException::class.java, failure?.javaClass)
        assertEquals(4, timeouts.get())
        assertEquals(listOf(1, 2, 3), retries)
    }

    @Test
    fun outputResetsTheIdleTimer() = runBlocking {
        var completed = false
        retryAiRequestWhenNoOutput(
            idleTimeoutMs = 100,
            onTimeout = { error("request should not time out") }
        ) { onOutput, _ ->
            repeat(4) {
                delay(40)
                onOutput()
            }
            completed = true
            "ok"
        }

        assertTrue(completed)
    }

    @Test
    fun callerCancellationDoesNotTriggerAutomaticRetry() = runBlocking {
        var attempts = 0
        val failure = runCatching {
            retryAiRequestWhenNoOutput(
                idleTimeoutMs = 1_000,
                onTimeout = { error("caller cancellation is not a timeout") }
            ) { _, _ ->
                attempts++
                throw CancellationException("cancelled")
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1, attempts)
    }
}
