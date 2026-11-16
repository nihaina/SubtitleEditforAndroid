package com.subtitleedit.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.util.SharedFileCache
import java.io.Closeable
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLibraryDirectoryRemovalTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun removalClearsOnlyItsPersistentCatalogAndPreservesCollectionsAndSharedCaches() =
        Fixture(context).use { fixture ->
            val removed = fixture.file("removed/track.wav")
            val sibling = fixture.file("removed-other/keep.wav")
            val repository = fixture.repository
            repository.scanDirectoriesToLibrary(listOf(fixture.directory))
            repository.toggleFavorite(removed.path)
            val playlist = requireNotNull(repository.createPlaylist("Saved"))
            repository.addTrackToPlaylist(playlist, removed.path)
            repository.saveCurrentQueue(listOf(removed.path, sibling.path), 0)
            repository.recordPlaybackProgress(removed.path, "removal", 2_000L, 1_000L)
            repository.recordListeningInterval(removed.path, "removal", System.currentTimeMillis(), 500L)
            val favorites = repository.getFavorites()
            val playlistTracks = repository.getPlaylistTracks(playlist)
            val history = repository.getPlaybackHistory()
            val queue = repository.getCurrentQueue()
            val stats = repository.getAllDailyStats()
            val metadata = requireNotNull(SharedFileCache.attributes.get(removed))
            SharedMediaCache.seedDuration(metadata, 19_999L)
            val revision = repository.catalogRevision.value

            assertEquals(1, repository.removeDirectoryFromLibrary(removed.parentFile!!, emptyList()))

            assertEquals(listOf(sibling.path), repository.getIndexedTracks().map { it.path })
            assertEquals(setOf(sibling.path), fixture.albumMetadataPaths())
            assertTrue(repository.catalogRevision.value > revision)
            assertEquals(favorites, repository.getFavorites())
            assertEquals(playlistTracks, repository.getPlaylistTracks(playlist))
            assertEquals(history, repository.getPlaybackHistory())
            assertEquals(queue, repository.getCurrentQueue())
            assertEquals(stats, repository.getAllDailyStats())
            assertTrue(removed.isFile)
            assertSame(metadata, SharedFileCache.attributes.peek(removed.path))
            assertEquals(19_999L, SharedMediaCache.preview(metadata, 48, allowVideoFrame = false).durationMs)
            val after = repository.catalogRevision.value
            assertEquals(0, repository.removeDirectoryFromLibrary(removed.parentFile!!, emptyList()))
            assertEquals(after, repository.catalogRevision.value)
        }

    @Test fun retainedChildRootsKeepTheirCatalogAndSamePrefixSiblingsRemainUntouched() =
        Fixture(context).use { fixture ->
            val removed = fixture.file("music_%/remove.wav")
            val retained = fixture.file("music_%/saved/keep.wav")
            val sibling = fixture.file("music_%_other/sibling.wav")
            fixture.repository.scanDirectoriesToLibrary(listOf(fixture.directory))

            assertEquals(1, fixture.repository.removeDirectoryFromLibrary(removed.parentFile!!, listOf(retained.parentFile!!)))

            val expected = setOf(retained.path, sibling.path)
            assertEquals(expected, fixture.repository.getIndexedTracks().map { it.path }.toSet())
            assertEquals(expected, fixture.albumMetadataPaths())
        }

    @Test fun aRetainedAncestorContinuesToOwnTheRemovedChildCatalog() =
        Fixture(context).use { fixture ->
            val child = fixture.file("parent/child/song.wav")
            fixture.repository.scanDirectoriesToLibrary(listOf(fixture.directory))
            val revision = fixture.repository.catalogRevision.value

            assertEquals(0, fixture.repository.removeDirectoryFromLibrary(child.parentFile!!, listOf(fixture.directory)))

            assertEquals(listOf(child.path), fixture.repository.getIndexedTracks().map { it.path })
            assertEquals(setOf(child.path), fixture.albumMetadataPaths())
            assertEquals(revision, fixture.repository.catalogRevision.value)
        }

    @Test fun orphanClassificationRowsAreRemovedEvenWithoutAnIndexedTrack() =
        Fixture(context).use { fixture ->
            val orphan = fixture.file("removed/orphan.wav")
            val sibling = fixture.file("removed-other/keep.wav")
            fixture.repository.scanDirectoriesToLibrary(listOf(fixture.directory))
            MediaLibraryDatabase(context, fixture.databaseName).use { helper ->
                helper.writableDatabase.delete("library_tracks", "path=?", arrayOf(orphan.path))
            }

            assertEquals(1, fixture.repository.removeDirectoryFromLibrary(orphan.parentFile!!, emptyList()))
            assertEquals(setOf(sibling.path), fixture.albumMetadataPaths())
        }

    @Test fun readdingAnUnchangedDirectoryReadsFreshClassificationAfterRemoval() =
        Fixture(context).use { fixture ->
            val song = fixture.file("removed/song.wav")
            fixture.repository.scanDirectoriesToLibrary(listOf(song.parentFile!!))
            assertEquals(1, fixture.classificationReads)
            assertEquals("Before", fixture.repository.getPersistedAlbumTracks(fixture.repository.getIndexedTracks()).single().composer)
            fixture.repository.removeDirectoryFromLibrary(song.parentFile!!, emptyList())
            fixture.composer = "After"

            fixture.repository.scanDirectoriesToLibrary(listOf(song.parentFile!!))

            assertEquals(2, fixture.classificationReads)
            assertEquals("After", fixture.repository.getPersistedAlbumTracks(fixture.repository.getIndexedTracks()).single().composer)
        }

    private class Fixture(private val context: Context) : Closeable {
        val databaseName = "library-removal-${System.nanoTime()}.db"
        val directory = File(context.cacheDir, "library-removal-${System.nanoTime()}").apply { check(mkdirs()) }
        var composer = "Before"
        var classificationReads = 0
        val repository = MediaLibraryRepository.open(context, databaseName,
            albumMetadataReader = { classificationReads++; MediaAlbumMetadata(composer = composer) }) { metadata, duration ->
            MediaLibraryTrack(metadata.path, metadata.file.nameWithoutExtension, durationMs = duration)
        }

        fun file(relative: String) = File(directory, relative).apply {
            check(parentFile!!.mkdirs() || parentFile!!.isDirectory)
            writeBytes(byteArrayOf(0))
        }

        fun albumMetadataPaths(): Set<String> = MediaLibraryDatabase(context, databaseName).use { helper ->
            MediaAlbumMetadataStore.read(helper.readableDatabase).keys
        }

        override fun close() {
            repository.close()
            context.deleteDatabase(databaseName)
            SharedFileCache.directories.invalidate(directory.path)
            directory.deleteRecursively()
        }
    }
}
