package com.subtitleedit

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaConvertSourceCoverTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun picturePacketsAreCopiedWithoutReencodingIntoATaskOwnedFile() {
        val cache = temporary.newFolder()
        val source = MediaConvertSource(true, 30.0, attachedPictureStreamIndex = 2,
            attachedPictureCodecName = "png", attachmentStreamCount = 1)
        val originalPacket = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 1, 2, 3)
        val prepared = MediaConvertSourceCover.prepare("saf:source with spaces", cache, source) { args ->
            val pairs = args.toList().windowed(2)
            assertTrue(pairs.contains(listOf("-i", "saf:source with spaces")))
            assertTrue(pairs.contains(listOf("-map", "0:2")))
            assertTrue(pairs.contains(listOf("-c:v", "copy")))
            assertTrue(pairs.contains(listOf("-frames:v", "1")))
            File(args.last()).writeBytes(originalPacket)
            true
        }
        val image = File(requireNotNull(prepared.attachedPicturePath))
        assertEquals(cache.canonicalFile, image.parentFile.canonicalFile)
        assertEquals("png", image.extension)
        assertArrayEquals(originalPacket, image.readBytes())
        assertEquals(source, prepared.copy(attachedPicturePath = null))
        assertEquals("image/png", MediaConvertSourceCover.mimeType(image.path))
    }

    @Test fun sourcesWithoutPicturesAndAlreadyPreparedSourcesDoNotRunExtraction() {
        val cache = temporary.newFolder()
        val source = MediaConvertSource(true, 30.0)
        val prepared = source.copy(attachedPictureStreamIndex = 2, attachedPicturePath = "/task/cover.jpg")
        for (input in listOf(source, prepared)) {
            assertSame(input, MediaConvertSourceCover.prepare("source", cache, input) {
                fail("Unexpected image extraction")
                false
            })
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun unsuccessfulOrEmptyExtractionIsRejectedAndCleanedUp() {
        val cache = temporary.newFolder()
        val source = MediaConvertSource(true, 30.0, attachedPictureStreamIndex = 2, attachedPictureCodecName = "mjpeg")
        for (success in listOf(false, true)) {
            try {
                MediaConvertSourceCover.prepare("source", cache, source) { success }
                fail("Missing cover bytes were accepted")
            } catch (_: IllegalStateException) { }
            assertTrue(cache.listFiles()!!.isEmpty())
        }
    }

    @Test fun nativeExceptionsDoNotLeavePreparedImages() {
        val cache = temporary.newFolder()
        val failure = IllegalStateException("Native extraction failed")
        try {
            MediaConvertSourceCover.prepare("source", cache,
                MediaConvertSource(true, 30.0, attachedPictureStreamIndex = 2)) { throw failure }
            fail("Expected native exception")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertTrue(cache.listFiles()!!.isEmpty())
    }
}
