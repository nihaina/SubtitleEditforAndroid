package com.subtitleedit.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.util.SharedFileCache
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLibraryObservationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun recursiveFileEventsUpdateOnlyChangedMediaAndDiscoverNewFolders() = runBlocking {
        val directory = File(context.cacheDir, "library-observe-${System.nanoTime()}").apply { mkdirs() }
        val database = "library-observe-${System.nanoTime()}.db"
        val original = File(directory, "original.wav").apply { writeText("one") }
        val keep = File(directory, "keep.wav").apply { writeText("keep") }
        val reads = AtomicInteger()
        var title = "Before"
        val repository = MediaLibraryRepository.open(context, database,
            albumMetadataReader = { MediaAlbumMetadata(composer = "Composer") }) { metadata, _ ->
            reads.incrementAndGet()
            MediaLibraryTrack(metadata.path, title)
        }
        val observation = MediaLibraryObservation(repository)
        try {
            observation.syncRoots(context, listOf(directory))
            await { repository.getIndexedTracks().size == 2 }
            assertEquals(2, reads.get())
            reads.set(0)
            val stamp = original.lastModified()
            title = "After"
            original.writeText("two")
            check(original.setLastModified(stamp))
            await { repository.getIndexedTracks().any { it.path == original.path && it.title == "After" } }
            assertEquals(1, reads.get())
            assertEquals("Before", repository.getIndexedTracks().single { it.path == keep.path }.title)
            val nested = File(directory, "new-folder").apply { mkdir() }
            val added = File(nested, "added.wav").apply { writeText("added") }
            await { repository.getIndexedTracks().any { it.path == added.path } }
            added.delete()
            await { repository.getIndexedTracks().none { it.path == added.path } }
        } finally {
            observation.stop()
            repository.close()
            context.deleteDatabase(database)
            SharedFileCache.directories.invalidate(directory.path)
            directory.deleteRecursively()
        }
    }

    @Test fun unchangedRootRegistrationAndPageReentryDoNotRescanOrRebuildCatalog() = runBlocking {
        val directory = File(context.cacheDir, "library-reentry-${System.nanoTime()}").apply { mkdirs() }
        val database = "library-reentry-${System.nanoTime()}.db"
        File(directory, "saved.wav").writeText("saved")
        val reads = AtomicInteger()
        val repository = MediaLibraryRepository.open(context, database,
            albumMetadataReader = { MediaAlbumMetadata() }) { metadata, _ ->
            reads.incrementAndGet()
            MediaLibraryTrack(metadata.path, "Saved")
        }
        val observation = MediaLibraryObservation(repository)
        try {
            observation.syncRoots(context, listOf(directory))
            await { repository.getIndexedTracks().size == 1 }
            val revision = repository.catalogRevision.value
            observation.syncRoots(context, emptyList())
            assertEquals(0, observation.reconcile(context, emptyList()))
            assertEquals("A temporary empty navigation snapshot must not delete persisted tags", 1,
                repository.getIndexedTracks().size)
            repeat(4) { observation.syncRoots(context, listOf(File(directory.path))) }
            delay(400)
            assertEquals(1, reads.get())
            assertEquals(revision, repository.catalogRevision.value)
        } finally {
            observation.stop()
            repository.close()
            context.deleteDatabase(database)
            SharedFileCache.directories.invalidate(directory.path)
            directory.deleteRecursively()
        }
    }

    @Test fun eventsFromRemovedRootsCannotUpdateTheCurrentLibrary() = runBlocking {
        val directory = File(context.cacheDir, "library-roots-${System.nanoTime()}").apply { mkdirs() }
        val first = File(directory, "first").apply { mkdir() }
        val second = File(directory, "second").apply { mkdir() }
        val old = File(first, "old.wav").apply { writeText("old") }
        val current = File(second, "current.wav").apply { writeText("current") }
        val database = "library-roots-${System.nanoTime()}.db"
        val reads = AtomicInteger()
        val repository = MediaLibraryRepository.open(context, database,
            albumMetadataReader = { MediaAlbumMetadata() }) { metadata, _ ->
            reads.incrementAndGet()
            MediaLibraryTrack(metadata.path, "Title")
        }
        val observation = MediaLibraryObservation(repository)
        try {
            observation.syncRoots(context, listOf(first))
            await { repository.getIndexedTracks().any { it.path == old.path } }
            observation.removeDirectory(context, first, listOf(second))
            observation.syncRoots(context, listOf(second))
            await { repository.getIndexedTracks().any { it.path == current.path } }
            reads.set(0)
            old.writeText("no longer registered")
            delay(600)
            assertEquals(0, reads.get())
            assertTrue(repository.getIndexedTracks().none { it.path == old.path })
            assertTrue(repository.getIndexedTracks().any { it.path == current.path })
        } finally {
            observation.stop()
            repository.close()
            context.deleteDatabase(database)
            SharedFileCache.directories.invalidate(directory.path)
            directory.deleteRecursively()
        }
    }

    @Test fun explicitRemovalCleansPersistedIndexBeforeAnyRootsAreObservedWithoutClearingSharedMetadata() = runBlocking {
        val directory = File(context.cacheDir, "library-unobserved-removal-${System.nanoTime()}").apply { mkdirs() }
        val source = File(directory, "saved.wav").apply { writeText("saved") }
        val database = "library-unobserved-removal-${System.nanoTime()}.db"
        val repository = MediaLibraryRepository.open(context, database,
            albumMetadataReader = { MediaAlbumMetadata(composer = "Composer") }) { metadata, _ ->
            MediaLibraryTrack(metadata.path, "Saved")
        }
        val observation = MediaLibraryObservation(repository)
        try {
            repository.scanDirectoriesToLibrary(listOf(directory))
            val metadata = requireNotNull(SharedFileCache.attributes.peek(source.path))
            assertEquals(listOf(source.path), repository.getIndexedTracks().map { it.path })

            observation.removeDirectory(context, directory, emptyList())
            // The empty reconcile acts as a queue barrier without adding a root or scanning.
            assertEquals(0, observation.reconcile(context, emptyList()))

            assertTrue(repository.getIndexedTracks().isEmpty())
            assertSame("Removing library-only data keeps the shared file snapshot", metadata,
                SharedFileCache.attributes.peek(source.path))
            assertTrue(source.isFile)
        } finally {
            observation.stop()
            repository.close()
            context.deleteDatabase(database)
            SharedFileCache.directories.invalidate(directory.path)
            directory.deleteRecursively()
        }
    }

    @Test fun removalQueuedDuringScanRunsAfterItsWritesAndLeavesNoIndexBehind() = runBlocking {
        val directory = File(context.cacheDir, "library-scanning-removal-${System.nanoTime()}").apply { mkdirs() }
        File(directory, "saved.wav").writeText("saved")
        val database = "library-scanning-removal-${System.nanoTime()}.db"
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val repository = MediaLibraryRepository.open(context, database,
            albumMetadataReader = { MediaAlbumMetadata() }) { metadata, _ ->
            reads.incrementAndGet()
            parsing.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Test did not release the metadata reader" }
            MediaLibraryTrack(metadata.path, "Saved")
        }
        val observation = MediaLibraryObservation(repository)
        try {
            observation.syncRoots(context, listOf(directory))
            assertTrue("Scan reaches its metadata read", parsing.await(5, TimeUnit.SECONDS))
            observation.removeDirectory(context, directory, emptyList())
            release.countDown()
            assertEquals(0, observation.reconcile(context, emptyList()))

            assertEquals(1, reads.get())
            assertTrue("The completed scan cannot leave removed-directory index rows", repository.getIndexedTracks().isEmpty())
        } finally {
            release.countDown()
            observation.stop()
            repository.close()
            context.deleteDatabase(database)
            SharedFileCache.directories.invalidate(directory.path)
            directory.deleteRecursively()
        }
    }

    @Test fun stalePickerReconcileCannotRestoreARemovedConfiguredRoot() = runBlocking {
        val directory = File(context.cacheDir, "library-stale-picker-${System.nanoTime()}").apply { mkdirs() }
        File(directory, "saved.wav").writeText("saved")
        val database = "library-stale-picker-${System.nanoTime()}.db"
        val configured = AtomicReference(setOf(directory.path))
        val reads = AtomicInteger()
        val repository = MediaLibraryRepository.open(context, database,
            albumMetadataReader = { MediaAlbumMetadata() }) { metadata, _ ->
            reads.incrementAndGet()
            MediaLibraryTrack(metadata.path, "Saved")
        }
        val observation = MediaLibraryObservation(repository, configuredRoots = configured::get)
        try {
            assertEquals(1, observation.reconcile(context, listOf(directory)))
            configured.set(emptySet())
            observation.removeDirectory(context, directory, emptyList())

            try {
                observation.reconcile(context, listOf(directory))
                org.junit.Assert.fail("A picker result received after removal must be cancelled")
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Cancellation also prevents the manual scan entry from reporting success.
            }
            assertEquals(1, reads.get())
            assertTrue(repository.getIndexedTracks().isEmpty())
        } finally {
            observation.stop()
            repository.close()
            context.deleteDatabase(database)
            SharedFileCache.directories.invalidate(directory.path)
            directory.deleteRecursively()
        }
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(6000) { while (!condition()) delay(30) }
    }
}
