package com.subtitleedit

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaTagEditorWavMetadataTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun editingBothId3VersionsPreservesRawLyricsLanguagesFlagsAndUnknownFrames() {
        for (version in listOf(3, 4)) {
            val preserved = listOf(
                WavTagFixture.Frame("USLT", byteArrayOf(0, 0), byteArrayOf(0) + "engOriginal\u0000[00:00.125]First lyric".toByteArray()),
                WavTagFixture.Frame("USLT", byteArrayOf(0, 0), byteArrayOf(0) + "fraTranslation\u0000[00:00.125]Autre parole".toByteArray()),
                WavTagFixture.Frame("SYLT", byteArrayOf(0, 0), byteArrayOf(0) + "jpn".toByteArray() +
                    byteArrayOf(2, 1) + "Timed\u0000First\u0000".toByteArray() + WavTagFixture.intBytes(125, ByteOrder.BIG_ENDIAN)),
                WavTagFixture.Frame("PRIV", byteArrayOf(0x10, 0), byteArrayOf(4, 0, 7, 9))
            )
            val oldText = listOf("TIT2", "TPE1", "TALB", "TCON", "TYER", "TDRC").map {
                WavTagFixture.Frame(it, byteArrayOf(0, 0), byteArrayOf(0) + "Old".toByteArray())
            }
            val sourceBytes = WavTagFixture.wav(WavTagFixture.chunk("data", byteArrayOf(1, 2, 3)),
                WavTagFixture.chunk("id3 ", WavTagFixture.tag(version, oldText + preserved)))
            val source = temporary.newFile("source-$version.wav").apply { writeBytes(sourceBytes) }
            val audio = WavTagFixture.chunk("data", byteArrayOf(8, 9, 10, 11))
            val unknown = WavTagFixture.chunk("JUNK", byteArrayOf(12, 13, 14))
            val target = temporary.newFile("edited-$version.wav").apply { writeBytes(WavTagFixture.wav(unknown, audio)) }
            val changes = MediaConvertMetadata()
                .withValue(MediaConvertMetadataField.TITLE, "新标题 🌊")
                .withValue(MediaConvertMetadataField.ARTIST, "新歌手")
                .withValue(MediaConvertMetadataField.ALBUM, "新专辑")
                .withValue(MediaConvertMetadataField.GENRE, "Jazz")
                .withValue(MediaConvertMetadataField.YEAR, "2026")

            MediaConvertId3Lyrics.restoreWavMetadata(source, target, changes, MediaConvertMetadata())

            val tag = WavTagFixture.readTag(target)
            assertEquals(version, tag.version)
            assertEquals("新标题 🌊", WavTagFixture.text(tag.frames.single { it.id == "TIT2" }))
            assertEquals("新歌手", WavTagFixture.text(tag.frames.single { it.id == "TPE1" }))
            assertEquals("新专辑", WavTagFixture.text(tag.frames.single { it.id == "TALB" }))
            assertEquals("Jazz", WavTagFixture.text(tag.frames.single { it.id == "TCON" }))
            val years = tag.frames.filter { it.id in setOf("TYER", "TDRC") }
            assertEquals(listOf(if (version == 3) "TYER" else "TDRC"), years.map { it.id })
            assertEquals("2026", WavTagFixture.text(years.single()))
            val actualPreserved = tag.frames.filter { it.id in setOf("USLT", "SYLT", "PRIV") }
            assertEquals(preserved.map { it.id }, actualPreserved.map { it.id })
            preserved.zip(actualPreserved).forEach { (expected, actual) ->
                assertArrayEquals(expected.flags, actual.flags)
                assertArrayEquals(expected.body, actual.body)
            }
            val chunks = WavTagFixture.chunks(target.readBytes())
            assertArrayEquals(unknown, chunks.first { it.first == "JUNK" }.second)
            assertArrayEquals(audio, chunks.first { it.first == "data" }.second)
            assertArrayEquals(sourceBytes, source.readBytes())
        }
    }

    @Test fun infoOnlySourceSeedsUneditedTextForId3ReadersAndClearsDoNotRestoreIt() {
        val source = temporary.newFile("info-only.wav").apply {
            writeBytes(WavTagFixture.wav(WavTagFixture.chunk("data", byteArrayOf(1, 2))))
        }
        val original = MediaConvertMetadata(title = "Original", artist = "Singer", album = "Album", genre = "Rock", year = "2001")
        val edited = temporary.newFile("edited.wav").apply { writeBytes(source.readBytes()) }
        MediaConvertId3Lyrics.restoreWavMetadata(source, edited,
            MediaConvertMetadata().withValue(MediaConvertMetadataField.ARTIST, "New singer"), original)
        val seeded = WavTagFixture.readTag(edited).frames.associate { it.id to WavTagFixture.text(it) }
        assertEquals(mapOf("TIT2" to "Original", "TPE1" to "New singer", "TALB" to "Album", "TCON" to "Rock", "TYER" to "2001"), seeded)
        val cleared = temporary.newFile("cleared.wav").apply { writeBytes(source.readBytes()) }
        val changes = MediaConvertMetadata.audioFields.fold(MediaConvertMetadata()) { metadata, field -> metadata.withValue(field, "") }
        MediaConvertId3Lyrics.restoreWavMetadata(edited, cleared, changes, original.copy(artist = "New singer"))
        assertTrue(WavTagFixture.readTag(cleared).frames.isEmpty())
    }

    @Test fun malformedOriginalId3CannotReplaceTheStagingOutput() {
        val sourceBytes = WavTagFixture.wav(WavTagFixture.chunk("id3 ", "invalid id3".toByteArray()))
        val source = temporary.newFile("invalid.wav").apply { writeBytes(sourceBytes) }
        val targetBytes = WavTagFixture.wav(WavTagFixture.chunk("data", byteArrayOf(1, 2)))
        val target = temporary.newFile("untouched.wav").apply { writeBytes(targetBytes) }
        try {
            MediaConvertId3Lyrics.restoreWavMetadata(source, target,
                MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, "New"), MediaConvertMetadata())
            fail("Malformed source tags were silently discarded")
        } catch (_: IllegalArgumentException) { }
        assertArrayEquals(sourceBytes, source.readBytes())
        assertArrayEquals(targetBytes, target.readBytes())
    }
}

