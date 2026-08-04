package com.subtitleedit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileBrowserSearchTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `search finds matching files in nested directories`() {
        val root = temporaryFolder.root
        File(root, "direct.srt").writeText("direct")
        val nested = File(root, "season/episode").apply { mkdirs() }
        val match = File(nested, "Movie.SRT").apply { writeText("subtitle") }
        File(nested, "movie.mp3").writeText("audio")

        val results = FileBrowserSearch.search(
            root = root,
            query = "movie",
            includeHidden = false,
            includeFile = { it.extension.equals("srt", ignoreCase = true) }
        )

        assertEquals(listOf(match), results)
    }

    @Test
    fun `search skips hidden trees unless hidden files are enabled`() {
        val root = temporaryFolder.root
        val hiddenDirectory = File(root, ".hidden").apply { mkdir() }
        val hiddenMatch = File(hiddenDirectory, "target.srt").apply { writeText("subtitle") }

        val hiddenDisabled = FileBrowserSearch.search(
            root = root,
            query = "target",
            includeHidden = false,
            includeFile = { true }
        )
        val hiddenEnabled = FileBrowserSearch.search(
            root = root,
            query = "target",
            includeHidden = true,
            includeFile = { true }
        )

        assertFalse(hiddenDisabled.contains(hiddenMatch))
        assertTrue(hiddenEnabled.contains(hiddenMatch))
    }

    @Test
    fun `search does not descend into blocked directories`() {
        val root = temporaryFolder.root
        val blocked = File(root, "blocked").apply { mkdir() }
        val nestedMatch = File(blocked, "target.srt").apply { writeText("subtitle") }

        val results = FileBrowserSearch.search(
            root = root,
            query = "target",
            includeHidden = true,
            includeFile = { true },
            canEnterDirectory = { it != blocked }
        )

        assertFalse(results.contains(nestedMatch))
    }
}
