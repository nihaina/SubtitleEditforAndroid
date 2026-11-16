package com.subtitleedit.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.util.SharedFileCache
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLibraryIndexingStateTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun automaticStartupScanRemainsVisibleToANewSubscriberUntilItsWorkCompletes() = runBlocking {
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        withFixture(onRead = {
            parsing.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "The test did not release indexing" }
        }) { fixture ->
            val song = fixture.file("saved.wav")
            try {
                fixture.observation.syncRoots(context, listOf(fixture.directory))
                assertTrue("Automatic startup reaches actual indexing", parsing.await(5, TimeUnit.SECONDS))

                // The screen's new collector must immediately see an already-running scan.
                assertTrue(fixture.indexing.first())
                assertTrue(fixture.repository.getIndexedTracks().isEmpty())
            } finally {
                release.countDown()
            }
            fixture.barrier()

            assertFalse(fixture.indexing.first())
            assertEquals(listOf(song.path), fixture.repository.getIndexedTracks().map { it.path })
            assertEquals(listOf(true, false), fixture.transitions.toList())
        }
    }

    @Test fun explicitReconcileAlsoPublishesTheActualWorkerState() = runBlocking {
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        withFixture(onRead = {
            parsing.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "The test did not release indexing" }
        }) { fixture ->
            fixture.file("saved.wav")
            coroutineScope {
                val scan = async { fixture.observation.reconcile(context, listOf(fixture.directory)) }
                // Dispatch the queued reconcile before waiting on its blocking metadata reader.
                kotlinx.coroutines.yield()
                try {
                    assertTrue(parsing.await(5, TimeUnit.SECONDS))
                    assertTrue(fixture.indexing.first())
                } finally {
                    release.countDown()
                }
                assertEquals(1, scan.await())
            }
            assertFalse(fixture.indexing.value)
            assertEquals(listOf(true, false), fixture.transitions.toList())
        }
    }

    @Test fun aFailedAutomaticScanAlwaysRestoresTheIdleState() = runBlocking {
        assertFailedScanRestoresIdle(IllegalStateException("test metadata read failed"))
    }

    @Test fun aCancelledAutomaticScanAlwaysRestoresTheIdleState() = runBlocking {
        assertFailedScanRestoresIdle(CancellationException("test metadata read cancelled"))
    }

    @Test fun unchangedRootRegistrationAndReadyReconcileDoNotShowAnotherScan() = runBlocking {
        withFixture { fixture ->
            fixture.file("saved.wav")
            fixture.observation.syncRoots(context, listOf(fixture.directory))
            fixture.barrier()
            assertEquals(listOf(true, false), fixture.transitions.toList())
            fixture.transitions.clear()

            repeat(3) { fixture.observation.syncRoots(context, listOf(fixture.directory)) }
            assertEquals(1, fixture.observation.reconcile(context, listOf(fixture.directory)))

            assertFalse(fixture.indexing.value)
            assertTrue("A ready root needs no new indexing work", fixture.transitions.isEmpty())
        }
    }

    private suspend fun assertFailedScanRestoresIdle(error: Exception) {
        withFixture(onRead = { throw error }) { fixture ->
            fixture.file("saved.wav")
            fixture.observation.syncRoots(context, listOf(fixture.directory))
            fixture.barrier()

            assertFalse(fixture.indexing.value)
            assertEquals(listOf(true, false), fixture.transitions.toList())
            assertTrue(fixture.repository.getIndexedTracks().isEmpty())
        }
    }

    private suspend fun withFixture(
        onRead: (BrowserFileMetadata) -> Unit = {},
        block: suspend (Fixture) -> Unit
    ) {
        val fixture = Fixture(context, onRead)
        try {
            block(fixture)
        } finally {
            fixture.observation.stop()
            fixture.repository.close()
            context.deleteDatabase(fixture.database)
            SharedFileCache.directories.invalidate(fixture.directory.path)
            fixture.directory.deleteRecursively()
        }
    }

    private class Fixture(context: Context, onRead: (BrowserFileMetadata) -> Unit) {
        val database = "library-indexing-state-${System.nanoTime()}.db"
        val directory = File(context.cacheDir, "library-indexing-state-${System.nanoTime()}").apply { check(mkdirs()) }
        val indexing = MutableStateFlow(false)
        val transitions = CopyOnWriteArrayList<Boolean>()
        val repository = MediaLibraryRepository.open(context, database,
            albumMetadataReader = { MediaAlbumMetadata() }) { metadata, _ ->
            onRead(metadata)
            MediaLibraryTrack(metadata.path, metadata.file.nameWithoutExtension)
        }
        val observation = MediaLibraryObservation(repository, onIndexingChanged = {
            indexing.value = it
            transitions += it
        })
        private val app = context

        fun file(name: String) = File(directory, name).apply { writeText("saved") }

        suspend fun barrier() {
            assertEquals(0, observation.reconcile(app, emptyList()))
        }
    }
}
