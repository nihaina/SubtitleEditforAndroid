package com.subtitleedit

import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaProcessingSelectionPolicyTest {
    @get:Rule val directory = TemporaryFolder()

    private fun isMedia(file: File) = file.extension.lowercase(Locale.ROOT) in setOf("mp3", "wav", "mp4", "mkv")
    private fun select(vararg files: File) = MediaProcessingSelectionPolicy.fromFiles(files.toList(), ::isMedia)

    @Test fun mediaOnlySelectionsOfferBothPagesWithoutReorderingInputs() {
        val audio = directory.newFile("second.wav")
        val video = directory.newFile("first.mp4")
        for (files in listOf(listOf(video), listOf(audio), listOf(audio, video))) {
            val selection = requireNotNull(MediaProcessingSelectionPolicy.fromFiles(files, ::isMedia))
            assertTrue(selection.supports(MediaProcessingPage.CONVERT))
            assertTrue(selection.supports(MediaProcessingPage.MUX))
            assertEquals(files, selection.files)
            assertEquals(files, selection.conversion!!.mediaFiles)
            assertEquals(files, selection.muxFiles)
        }
    }

    @Test fun oneMediaWithSubtitlesOffersOnlyConversionAndRetainsBothOrders() {
        val video = directory.newFile("movie.mkv")
        val original = directory.newFile("原文.SRT")
        val translated = directory.newFile("translated.lrc")
        val selection = requireNotNull(select(original, video, translated))
        assertTrue(selection.supports(MediaProcessingPage.CONVERT))
        assertFalse(selection.supports(MediaProcessingPage.MUX))
        assertEquals(listOf(original, video, translated), selection.files)
        assertEquals(listOf(video), selection.conversion!!.mediaFiles)
        assertEquals(listOf(original, translated), selection.conversion.subtitleFiles)
        assertNull(selection.muxFiles)
    }

    @Test fun multipleMediaWithAnySubtitleOffersNeitherPage() {
        val video = directory.newFile("movie.mp4")
        val audio = directory.newFile("song.mp3")
        val subtitle = directory.newFile("caption.srt")
        assertNull(select(video, audio, subtitle))
        assertNull(select(subtitle, audio, video))
    }

    @Test fun emptySubtitleOnlyAndUnrelatedSelectionsDoNotOfferTheMenu() {
        assertNull(select())
        val video = directory.newFile("movie.mp4")
        val subtitle = directory.newFile("caption.srt")
        assertNull(select(subtitle))
        assertNull(select(video, directory.newFile("cover.jpg")))
        assertNull(select(video, directory.newFile("unknown.sub")))
        assertNull(select(video, directory.newFolder("folder.mp4")))
        assertNull(select(video, File(directory.root, "missing.mp3")))
    }

    @Test fun chooserSnapshotCanBeRevalidatedAfterItsSourceDisappears() {
        val video = directory.newFile("movie.mp4")
        val audio = directory.newFile("song.mp3")
        val pending = requireNotNull(select(video, audio))
        assertTrue(audio.delete())
        assertNull(MediaProcessingSelectionPolicy.fromFiles(pending.files, ::isMedia))
    }
}
