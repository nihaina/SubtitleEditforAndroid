package com.subtitleedit.library

import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFolderFilesTest {
    private val root = File("folder")
    private val ascending = MediaFolderOrder(FileSortField.NAME, FileSortDirection.ASCENDING)

    @Test fun mixedRowsFollowTheSelectedOrderButOnlyDirectMediaEnterTheQueue() {
        val child = entry(root, "Child", directory = true)
        val image = entry(root, "Cover.png")
        val subtitle = entry(root, "Lyrics.srt")
        val first = entry(root, "A.wav")
        val second = entry(root, "Z.mp4")
        val other = entry(root, "Notes.pdf")
        val nested = entry(child.file, "Nested.wav")
        val tracks = listOf(MediaLibraryTrack(first.path, "First", durationMs = 2_000L),
            MediaLibraryTrack(second.path, "Second", durationMs = 3_000L))
        val catalog = MediaFolderCatalog(listOf(root.absolutePath),
            mapOf(root.absolutePath to tracks, child.path to listOf(MediaLibraryTrack(nested.path, "Nested"))),
            mapOf(root.absolutePath to 3, child.path to 1))

        val result = loadMediaFolderListing(listOf(root.absolutePath), catalog, ascending.copy(direction = FileSortDirection.DESCENDING)) {
            listOf(first, subtitle, other, image, child, second)
        }

        assertEquals(listOf(child, second, subtitle, image, first), result.entries)
        assertEquals(tracks.reversed(), result.tracks)
        assertEquals(5_000L, result.tracks.sumOf { it.durationMs })
    }

    @Test fun hidesOnlyBranchesWithoutMediaInTheirSubtreeOrBrowsePath() {
        val rootPath = root.absolutePath
        val music = entry(root, "Music", directory = true)
        val album = entry(music.file, "Album", directory = true)
        val unrelated = entry(root, "Unrelated", directory = true)
        val sidecars = entry(album.file, "Sidecars", directory = true)
        val artwork = entry(sidecars.file, "Artwork", directory = true)
        val song = entry(album.file, "Song.wav")
        val cover = entry(artwork.file, "Cover.png")
        val outer = requireNotNull(root.absoluteFile.parentFile)
        val catalog = MediaFolderCatalog(
            listOf(rootPath),
            mapOf(
                outer.path to listOf(MediaLibraryTrack(File(outer, "Outside.mp4").path, "Outside")),
                album.path to listOf(MediaLibraryTrack(song.path, "Song"))
            ),
            mapOf(outer.path to 2, rootPath to 1, music.path to 1, album.path to 1)
        )
        val children = mapOf(
            rootPath to listOf(music, unrelated),
            music.path to listOf(album),
            album.path to listOf(sidecars, song),
            sidecars.path to listOf(artwork),
            artwork.path to listOf(cover)
        )
        fun entries(path: List<String>) = loadMediaFolderListing(path, catalog, ascending) {
            children[it.absolutePath].orEmpty()
        }.entries

        val albumPath = listOf(rootPath, music.path, album.path)
        val sidecarsPath = albumPath + sidecars.path
        assertEquals(listOf(music), entries(listOf(rootPath)))
        assertEquals(listOf(album), entries(albumPath.dropLast(1)))
        assertEquals(listOf(sidecars, song), entries(albumPath))
        assertEquals(listOf(artwork), entries(sidecarsPath))
        assertEquals(listOf(cover), entries(sidecarsPath + artwork.path))
    }

    @Test fun imagesAreTriedFromOuterToInnerBeforeAnyMediaArtwork() = runBlocking {
        val first = entry(root, "a", directory = true)
        val second = entry(root, "b", directory = true)
        val inner = entry(first.file, "inner", directory = true)
        val unreadable = entry(root, "invalid.png")
        val outerImage = entry(second.file, "outer.png")
        val deeperImage = entry(inner.file, "deeper.png")
        val listings = mapOf(
            root to listOf(second, first, entry(root, "song.wav"), unreadable),
            first.file to listOf(inner), second.file to listOf(outerImage), inner.file to listOf(deeperImage)
        )
        val attemptedImages = mutableListOf<String>()
        val artwork = findMediaFolderArtwork(
            mediaFolderArtworkCandidates(root, ascending, children = { listings[it].orEmpty() }),
            readImage = { attemptedImages += it.file.name; it.file.name.takeUnless { name -> name == "invalid.png" } },
            readMediaArtwork = { error("Image files take precedence over media artwork, including at a lower level") }
        )
        assertEquals("outer.png", artwork)
        assertEquals(listOf("invalid.png", "outer.png"), attemptedImages)
    }

    @Test fun selectedOrderDeterminesTheFirstImageOnTheSameLevel() = runBlocking {
        val early = entry(root, "a.png", modifiedAt = 200)
        val late = entry(root, "b.png", modifiedAt = 100)
        val children: (File) -> List<BrowserFileMetadata> = { listOf(late, early) }
        suspend fun firstImage(order: MediaFolderOrder) = findMediaFolderArtwork(
            mediaFolderArtworkCandidates(root, order, children), { it.file.name }, { null }
        )
        assertEquals("a.png", firstImage(ascending))
        assertEquals("b.png", firstImage(ascending.copy(direction = FileSortDirection.DESCENDING)))
        assertEquals("b.png", firstImage(ascending.copy(field = FileSortField.DATE)))
    }

    @Test fun fallbackSkipsMediaWithoutArtworkAndReturnsNullWhenNoneCanBeDecoded() = runBlocking {
        val candidates = mediaFolderArtworkCandidates(root, ascending, children = {
            listOf(entry(root, "b.mp4"), entry(root, "a.wav"), entry(root, "invalid.png"), entry(root, "lyrics.srt"))
        })
        val attemptedMedia = mutableListOf<String>()
        assertEquals("b.mp4", findMediaFolderArtwork(candidates, { null }, {
            attemptedMedia += it.file.name
            it.file.name.takeIf { name -> name == "b.mp4" }
        }))
        assertEquals(listOf("a.wav", "b.mp4"), attemptedMedia)
        assertNull(findMediaFolderArtwork<String>(candidates, { null }, { null }))
    }

    private fun entry(parent: File, name: String, directory: Boolean = false, modifiedAt: Long = 0L) =
        BrowserFileMetadata(File(parent, name), directory, size = 0L, modifiedTimeMillis = modifiedAt)
}
