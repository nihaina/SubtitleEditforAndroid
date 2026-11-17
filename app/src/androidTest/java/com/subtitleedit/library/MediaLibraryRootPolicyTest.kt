package com.subtitleedit.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.util.FavoriteDirectoryStore
import com.subtitleedit.util.SharedFileCache
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLibraryRootPolicyTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun commonShortcutsDoNotIndexUntilExplicitlyPromotedToLibraryRoots() = runBlocking {
        withFixture { fixture ->
            val common = fixture.directory("common")
            val library = fixture.directory("library")
            val ordinarySong = fixture.song(common, "ordinary.wav")
            val librarySong = fixture.song(library, "library.wav")
            assertTrue(fixture.store.addCommon(common))
            fixture.store.addMediaLibrary(library)

            // Even a caller passing every shortcut cannot expand the configured library.
            fixture.observation.syncRoots(context, fixture.store.load().directories)
            fixture.barrier()
            assertEquals(setOf(librarySong.path), fixture.indexedPaths())
            assertFalse(common.path in fixture.coverage.get())
            assertEquals(1, fixture.reads.get())

            val beforeUpgrade = fixture.song(common, "before-upgrade.wav")
            delay(600)
            assertEquals(setOf(librarySong.path), fixture.indexedPaths())
            assertEquals(1, fixture.reads.get())

            fixture.store.addMediaLibrary(common)
            fixture.observation.syncRoots(context, fixture.store.load().directories)
            fixture.barrier()
            assertEquals(setOf(librarySong.path, ordinarySong.path, beforeUpgrade.path), fixture.indexedPaths())
            assertTrue(common.path in fixture.coverage.get())
            assertEquals(3, fixture.reads.get())

            val afterUpgrade = fixture.song(common, "after-upgrade.wav")
            await { afterUpgrade.path in fixture.indexedPaths() }
            assertEquals(4, fixture.reads.get())
        }
    }

    @Test fun startupPrunesMisindexedShortcutsAndOrphanTagsWithoutReparsingSurvivingLibrary() = runBlocking {
        withFixture { fixture ->
            val common = fixture.directory("common")
            val library = fixture.directory("library")
            val ordinarySong = fixture.song(common, "ordinary.wav")
            val orphan = fixture.song(common, "orphan.wav")
            val librarySong = fixture.song(library, "library.wav")
            fixture.repository.scanDirectoriesToLibrary(listOf(fixture.root))
            fixture.repository.toggleFavorite(ordinarySong.path)
            val playlist = requireNotNull(fixture.repository.createPlaylist("Saved"))
            fixture.repository.addTrackToPlaylist(playlist, ordinarySong.path)
            fixture.repository.recordPlaybackProgress(ordinarySong.path, "policy", 2_000L, 1_000L)
            fixture.repository.recordListeningInterval(ordinarySong.path, "policy", System.currentTimeMillis(), 500L)
            MediaLibraryDatabase(context, fixture.database).use {
                it.writableDatabase.delete("library_tracks", "path=?", arrayOf(orphan.path))
            }
            val favorites = fixture.repository.getFavorites()
            val playlistTracks = fixture.repository.getPlaylistTracks(playlist)
            val history = fixture.repository.getPlaybackHistory()
            val stats = fixture.repository.getAllDailyStats()
            val reads = fixture.reads.get()
            val metadata = requireNotNull(SharedFileCache.attributes.get(ordinarySong))
            fixture.allowReads.set(false)
            fixture.store.addCommon(common)
            fixture.store.addMediaLibrary(library)

            fixture.observation.syncRoots(context, fixture.store.load().directories)
            fixture.barrier()

            assertEquals(setOf(librarySong.path), fixture.indexedPaths())
            assertEquals(setOf(librarySong.path), fixture.albumMetadataPaths())
            assertEquals(reads, fixture.reads.get())
            assertEquals(favorites, fixture.repository.getFavorites())
            assertEquals(playlistTracks, fixture.repository.getPlaylistTracks(playlist))
            assertEquals(history, fixture.repository.getPlaybackHistory())
            assertEquals(stats, fixture.repository.getAllDailyStats())
            assertSame(metadata, SharedFileCache.attributes.peek(ordinarySong.path))
        }
    }

    @Test fun firstEmptyConfiguredRootSnapshotRemovesLegacyShortcutIndex() = runBlocking {
        withFixture { fixture ->
            val common = fixture.directory("common")
            fixture.song(common, "ordinary.wav")
            fixture.repository.scanDirectoriesToLibrary(listOf(common))
            fixture.store.addCommon(common)
            fixture.allowReads.set(false)

            fixture.observation.syncRoots(context, emptyList())
            fixture.barrier()

            assertTrue(fixture.indexedPaths().isEmpty())
            assertTrue(fixture.albumMetadataPaths().isEmpty())
            assertTrue(fixture.coverage.get().isEmpty())
            assertEquals(1, fixture.reads.get())
        }
    }

    @Test fun staleRootSnapshotsCannotKeepParentFilesOutsideARetainedNestedLibrary() = runBlocking {
        withFixture { fixture ->
            val parent = fixture.directory("parent")
            val child = File(parent, "child").apply { check(mkdir()) }
            val removed = fixture.song(parent, "removed.wav")
            val kept = fixture.song(child, "kept.wav")
            fixture.store.addMediaLibrary(parent)
            fixture.store.addMediaLibrary(child)
            val oldSnapshot = fixture.store.load().directories
            fixture.observation.syncRoots(context, oldSnapshot)
            fixture.barrier()
            assertEquals(setOf(removed.path, kept.path), fixture.indexedPaths())

            fixture.store.remove(parent)
            fixture.observation.syncRoots(context, oldSnapshot)
            fixture.barrier()
            assertEquals(setOf(kept.path), fixture.indexedPaths())
            assertEquals(setOf(kept.path), fixture.albumMetadataPaths())
            assertFalse(parent.path in fixture.coverage.get())
            val reads = fixture.reads.get()
            fixture.song(parent, "not-a-library-file.wav")
            delay(600)
            assertEquals(reads, fixture.reads.get())
            assertEquals(setOf(kept.path), fixture.indexedPaths())
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(context)
        try {
            block(fixture)
        } finally {
            fixture.observation.stop()
            fixture.repository.close()
            context.deleteDatabase(fixture.database)
            fixture.preferences.edit().clear().commit()
            SharedFileCache.directories.invalidate(fixture.root.path)
            fixture.root.deleteRecursively()
        }
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(6_000L) { while (!condition()) delay(30L) }
    }

    private class Fixture(private val context: Context) {
        private val id = System.nanoTime()
        val database = "library-root-policy-$id.db"
        val root = File(context.cacheDir, "library-root-policy-$id").apply { check(mkdirs()) }
        val preferences = context.getSharedPreferences("library-root-policy-$id", Context.MODE_PRIVATE)
        val store = FavoriteDirectoryStore(preferences)
        val reads = AtomicInteger()
        val allowReads = AtomicBoolean(true)
        val coverage = AtomicReference<Set<String>>(emptySet())
        val repository = MediaLibraryRepository.open(context, database,
            albumMetadataReader = {
                check(allowReads.get()) { "Surviving library tags must be reused" }
                MediaAlbumMetadata(composer = "Composer")
            }) { metadata, duration ->
            check(allowReads.get()) { "Common shortcut tags must not be read" }
            reads.incrementAndGet()
            MediaLibraryTrack(metadata.path, metadata.file.nameWithoutExtension, durationMs = duration)
        }
        val observation = MediaLibraryObservation(repository, onCoverageChanged = coverage::set,
            configuredRoots = { store.load().mediaLibraryPaths })

        fun directory(name: String) = File(root, name).apply { check(mkdirs()) }
        fun song(directory: File, name: String) = File(directory, name).apply { writeText("song") }
        fun indexedPaths(): Set<String> = repository.getIndexedTracks().mapTo(linkedSetOf()) { it.path }
        fun albumMetadataPaths(): Set<String> = MediaLibraryDatabase(context, database).use {
            MediaAlbumMetadataStore.read(it.readableDatabase).keys
        }
        suspend fun barrier() { assertEquals(0, observation.reconcile(context, emptyList())) }
    }
}
