package com.subtitleedit.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Counting readers verify disk parsing is avoided, including after process-owned caches are lost. */
@RunWith(AndroidJUnit4::class)
class MediaAlbumMetadataIndexTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private class Fixture(private val context: Context) : Closeable {
        val name = "album-metadata-index-${System.nanoTime()}.db"
        val directory = File(context.cacheDir, "album-metadata-index-${System.nanoTime()}")
            .apply { check(mkdirs()) }

        fun file(name: String): File = File(directory, name).apply { writeBytes(byteArrayOf(0)) }

        fun open(reader: (File) -> MediaAlbumMetadata): MediaLibraryRepository =
            MediaLibraryRepository.open(context, name, albumMetadataReader = reader) { file, duration ->
                MediaLibraryTrack(
                    file.absolutePath,
                    "Song ${file.nameWithoutExtension}",
                    artist = "Singer",
                    album = "Album",
                    durationMs = duration,
                )
            }

        override fun close() {
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }

    @Test fun scansPersistClassificationForLibrariesLargerThanBothFormerMemoryCaches() =
        Fixture(context).use { fixture ->
            // Exceeds the former 128-entry snapshot and 2048-entry album LRU capacities.
            repeat(2_050) { fixture.file("$it.wav") }
            val reads = AtomicInteger()
            val tags = tags("Indexed")
            fixture.open { reads.incrementAndGet(); tags }.use { repository ->
                assertEquals(2_050, repository.scanDirectoriesToLibrary(listOf(fixture.directory)))
                assertEquals(2_050, reads.get())
                val indexed = repository.getIndexedTracks()
                assertEquals(2_050, repository.getCachedAlbumTracks(indexed).size)
                repeat(2) {
                    val catalog = repository.loadAlbumTracks(indexed)
                    assertEquals(2_050, catalog.size)
                    assertTrue(catalog.all { it.albumArtist == "Indexed artist" && it.genre == "Indexed genre" })
                }
                assertEquals("Classification must reuse the scan's persisted tags", 2_050, reads.get())
            }

            fixture.open { error("Reopening an indexed library must not parse media again") }.use { repository ->
                val catalog = repository.loadAlbumTracks(repository.getIndexedTracks())
                assertEquals(2_050, catalog.size)
                assertEquals("Indexed composer", catalog.first().composer)
                assertEquals(2024, catalog.first().year)
                assertEquals(2, catalog.first().trackNumber)
                assertEquals(1, catalog.first().discNumber)
            }
        }

    @Test fun changingOneFileOnlyReparsesItsClassificationAndPersistsTheNewStamp() =
        Fixture(context).use { fixture ->
            val changed = fixture.file("changed.wav")
            fixture.file("unchanged.wav")
            val reads = AtomicInteger()
            var label = "Before"
            fixture.open { reads.incrementAndGet(); tags(label) }.use { repository ->
                repository.scanDirectoriesToLibrary(listOf(fixture.directory))
                val indexed = repository.getIndexedTracks()
                reads.set(0)
                label = "After"
                // Changing length is reliable even on filesystems with coarse timestamp resolution.
                changed.appendBytes(byteArrayOf(1))
                val preview = repository.getCachedAlbumTracks(indexed)
                assertEquals(0, reads.get())
                assertEquals("Album", preview.first { it.track.path == changed.path }.track.album)

                val refreshed = repository.loadAlbumTracks(indexed)
                assertEquals(1, reads.get())
                assertEquals("After composer", refreshed.first { it.track.path == changed.path }.composer)
                assertEquals("Before composer", refreshed.first { it.track.path != changed.path }.composer)
                repository.loadAlbumTracks(indexed)
                assertEquals(1, reads.get())
            }

            fixture.open { error("Refreshed tags must survive reopening the database") }.use { repository ->
                val catalog = repository.loadAlbumTracks(repository.getIndexedTracks())
                assertEquals("After composer", catalog.first { it.track.path == changed.path }.composer)
            }
        }

