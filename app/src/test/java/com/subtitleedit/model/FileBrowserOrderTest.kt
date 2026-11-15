package com.subtitleedit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileBrowserOrderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `metadata sorting never asks File for attributes`() {
        fun file(name: String): File = object : File(name) {
            override fun isDirectory(): Boolean = error("Unexpected filesystem query")
            override fun length(): Long = error("Unexpected filesystem query")
            override fun lastModified(): Long = error("Unexpected filesystem query")
        }
        val folder = BrowserFileMetadata(file("z-folder"), true, 0L, 0L)
        val small = BrowserFileMetadata(file("a.txt"), false, 1L, 1000L)
        val large = BrowserFileMetadata(file("b.srt"), false, 5L, 2000L)
        val entries = listOf(large, folder, small)

        assertEquals(listOf(folder, small, large), FileBrowserOrder.sortMetadata(entries, FileSortField.NAME, FileSortDirection.ASCENDING))
        assertEquals(listOf(folder, small, large), FileBrowserOrder.sortMetadata(entries, FileSortField.TYPE, FileSortDirection.DESCENDING))
        assertEquals(listOf(folder, large, small), FileBrowserOrder.sortMetadata(entries, FileSortField.SIZE, FileSortDirection.DESCENDING))
        assertEquals(listOf(folder, large, small), FileBrowserOrder.sortMetadata(entries, FileSortField.DATE, FileSortDirection.DESCENDING))
    }

    @Test
    fun `folders stay before files and names default ascending`() {
        val root = temporaryFolder.root
        val file = File(root, "a.srt").apply { writeText("text") }
        val folder = File(root, "z-folder").apply { mkdir() }

        val sorted = FileBrowserOrder.sort(
            listOf(file, folder),
            FileSortField.NAME,
            FileSortDirection.ASCENDING
        )

        assertEquals(listOf(folder, file), sorted)
    }

    @Test
    fun `type size and date support descending order`() {
        val root = temporaryFolder.root
        val smallTxt = File(root, "small.txt").apply { writeText("1"); setLastModified(1000) }
        val largeSrt = File(root, "large.srt").apply { writeText("12345"); setLastModified(2000) }

        assertEquals(
            listOf(smallTxt, largeSrt),
            FileBrowserOrder.sort(listOf(largeSrt, smallTxt), FileSortField.TYPE, FileSortDirection.DESCENDING)
        )
        assertEquals(
            listOf(largeSrt, smallTxt),
            FileBrowserOrder.sort(listOf(smallTxt, largeSrt), FileSortField.SIZE, FileSortDirection.DESCENDING)
        )
        assertEquals(
            listOf(largeSrt, smallTxt),
            FileBrowserOrder.sort(listOf(smallTxt, largeSrt), FileSortField.DATE, FileSortDirection.DESCENDING)
        )
    }

    @Test
    fun `filter ignores case and blank query returns all files`() {
        val files = listOf(File("Movie.SRT"), File("audio.mp3"))

        assertEquals(listOf(files.first()), FileBrowserOrder.filter(files, "movie"))
        assertEquals(files, FileBrowserOrder.filter(files, "  "))
    }

    @Test
    fun `file names and extensions are normalized and invalid names rejected`() {
        assertEquals("example.txt", FileBrowserOrder.composeFileName(" example ", "txt"))
        assertEquals("example..srt", FileBrowserOrder.composeFileName("example", ".srt"))
        assertEquals("example..", FileBrowserOrder.composeFileName("example", "."))
        assertEquals("example", FileBrowserOrder.composeFileName("example", ""))
        assertNull(FileBrowserOrder.validateName("valid name"))
        assertNull(FileBrowserOrder.validateExtension("txt"))
        assertNull(FileBrowserOrder.validateExtension(""))
        assertEquals("名称不能为空", FileBrowserOrder.validateName("  "))
        assertEquals("名称无效", FileBrowserOrder.validateName(".."))
        assertEquals("名称不能包含 \\ / : * ? \" < > |", FileBrowserOrder.validateName("bad/name"))
        assertNull(FileBrowserOrder.validateExtension("."))
        assertEquals("扩展名不能包含 \\ / : * ? \" < > |", FileBrowserOrder.validateExtension("bad/name"))
    }
}
