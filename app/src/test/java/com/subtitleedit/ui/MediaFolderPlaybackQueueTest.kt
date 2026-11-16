package com.subtitleedit.ui

import org.junit.Assert.*
import org.junit.Test

class MediaFolderPlaybackQueueTest {
    @Test fun folderQueueKeepsVisibleMediaOrderAndExcludesFoldersAndOtherFiles() {
        val entries = listOf(
            MediaFolderEntry("/music/subfolder", "subfolder", true, 3, 9000),
            MediaFolderEntry("/music/B.MP3", "B.MP3", false, 1, 2000),
            MediaFolderEntry("/music/a.mp4", "a.mp4", false, 1, 3000),
            MediaFolderEntry("/music/notes.txt", "notes.txt", false, 1, 0),
            MediaFolderEntry("/music/c.flac", "c.flac", false, 1, 4000)
        )
        val queue = mediaFolderPlaybackQueue(entries)
        assertEquals(listOf("/music/B.MP3", "/music/a.mp4", "/music/c.flac"), queue.map { it.path })
        assertEquals(listOf(false, true, false), queue.map { it.isVideo })
        assertEquals(listOf(2000L, 3000L, 4000L), queue.map { it.durationMs })
        val selection = LibraryPlaybackSelection.resolve(queue.map { it.path },
            queue.indexOfFirst { it.path == "/music/a.mp4" }, false) { true }
        assertEquals(1, selection?.startIndex)
        assertEquals(queue.map { it.path }, selection?.paths)
    }

    @Test fun missingFilesCannotShiftTheClickedTrackToAnotherSong() {
        val entries = listOf("one.wav", "two.wav", "three.wav").map {
            MediaFolderEntry("/music/$it", it, false, 1, 1000)
        }
        val paths = mediaFolderPlaybackQueue(entries).map { it.path }
        assertEquals(0, LibraryPlaybackSelection.resolve(paths, 1, false) { !it.endsWith("one.wav") }?.startIndex)
        assertNull(LibraryPlaybackSelection.resolve(paths, 1, false) { !it.endsWith("two.wav") })
    }
}
