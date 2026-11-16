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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLibraryScanCancellationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun removingTheOnlyRootStopsBeforeReadingTheRemainingSongsAndClearsBusyState() = runBlocking {
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        withFixture(onRead = {
            if (firstRead.getAndSet(false)) awaitRelease(parsing, release)
        }) { fixture ->
            val removed = fixture.directory("removed")
            repeat(12) { fixture.song(removed, "song-$it.wav") }
            try {
                fixture.observation.syncRoots(context, listOf(removed))
                assertTrue("The scan reaches its first song", parsing.await(5, TimeUnit.SECONDS))
                assertTrue(fixture.indexing.value)

                fixture.observation.removeDirectory(context, removed, emptyList())
            } finally {
                release.countDown()
            }
            fixture.barrier()

            assertEquals("Removal must stop the directory traversal after the current read", 1, fixture.reads.size)
            assertTrue(fixture.repository.getIndexedTracks().isEmpty())
            assertFalse("No remaining roots means there is no indexing left to display", fixture.indexing.value)
            assertEquals(12, removed.listFiles()!!.size)
        }
    }

    @Test fun removingOneRootResumesTheOtherIncompleteRootAndKeepsItsBusyState() = runBlocking {
        val parsingRemoved = CountDownLatch(1)
        val releaseRemoved = CountDownLatch(1)
        val parsingKept = CountDownLatch(1)
        val releaseKept = CountDownLatch(1)
        val firstRemoved = AtomicBoolean(true)
        val firstKept = AtomicBoolean(true)
        withFixture(onRead = { metadata ->
            when (metadata.file.parentFile!!.name) {
                "removed" -> if (firstRemoved.getAndSet(false)) awaitRelease(parsingRemoved, releaseRemoved)
                "kept" -> if (firstKept.getAndSet(false)) awaitRelease(parsingKept, releaseKept)
            }
        }) { fixture ->
            val removed = fixture.directory("removed")
            val kept = fixture.directory("kept")
            repeat(12) { fixture.song(removed, "removed-$it.wav") }
            val expected = (1..5).map { fixture.song(kept, "kept-$it.wav").path }.toSet()
            try {
                fixture.observation.syncRoots(context, listOf(removed, kept))
                assertTrue(parsingRemoved.await(5, TimeUnit.SECONDS))
                fixture.observation.removeDirectory(context, removed, listOf(kept))
                releaseRemoved.countDown()

                assertTrue("The retained root resumes instead of being abandoned", parsingKept.await(5, TimeUnit.SECONDS))
                assertTrue("Actual remaining indexing still shows as busy", fixture.indexing.value)
            } finally {
                releaseRemoved.countDown()
                releaseKept.countDown()
            }
            fixture.barrier()

            assertEquals(1, fixture.reads.count { File(it).parentFile == removed })
            assertEquals(expected, fixture.repository.getIndexedTracks().map { it.path }.toSet())
            assertFalse(fixture.indexing.value)
        }
    }

    @Test fun aQueuedManualScanIsCancelledInsteadOfRecreatingTheRemovedRoot() = runBlocking {
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        withFixture(onRead = {
            if (firstRead.getAndSet(false)) awaitRelease(parsing, release)
        }) { fixture ->
            val removed = fixture.directory("removed")
            repeat(12) { fixture.song(removed, "song-$it.wav") }
            supervisorScope {
                fixture.observation.syncRoots(context, listOf(removed))
                try {
                    assertTrue(parsing.await(5, TimeUnit.SECONDS))
                    // UNDISPATCHED makes sure reconcile is queued before the removal.
                    val queued = async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { fixture.observation.reconcile(context, listOf(removed)) }
                    }
                    fixture.observation.removeDirectory(context, removed, emptyList())
                    release.countDown()

                    assertTrue("Cancellation must reach the manual scan caller, which owns its pending spinner",
                        queued.await().exceptionOrNull() is CancellationException)
                } finally {
                    release.countDown()
                }
            }
            fixture.barrier()

            assertEquals(1, fixture.reads.size)
            assertTrue(fixture.repository.getIndexedTracks().isEmpty())
            assertFalse(fixture.indexing.value)
        }
    }

    @Test fun removingAParentResumesItsRetainedChildWithoutIndexingTheOtherSongs() = runBlocking {
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        withFixture(onRead = {
            if (firstRead.getAndSet(false)) awaitRelease(parsing, release)
        }) { fixture ->
            val parent = fixture.directory("parent")
            repeat(12) { fixture.song(parent, "removed-$it.wav") }
            val child = File(parent, "child").apply { check(mkdir()) }
            val expected = (1..5).map { fixture.song(child, "kept-$it.wav").path }.toSet()
            try {
                fixture.observation.syncRoots(context, listOf(parent, child))
                assertTrue(parsing.await(5, TimeUnit.SECONDS))
                fixture.observation.removeDirectory(context, parent, listOf(child))
            } finally {
                release.countDown()
            }
            fixture.barrier()

            assertEquals(1, fixture.reads.count { File(it).parentFile == parent })
            assertEquals(expected, fixture.repository.getIndexedTracks().map { it.path }.toSet())
            assertFalse(fixture.indexing.value)
        }
    }

    private fun awaitRelease(started: CountDownLatch, release: CountDownLatch) {
        started.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "The test did not release its metadata reader" }
    }

    private suspend fun withFixture(
        onRead: (BrowserFileMetadata) -> Unit,
        block: suspend (Fixture) -> Unit
    ) {
        val fixture = Fixture(context, onRead)
        try {
            block(fixture)
        } finally {
            fixture.observation.stop()
            fixture.repository.close()
            context.deleteDatabase(fixture.database)
            SharedFileCache.directories.invalidate(fixture.root.path)
            fixture.root.deleteRecursively()
        }
    }

    private class Fixture(context: Context, onRead: (BrowserFileMetadata) -> Unit) {
        val database = "library-scan-cancel-${System.nanoTime()}.db"
        val root = File(context.cacheDir, "library-scan-cancel-${System.nanoTime()}").apply { check(mkdirs()) }
        val indexing = MutableStateFlow(false)
        val reads = CopyOnWriteArrayList<String>()
        val repository = MediaLibraryRepository.open(context, database,
            albumMetadataReader = { MediaAlbumMetadata() }) { metadata, _ ->
            reads += metadata.path
            onRead(metadata)
            MediaLibraryTrack(metadata.path, metadata.file.nameWithoutExtension)
        }
        val observation = MediaLibraryObservation(repository, onIndexingChanged = { indexing.value = it })
        private val app = context

        fun directory(name: String) = File(root, name).apply { check(mkdir()) }
        fun song(directory: File, name: String) = File(directory, name).apply { writeText("saved") }

        suspend fun barrier() {
            assertEquals(0, observation.reconcile(app, emptyList()))
        }
    }
}
