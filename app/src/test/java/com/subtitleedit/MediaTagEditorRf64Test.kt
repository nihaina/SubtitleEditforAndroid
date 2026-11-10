package com.subtitleedit

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaTagEditorRf64Test {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rf64AndBw64TagEditsKeepSizeTablesAudioAndLyrics() {
        for (container in listOf("RF64", "BW64")) {
            val lyric = WavTagFixture.Frame("USLT", byteArrayOf(0, 0),
                byteArrayOf(0) + "engLyrics\u0000[00:00.125]Original lyric".toByteArray())
            val tag = WavTagFixture.chunk("id3 ", WavTagFixture.tag(3, listOf(lyric)))
            val sourceBytes = largeWav(container, tag)
            val source = temporary.newFile("source-$container.wav").apply { writeBytes(sourceBytes) }
            val target = temporary.newFile("target-$container.wav").apply { writeBytes(largeWav(container)) }
            val before = target.readBytes()
            MediaConvertId3Lyrics.restoreWavMetadata(source, target,
                MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, "Changed title"), MediaConvertMetadata())
            val after = target.readBytes()
            assertEquals(container, String(after, 0, 4, Charsets.US_ASCII))
            assertEquals(-1, littleInt(after, 4))
            assertEquals(after.size - 8L, littleLong(after, 20))
            assertEquals(4L, littleLong(after, 28))
            assertEquals(123L, littleLong(after, 36))
            assertEquals(1, littleInt(after, 44))
            assertEquals(3L, littleLong(after, 52))
            // ds64 ends at 60, followed by the sentinel-sized JUNK and data chunks.
            assertArrayEquals(before.copyOfRange(60, 84), after.copyOfRange(60, 84))
            assertEquals(source.inputStream().buffered().use(MediaConvertEmbeddedLyricReader::read),
                target.inputStream().buffered().use(MediaConvertEmbeddedLyricReader::read))
            val normal = temporary.newFile("inspect-$container.wav").apply { writeBytes(normalizeFixture(after)) }
            val frames = WavTagFixture.readTag(normal).frames
            assertEquals("Changed title", WavTagFixture.text(frames.single { it.id == "TIT2" }))
            assertArrayEquals(lyric.body, frames.single { it.id == "USLT" }.body)
            assertArrayEquals(sourceBytes, source.readBytes())
        }
    }

    @Test fun largeContainerSourceCanRestoreTagsIntoRiffOutput() {
        val source = temporary.newFile("source.rf64").apply { writeBytes(largeWav("RF64")) }
        val target = temporary.newFile("small-remux.wav").apply {
            writeBytes(WavTagFixture.wav(WavTagFixture.chunk("data", byteArrayOf(1, 2, 3, 4))))
        }
        MediaConvertId3Lyrics.restoreWavMetadata(source, target,
            MediaConvertMetadata().withValue(MediaConvertMetadataField.ARTIST, "Singer"), MediaConvertMetadata())
        assertEquals("RIFF", String(target.readBytes(), 0, 4, Charsets.US_ASCII))
        assertEquals("Singer", WavTagFixture.text(WavTagFixture.readTag(target).frames.single()))
    }

    @Test fun invalidLargeContainerSizeTableDoesNotReplaceEitherFile() {
        val sourceBytes = largeWav("RF64").also { it[20] = 0 }
        val source = temporary.newFile("bad.rf64").apply { writeBytes(sourceBytes) }
        val targetBytes = largeWav("BW64")
        val target = temporary.newFile("untouched.wav").apply { writeBytes(targetBytes) }
        try {
            MediaConvertId3Lyrics.restoreWavMetadata(source, target,
                MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, "New"), MediaConvertMetadata())
            fail("Invalid ds64 was accepted")
        } catch (_: IllegalArgumentException) { }
        assertArrayEquals(sourceBytes, source.readBytes())
        assertArrayEquals(targetBytes, target.readBytes())
    }

    private fun largeWav(container: String, vararg tags: ByteArray): ByteArray {
        val junk = "JUNK".toByteArray() + int(-1) + byteArrayOf(7, 8, 9, 0)
        val audio = "data".toByteArray() + int(-1) + byteArrayOf(1, 2, 3, 4)
        val tail = junk + audio + tags.fold(byteArrayOf()) { all, bytes -> all + bytes }
        val ds64 = WavTagFixture.chunk("ds64", long(52L + tail.size) + long(4) + long(123) + int(1) +
            "JUNK".toByteArray() + long(3))
        return container.toByteArray() + int(-1) + "WAVE".toByteArray() + ds64 + tail
    }

    private fun normalizeFixture(bytes: ByteArray): ByteArray = bytes.copyOf().apply {
        "RIFF".toByteArray().copyInto(this)
        int(size - 8).copyInto(this, 4)
        int(3).copyInto(this, 64)
        int(4).copyInto(this, 76)
    }
    private fun int(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun long(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    private fun littleInt(bytes: ByteArray, offset: Int): Int = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun littleLong(bytes: ByteArray, offset: Int): Long = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
}
