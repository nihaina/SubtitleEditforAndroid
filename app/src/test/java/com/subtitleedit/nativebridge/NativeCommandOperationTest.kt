package com.subtitleedit.nativebridge

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCommandOperationTest {
    @Test
    fun cancellingOneOperationOnlyCancelsItsSession() = runBlocking {
        val cancelled = AtomicInteger(0)
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        val executor = NativeCommandExecutor { _, complete ->
            callbacks += complete
            NativeCommandSession { cancelled.incrementAndGet() }
        }
        val first = NativeCommandOperation(executor)
        val second = NativeCommandOperation(executor)
        val firstJob = launch { first.execute(arrayOf("first")) }
        val secondJob = launch { second.execute(arrayOf("second")) }
        while (callbacks.size < 2) delay(1)

        first.cancel()
        assertEquals(1, cancelled.get())
        callbacks[0](false)
        callbacks[1](true)

        firstJob.join()
        secondJob.join()
    }
}
