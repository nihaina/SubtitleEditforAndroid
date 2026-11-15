package com.subtitleedit.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The default playlist and the player heart operate on the same existing collection. */
@RunWith(AndroidJUnit4::class)
class FavoritesPlaylistTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val favoritesId get() = MediaLibraryRepository.FAVORITES_PLAYLIST_ID

    private class Fixture(private val context: Context) : Closeable {
        val name = "favorites-playlist-${System.nanoTime()}.db"

        fun open(): MediaLibraryRepository = MediaLibraryRepository.open(context, name) { snapshot, duration ->
            val file = snapshot.file
            MediaLibraryTrack(file.absolutePath, file.nameWithoutExtension, durationMs = duration)
        }

        override fun close() {
            context.deleteDatabase(name)
        }
    }

    @Test fun defaultPlaylistExistsWhenEmptyAndCannotBeRenamedOrDeleted() = Fixture(context).use { fixture ->
        fixture.open().use { repository ->
            val default = repository.getPlaylists().single()
            assertEquals(favoritesId, default.id)
            assertEquals(context.getString(R.string.media_lib_tab_favorites), default.name)
            assertTrue(default.isSystem)
            assertEquals(0, default.itemCount)
            assertNull(default.firstTrackPath)
            assertTrue(repository.getPlaylistTracks(favoritesId).isEmpty())

            val revision = repository.revision.value
            assertFalse(repository.renamePlaylist(favoritesId, "Changed"))
            repository.deletePlaylist(favoritesId)
            assertEquals(revision, repository.revision.value)
            assertEquals(default, repository.getPlaylistById(favoritesId))

            val customId = requireNotNull(repository.createPlaylist("User playlist"))
            assertEquals(favoritesId, repository.getPlaylists().first().id)
            assertTrue(repository.renamePlaylist(customId, "Renamed user playlist"))
            repository.deletePlaylist(customId)
            assertNull(repository.getPlaylistById(customId))
            assertEquals(favoritesId, repository.getPlaylists().single().id)
        }
    }

    @Test fun existingFavoritesAppearWithoutMigrationOrMetadataReadsAndSurviveReopening() = Fixture(context).use { fixture ->
        MediaLibraryDatabase(context, fixture.name).use { helper ->
            helper.writableDatabase.execSQL(
                "INSERT INTO favorites(path,title,artist,album,duration_ms,added_at_ms) VALUES (?,?,?,?,?,?)",
                arrayOf<Any>("/existing-a.wav", "Existing A", "Artist", "Album", 12_000L, 100L),
            )
            helper.writableDatabase.execSQL(
                "INSERT INTO favorites(path,title,duration_ms,added_at_ms) VALUES (?,?,?,?)",
                arrayOf<Any>("/existing-b.mp4", "Existing B", 30_000L, 200L),
            )
        }

        repeat(2) {
            MediaLibraryRepository.open(context, fixture.name) { _, _ ->
                error("Existing favorites must not be parsed or copied into a new playlist")
            }.use { repository ->
                val default = repository.getPlaylists().single()
                assertEquals(favoritesId, default.id)
                assertEquals(2, default.itemCount)
                assertEquals("/existing-b.mp4", default.firstTrackPath)
                val tracks = repository.getPlaylistTracks(favoritesId)
                assertEquals(repository.getFavorites(), tracks)
                assertEquals(listOf("Existing B", "Existing A"), tracks.map { track -> track.title })
                assertEquals(12_000L, tracks.last().durationMs)
                assertEquals(setOf(favoritesId), repository.getPlaylistIdsContainingTrack("/existing-a.wav"))
                assertEquals(2, repository.searchLibrary("").size)
            }
        }
    }

    @Test fun addingAndRemovingDefaultPlaylistTracksKeepsTheFavoriteHeartInSync() = Fixture(context).use { fixture ->
        fixture.open().use { repository ->
            val customId = requireNotNull(repository.createPlaylist("Other playlist"))
            val path = "/shared.wav"
            assertEquals(2, repository.addTracksToPlaylists(listOf(favoritesId, favoritesId, customId), path, 15_000L))
            assertTrue(repository.isFavorite(path))
            assertEquals(setOf(favoritesId, customId), repository.getPlaylistIdsContainingTrack(path))
            assertEquals(15_000L, repository.getFavorites().single().durationMs)
            val revision = repository.revision.value
            assertFalse(repository.addTrackToPlaylist(favoritesId, path))
            assertEquals(0, repository.addTracksToPlaylists(listOf(favoritesId, customId), path))
            assertEquals(revision, repository.revision.value)
            assertTrue(repository.isFavorite(path))

            repository.removeTrackFromPlaylist(favoritesId, path)
            assertFalse(repository.isFavorite(path))
            assertTrue(repository.getPlaylistTracks(favoritesId).isEmpty())
            assertEquals(setOf(customId), repository.getPlaylistIdsContainingTrack(path))
            assertEquals(path, repository.getPlaylistTracks(customId).single().path)

            assertTrue(repository.toggleFavorite(path))
            assertEquals(path, repository.getPlaylistTracks(favoritesId).single().path)
            repository.removeFavorite(path)
            assertEquals(0, requireNotNull(repository.getPlaylistById(favoritesId)).itemCount)
            assertNull(requireNotNull(repository.getPlaylistById(favoritesId)).firstTrackPath)
        }
    }

    @Test fun concurrentDefaultPlaylistAddsNeverToggleOrDuplicateAFavorite() = Fixture(context).use { fixture ->
        fixture.open().use { repository ->
            val executor = Executors.newFixedThreadPool(4)
            try {
                val results = (0 until 8).map {
                    executor.submit<Boolean> { repository.addTrackToPlaylist(favoritesId, "/concurrent.wav") }
                }.map { it.get(10L, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it })
                assertTrue(repository.isFavorite("/concurrent.wav"))
                assertEquals(1, repository.getPlaylistTracks(favoritesId).size)
                assertEquals(1, requireNotNull(repository.getPlaylistById(favoritesId)).itemCount)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
