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

    private fun media(file: File) = file.extension.lowercase(Locale.ROOT) in setOf(
        "mp3", "wav", "flac", "aac", "ogg", "oga", "opus", "m4a", "m4b", "wma", "ape", "wv", "aif", "aiff",
        "mp4", "m4v", "mov", "mkv", "avi", "webm", "flv", "wmv", "ts", "3gp", "mpg", "mpeg", "mts", "m2ts"
    )
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
        val wav = directory.newFile("third.WAV")
        assertEquals(MediaTagFileSelection(listOf(first), null), select(first))
        assertEquals(MediaTagFileSelection(listOf(first, second), null), select(first, second))
        assertEquals(MediaTagFileSelection(listOf(wav), null), select(wav))
        assertEquals(MediaTagFileSelection(listOf(first, wav), null), select(first, wav))
    }

    @Test fun unsupportedArtworkTargetsRejectTheImageShortcutInEitherOrder() {
        val cover = directory.newFile("cover.jpg")
        for (extension in listOf("WAV", "avi", "webm", "flv", "wmv", "ts", "3gp", "mpg", "mpeg", "mts", "m2ts")) {
            val source = directory.newFile("source.$extension")
            assertNull(extension, select(source, cover))
            assertNull(extension, select(cover, source))
            assertEquals(MediaTagFileSelection(listOf(source), null), select(source))
        }
    }

    @Test fun everyTargetMustSupportArtworkWhenAnImageIsSelected() {
        val wav = directory.newFile("source.wav")
        val mp3 = directory.newFile("source.mp3")
        val cover = directory.newFile("cover.png")
        assertNull(select(mp3, cover, wav))
        assertNull(select(wav, mp3, cover))
        assertNull(select(cover, wav, mp3))
    }

    @Test fun nativeAudioArtworkAndSupportedVideoShortcutsRemainAvailable() {
        val cover = directory.newFile("cover.png")
        for (extension in listOf("MP3", "flac", "aac", "ogg", "oga", "opus", "m4a", "m4b", "wma", "ape", "wv",
            "aif", "aiff", "mp4", "m4v", "mov", "mkv")) {
            val source = directory.newFile("source.$extension")
            assertEquals(MediaTagFileSelection(listOf(source), cover), select(source, cover))
        }
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
