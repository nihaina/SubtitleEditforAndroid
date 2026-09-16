package com.subtitleedit.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp3SeekIndexTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun unindexedCbrFramesAreReportedWithoutScanningTheWholeFile() {
        assertEquals(false, Mp3SeekIndex.hasSeekIndex(mp3(frame(), frame())))
    }

    @Test
    fun xingAndInfoIndexesAreRecognizedForDifferentMpegVersionsAndChannels() {
        val headers = listOf(
            Triple("fffb9064", 417, 36), // MPEG-1 stereo, 44.1 kHz, 128 kbps
            Triple("fffb90c0", 417, 21), // MPEG-1 mono
            Triple("fff380c0", 208, 13), // MPEG-2 mono, 22.05 kHz, 64 kbps
            Triple("ffe380c0", 417, 13), // MPEG-2.5 mono, 11.025 kHz, 64 kbps
            Triple("fffa9064", 417, 38) // MPEG-1 stereo with CRC
        )
        for ((header, size, offset) in headers) {
            for (marker in listOf("Xing", "Info")) {
                val first = frame(header, size).apply { putXing(this, offset, marker) }
                assertEquals("$header $marker", true, Mp3SeekIndex.hasSeekIndex(mp3(first, frame(header, size))))
            }
        }
    }

    @Test
    fun id3ContentsIncludingFakeMpegFramesCannotMasqueradeAsAnIndex() {
        val fakeIndex = frame().apply { putXing(this, 36) } + frame()
        val tag = id3(fakeIndex)
        assertEquals(false, Mp3SeekIndex.hasSeekIndex(mp3(tag, frame(), frame())))
        assertEquals(true, Mp3SeekIndex.hasSeekIndex(mp3(tag, fakeIndex)))
    }

    @Test
    fun multipleTagsAndId3v24FooterAreSkipped() {
        val first = frame().apply { putXing(this, 36) }
        assertEquals(true, Mp3SeekIndex.hasSeekIndex(mp3(
            id3(ByteArray(7)), id3(ByteArray(11), footer = true), first, frame()
        )))
    }

    @Test
    fun xingMarkerWithoutUsableTocIsNotAnIndex() {
        for (flags in listOf(0, 1, 3)) {
            val first = frame().apply { putXing(this, 36); putUInt(this, 40, flags) }
            assertEquals(false, Mp3SeekIndex.hasSeekIndex(mp3(first, frame())))
        }
        val emptyToc = frame().apply {
            putXing(this, 36)
            fill(0, 52, 152)
        }
        assertEquals(false, Mp3SeekIndex.hasSeekIndex(mp3(emptyToc, frame())))
        val unorderedToc = frame().apply {
            putXing(this, 36)
            this[120] = 0
        }
        assertEquals(false, Mp3SeekIndex.hasSeekIndex(mp3(unorderedToc, frame())))
    }

    @Test
    fun xingTextOutsideItsHeaderPositionIsIgnored() {
        val first = frame().apply { putXing(this, 80) }
        assertEquals(false, Mp3SeekIndex.hasSeekIndex(mp3(first, frame())))
    }

    @Test
    fun vbriNeedsACompleteSeekTable() {
        val first = frame().apply {
            "VBRI".toByteArray().copyInto(this, 36)
            putUShort(this, 40, 1)
            putUInt(this, 46, 834)
            putUInt(this, 50, 2)
            putUShort(this, 54, 2)
            putUShort(this, 56, 1)
            putUShort(this, 58, 2)
            putUShort(this, 60, 1)
            putUShort(this, 62, 417)
            putUShort(this, 64, 417)
        }
        assertEquals(true, Mp3SeekIndex.hasSeekIndex(mp3(first, frame())))
        putUShort(first, 54, 65535)
        assertEquals(false, Mp3SeekIndex.hasSeekIndex(mp3(first, frame())))
    }

    @Test
    fun malformedOrIncompleteInputIsUnknownRatherThanConfirmedMissingIndex() {
        for (bytes in listOf(ByteArray(0), "not an MP3".toByteArray(), frame().copyOf(100), id3(ByteArray(10)).copyOf(12))) {
            assertNull(Mp3SeekIndex.hasSeekIndex(mp3(bytes)))
        }
    }

    @Test
    fun lastAudioFrameMayBeFollowedByAnId3v1Tag() {
        val tag = ByteArray(128).apply { "TAG".toByteArray().copyInto(this) }
        assertEquals(false, Mp3SeekIndex.hasSeekIndex(mp3(frame(), tag)))
    }

    @Test
    fun issuesIncludeEitherConditionOrBothButIgnoreUnknownValues() {
        assertFalse(Mp3FileIssues.from(0.0, true).hasIssues)
        assertFalse(Mp3FileIssues.from(null, null).hasIssues)
        assertFalse(Mp3FileIssues.from(Double.NaN, null).hasIssues)
        assertTrue(Mp3FileIssues.from(0.0, false).missingSeekIndex)
        assertEquals(0.025, Mp3FileIssues.from(0.025, true).nonZeroStartTimeSeconds)
        assertEquals(-0.01, Mp3FileIssues.from(-0.01, true).nonZeroStartTimeSeconds)
        val both = Mp3FileIssues.from(0.0001, false)
        assertEquals(0.0001, both.nonZeroStartTimeSeconds)
        assertTrue(both.missingSeekIndex)
    }

    private fun mp3(vararg parts: ByteArray): File = temporaryFolder.newFile().apply {
        outputStream().use { output -> parts.forEach { output.write(it) } }
    }

    private fun frame(header: String = "fffb9064", size: Int = 417): ByteArray =
        ByteArray(size).apply {
            header.chunked(2).forEachIndexed { index, byte -> this[index] = byte.toInt(16).toByte() }
        }

    private fun putXing(bytes: ByteArray, offset: Int, marker: String = "Xing") {
        marker.toByteArray().copyInto(bytes, offset)
        putUInt(bytes, offset + 4, 7)
        putUInt(bytes, offset + 8, 2)
        putUInt(bytes, offset + 12, bytes.size * 2)
        for (index in 0 until 100) bytes[offset + 16 + index] = (index * 255 / 100).toByte()
    }

    private fun id3(payload: ByteArray, footer: Boolean = false): ByteArray {
        val header = ByteArray(10)
        "ID3".toByteArray().copyInto(header)
        header[3] = if (footer) 4 else 3
        header[5] = if (footer) 0x10 else 0
        for (index in 0..3) header[6 + index] = ((payload.size ushr (21 - index * 7)) and 0x7f).toByte()
        return header + payload + if (footer) ByteArray(10) else ByteArray(0)
    }

    private fun putUShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun putUInt(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0..3) bytes[offset + index] = (value ushr (24 - index * 8)).toByte()
    }
}
