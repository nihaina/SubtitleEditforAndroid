package com.subtitleedit.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCreditCatalogTest {
    @Test fun categoriesUseOnlyTheirOwnMetadataField() {
        val tracks = listOf(
            track("/a", "Singer", "Artist A", "Composer B"),
            track("/b", "Singer", "Artist B", "Composer A"),
            track("/c", "Other singer", "Artist A", "Composer A")
        )

        assertEquals(listOf("/a", "/b"), members(tracks, MediaCreditRole.SINGER, "singer"))
        assertEquals(listOf("/a", "/c"), members(tracks, MediaCreditRole.ARTIST, "artist a"))
        assertEquals(listOf("/b", "/c"), members(tracks, MediaCreditRole.COMPOSER, "composer a"))
        assertEquals(buildMediaSingers(tracks), buildMediaCredits(tracks, MediaCreditRole.SINGER))
    }

    @Test fun absentCreditsJoinUnknownWithoutFallingBackToSingerOrOtherCredit() {
        val tracks = listOf(
            track("/a", "Known singer", "", " <UNKNOWN> "),
            track("/b", "Another singer", "   ", ""),
            track("/c", "Third singer", "<unknown>", "  ")
        )

        for (role in listOf(MediaCreditRole.ARTIST, MediaCreditRole.COMPOSER)) {
            val unknown = buildMediaCredits(tracks, role).single()
            assertEquals("", unknown.name)
            assertEquals("", unknown.key)
            assertEquals(3, unknown.tracks.size)
        }
        assertEquals(3, buildMediaSingers(tracks).size)
    }

    @Test fun creditNamesKeepSeparatorsAndMergeOnlyCaseAndWhitespace() {
        val tracks = listOf(
            track("/a", "Singer", " A/B;C ", "A/B;C"),
            track("/b", "Singer", "a/b;c", " a/b;c "),
            track("/c", "Singer", "A", "A")
        )

        for (role in listOf(MediaCreditRole.ARTIST, MediaCreditRole.COMPOSER)) {
            val credits = buildMediaCredits(tracks, role)
            assertEquals(2, credits.size)
            assertEquals(listOf("/a", "/b"), credits.single { it.key == "a/b;c" }.tracks.map { it.track.path })
            assertEquals(listOf("/c"), credits.single { it.key == "a" }.tracks.map { it.track.path })
            assertEquals(listOf("a/b;c"), filterAndSortMediaSingers(credits, " B;C ", SingerSortField.NAME, true).map { it.key })
        }
    }

    @Test fun audioAndVideoAreIncludedOnceWithOnlyTheirOwnCreditTotals() {
        val audio = track("/a", "Singer A", "Artist", "Composer").copy(
            track = MediaLibraryTrack("/a", "Audio", "Singer A", "Album", durationMs = 100L),
            modifiedAtMs = 20L, year = 2023
        )
        val video = track("/b", "Singer B", "artist", "composer").copy(
            track = MediaLibraryTrack("/b", "Video", "Singer B", "Album", isVideo = true, durationMs = 200L),
            modifiedAtMs = 40L, year = 2020
        )
        val invalidDuration = track("/c", "Singer", "Artist", "Composer").copy(
            track = MediaLibraryTrack("/c", "Silent", "Singer", "Other album", durationMs = -1L)
        )
        val unrelated = track("/d", "Singer", "Other artist", "Other composer").copy(
            track = MediaLibraryTrack("/d", "Other", "Singer", durationMs = 999L), year = 1900
        )
        val tracks = listOf(audio, video, audio.copy(modifiedAtMs = 999L), invalidDuration, unrelated)

        for (role in listOf(MediaCreditRole.ARTIST, MediaCreditRole.COMPOSER)) {
            val credit = buildMediaCredits(tracks, role).single { it.key == if (role == MediaCreditRole.ARTIST) "artist" else "composer" }
            assertEquals(3, credit.tracks.size)
            assertTrue(credit.tracks.any { it.track.isVideo })
            assertEquals(300L, credit.durationMs)
            assertEquals(40L, credit.updatedAtMs)
            assertEquals(2020, credit.year)
            assertEquals(2, credit.albums.size)
        }
    }

    @Test fun everySortCriterionAppliesBothDirectionsToEachCreditCategory() {
        val tracks = listOf(
            track("/a", "Singer", "Alpha", "Alpha").copy(
                track = MediaLibraryTrack("/a", "A", durationMs = 10L), modifiedAtMs = 100L
            ),
            track("/b", "Singer", "Beta", "Beta").copy(
                track = MediaLibraryTrack("/b", "B", durationMs = 20L), modifiedAtMs = 200L
            ),
            track("/c", "Singer", "Beta", "Beta").copy(
                track = MediaLibraryTrack("/c", "C", durationMs = 20L), modifiedAtMs = 200L
            )
        )
        for (role in listOf(MediaCreditRole.ARTIST, MediaCreditRole.COMPOSER)) {
            val credits = buildMediaCredits(tracks, role)
            for (field in SingerSortField.entries) {
                assertEquals("$role ascending $field", listOf("Alpha", "Beta"),
                    filterAndSortMediaSingers(credits, "", field, true).map { it.name })
                assertEquals("$role descending $field", listOf("Beta", "Alpha"),
                    filterAndSortMediaSingers(credits, "", field, false).map { it.name })
            }
        }
    }

    @Test fun emptyCatalogHasNoInventedUnknownCredit() {
        for (role in MediaCreditRole.entries) assertTrue(buildMediaCredits(emptyList(), role).isEmpty())
    }

    private fun members(tracks: List<MediaAlbumTrack>, role: MediaCreditRole, key: String): List<String> =
        buildMediaCredits(tracks, role).single { it.key == key }.tracks.map { it.track.path }

    private fun track(path: String, singer: String, artist: String, composer: String) = MediaAlbumTrack(
        track = MediaLibraryTrack(path, path, singer), albumArtist = artist, composer = composer
    )
}
