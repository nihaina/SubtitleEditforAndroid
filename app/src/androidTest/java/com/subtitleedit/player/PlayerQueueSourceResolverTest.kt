package com.subtitleedit.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.library.AlbumSortField
import com.subtitleedit.library.AlbumTrackSortField
import com.subtitleedit.library.AlbumTrackSortOptions
import com.subtitleedit.library.MediaAlbumMetadata
import com.subtitleedit.library.MediaCreditRole
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.SingerSortField
import com.subtitleedit.model.FileBrowserOrder
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import com.subtitleedit.ui.MediaAlbumPreferences
import com.subtitleedit.ui.MediaSingerPreferences
import com.subtitleedit.util.SettingsManager
import java.io.Closeable
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses disposable files/database and restores browser preferences after each scenario. */
@RunWith(AndroidJUnit4::class)
class PlayerQueueSourceResolverTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun directoriesFollowCurrentSortAndIgnoreEmptyNestedAndHiddenFiles() = Fixture(context).use { f ->
        val current = f.directory("a")
        f.file("a/current.wav")
        f.directory("b")
        f.file("b/sub/nested.wav")
        f.file("c/.hidden.wav")
        val first = f.file("d/a.wav")
        val second = f.file("d/z.mp4")
        f.file("d/readme.txt")
        val source = PlayerQueueSource(PlayerQueueSourceKind.DIRECTORY, current.path)
        val next = requireNotNull(f.resolver.next(source))
        assertEquals("d", next.title)
        assertEquals(listOf(first.path, second.path), next.tracks.map { it.path })
        assertTrue(next.tracks.last().isVideo)
        assertNull(f.resolver.next(next.source))

