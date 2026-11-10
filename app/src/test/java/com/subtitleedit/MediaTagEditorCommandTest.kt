package com.subtitleedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTagEditorCommandTest {
    @Test fun rawAacFallbackExplicitlyEnablesId3Tags() {
        val arguments = MediaTagEditorCommand.build("source.aac", "output.aac", "adts",
            listOf(MediaTagStream(0, "audio")),
            MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, "Title"), true)
        assertTrue(arguments.windowed(2).contains(listOf("-write_id3v2", "1")))
    }

    private val videoStreams = listOf(
        MediaTagStream(0, "video"), MediaTagStream(1, "audio"), MediaTagStream(2, "audio"),
        MediaTagStream(3, "subtitle"), MediaTagStream(4, "attachment"),
        MediaTagStream(5, "attachment", coverAttachment = true)
    )

    @Test fun defaultArtworkPreservesAllStreamsAndOnlyOverridesEditedTags() {
        val args = MediaTagEditorCommand.build("source.mkv", "temporary", "matroska", videoStreams,
            MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, "New title"), false)
        assertTrue(args.windowed(2).contains(listOf("-map", "0")))
        assertTrue(args.windowed(2).contains(listOf("-c", "copy")))
        assertTrue(args.windowed(2).contains(listOf("-map_chapters", "0")))
        assertEquals(listOf("title=New title"), args.windowed(2).filter { it[0] == "-metadata" }.map { it[1] })
        assertFalse(args.any { it.startsWith("-0:") })
        assertFalse("-sn" in args || "-dn" in args || "-vn" in args)
        assertEquals(listOf("-f", "matroska", "temporary"), args.takeLast(3))
    }

    @Test fun replacingMatroskaArtworkKeepsFontsAndIndexesTheNewAttachmentAfterThem() {
        val args = MediaTagEditorCommand.build("source.mkv", "temporary", "matroska", videoStreams,
            MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = "cover image.jpg"), false)
        assertTrue(args.windowed(2).contains(listOf("-map", "-0:5")))
        assertFalse("-0:4" in args)
        assertTrue(args.windowed(2).contains(listOf("-attach", "cover image.jpg")))
        assertTrue(args.windowed(2).contains(listOf("-metadata:s:t:1", "filename=cover.jpg")))
    }

    @Test fun replacementPictureIndexCountsAllRetainedVideoTracks() {
        val streams = listOf(MediaTagStream(0, "video"), MediaTagStream(1, "audio"),
            MediaTagStream(2, "video", attachedPicture = true), MediaTagStream(3, "video"))
        val args = MediaTagEditorCommand.build("source.mp4", "temporary", "mp4", streams,
            MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = "cover.jpg"), false)
        assertTrue(args.windowed(2).contains(listOf("-map", "-0:2")))
        assertTrue(args.windowed(2).contains(listOf("-map", "1:v:0")))
        assertTrue(args.windowed(2).contains(listOf("-disposition:v:2", "attached_pic")))
    }

    @Test fun removingArtworkDoesNotRemoveActualVideoOrOtherAttachments() {
        val args = MediaTagEditorCommand.build("source.mkv", "temporary", "matroska", videoStreams,
            MediaConvertMetadata(cover = MediaConvertCoverMode.NONE), false)
        assertEquals(listOf("-0:5"), args.filter { it.startsWith("-0:") })
        assertFalse("-attach" in args)
    }

    @Test(expected = IllegalArgumentException::class)
    fun customArtworkRequiresPreparedImage() {
        MediaTagEditorCommand.build("source.mp3", "temporary", "mp3", listOf(MediaTagStream(0, "audio")),
            MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM), true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun customArtworkRejectsUnsupportedContainers() {
        MediaTagEditorCommand.build("source.ogg", "temporary", "ogg", listOf(MediaTagStream(0, "audio")),
            MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = "cover.jpg"), true)
    }

    @Test fun oggEditsThePrimaryAudioCommentsWithoutRenamingOtherTracks() {
        val args = MediaTagEditorCommand.build("source.ogg", "temporary", "ogg",
            listOf(MediaTagStream(0, "audio"), MediaTagStream(1, "audio")),
            MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, "New title"), true)
        assertTrue(args.windowed(2).contains(listOf("-metadata:s:0", "title=New title")))
        assertFalse("-metadata:s:1" in args)
    }

    @Test fun clearingATagRemovesPrimaryStreamFallbacksAndPreservesOtherTracks() {
        val args = MediaTagEditorCommand.build("source.mkv", "temporary", "matroska", videoStreams,
            MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, ""), false)
        assertTrue(args.windowed(2).contains(listOf("-metadata:s:0", "title=")))
        assertTrue(args.windowed(2).contains(listOf("-metadata:s:1", "title=")))
        assertFalse("-metadata:s:2" in args)
    }
}
