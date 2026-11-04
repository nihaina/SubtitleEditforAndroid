package com.subtitleedit.util

import com.subtitleedit.model.FileSortField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BatchRenameOrderTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun sortingUsesExistingBrowserFieldsAndDoesNotChangeFileContents() {
        val small = folder.newFile("z.txt").apply { writeText("a"); setLastModified(1_000L) }
        val large = folder.newFile("a.srt").apply { writeText("longer"); setLastModified(2_000L) }
        val sources = listOf(small, large)

        assertEquals(listOf(large, small), BatchRenameOrder.sort(sources, FileSortField.NAME, false))
        assertEquals(listOf(small, large), BatchRenameOrder.sort(sources, FileSortField.DATE, false))
        assertEquals(listOf(small, large), BatchRenameOrder.sort(sources, FileSortField.SIZE, false))
        assertEquals(listOf(large, small), BatchRenameOrder.sort(sources, FileSortField.TYPE, false))
        assertEquals("a", small.readText())
        assertEquals("longer", large.readText())
        assertEquals(listOf(small, large), sources)
    }

    @Test
    fun reverseFlipsTheEntireOrderedSelectionIncludingDirectories() {
        val file = folder.newFile("a.txt")
        val directory = folder.newFolder("z")

        assertEquals(listOf(directory, file), BatchRenameOrder.sort(listOf(file, directory), FileSortField.NAME, false))
        assertEquals(listOf(file, directory), BatchRenameOrder.sort(listOf(file, directory), FileSortField.NAME, true))
    }

    @Test
    fun movingAnItemAcrossSeveralRowsPreservesTheRemainingOrderAndAllFiles() {
        val sources = (0..4).map { File(folder.root, "$it.srt") }

        assertEquals(listOf(sources[1], sources[2], sources[3], sources[0], sources[4]), BatchRenameOrder.move(sources, 0, 3))
        assertEquals(listOf(sources[4], sources[0], sources[1], sources[2], sources[3]), BatchRenameOrder.move(sources, 4, 0))
        assertEquals(sources.toSet(), BatchRenameOrder.move(sources, 1, 4).toSet())
        assertEquals((0..4).map { File(folder.root, "$it.srt") }, sources)
    }

    @Test
    fun invalidOrUnchangedDragPositionsLeaveTheSelectionUntouched() {
        val sources = listOf(File(folder.root, "a.txt"), File(folder.root, "b.txt"))

        assertSame(sources, BatchRenameOrder.move(sources, -1, 1))
        assertSame(sources, BatchRenameOrder.move(sources, 0, 2))
        assertSame(sources, BatchRenameOrder.move(sources, 1, 1))
        assertEquals(emptyList<File>(), BatchRenameOrder.move(emptyList(), 0, 1))
    }

    @Test
    fun restoredOrderingDropsDeletedAndDuplicatePathsAndAppendsNewSelections() {
        val sources = listOf("a.txt", "b.txt", "c.txt").map { File(folder.root, it) }
        val saved = listOf(sources[1].absolutePath, "missing", sources[1].absolutePath, sources[0].absolutePath)

        assertEquals(listOf(sources[1], sources[0], sources[2]), BatchRenameOrder.restore(sources, saved))
        assertEquals(sources, BatchRenameOrder.restore(sources, emptyList()))
    }
}
