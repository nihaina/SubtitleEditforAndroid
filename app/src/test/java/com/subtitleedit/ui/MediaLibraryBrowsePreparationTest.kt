package com.subtitleedit.ui

import com.subtitleedit.library.MediaLibraryTrack
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaLibraryBrowsePreparationTest {
    private fun track(path: String, duration: Long) = MediaLibraryTrack(path, path.substringAfterLast('/'), durationMs = duration)

    @Test fun folderPreparationKeepsDirectoriesFirstAndAggregatesOnlyDirectChildGroups() {
        val tracks = listOf(
            track("/music/z.wav", 1), track("/music/Beta/one.mp3", 2),
            track("/music/alpha/deep/two.mp3", 3), track("/music/Beta/three.mp3", 4),
            track("/music/A.wav", 5), track("/music-other/outside.mp3", 99)
        )
        val prepared = prepareMediaLibraryFolder(tracks, "/music/")
        assertSame(tracks, prepared.tracks)
        assertEquals(listOf("alpha", "Beta", "A.wav", "z.wav"), prepared.entries.map { it.name })
        assertEquals(listOf(1, 2, 1, 1), prepared.entries.map { it.mediaCount })
        assertEquals(listOf(3L, 6L, 5L, 1L), prepared.entries.map { it.totalDurationMs })
        assertEquals(listOf("/music/alpha", "/music/Beta", "/music/A.wav", "/music/z.wav"), prepared.entries.map { it.path })
    }

    @Test fun indexPreservesRootBoundariesNestedRootsAndSelectedDurationLookup() {
        val tracks = listOf(
            track("/music/a.mp3", 10), track("/music/nested/b.mp3", 20),
            track("/music-other/c.mp3", 30), track("/outside.mp3", 40)
        )
        val roots = listOf("/music", "/music/nested/", "/empty")
        val prepared = prepareMediaLibraryIndex(tracks, roots)
        assertSame(tracks, prepared.tracks)
        assertEquals(mapOf("/music" to 2, "/music/nested/" to 1, "/empty" to 0), prepared.rootMediaCounts)
        assertEquals(30L, listOf("/music/a.mp3", "/music/nested/b.mp3").sumOf { prepared.durationByPath[it] ?: 0L })
    }

    @Test fun cancelledPreparationsStopBeforeWalkingTheRestOfTheLibrary() {
        val tracks = (0..99).map { track("/music/$it.mp3", 1) }
        var visited = 0
        assertThrows(CancellationException::class.java) {
            prepareMediaLibraryIndex(tracks, listOf("/music")) {
                if (++visited == 3) throw CancellationException("Page exited")
            }
        }
        assertEquals(3, visited)
        visited = 0
        assertThrows(CancellationException::class.java) {
            prepareMediaLibraryFolder(tracks, "/music") {
                if (++visited == 4) throw CancellationException("Folder changed")
            }
        }
        assertEquals(4, visited)
    }
}
