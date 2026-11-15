package com.subtitleedit.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryUpdateTrackerTest {
    @Test fun `a slow view retains its update after a burst of unrelated directories`() = runBlocking {
        val tracker = DirectoryUpdateTracker()
        val consumerPaused = CompletableDeferred<Unit>()
        val resumeConsumer = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Long>()
        val collector = launch {
            tracker.revision.collect { revision ->
                if (revision == 0L) {
                    consumerPaused.complete(Unit)
                    resumeConsumer.await()
                } else observed.complete(tracker.versionOf("/visible"))
            }
        }
        try {
            consumerPaused.await()
            tracker.publish(setOf("/visible"))
            repeat(1000) { tracker.publish(setOf("/other-$it")) }
            resumeConsumer.complete(Unit)
            assertEquals(1L, withTimeout(5000) { observed.await() })
            assertEquals(1001L, tracker.revision.value)
        } finally { collector.cancel() }
    }

    @Test fun `only changed directory versions advance and removed roots release their records`() {
        val tracker = DirectoryUpdateTracker()
        tracker.publish(setOf("/music", "/music/album"))
        val album = tracker.versionOf("/music/album")
        tracker.publish(setOf("/videos"))
        assertEquals(album, tracker.versionOf("/music/album"))
        tracker.publish(setOf("/music/album"))
        assertTrue(tracker.versionOf("/music/album") > album)
        tracker.retain(setOf("/videos"))
        assertEquals(0L, tracker.versionOf("/music/album"))
        assertEquals(2L, tracker.versionOf("/videos"))
    }
}
