package com.subtitleedit.library

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAlbumTrackSortTest {
    @Test fun allSortCriteriaApplyBothDirections() {
        val alpha = track("/alpha/alpha.flac", "Alpha", 1)
        val beta = track("/beta/beta.flac", "Beta", 2)
        for (field in AlbumTrackSortField.entries) {
            assertEquals("Ascending $field", listOf(alpha, beta),
                sortMediaAlbumTracks(listOf(beta, alpha), AlbumTrackSortOptions(field, true)))
            assertEquals("Descending $field", listOf(beta, alpha),
                sortMediaAlbumTracks(listOf(alpha, beta), AlbumTrackSortOptions(field, false)))
        }
    }

    @Test fun defaultSortUsesNumericDiscAndTrackOrderRatherThanLexicalNumber() {
        val secondDisc = track("/d", "A", 1).copy(discNumber = 2, trackNumber = 1)
        val tenth = track("/c", "B", 1).copy(discNumber = 1, trackNumber = 10)
        val second = track("/b", "C", 1).copy(trackNumber = 2)
        val first = track("/a", "D", 1).copy(trackNumber = 1)
        val input = listOf(secondDisc, tenth, second, first)

        assertEquals(listOf(first, second, tenth, secondDisc), sortMediaAlbumTracks(input, AlbumTrackSortOptions()))
        assertEquals(listOf(secondDisc, tenth, second, first),
            sortMediaAlbumTracks(input, AlbumTrackSortOptions(ascending = false)))
        assertEquals(listOf(secondDisc, tenth, second, first), input)
    }

    @Test fun missingNumericTagsStayLastInBothDirections() {
        val unknown = track("/unknown", "A", 1).copy(year = null, trackNumber = null)
        val invalid = track("/invalid", "B", 1).copy(year = 0, trackNumber = 0)
        val older = track("/older", "C", 1).copy(year = 2000, trackNumber = 2)
        val newer = track("/newer", "D", 1).copy(year = 2020, trackNumber = 3)
        val input = listOf(unknown, newer, invalid, older)
        for (field in listOf(AlbumTrackSortField.TRACK_NUMBER, AlbumTrackSortField.YEAR)) {
            assertEquals(listOf(older, newer, unknown, invalid),
                sortMediaAlbumTracks(input, AlbumTrackSortOptions(field, true)))
            assertEquals(listOf(newer, older, unknown, invalid),
                sortMediaAlbumTracks(input, AlbumTrackSortOptions(field, false)))
        }
    }

    @Test fun performerAndAlbumArtistHaveIndependentSortKeys() {
        val alpha = track("/alpha", "Alpha", 1).copy(albumArtist = "Zulu")
        val beta = track("/beta", "Beta", 2).copy(albumArtist = "Alpha")

        assertEquals(listOf(alpha, beta), sortMediaAlbumTracks(listOf(beta, alpha), AlbumTrackSortOptions(AlbumTrackSortField.ARTIST)))
        assertEquals(listOf(beta, alpha), sortMediaAlbumTracks(listOf(alpha, beta), AlbumTrackSortOptions(AlbumTrackSortField.ALBUM_ARTIST)))
    }

    @Test fun fileNameAndFolderPathSortIndependentlyOfSongTitle() {
        val alphaFolder = track("/alpha/zulu.mp3", "Zulu", 1)
        val betaFolder = track("/beta/alpha.mp3", "Alpha", 2)
        assertEquals(listOf(betaFolder, alphaFolder),
            sortMediaAlbumTracks(listOf(alphaFolder, betaFolder), AlbumTrackSortOptions(AlbumTrackSortField.FILE_NAME)))
        assertEquals(listOf(alphaFolder, betaFolder),
            sortMediaAlbumTracks(listOf(betaFolder, alphaFolder), AlbumTrackSortOptions(AlbumTrackSortField.FOLDER_PATH)))
    }

    @Test fun equalPrimaryValuesUseStableCaseInsensitiveTitleAndPathTies() {
        val alpha = track("/a", "same", 1).copy(genre = "Same genre")
        val beta = track("/b", "Same", 2).copy(genre = "same GENRE")
        val zulu = track("/z", "Zulu", 3).copy(genre = "Same genre")
        for (ascending in listOf(true, false)) {
            assertEquals(listOf(alpha, beta, zulu),
                sortMediaAlbumTracks(listOf(zulu, beta, alpha), AlbumTrackSortOptions(AlbumTrackSortField.GENRE, ascending)))
        }
    }

    @Test fun firstEntryForEachPathIsKeptAndSourceListIsNotMutated() {
        val first = track("/same", "First metadata", 1)
        val duplicate = track("/same", "Updated metadata", 2)
        val other = track("/other", "Other", 3)
        val source = listOf(other, first, duplicate)
        assertEquals(listOf(first, other), sortMediaAlbumTracks(source, AlbumTrackSortOptions(AlbumTrackSortField.TITLE)))
        assertEquals(listOf(other, first, duplicate), source)
    }

    @Test fun neverPlayedFilesParticipateInPlayCountAndLastPlayedSorts() {
        val never = track("/never", "Never", 1).copy(playCount = 0, lastPlayedAtMs = 0L)
        val played = track("/played", "Played", 2).copy(playCount = 5, lastPlayedAtMs = 100L)
        for (field in listOf(AlbumTrackSortField.PLAY_COUNT, AlbumTrackSortField.LAST_PLAYED)) {
            assertEquals(listOf(never, played), sortMediaAlbumTracks(listOf(played, never), AlbumTrackSortOptions(field)))
            assertEquals(listOf(played, never), sortMediaAlbumTracks(listOf(never, played), AlbumTrackSortOptions(field, false)))
        }
    }

    @Test fun emptyTrackListRemainsEmptyForEveryCriterion() {
        for (field in AlbumTrackSortField.entries) {
            assertEquals(emptyList<MediaAlbumTrack>(), sortMediaAlbumTracks(emptyList(), AlbumTrackSortOptions(field)))
        }
    }

    private fun track(path: String, label: String, rank: Int) = MediaAlbumTrack(
        track = MediaLibraryTrack(path, label, artist = label, album = label, durationMs = rank * 1_000L, addedAtMs = rank * 1_000L),
        albumArtist = label,
        composer = label,
        year = 2000 + rank,
        trackNumber = rank,
        discNumber = 1,
        modifiedAtMs = rank * 1_000L,
        genre = label,
        lastPlayedAtMs = rank * 1_000L,
        playCount = rank
    )
}
