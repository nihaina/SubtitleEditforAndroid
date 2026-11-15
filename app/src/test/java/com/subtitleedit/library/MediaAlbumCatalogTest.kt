package com.subtitleedit.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAlbumCatalogTest {
    @Test fun groupingUsesTrimmedCaseInsensitiveAlbumTagsAndDeduplicatesPaths() {
        val first = track("/a.flac", " Album ")
        val second = track("/b.mp3", "album")
        val albums = buildMediaAlbums(listOf(first, second, first.copy(year = 2025)))

        assertEquals(1, albums.size)
        assertEquals("Album", albums.single().name)
        assertEquals("album", albums.single().key)
        assertEquals(listOf("/a.flac", "/b.mp3"), albums.single().tracks.map { it.track.path })
    }

    @Test fun blankAndUnknownAlbumTagsShareOneUnknownGroup() {
        val albums = buildMediaAlbums(listOf(track("/a", ""), track("/b", "  "), track("/c", "<unknown>")))

        assertEquals(1, albums.size)
        assertEquals("", albums.single().name)
        assertEquals("", albums.single().key)
        assertEquals(3, albums.single().tracks.size)
    }

    @Test fun aggregateUsesUniqueTracksNonnegativeDurationsEarliestYearAndLatestFileModification() {
        val first = track("/a", "Album", duration = 50L, artist = "Singer", modified = 100L, year = 2023)
        val second = track("/b", "Album", duration = -1L, artist = "Other", modified = 500L, year = 2024)
            .copy(albumArtist = "Album artist")
        val album = buildMediaAlbums(listOf(first, second, first)).single()

        assertEquals(50L, album.durationMs)
        assertEquals(2023, album.year)
        assertEquals(500L, album.updatedAtMs)
        assertEquals("Album artist / Singer", album.artist)
        assertEquals(listOf("/a", "/b"), album.artworkPaths)
    }

    @Test fun songsSortByDiscThenTrackNumberAndUseTitleAsStableFallback() {
        val tracks = listOf(
            track("/d", "Album", title = "Fourth").copy(discNumber = 2, trackNumber = 1),
            track("/c", "Album", title = "Third").copy(trackNumber = 3),
            track("/a", "Album", title = "First").copy(discNumber = 1, trackNumber = 1),
            track("/b", "Album", title = "Second").copy(trackNumber = 2)
        )

        assertEquals(listOf("/a", "/b", "/c", "/d"), buildMediaAlbums(tracks).single().tracks.map { it.track.path })
    }

    @Test fun searchMatchesAlbumNameWithoutCaseOrSurroundingWhitespace() {
        val albums = buildMediaAlbums(listOf(track("/a", "Blue Sky"), track("/b", "Green")))

        assertEquals(listOf("Blue Sky"), filterAndSortMediaAlbums(albums, " SKY ", AlbumSortField.NAME, true).map { it.name })
        assertTrue(filterAndSortMediaAlbums(albums, "missing", AlbumSortField.NAME, true).isEmpty())
        assertEquals(2, filterAndSortMediaAlbums(albums, " ", AlbumSortField.NAME, true).size)
    }

    @Test fun allSixSortFieldsRespectDirection() {
        val albums = buildMediaAlbums(
            listOf(
                track("/b1", "Beta", duration = 20L, artist = "Bravo", modified = 200L, year = 2020),
                track("/b2", "Beta", duration = 20L, artist = "Bravo", modified = 200L, year = 2020),
                track("/a", "Alpha", duration = 10L, artist = "Alpha", modified = 100L, year = 2010)
            )
        )
        for (field in AlbumSortField.entries) {
            assertEquals("Ascending $field", listOf("Alpha", "Beta"), filterAndSortMediaAlbums(albums, "", field, true).map { it.name })
            assertEquals("Descending $field", listOf("Beta", "Alpha"), filterAndSortMediaAlbums(albums, "", field, false).map { it.name })
        }
    }

    @Test fun unknownYearRemainsLastInEitherDirection() {
        val albums = buildMediaAlbums(listOf(track("/u", "Unknown"), track("/a", "Older", year = 2000), track("/b", "Newer", year = 2020)))

        assertEquals(listOf("Older", "Newer", "Unknown"), filterAndSortMediaAlbums(albums, "", AlbumSortField.YEAR, true).map { it.name })
        assertEquals(listOf("Newer", "Older", "Unknown"), filterAndSortMediaAlbums(albums, "", AlbumSortField.YEAR, false).map { it.name })
    }

    @Test fun singerSortUsesPerformerRatherThanAlbumArtist() {
        val albums = buildMediaAlbums(listOf(
            track("/a", "One", artist = "Alpha").copy(albumArtist = "Zulu"),
            track("/b", "Two", artist = "Zulu").copy(albumArtist = "Alpha")
        ))
        assertEquals(listOf("One", "Two"), filterAndSortMediaAlbums(albums, "", AlbumSortField.ARTIST, true).map { it.name })
        assertEquals(listOf("Two", "One"), filterAndSortMediaAlbums(albums, "", AlbumSortField.ARTIST, false).map { it.name })
    }

    @Test fun sortingTiesAlwaysUseStableAlbumNameOrder() {
        val albums = buildMediaAlbums(listOf(track("/z", "Zulu"), track("/a", "Alpha"), track("/b", "Bravo")))

        assertEquals(listOf("Alpha", "Bravo", "Zulu"), filterAndSortMediaAlbums(albums, "", AlbumSortField.DURATION, true).map { it.name })
        assertEquals(listOf("Alpha", "Bravo", "Zulu"), filterAndSortMediaAlbums(albums.reversed(), "", AlbumSortField.DURATION, false).map { it.name })
    }

    @Test fun missingMetadataIsSafeForEmptyAlbumAndEmptyCatalog() {
        val emptyAlbum = MediaAlbum("", emptyList())

        assertEquals(0L, emptyAlbum.durationMs)
        assertEquals(null, emptyAlbum.year)
        assertEquals(0L, emptyAlbum.updatedAtMs)
        assertEquals("", emptyAlbum.artist)
        assertTrue(emptyAlbum.artworkPaths.isEmpty())
        assertTrue(buildMediaAlbums(emptyList()).isEmpty())
        assertTrue(filterAndSortMediaAlbums(emptyList(), "", AlbumSortField.NAME, true).isEmpty())
    }

    @Test fun coverCandidatesFollowEachAppliedSortWithoutChangingPlaybackOrder() {
        val alpha = track("/alpha", "Album", title = "Alpha", duration = 10L, artist = "Alpha", modified = 100L, year = 2020)
            .copy(trackNumber = 2, albumArtist = "Zulu")
        val beta = track("/beta", "Album", title = "Beta", duration = 20L, artist = "Beta", modified = 200L, year = 2021)
            .copy(trackNumber = 1, albumArtist = "Alpha")
        val album = buildMediaAlbums(listOf(alpha, beta)).single()
        for (field in AlbumSortField.entries) {
            assertEquals("Ascending cover order $field", listOf("/alpha", "/beta"), mediaAlbumArtworkPaths(album, field, true))
            assertEquals("Descending cover order $field", listOf("/beta", "/alpha"), mediaAlbumArtworkPaths(album, field, false))
        }
        assertEquals(listOf("/beta", "/alpha"), album.tracks.map { it.track.path })
    }

    @Test fun unknownCoverCandidateYearRemainsAfterKnownYearsInBothDirections() {
        val album = buildMediaAlbums(listOf(
            track("/unknown", "Album", title = "Alpha"),
            track("/newer", "Album", title = "Newer", year = 2024),
            track("/older", "Album", title = "Older", year = 2020)
        )).single()
        assertEquals(listOf("/older", "/newer", "/unknown"), mediaAlbumArtworkPaths(album, AlbumSortField.YEAR, true))
        assertEquals(listOf("/newer", "/older", "/unknown"), mediaAlbumArtworkPaths(album, AlbumSortField.YEAR, false))
    }

    @Test fun coverCandidateTiesUseStableTitleAndPathWithoutDuplicateFiles() {
        val album = MediaAlbum("Album", listOf(
            track("/z", "Album", title = "Same"), track("/a", "Album", title = "same"),
            track("/z", "Album", title = "Same")
        ))
        assertEquals(listOf("/a", "/z"), mediaAlbumArtworkPaths(album, AlbumSortField.DURATION, false))
    }

    private fun track(
        path: String,
        album: String,
        title: String = path,
        duration: Long = 0L,
        artist: String = "",
        modified: Long = 0L,
        year: Int? = null
    ) = MediaAlbumTrack(
        track = MediaLibraryTrack(path, title, artist = artist, album = album, durationMs = duration),
        year = year,
        modifiedAtMs = modified
    )
}
