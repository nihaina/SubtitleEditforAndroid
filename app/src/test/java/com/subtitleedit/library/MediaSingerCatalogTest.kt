package com.subtitleedit.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSingerCatalogTest {
    @Test fun performersAreCaseInsensitiveAndDuplicatePathsDoNotIncreaseTotals() {
        val first = track("/a", " Singer ", durationMs = 100L)
        val second = track("/b", "singer", durationMs = 200L)
        val singers = buildMediaSingers(listOf(first, second, first.copy(year = 2026)))

        assertEquals(1, singers.size)
        assertEquals("Singer", singers.single().name)
        assertEquals("singer", singers.single().key)
        assertEquals(2, singers.single().tracks.size)
        assertEquals(300L, singers.single().durationMs)
    }

    @Test fun missingPerformerTagsShareOneUnknownGroup() {
        val singers = buildMediaSingers(listOf(track("/a", ""), track("/b", "  "), track("/c", " <UNKNOWN> ")))

        assertEquals(1, singers.size)
        assertEquals("", singers.single().name)
        assertEquals("", singers.single().key)
        assertEquals(3, singers.single().tracks.size)
    }

    @Test fun albumArtistAndNameSeparatorsDoNotChangePerformerMembership() {
        val singers = buildMediaSingers(listOf(
            track("/a", "初音ミク/とあ").copy(albumArtist = "Different artist"),
            track("/b", "初音ミク"),
            track("/c", "Different artist").copy(albumArtist = "初音ミク/とあ")
        ))

        assertEquals(3, singers.size)
        assertEquals(listOf("/a"), singers.single { it.name == "初音ミク/とあ" }.tracks.map { it.track.path })
        assertEquals(listOf("/c"), singers.single { it.name == "Different artist" }.tracks.map { it.track.path })
    }

    @Test fun aggregateAndAlbumsUseOnlyThisPerformersTracks() {
        val singers = buildMediaSingers(listOf(
            track("/a", "Singer", " Album ", durationMs = 100L, modifiedAtMs = 20L, year = 2023),
            track("/b", "Singer", "album", durationMs = -1L, modifiedAtMs = 40L, year = 2020),
            track("/c", "Singer", "Other", durationMs = 300L),
            track("/d", "Another", "Album", durationMs = 999L, year = 1900)
        ))
        val singer = singers.single { it.name == "Singer" }

        assertEquals(400L, singer.durationMs)
        assertEquals(40L, singer.updatedAtMs)
        assertEquals(2020, singer.year)
        assertEquals(2, singer.albums.size)
        assertEquals(listOf("/a", "/b"), singer.albums.single { it.key == "album" }.tracks.map { it.track.path })
    }

    @Test fun searchUsesPerformerNameWithoutCaseOrSurroundingWhitespace() {
        val singers = buildMediaSingers(listOf(track("/a", "Blue Singer"), track("/b", "Green Singer")))

        assertEquals(listOf("Blue Singer"), filterAndSortMediaSingers(singers, " BLUE ", SingerSortField.NAME, true).map { it.name })
        assertTrue(filterAndSortMediaSingers(singers, "missing", SingerSortField.NAME, true).isEmpty())
        assertEquals(2, filterAndSortMediaSingers(singers, " ", SingerSortField.NAME, true).size)
    }

    @Test fun everyCriterionRespectsAscendingAndDescending() {
        val singers = buildMediaSingers(listOf(
            track("/b1", "Beta", durationMs = 20L, modifiedAtMs = 200L),
            track("/b2", "Beta", durationMs = 20L, modifiedAtMs = 200L),
            track("/a", "Alpha", durationMs = 10L, modifiedAtMs = 100L)
        ))
        for (field in SingerSortField.entries) {
            assertEquals("Ascending $field", listOf("Alpha", "Beta"), filterAndSortMediaSingers(singers, "", field, true).map { it.name })
            assertEquals("Descending $field", listOf("Beta", "Alpha"), filterAndSortMediaSingers(singers, "", field, false).map { it.name })
        }
    }

    @Test fun tiedSortValuesKeepStableNameOrderInEitherDirection() {
        val singers = buildMediaSingers(listOf(track("/z", "Zulu"), track("/a", "Alpha"), track("/b", "Bravo")))

        assertEquals(listOf("Alpha", "Bravo", "Zulu"), filterAndSortMediaSingers(singers, "", SingerSortField.DURATION, true).map { it.name })
        assertEquals(listOf("Alpha", "Bravo", "Zulu"), filterAndSortMediaSingers(singers.reversed(), "", SingerSortField.DURATION, false).map { it.name })
    }

    @Test fun emptyCatalogAndMissingMetadataHaveSafeAggregates() {
        val singer = MediaSinger("", emptyList())

        assertTrue(buildMediaSingers(emptyList()).isEmpty())
        assertTrue(filterAndSortMediaSingers(emptyList(), "", SingerSortField.NAME, true).isEmpty())
        assertEquals(0L, singer.durationMs)
        assertEquals(0L, singer.updatedAtMs)
        assertEquals(null, singer.year)
        assertTrue(singer.albums.isEmpty())
    }

    private fun track(
        path: String,
        singer: String,
        album: String = "",
        durationMs: Long = 0L,
        modifiedAtMs: Long = 0L,
        year: Int? = null
    ) = MediaAlbumTrack(
        track = MediaLibraryTrack(path, path, singer, album, durationMs = durationMs),
        modifiedAtMs = modifiedAtMs,
        year = year
    )
}