    @Test fun versionTwoIndexDisplaysBaseTagsBeforeOneTimeClassificationBackfill() =
        Fixture(context).use { fixture ->
            val file = fixture.file("legacy.wav")
            val helper = MediaLibraryDatabase(context, fixture.name)
            try {
                val db = helper.writableDatabase
                db.execSQL(
                    "INSERT INTO library_tracks(path,title,artist,album,added_at_ms) VALUES (?,?,?,?,?)",
                    arrayOf(file.absolutePath, "Legacy song", "Legacy singer", "Legacy album", 1L),
                )
                db.execSQL("DROP TABLE album_metadata")
                db.version = 2
            } finally {
                helper.close()
            }

            val reads = AtomicInteger()
            fixture.open { reads.incrementAndGet(); tags("Backfilled") }.use { repository ->
                val indexed = repository.getIndexedTracks()
                val preview = repository.getCachedAlbumTracks(indexed).single()
                assertEquals(0, reads.get())
                assertEquals("Legacy song", preview.track.title)
                assertEquals("Legacy singer", preview.track.artist)
                assertEquals("Legacy album", preview.track.album)

                val enriched = repository.loadAlbumTracks(indexed).single()
                assertEquals(1, reads.get())
                assertEquals("Backfilled artist", enriched.albumArtist)
                assertEquals("Backfilled composer", enriched.composer)
                assertEquals("Backfilled genre", enriched.genre)
                assertEquals(2024, enriched.year)
                assertEquals(2, enriched.trackNumber)
                assertEquals(1, enriched.discNumber)
            }

            fixture.open { error("A migrated library must only backfill classification once") }.use { repository ->
                val cached = repository.getCachedAlbumTracks(repository.getIndexedTracks()).single()
                assertEquals("Legacy album", cached.track.album)
                assertEquals("Backfilled composer", cached.composer)
                assertEquals("Backfilled genre", repository.loadAlbumTracks(repository.getIndexedTracks()).single().genre)
            }
        }

    @Test fun listeningAndCollectionChangesDoNotInvalidateTheCatalogWhileReindexingDoes() =
        Fixture(context).use { fixture ->
            val file = fixture.file("song.wav")
            var label = "Before"
            fixture.open { tags(label) }.use { repository ->
                repository.indexTrack(file.path)
                val catalogRevision = repository.catalogRevision.value
                val revision = repository.revision.value
                repository.toggleFavorite(file.path)
                repository.recordPlaybackProgress(file.path, "catalog-revision-run", 10_000L, 1_000L)
                repository.recordListeningInterval(file.path, "catalog-revision-run", 10_000L, 500L)
                assertTrue(repository.revision.value > revision)
                assertEquals(catalogRevision, repository.catalogRevision.value)

                label = "After"
                repository.indexTrack(file.path)
                assertTrue(repository.catalogRevision.value > catalogRevision)
                val catalog = repository.loadAlbumTracks(repository.getIndexedTracks()).single()
                assertEquals("After artist", catalog.albumArtist)
                assertEquals("After composer", catalog.composer)
            }
        }

    @Test fun filesChangedDuringParsingAreNotCachedWithTheirNewStamp() =
        Fixture(context).use { fixture ->
            val file = fixture.file("replaced.wav")
            val reads = AtomicInteger()
            fixture.open {
                when (reads.incrementAndGet()) {
                    1, 2 -> {
                        // Simulate a tag editor replacing the file while a reader still has old tags.
                        file.appendBytes(byteArrayOf(1))
                        tags("Old")
                    }
                    else -> tags("Current")
                }
            }.use { repository ->
                repository.indexTrack(file.path)
                val indexed = repository.getIndexedTracks()
                assertEquals("", repository.getCachedAlbumTracks(indexed).single().composer)
                repository.loadAlbumTracks(indexed)
                assertEquals("", repository.getCachedAlbumTracks(indexed).single().composer)
                assertEquals("Current composer", repository.loadAlbumTracks(indexed).single().composer)
                repository.loadAlbumTracks(indexed)
                assertEquals(3, reads.get())
            }
        }

    private fun tags(label: String) = MediaAlbumMetadata(
        albumArtist = "$label artist",
        composer = "$label composer",
        year = "2024-02-03",
        trackNumber = "2/12",
        discNumber = "1/2",
        genre = "$label genre",
    )
}
