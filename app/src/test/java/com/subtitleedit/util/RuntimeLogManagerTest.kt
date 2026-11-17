package com.subtitleedit.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedWriter
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RuntimeLogManagerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun pruningKeepsTheActiveFileEvenWhenBufferedWritesHaveNotUpdatedItsMtime() {
        val now = System.currentTimeMillis()
        val active = temporaryFolder.newFile("runtime-live-active.txt")
        val expired = temporaryFolder.newFile("runtime-live-expired.txt")
        val recent = temporaryFolder.newFile("runtime-live-recent.txt")
        assertTrue(active.setLastModified(now - 3_600_001L))
        assertTrue(expired.setLastModified(now - 3_600_001L))

        RuntimeLogManager.pruneOldLogFiles(temporaryFolder.root, active, now)

        assertTrue(active.exists())
        assertFalse(expired.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun queuedWritesLeaveTheCallerFreeAndAreDrainedBeforeTheReadBarrier() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val io = RuntimeLogIo(executor)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = mutableListOf<String>()
        val writerThread = AtomicReference<Thread>()
        try {
            io.enqueue {
                writerThread.set(Thread.currentThread())
                entered.countDown()
                release.await()
                events += "first"
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            io.enqueue { events += "second" }
            assertNotEquals(Thread.currentThread(), writerThread.get())
            release.countDown()

            io.runAndWait { events += "read" }

            assertEquals(listOf("first", "second", "read"), events)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun periodicFlushPersistsSparseOutputWithoutAnotherWrite() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val io = RuntimeLogIo(executor)
        val sink = StringWriter()
        val writer = BufferedWriter(sink)
        val flushed = CountDownLatch(1)
        try {
            io.enqueue { writer.write("sparse logcat line") }
            io.flushPeriodically(10L) {
                writer.flush()
                flushed.countDown()
            }

            assertTrue(flushed.await(5, TimeUnit.SECONDS))
            assertEquals("sparse logcat line", sink.toString())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aFailedPeriodicFlushDoesNotDisableLaterFlushes() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val io = RuntimeLogIo(executor)
        val attempts = AtomicInteger()
        val recovered = CountDownLatch(1)
        try {
            io.flushPeriodically(10L) {
                if (attempts.incrementAndGet() == 1) error("temporary failure")
                recovered.countDown()
            }

            assertTrue(recovered.await(5, TimeUnit.SECONDS))
            assertTrue(attempts.get() >= 2)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aReadOrCrashBarrierOnTheWriterThreadDoesNotDeadlock() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val io = RuntimeLogIo(executor)
        val completed = CountDownLatch(1)
        try {
            io.enqueue { io.runAndWait { completed.countDown() } }

            assertTrue(completed.await(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun previewCollector_keepsTheCompleteAiResponseSection() {
        val collector = RuntimeLogManager.PreviewCollector(maxLines = 3, maxChars = 30)
        collector.add("old log line")
        collector.add("AI_TRANSLATION_RESPONSE_BEGIN")
        repeat(10) { index -> collector.add("response line $index") }
        collector.add("AI_TRANSLATION_RESPONSE_END")
        collector.add("new log line")

        val content = collector.content()
        assertTrue(content.contains("AI_TRANSLATION_RESPONSE_BEGIN"))
        assertTrue(content.contains("response line 0"))
        assertTrue(content.contains("response line 9"))
        assertTrue(content.contains("AI_TRANSLATION_RESPONSE_END"))
        assertFalse(content.contains("old log line"))
        assertTrue(collector.isTruncated)
    }
}