/** Independent byte-level observations: no production tag parsing or serialization helpers. */
internal object WavTagFixture {
    data class Frame(val id: String, val flags: ByteArray, val body: ByteArray)
    data class Tag(val version: Int, val frames: List<Frame>)

    fun readTag(file: File): Tag {
        val id3 = chunks(file.readBytes()).single { it.first.equals("id3 ", ignoreCase = true) }.second
        val bytes = id3.copyOfRange(8, 8 + uint32(id3, 4, ByteOrder.LITTLE_ENDIAN))
        assertEquals("ID3", String(bytes, 0, 3, Charsets.US_ASCII))
        val version = bytes[3].toInt()
        val end = 10 + synchsafe(bytes, 6)
        val frames = mutableListOf<Frame>()
        var offset = 10
        while (offset < end && bytes[offset] != 0.toByte()) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = if (version == 4) synchsafe(bytes, offset + 4) else uint32(bytes, offset + 4, ByteOrder.BIG_ENDIAN)
            frames += Frame(id, bytes.copyOfRange(offset + 8, offset + 10), bytes.copyOfRange(offset + 10, offset + 10 + length))
            offset += 10 + length
        }
        assertTrue(offset <= end)
        return Tag(version, frames)
    }

    fun text(frame: Frame): String {
        val encoding = when (frame.body[0].toInt()) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> throw AssertionError("Unknown text encoding")
        }
        return String(frame.body, 1, frame.body.size - 1, encoding).trimEnd('\u0000')
    }

    fun chunks(bytes: ByteArray): List<Pair<String, ByteArray>> {
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(bytes.size - 8, uint32(bytes, 4, ByteOrder.LITTLE_ENDIAN))
        val result = mutableListOf<Pair<String, ByteArray>>()
        var offset = 12
        while (offset < bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = uint32(bytes, offset + 4, ByteOrder.LITTLE_ENDIAN)
            val end = offset + 8 + size + (size and 1)
            result += id to bytes.copyOfRange(offset, end)
            offset = end
        }
        assertEquals(bytes.size, offset)
        return result
    }

    fun tag(version: Int, frames: List<Frame>): ByteArray {
        val payload = ByteArrayOutputStream()
        frames.forEach { frame ->
            payload.write(frame.id.toByteArray(Charsets.US_ASCII))
            payload.write(if (version == 4) synchsafeBytes(frame.body.size) else intBytes(frame.body.size, ByteOrder.BIG_ENDIAN))
            payload.write(frame.flags)
            payload.write(frame.body)
        }
        return "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + synchsafeBytes(payload.size()) + payload.toByteArray()
    }

    fun wav(vararg chunks: ByteArray): ByteArray {
        val payload = "WAVE".toByteArray() + chunks.fold(byteArrayOf()) { all, chunk -> all + chunk }
        return "RIFF".toByteArray() + intBytes(payload.size, ByteOrder.LITTLE_ENDIAN) + payload
    }

    fun chunk(id: String, body: ByteArray): ByteArray = id.toByteArray() + intBytes(body.size, ByteOrder.LITTLE_ENDIAN) +
        body + if (body.size and 1 != 0) byteArrayOf(0x35) else byteArrayOf()

    fun intBytes(value: Int, order: ByteOrder): ByteArray = ByteBuffer.allocate(4).order(order).putInt(value).array()
    private fun uint32(bytes: ByteArray, offset: Int, order: ByteOrder): Int = ByteBuffer.wrap(bytes, offset, 4).order(order).int
    private fun synchsafe(bytes: ByteArray, offset: Int): Int = (0..3).fold(0) { result, index ->
        (result shl 7) or (bytes[offset + index].toInt() and 0x7f)
    }
    private fun synchsafeBytes(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7f).toByte(), ((value ushr 14) and 0x7f).toByte(),
        ((value ushr 7) and 0x7f).toByte(), (value and 0x7f).toByte()
    )
}
