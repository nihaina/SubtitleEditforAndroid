package com.subtitleedit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AutoTranslateQueueControllerTest {
    private val configuration = AutoTranslateRunConfiguration(null, 8, "custom", false, true, false)

    @Test fun workerProgressPreservesNewFilesAndPageOptions(): Unit = runBlocking {
        var durable: AutoTranslateQueueSnapshot? = null
        val queue = AutoTranslateQueueController({ durable }, { durable = it })
        queue.add(file("A"))
        queue.runFile("A", onStop = {}) { execution ->
            queue.add(file("B"))
            queue.setOptions(punctuationEnabled = true, translationEnabled = false)
            queue.progress(execution, execution.initial.copy(status = "RUNNING", processedLines = 4))
            assertEquals(listOf("A", "B"), durable!!.files.map { it.sessionId })
            assertEquals(4, durable!!.files.first().processedLines)
            assertTrue(durable!!.punctuationPredictionEnabled)
            assertFalse(durable!!.translationEnabled)
        }
    }

    @Test fun preparingAnotherFileDoesNotRestoreStaleProgress(): Unit = runBlocking {
        val queue = queue(file("A"), file("B"))
        queue.runFile("A", onStop = {}) { execution ->
            queue.progress(execution, execution.initial.copy(status = "RUNNING", processedLines = 7))
            queue.prepare(setOf("B"), configuration, "B")
            assertEquals(7, queue.snapshot().files.first().processedLines)
        }
    }

    @Test fun removalCancelsRealExecutionRejectsLateProgressAndContinuesNextFile() = runBlocking {
        val queue = queue(file("A"), file("B"))
        val started = CompletableDeferred<Unit>()
        var requestCancelled = false
        var outputWritten = false
        val worker = async {
            queue.runFile("A", onStop = { requestCancelled = true }) { execution ->
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    queue.progress(execution, execution.initial.copy(status = "RUNNING", processedLines = 9))
                    runCatching { queue.writeOutput(execution) { outputWritten = true } }
                }
            }
        }
        started.await()
        queue.remove("A")
        assertFalse(withTimeout(1_000) { worker.await() })
        assertTrue(requestCancelled)
        assertFalse(outputWritten)
        assertEquals(listOf("B"), queue.snapshot().files.map { it.sessionId })
        assertTrue(queue.runFile("B", onStop = {}) { execution ->
            queue.progress(execution, execution.initial.copy(status = "COMPLETED"))
        })
        assertTrue(queue.snapshot().files.isEmpty())
    }

    @Test fun removedWaitingFileCannotBeClaimedFromAnOldWorkerPlan() = runBlocking {
        val queue = queue(file("A"))
        queue.remove("A")
        assertFalse(queue.runFile("A", onStop = {}) { fail("Removed file must not run") })
    }

    @Test fun pauseRetainsLatestCheckpointAndResumeConfiguration() = runBlocking {
        val original = file("A").copy(translationBatchSize = 8, processingStage = "TRANSLATION")
        val queue = queue(original)
        val started = CompletableDeferred<Unit>()
        var pauseDelivered = false
        val worker = async {
            runCatching {
                queue.runFile("A", onStop = { pauseDelivered = it == AutoTranslateQueueController.StopReason.PAUSED }) { execution ->
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        queue.progress(execution, original.copy(processedLines = 6, status = "RUNNING"))
                    }
                }
            }.exceptionOrNull()
        }
        started.await()
        queue.pause("A")
        assertTrue(withTimeout(1_000) { worker.await() } is CancellationException)
        assertTrue(pauseDelivered)
        assertEquals("PAUSED", queue.snapshot().files.single().status)
        queue.prepare(setOf("A"), configuration.copy(batchSize = 99), "A")
        val resumed = queue.snapshot().files.single()
        assertEquals(6, resumed.processedLines)
        assertEquals(8, resumed.translationBatchSize)
        assertEquals("TRANSLATION", resumed.processingStage)
    }

    @Test fun completedExecutionCannotOverwriteANewerExecution(): Unit = runBlocking {
        val queue = queue(file("A"))
        lateinit var previous: AutoTranslateQueueController.Execution
        queue.runFile("A", onStop = {}) { previous = it }
        queue.runFile("A", onStop = {}) { current ->
            queue.progress(current, current.initial.copy(processedLines = 7))
            queue.progress(previous, previous.initial.copy(processedLines = 1))
            assertEquals(7, queue.snapshot().files.single().processedLines)
        }
    }

    @Test fun replacementWorkersWaitForPreviousCleanup() = runBlocking {
        val queue = queue(file("A"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var firstCleaned = false
        var secondStarted = false
        val first = async {
            queue.withWorker {
                started.complete(Unit)
                release.await()
                firstCleaned = true
            }
        }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            queue.withWorker {
                assertTrue(firstCleaned)
                secondStarted = true
            }
        }
        assertFalse(secondStarted)
        release.complete(Unit)
        withTimeout(1_000) { first.await(); second.await() }
        assertTrue(secondStarted)
        assertFalse(queue.snapshot().workRunning)
    }

    @Test fun failedPersistenceDoesNotPublishUncommittedState() {
        val queue = AutoTranslateQueueController({ null }, { throw IllegalStateException("disk full") })
        assertNotNull(runCatching { queue.add(file("A")) }.exceptionOrNull())
        assertTrue(queue.snapshot().files.isEmpty())
    }

    @Test fun pauseOrRemovalBeforeWorkerStartsClearsPendingRunningFlag() {
        val queue = queue(file("A"))
        queue.prepare(setOf("A"), configuration, "A")
        assertTrue(queue.snapshot().workRunning)
        queue.pause("A")
        assertFalse(queue.snapshot().workRunning)
        queue.prepare(setOf("A"), configuration, "A")
        queue.remove("A")
        assertFalse(queue.snapshot().workRunning)
        assertTrue(queue.snapshot().files.isEmpty())
    }

    private fun queue(vararg files: AutoTranslateFileSnapshot) = AutoTranslateQueueController(
        { AutoTranslateQueueSnapshot(false, true, files.toList()) }, {}
    )

    private fun file(id: String) = AutoTranslateFileSnapshot(
        uri = "content://subtitle/$id", fileName = "$id.srt", fileSize = 1L, sessionId = id,
        status = "WAITING", processingStage = "READING", totalLines = 10, processedLines = 0,
        progressStage = null, documentContent = null, originalText = null,
        punctuationPredictionCompleted = false, translatedTexts = emptyList(),
        translationResumeStart = null, translationBatchSize = null, translationConfig = null,
        punctuationProvider = null, punctuationEnabled = false, translationEnabled = true,
        overwriteOutput = false, punctuationCompletedTexts = emptyList(), textPunctuationPredictions = emptyList(),
        textTranslations = emptyList(), punctuationResumeHistory = false, translationResumeHistory = false
    )
}