        f.settings.setFileSortDirection(FileSortDirection.DESCENDING)
        val previous = requireNotNull(f.resolver.next(next.source))
        assertEquals(current.path, previous.source.key)
    }

    @Test fun directoryResolutionReadsChangedHiddenSettings() = Fixture(context).use { f ->
        val current = f.directory("a")
        val hidden = f.file("b/.hidden.wav")
        val small = f.file("c/small.wav", size = 1)
        val large = f.file("c/large.wav", size = 20)
        val source = PlayerQueueSource(PlayerQueueSourceKind.DIRECTORY, current.path)
        assertEquals("c", f.resolver.next(source)?.title)
        f.settings.setShowHiddenFilesEnabled(true)
        assertEquals(listOf(hidden.path), f.resolver.next(source)?.tracks?.map { it.path })
        f.settings.setShowHiddenFilesEnabled(false)
        val selected = requireNotNull(f.resolver.next(source))
        assertEquals(listOf(large.path, small.path), selected.tracks.map { it.path })
    }

    @Test fun directoriesAndTheirTracksUseTheSavedSizeSort() = Fixture(context).use { f ->
        f.file("a/small.wav", size = 1)
        f.file("a/large.wav", size = 20)
        f.file("b/short.wav", size = 2)
        f.file("b/long.wav", size = 30)
        f.settings.setFileSortField(FileSortField.SIZE)
        f.settings.setFileSortDirection(FileSortDirection.DESCENDING)
        val directories = FileBrowserOrder.sort(
            listOf(File(f.root, "a"), File(f.root, "b")),
            FileSortField.SIZE, FileSortDirection.DESCENDING
        )
        val source = PlayerQueueSource(PlayerQueueSourceKind.DIRECTORY, directories.first().path)
        val next = requireNotNull(f.resolver.next(source))
        assertEquals(directories.last().path, next.source.key)
        assertEquals(
            directories.last().listFiles()!!.sortedByDescending { it.length() }.map { it.path },
            next.tracks.map { it.path }
        )
    }

    @Test fun directoriesWrapInBothDirectionsAndSkipUnavailableQueues() = Fixture(context).use { f ->
        val first = f.file("a/first.wav")
        f.file("b/unplayable.txt")
        val removed = f.file("c/missing.wav")
        check(removed.delete())
        val last = f.file("d/last.wav")
        val start = PlayerQueueSource(PlayerQueueSourceKind.DIRECTORY, first.parent!!)
        val end = start.copy(key = last.parent!!)
        assertNull(f.resolver.previous(start))
        assertNull(f.resolver.next(end))
        assertEquals(start, f.resolver.next(end, wrap = true)?.source)
        assertEquals(end, f.resolver.previous(start, wrap = true)?.source)
        assertEquals(end, f.resolver.next(start)?.source)
        assertEquals(start, f.resolver.previous(end)?.source)

        f.settings.setFileSortDirection(FileSortDirection.DESCENDING)
        assertEquals(end, f.resolver.next(start, wrap = true)?.source)
        assertEquals(start, f.resolver.previous(end, wrap = true)?.source)
        assertNull(f.resolver.next(start))
        assertNull(f.resolver.previous(end))
    }

    @Test fun wrappingDoesNotReloadTheOnlyPlayableQueueOrAnUnknownSource() = Fixture(context).use { f ->
        val first = f.file("a/first.wav")
        f.file("b/ignored.txt")
        val source = PlayerQueueSource(PlayerQueueSourceKind.DIRECTORY, first.parent!!)
        assertNull(f.resolver.next(source, wrap = true))
        assertNull(f.resolver.previous(source, wrap = true))
        assertNull(f.resolver.next(source.copy(key = File(f.root, "deleted").path), wrap = true))
        assertNull(f.resolver.previous(source.copy(key = File(f.root, "deleted").path), wrap = true))
    }

    @Test fun playlistsUseDisplayedOrderAndKeepTheirSavedTrackOrder() = Fixture(context).use { f ->
        val first = f.file("first.wav")
        val second = f.file("second.wav")
        val playable = requireNotNull(f.repository.createPlaylist("Playable"))
        f.repository.addTrackToPlaylist(playable, second.path)
        f.repository.addTrackToPlaylist(playable, first.path)
        val missing = requireNotNull(f.repository.createPlaylist("Missing"))
        f.repository.addTrackToPlaylist(missing, File(f.root, "missing.wav").path)
        f.repository.createPlaylist("Empty")
        val source = PlayerQueueSource(PlayerQueueSourceKind.PLAYLIST, "-1")
        val next = requireNotNull(f.resolver.next(source))
        assertEquals(playable.toString(), next.source.key)
        assertEquals(listOf(second.path, first.path), next.tracks.map { it.path })
        assertNull(f.resolver.next(next.source))
        assertNull(f.resolver.next(source.copy(key = "unknown")))
    }

    @Test fun playlistsWrapInDisplayedOrderWithoutSelectingEmptyQueues() = Fixture(context).use { f ->
        val first = f.file("first.wav")
        val last = f.file("last.wav")
        f.repository.toggleFavorite(first.path)
        val lastId = requireNotNull(f.repository.createPlaylist("Last"))
        f.repository.addTrackToPlaylist(lastId, last.path)
        f.repository.createPlaylist("Empty")
        val start = PlayerQueueSource(PlayerQueueSourceKind.PLAYLIST, "-1")
        val end = start.copy(key = lastId.toString())
        assertNull(f.resolver.previous(start))
        assertNull(f.resolver.next(end))
        assertEquals(start, f.resolver.next(end, wrap = true)?.source)
        assertEquals(end, f.resolver.previous(start, wrap = true)?.source)
        assertEquals(listOf(last.path), f.resolver.previous(start, wrap = true)?.tracks?.map { it.path })
    }

    @Test fun albumsUseRootBoundaryAndCurrentAlbumAndSongSorts() = Fixture(context).use { f ->
        val root = f.directory("catalog")
        f.track("catalog/a.wav", album = "A")
        val removed = f.track("catalog/b.wav", album = "B")
        val alpha = f.track("catalog/c-alpha.wav", album = "C", title = "Alpha")
        val zulu = f.track("catalog/c-zulu.wav", album = "C", title = "Zulu")
        f.track("catalog-other/foreign.wav", album = "AA")
        check(removed.delete())
        f.albumPreferences.save(f.albumPreferences.load().copy(
            groupTracks = false,
            trackSort = AlbumTrackSortOptions(AlbumTrackSortField.TITLE, ascending = false)
        ))
        val source = PlayerQueueSource(PlayerQueueSourceKind.ALBUM, "a", listOf(root.path))
        val next = requireNotNull(f.resolver.next(source))
        assertEquals("c", next.source.key)
        assertEquals(listOf(zulu.path, alpha.path), next.tracks.map { it.path })
        assertNull(f.resolver.next(next.source))

        f.albumPreferences.save(f.albumPreferences.load().copy(ascending = false))
        assertEquals("a", f.resolver.next(next.source)?.source?.key)
    }

    @Test fun albumGroupingUsesTheFirstSingerGroupShownInDetails() = Fixture(context).use { f ->
        f.track("a.wav", album = "A")
        val first = f.track("b-first.wav", album = "B", singer = "First", number = 1)
        f.track("b-other.wav", album = "B", singer = "Other", number = 2)
        f.albumPreferences.save(f.albumPreferences.load().copy(groupTracks = true))
        val next = f.resolver.next(PlayerQueueSource(PlayerQueueSourceKind.ALBUM, "a"))
        assertEquals(listOf(first.path), next?.tracks?.map { it.path })
    }

    @Test fun albumWrappingUsesCurrentSortAndKeepsTrackOrder() = Fixture(context).use { f ->
        f.track("a.wav", album = "A")
        val alpha = f.track("c-alpha.wav", album = "C", title = "Alpha")
        val zulu = f.track("c-zulu.wav", album = "C", title = "Zulu")
        val missing = f.track("b-missing.wav", album = "B")
        check(missing.delete())
        f.albumPreferences.save(f.albumPreferences.load().copy(
            groupTracks = false,
            trackSort = AlbumTrackSortOptions(AlbumTrackSortField.TITLE, ascending = false)
        ))
        val start = PlayerQueueSource(PlayerQueueSourceKind.ALBUM, "a")
        val end = start.copy(key = "c")
        assertNull(f.resolver.previous(start))
        assertNull(f.resolver.next(end))
        assertEquals(start, f.resolver.next(end, wrap = true)?.source)
        assertEquals(end, f.resolver.previous(start, wrap = true)?.source)
        assertEquals(listOf(zulu.path, alpha.path), f.resolver.previous(start, wrap = true)?.tracks?.map { it.path })
        f.albumPreferences.save(f.albumPreferences.load().copy(ascending = false))
        assertEquals(end, f.resolver.next(start, wrap = true)?.source)
        assertEquals(start, f.resolver.previous(end, wrap = true)?.source)
    }

    @Test fun eachCreditKindKeepsItsOwnCatalogAndSortPreference() = Fixture(context).use { f ->
        f.track("alpha.wav", singer = "Alpha", credit = "Alpha")
        val beta = f.track("beta.wav", singer = "Beta", credit = "Beta")
        for ((kind, role) in creditKinds) {
            val preferences = MediaSingerPreferences(context, role)
            preferences.save(preferences.load().copy(groupTracks = false, ascending = true))
            val source = PlayerQueueSource(kind, "alpha")
            val next = requireNotNull(f.resolver.next(source))
            assertEquals(kind, next.source.kind)
            assertEquals("beta", next.source.key)
            assertEquals(listOf(beta.path), next.tracks.map { it.path })
            assertNull(f.resolver.next(next.source))
            preferences.save(preferences.load().copy(ascending = false))
            assertEquals("alpha", f.resolver.next(next.source)?.source?.key)
        }
    }

    @Test fun creditGroupingUsesItsConfiguredAlbumOrder() = Fixture(context).use { f ->
        f.track("alpha.wav", singer = "Alpha")
        f.track("beta-a.wav", singer = "Beta", album = "A")
        val zulu = f.track("beta-z.wav", singer = "Beta", album = "Z")
        val preferences = MediaSingerPreferences(context)
        preferences.save(preferences.load().copy(groupTracks = true, albumAscending = false))
        val next = f.resolver.next(PlayerQueueSource(PlayerQueueSourceKind.SINGER, "alpha"))
        assertEquals(listOf(zulu.path), next?.tracks?.map { it.path })
    }

    @Test fun everyCreditKindWrapsBothDirectionsUsingItsOwnSavedSort() = Fixture(context).use { f ->
        val first = f.track("alpha.wav", singer = "Alpha", credit = "Alpha")
        val last = f.track("zulu.wav", singer = "Zulu", credit = "Zulu")
        val missing = f.track("missing.wav", singer = "Middle", credit = "Middle")
        check(missing.delete())
        for ((kind, role) in creditKinds) {
            val preferences = MediaSingerPreferences(context, role)
            preferences.save(preferences.load().copy(groupTracks = false, ascending = true))
            val start = PlayerQueueSource(kind, "alpha")
            val end = start.copy(key = "zulu")
            assertNull(f.resolver.previous(start))
            assertNull(f.resolver.next(end))
            assertEquals(start, f.resolver.next(end, wrap = true)?.source)
            assertEquals(end, f.resolver.previous(start, wrap = true)?.source)
            assertEquals(listOf(last.path), f.resolver.previous(start, wrap = true)?.tracks?.map { it.path })
            preferences.save(preferences.load().copy(ascending = false))
            assertEquals(end, f.resolver.next(start, wrap = true)?.source)
            assertEquals(listOf(first.path), f.resolver.previous(end, wrap = true)?.tracks?.map { it.path })
        }
    }

    @Test fun trackSortUsesPersistedListeningHistory() = Fixture(context).use { f ->
        f.track("a.wav", album = "A")
        val earlier = f.track("b-earlier.wav", album = "B")
        val later = f.track("b-later.wav", album = "B")
        f.repository.recordPlaybackProgress(earlier.path, "earlier", 10_000L, 1_000L, 1_000L)
        f.repository.recordPlaybackProgress(later.path, "later", 10_000L, 1_000L, 2_000L)
        f.albumPreferences.save(f.albumPreferences.load().copy(
            groupTracks = false,
            trackSort = AlbumTrackSortOptions(AlbumTrackSortField.LAST_PLAYED, ascending = false)
        ))
        val next = f.resolver.next(PlayerQueueSource(PlayerQueueSourceKind.ALBUM, "a"))
        assertEquals(listOf(later.path, earlier.path), next?.tracks?.map { it.path })
    }

    private class Fixture(private val context: Context) : Closeable {
        val root = File(context.cacheDir, "queue-source-${System.nanoTime()}").apply { check(mkdirs()) }
        val settings = SettingsManager.getInstance(context)
        val albumPreferences = MediaAlbumPreferences(context)
        private val oldAlbumOptions = albumPreferences.load()
        private val oldCreditOptions = MediaCreditRole.entries.associateWith { MediaSingerPreferences(context, it).load() }
        private val oldSort = settings.getFileSortField()
        private val oldDirection = settings.getFileSortDirection()
        private val oldHidden = settings.isShowHiddenFilesEnabled()
        private val tracks = mutableMapOf<String, MediaLibraryTrack>()
        private val tags = mutableMapOf<String, MediaAlbumMetadata>()
        private val databaseName = "queue-source-${System.nanoTime()}.db"
        val repository = MediaLibraryRepository.open(context, databaseName,
            albumMetadataReader = { tags[it.file.path] ?: MediaAlbumMetadata() },
            metadataReader = { item, _ -> tracks[item.file.path] ?: MediaLibraryTrack(item.file.path, item.file.name) }
        )
        val resolver = PlayerQueueSourceResolver(context, repository)

        init {
            settings.setFileSortField(FileSortField.NAME)
            settings.setFileSortDirection(FileSortDirection.ASCENDING)
            settings.setShowHiddenFilesEnabled(false)
            albumPreferences.save(oldAlbumOptions.copy(sortField = AlbumSortField.NAME, ascending = true))
            oldCreditOptions.forEach { (role, options) ->
                MediaSingerPreferences(context, role).save(options.copy(
                    sortField = SingerSortField.NAME,
                    ascending = true, albumSortField = AlbumSortField.NAME
                ))
            }
        }

        fun directory(path: String): File = File(root, path).apply { check(isDirectory || mkdirs()) }
        fun file(path: String, size: Int = 1): File = File(root, path).apply {
            check(parentFile!!.isDirectory || parentFile!!.mkdirs())
            writeBytes(ByteArray(size))
        }

        fun track(
            path: String,
            album: String = "Album",
            title: String = path,
            singer: String = "Singer",
            credit: String = singer,
            number: Int = 1
        ): File = file(path).also { file ->
            tracks[file.path] = MediaLibraryTrack(file.path, title, singer, album)
            tags[file.path] = MediaAlbumMetadata(
                albumArtist = credit, composer = credit, genre = credit, trackNumber = number.toString()
            )
            repository.indexTrack(file.path)
        }

        override fun close() {
            try {
                repository.close()
                context.deleteDatabase(databaseName)
                root.deleteRecursively()
            } finally {
                settings.setFileSortField(oldSort)
                settings.setFileSortDirection(oldDirection)
                settings.setShowHiddenFilesEnabled(oldHidden)
                albumPreferences.save(oldAlbumOptions)
                oldCreditOptions.forEach { (role, options) -> MediaSingerPreferences(context, role).save(options) }
            }
        }
    }

    private companion object {
        val creditKinds = listOf(
            PlayerQueueSourceKind.SINGER to MediaCreditRole.SINGER,
            PlayerQueueSourceKind.ARTIST to MediaCreditRole.ARTIST,
            PlayerQueueSourceKind.COMPOSER to MediaCreditRole.COMPOSER,
            PlayerQueueSourceKind.GENRE to MediaCreditRole.GENRE
        )
    }
}
