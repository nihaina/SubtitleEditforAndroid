package com.subtitleedit.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGenreCatalogTest {
    @Test fun genresUseOnlyTheirOwnTagAndIncludeAudioAndVideo() {
        val audio = track("/a", "ASMR", duration = 100L)
        val video = track("/b", "ASMR", duration = 200L, video = true)
        val other = track("/c", "Electronica", duration = 900L)
        val groups = buildMediaCredits(listOf(audio, video, other), MediaCreditRole.GENRE)

        assertEquals(setOf("ASMR", "Electronica"), groups.map { it.name }.toSet())
        val asmr = groups.single { it.key == "asmr" }
        assertEquals(listOf("/a", "/b"), asmr.tracks.map { it.track.path })
        assertTrue(asmr.tracks.any { it.track.isVideo })
        assertEquals(300L, asmr.durationMs)
        assertEquals(listOf("Singer"), buildMediaCredits(listOf(audio, video), MediaCreditRole.SINGER).map { it.name })
        assertEquals(listOf("Artist"), buildMediaCredits(listOf(audio, video), MediaCreditRole.ARTIST).map { it.name })
        assertEquals(listOf("Composer"), buildMediaCredits(listOf(audio, video), MediaCreditRole.COMPOSER).map { it.name })
    }

    @Test fun missingGenreJoinsUnknownWithoutBorrowingOtherTags() {
        val tracks = listOf(track("/a", ""), track("/b", " <UNKNOWN> "), track("/c", "   "))
        val unknown = buildMediaCredits(tracks, MediaCreditRole.GENRE).single()

        assertEquals("", unknown.name)
        assertEquals("", unknown.key)
        assertEquals(3, unknown.tracks.size)
        assertTrue(buildMediaCredits(emptyList(), MediaCreditRole.GENRE).isEmpty())
    }

    @Test fun caseWhitespaceAndDuplicatePathsDoNotDuplicateGenreTotals() {
        val audio = track("/a", " ASMR ", duration = 100L).copy(modifiedAtMs = 10L, year = 2024)
        val video = track("/b", "asmr", duration = 200L, video = true).copy(modifiedAtMs = 30L, year = 2020)
        val invalidDuration = track("/c", "ASMR", duration = -20L)
        val group = buildMediaCredits(listOf(audio, video, audio.copy(modifiedAtMs = 999L), invalidDuration),
            MediaCreditRole.GENRE).single()

        assertEquals("ASMR", group.name)
        assertEquals(3, group.tracks.size)
        assertEquals(300L, group.durationMs)
        assertEquals(30L, group.updatedAtMs)
        assertEquals(2020, group.year)
    }

    @Test fun genreSeparatorsRemainPartOfTheWholeTagAndCanBeSearched() {
        val genres = buildMediaCredits(listOf(
            track("/a", " Ambient/ASMR;Other "),
            track("/b", "ambient/asmr;other"),
            track("/c", "ASMR")
        ), MediaCreditRole.GENRE)

        assertEquals(2, genres.size)
        assertEquals(listOf("/a", "/b"), genres.single { it.key == "ambient/asmr;other" }.tracks.map { it.track.path })
        assertEquals(listOf("ambient/asmr;other"),
            filterAndSortMediaSingers(genres, " /ASMR; ", SingerSortField.NAME, true).map { it.key })
    }

    @Test fun eachGenreSortAppliesBothDirections() {
        val genres = buildMediaCredits(listOf(
            track("/a", "ASMR", duration = 100L).copy(modifiedAtMs = 10L),
            track("/b", "Electronica", duration = 200L).copy(modifiedAtMs = 30L),
            track("/c", "Electronica", duration = 300L).copy(modifiedAtMs = 20L)
        ), MediaCreditRole.GENRE)

        for (field in SingerSortField.entries) {
            assertEquals("ascending $field", listOf("ASMR", "Electronica"),
                filterAndSortMediaSingers(genres, "", field, true).map { it.name })
            assertEquals("descending $field", listOf("Electronica", "ASMR"),
                filterAndSortMediaSingers(genres, "", field, false).map { it.name })
        }
    }

    private fun track(path: String, genre: String, duration: Long = 0L, video: Boolean = false) = MediaAlbumTrack(
        track = MediaLibraryTrack(path, path, artist = "Singer", album = "Album", isVideo = video, durationMs = duration),
        albumArtist = "Artist", composer = "Composer", genre = genre
    )
}
