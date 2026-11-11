package com.subtitleedit

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaConvertFileSelectionPolicyTest {
    @get:Rule val directory = TemporaryFolder()

    private fun isMedia(file: File) = file.extension.lowercase(Locale.ROOT) in setOf("mp3", "wav", "mp4", "mkv")
    private fun select(vararg files: File) = MediaConvertFileSelectionPolicy.fromFiles(files.toList(), ::isMedia)

    @Test fun oneMediaAndMultipleSubtitlesKeepTheirRelativeOrder() {
        val video = directory.newFile("first.mp4")
        val original = directory.newFile("Original.SRT")
        val translated = directory.newFile("翻译.lrc")
        assertEquals(MediaConvertFileSelection(listOf(video), listOf(original, translated)),
            select(original, video, translated))
    }

    @Test fun multipleMediaFilesWithAnySubtitleDoNotOfferTheShortcut() {
        val video = directory.newFile("first.mp4")
        val audio = directory.newFile("second.mp3")
        val subtitle = directory.newFile("captions.srt")
        assertNull(select(video, subtitle, audio))
        assertNull(select(audio, video, subtitle))
    }

    @Test fun singleAndMultipleMediaFilesNeedNoSubtitle() {
        val audio = directory.newFile("one.wav")
        val video = directory.newFile("two.mkv")
        assertEquals(MediaConvertFileSelection(listOf(audio), emptyList()), select(audio))
        assertEquals(MediaConvertFileSelection(listOf(audio, video), emptyList()), select(audio, video))
    }

    @Test fun everySupportedTextSubtitleCanBeImported() {
        val media = directory.newFile("video.mp4")
        for (extension in listOf("SRT", "ASS", "SSA", "VTT", "LRC", "TXT")) {
            val subtitle = directory.newFile("captions.$extension")
            assertEquals(listOf(subtitle), select(media, subtitle)!!.subtitleFiles)
        }
    }

    @Test fun subtitleOnlyAndEmptySelectionsDoNotOfferMediaProcessing() {
        assertNull(select())
        assertNull(select(directory.newFile("captions.srt")))
    }

    @Test fun unrelatedFilesFoldersAndMissingFilesRejectTheWholeSelection() {
        val media = directory.newFile("video.mp4")
        assertNull(select(media, directory.newFile("cover.jpg")))
        assertNull(select(media, directory.newFile("notes.md")))
        assertNull(select(media, directory.newFile("binary.sub")))
        assertNull(select(media, directory.newFolder("folder.srt")))
        assertNull(select(media, directory.newFolder("folder.mp4")))
        assertNull(select(media, File(directory.root, "missing.srt")))
    }
}
