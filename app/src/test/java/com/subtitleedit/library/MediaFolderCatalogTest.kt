package com.subtitleedit.library

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFolderCatalogTest {
    @Test fun firstLevelBelowTheAddedDirectoryCreatesTheTemporaryAlbum() {
        val deepTrack = track("/ADM/1/2/song.wav")
        val directTrack = track("/ADM/direct.mp3", album = " <unknown> ")
        val sibling = track("/ADM/10/clip.mp4")
        val taggedOnly = track("/ADM/Albums/song.flac", "Real album")
        val outside = track("/ADM-extra/ignored.wav")

        val catalog = buildMediaFolderCatalog(listOf(deepTrack, directTrack, sibling, taggedOnly, outside), listOf("/ADM/"))

        assertEquals(setOf("/ADM/1", "/ADM", "/ADM/10"), catalog.categoryRoots.toSet())
        assertEquals(emptyList<MediaLibraryTrack>(), catalog.tracksIn("/ADM/1"))
        assertEquals(listOf(deepTrack.track), catalog.tracksIn("/ADM/1/2"))
        assertEquals(4, catalog.mediaCount("/ADM"))
        assertEquals(0, catalog.mediaCount("/ADM-extra"))
    }

    @Test fun anExplicitlyAddedNestedDirectoryKeepsItsOwnNameAndDoesNotDuplicateTracks() {
        val untagged = track("/ADM/1/2/song.wav")
        val tagged = track("/ADM/1/2/tagged.mp3", "Real album")
        val tracks = listOf(untagged, tagged, untagged)

        val nested = buildMediaFolderCatalog(tracks, listOf("/ADM", "/ADM/1"))
        assertEquals(listOf("/ADM/1"), nested.categoryRoots)
        assertEquals(2, nested.mediaCount("/ADM/1"))
        assertEquals(2, nested.mediaCount("/ADM"))
        assertEquals(listOf(untagged.track, tagged.track), nested.tracksIn("/ADM/1/2"))

        val onlyNested = buildMediaFolderCatalog(tracks, listOf("/ADM/1"))
        assertEquals(listOf("/ADM/1/2"), onlyNested.categoryRoots)
    }

    @Test fun separateBranchesWithTheSameNameRemainSeparateTemporaryAlbums() {
        val catalog = buildMediaFolderCatalog(listOf(track("/ADM/Live/a.wav"), track("/Music/Live/b.wav")),
            listOf("/ADM", "/Music"))
        assertEquals(listOf("/ADM/Live", "/Music/Live"), catalog.categoryRoots)
        assertEquals(1, catalog.mediaCount("/ADM/Live"))
        assertEquals(1, catalog.mediaCount("/Music/Live"))
    }

    private fun track(path: String, album: String = "") = MediaAlbumTrack(MediaLibraryTrack(path, "Track", album = album))
}
