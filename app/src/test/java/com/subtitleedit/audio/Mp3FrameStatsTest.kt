package com.subtitleedit.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp3FrameStatsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun unpadded320kFramesExposeTheReportedFilesRateDeficit() {
        val audio = frame("fffbe064", 1044)
        val file = mp3(id3(ByteArray(9193)), audio, audio, id3v1())
        // This is what a no-index probe does: estimate duration from bytes / nominal bitrate.
        val estimatedDuration = audio.size * 2 * 8.0 / 320_000.0
        assertFalse(Mp3FileIssues.from(0.0, file.length(), estimatedDuration, 320_000.0).hasIssues)

        val stats = requireNotNull(Mp3FrameStats.read(file))
        assertEquals(2088L, stats.byteCount)
        assertEquals(2304L, stats.sampleCount)
        assertEquals(44100, stats.sampleRate)
        assertEquals(319_725.0, stats.byteCount * 8.0 / stats.durationSeconds, 0.000001)
        assertTrue(Mp3FileIssues.from(0.0, stats.byteCount, stats.durationSeconds, 320_000.0).hasIssues)
    }

    @Test
    fun missingIndexAloneDoesNotWarnWhenTheMeasuredRateMatches() {
        val stats = requireNotNull(Mp3FrameStats.read(mp3(frame("fffbe464", 960), frame("fffbe464", 960))))
        assertEquals(48000, stats.sampleRate)
        assertFalse(Mp3FileIssues.from(0.0, stats.byteCount, stats.durationSeconds, 320_000.0).hasIssues)
    }

    @Test
    fun leadingTagsAndCoverBytesAreNotCountedAsAudio() {
        val audio = frame()
        val fakeAudioInTag = audio + audio
        val plain = requireNotNull(Mp3FrameStats.read(mp3(audio, audio)))
        val tagged = requireNotNull(Mp3FrameStats.read(mp3(
            id3(fakeAudioInTag), id3(ByteArray(100_000), footer = true), audio, audio, id3v1()
        )))
        assertEquals(plain, tagged)
    }

    @Test
    fun optionalMetadataFramesDoNotContributeAudioBytesOrSamples() {
        val audio = frame()
        val plain = requireNotNull(Mp3FrameStats.read(mp3(audio, audio)))
        for (marker in listOf("Xing", "Info", "VBRI")) {
            val metadata = frame().apply { marker.toByteArray().copyInto(this, 36) }
            assertEquals(marker, plain, Mp3FrameStats.read(mp3(metadata, audio, audio)))
        }
        val crcMetadata = frame("fffa9064", 417).apply { "Info".toByteArray().copyInto(this, 38) }
        assertEquals(plain, Mp3FrameStats.read(mp3(crcMetadata, audio, audio)))
    }

    @Test
    fun mpegVersionsUseTheirOwnSampleCounts() {
        val cases = listOf(
            Triple("fffb90c0", 417, 44100),
            Triple("fff380c0", 208, 22050),
            Triple("ffe380c0", 417, 11025)
        )
        for ((header, size, rate) in cases) {
            val stats = requireNotNull(Mp3FrameStats.read(mp3(frame(header, size), frame(header, size))))
            assertEquals(size.toLong() * 2, stats.byteCount)
            assertEquals(rate, stats.sampleRate)
            assertEquals(if (rate == 44100) 2304L else 1152L, stats.sampleCount)
        }
    }

    @Test
    fun variableBitratesAndPaddingAreMeasuredFrameByFrame() {
        val stats = requireNotNull(Mp3FrameStats.read(mp3(
            frame(), frame("fffbe064", 1044), frame("fffb9264", 418)
        )))
        assertEquals(1879L, stats.byteCount)
        assertEquals(3456L, stats.sampleCount)
    }

    @Test
    fun apeAndId3v1TagsAreExcludedWithOrWithoutApeHeader() {
        val audio = frame()
        val plain = requireNotNull(Mp3FrameStats.read(mp3(audio, audio)))
        for (hasHeader in listOf(false, true)) {
            val footer = ByteArray(32).apply {
                "APETAGEX".toByteArray().copyInto(this)
                putUIntLe(this, 8, 2000)
                putUIntLe(this, 12, 39)
                if (hasHeader) putUIntLe(this, 20, Int.MIN_VALUE)
            }
            val header = if (hasHeader) footer.copyOf() else ByteArray(0)
            assertEquals(plain, Mp3FrameStats.read(mp3(audio, audio, header, ByteArray(7), footer, id3v1())))
        }
    }

    @Test
    fun incompleteUnsupportedOrInconsistentFramesHaveNoGuessedRate() {
        val audio = frame()
        for (parts in listOf(
            arrayOf(ByteArray(0)),
            arrayOf("not an MP3".toByteArray()),
            arrayOf(audio.copyOf(100)),
            arrayOf(audio, audio.copyOf(100)),
            arrayOf(id3(ByteArray(100)).copyOf(20)),
            arrayOf(audio, frame("fffb9464", 384)),
            arrayOf(frame("fffb0064", 417)),
            arrayOf(audio, byteArrayOf(1, 2, 3))
        )) {
            assertNull(Mp3FrameStats.read(mp3(*parts)))
        }
        assertNotNull(Mp3FrameStats.read(mp3(audio)))
    }

    private fun mp3(vararg parts: ByteArray): File = temporaryFolder.newFile().apply {
        outputStream().use { output -> parts.forEach { output.write(it) } }
    }

    private fun frame(header: String = "fffb9064", size: Int = 417): ByteArray =
        ByteArray(size).apply {
            header.chunked(2).forEachIndexed { index, byte -> this[index] = byte.toInt(16).toByte() }
        }

    private fun id3(payload: ByteArray, footer: Boolean = false): ByteArray {
        val header = ByteArray(10)
        "ID3".toByteArray().copyInto(header)
        header[3] = if (footer) 4 else 3
        header[5] = if (footer) 0x10 else 0
        for (index in 0..3) header[6 + index] = ((payload.size ushr (21 - index * 7)) and 0x7f).toByte()
        return header + payload + if (footer) ByteArray(10) else ByteArray(0)
    }

    private fun id3v1(): ByteArray = ByteArray(128).apply { "TAG".toByteArray().copyInto(this) }

    private fun putUIntLe(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0..3) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }
}
