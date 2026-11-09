package com.subtitleedit

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaTagFileSelectionPolicyTest {
    @get:Rule val directory = TemporaryFolder()

    private fun media(file: File) = file.extension.lowercase(Locale.ROOT) in setOf("mp3", "wav", "mp4", "mkv")
    private fun select(vararg files: File) = MediaTagFileSelectionPolicy.fromFiles(files.toList(), ::media)

    @Test fun oneCoverIsSeparatedFromMediaWithoutChangingMediaOrder() {
        val first = directory.newFile("first.mp3")
        val second = directory.newFile("second.mp4")
        val cover = directory.newFile("Cover.JpG")
        for (files in listOf(listOf(cover, first, second), listOf(first, cover, second), listOf(first, second, cover))) {
            val selected = MediaTagFileSelectionPolicy.fromFiles(files, ::media)!!
            assertEquals(listOf(first, second), selected.mediaFiles)
            assertEquals(cover, selected.coverFile)
        }
    }

    @Test fun mediaOnlySelectionsKeepTheirExistingBehavior() {
        val first = directory.newFile("first.mp3")
        val second = directory.newFile("second.mkv")
        assertEquals(MediaTagFileSelection(listOf(first), null), select(first))
        assertEquals(MediaTagFileSelection(listOf(first, second), null), select(first, second))
    }

    @Test fun everyImageFormatAcceptedByTheCoverPickerCanBeSelected() {
        val source = directory.newFile("source.mp4")
        for (extension in listOf("JPG", "JPEG", "PNG", "WEBP", "BMP", "GIF", "HEIC", "HEIF")) {
            val cover = directory.newFile("cover.$extension")
            assertEquals(cover, select(source, cover)!!.coverFile)
        }
    }

    @Test fun selectingOnlyImagesOrNothingDoesNotOpenTheTagEditor() {
        val cover = directory.newFile("cover.png")
        assertNull(select())
        assertNull(select(cover))
    }

    @Test fun multipleImagesAreRejectedInsteadOfChoosingOneArbitrarily() {
        val source = directory.newFile("source.mp4")
        val first = directory.newFile("first.jpg")
        val second = directory.newFile("second.png")
        assertNull(select(source, first, second))
    }

    @Test fun unrelatedFilesDirectoriesAndMissingFilesAreRejected() {
        val source = directory.newFile("source.mp4")
        val subtitle = directory.newFile("captions.srt")
        val imageDirectory = directory.newFolder("folder.jpg")
        val mediaDirectory = directory.newFolder("folder.mp3")
        val missing = File(directory.root, "missing.jpg")
        assertNull(select(source, subtitle))
        assertNull(select(source, imageDirectory))
        assertNull(select(source, mediaDirectory))
        assertNull(select(source, missing))
    }
}
