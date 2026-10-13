package com.subtitleedit.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubtitleFontManagerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun synchronizesFontsIntoTheNewSoftwareDirectoryAndKeepsConflicts() {
        val oldRoot = temporaryFolder.newFolder("old")
        val newRoot = temporaryFolder.newFolder("new")
        val oldFonts = File(oldRoot, SubtitleFontManager.FONT_DIR_NAME).apply { mkdirs() }
        File(oldFonts, "Example.ttf").writeText("old")
        File(oldFonts, "nested.otf").writeText("nested")
        val destinationFonts = File(newRoot, SubtitleFontManager.FONT_DIR_NAME).apply { mkdirs() }
        File(destinationFonts, "Example.ttf").writeText("existing")
        File(oldFonts, "ignore.txt").writeText("keep")

        val moved = SubtitleFontManager.synchronizeSoftwareDirectory(oldRoot, newRoot)

        assertEquals(2, moved)
        assertEquals("existing", File(destinationFonts, "Example.ttf").readText())
        assertEquals("old", File(destinationFonts, "Example-2.ttf").readText())
        assertEquals("nested", File(destinationFonts, "nested.otf").readText())
        assertFalse(File(oldFonts, "Example.ttf").exists())
        assertFalse(File(oldFonts, "nested.otf").exists())
        assertTrue(File(oldFonts, "ignore.txt").isFile)
    }

    @Test
    fun synchronizingSameRootOnlyEnsuresDestinationWithoutMovingFiles() {
        val root = temporaryFolder.newFolder("root")
        val fonts = File(root, SubtitleFontManager.FONT_DIR_NAME).apply { mkdirs() }
        File(fonts, "Example.ttf").writeText("font")

        val moved = SubtitleFontManager.synchronizeSoftwareDirectory(root, root)

        assertEquals(0, moved)
        assertTrue(File(root, "fonts/Example.ttf").isFile)
    }
}
