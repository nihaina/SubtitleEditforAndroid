package com.subtitleedit

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaConvertId3LyricsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun mp3WritesTwoUnicodeLyricsAndMillisecondSynchronizedFrames() {
        val audio = ByteArray(150_001) { (it * 37).toByte() }
        val file = newFile("song.mp3", audio)
        val first = "[ti:海风]\n[offset:125]\n[00:01.00][00:03.250]你好 🌊\n[00:05.50]终章\n"
        val second = "[00:00.05]Translation 🎵\n[00:02.99]Another line\n"

        MediaConvertId3Lyrics.writeMp3(file, listOf(
            MediaConvertLyric("中文 🌏", first), MediaConvertLyric("中文 🌏", second)
        ))

        val result = file.readBytes()
        val tag = decodeTag(result)
        assertEquals(3, tag.version)
        assertArrayEquals(audio, result.copyOfRange(tag.end, result.size))
        val plain = tag.frames.filter { it.id == "USLT" }.map(::decodeUslt)
        assertEquals(listOf("中文 🌏", "中文 🌏 (2)"), plain.map { it.descriptor })
        assertEquals(listOf(first, second), plain.map { it.text })
        val synced = tag.frames.filter { it.id == "SYLT" }.map(::decodeSylt)
        assertEquals(plain.map { it.descriptor }, synced.map { it.first })
        assertEquals(listOf(1125L to "你好 🌊", 3375L to "你好 🌊", 5625L to "终章"), synced[0].second)
        assertEquals(listOf(50L to "Translation 🎵", 2990L to "Another line"), synced[1].second)
    }

    @Test fun existingV23NonLyricFramesFlagsAndAudioArePreserved() {
        val title = RawFrame("TIT2", byteArrayOf(0, 0x40), byteArrayOf(0) + "Original title".toByteArray())
        val privateFrame = RawFrame("PRIV", byteArrayOf(0, 0), byteArrayOf(9, 0, 8, 7))
        val audio = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 2, 3, 4)
        val existing = tagBytes(3, listOf(title, oldLyric("USLT"), privateFrame, oldLyric("SYLT")), padding = 14)
        val file = newFile("tagged.mp3", existing + audio)

        MediaConvertId3Lyrics.writeMp3(file, listOf(MediaConvertLyric("New", "[00:00.00]新歌词\n")))

        val bytes = file.readBytes()
        val tag = decodeTag(bytes)
        assertEquals(listOf("TIT2", "PRIV", "USLT", "SYLT"), tag.frames.map { it.id })
        assertFrameEquals(title, tag.frames[0])
        assertFrameEquals(privateFrame, tag.frames[1])
        assertArrayEquals(audio, bytes.copyOfRange(tag.end, bytes.size))
        assertEquals("[00:00.00]新歌词\n", decodeUslt(tag.frames[2]).text)
    }

    @Test fun v24SyncsafeFramesAndFooterAreReadWithoutChangingFrameEncoding() {
        val title = RawFrame("TIT2", byteArrayOf(0x10, 0x02), byteArrayOf(3) + "Title ".repeat(40).toByteArray())
        val audio = byteArrayOf(11, 12, 13, 14)
        val file = newFile("v24.mp3", tagBytes(4, listOf(title), extended = true, footer = true) + audio)

        MediaConvertId3Lyrics.writeMp3(file, listOf(MediaConvertLyric("English", "[00:01.234]Text 🎶\n")))

        val bytes = file.readBytes()
        val tag = decodeTag(bytes)
        assertEquals(4, tag.version)
        assertEquals(0, bytes[5].toInt())
        assertFrameEquals(title, tag.frames.first())
        assertEquals(listOf(1234L to "Text 🎶"), decodeSylt(tag.frames.last()).second)
        assertArrayEquals(audio, bytes.copyOfRange(tag.end, bytes.size))
    }

    @Test fun v23ExtendedHeaderIsRemovedWhileMetadataIsPreserved() {
        val title = RawFrame("TIT2", byteArrayOf(0, 0), byteArrayOf(0) + "Name".toByteArray())
        val file = newFile("extended.mp3", tagBytes(3, listOf(title), extended = true) + byteArrayOf(7, 8))
        MediaConvertId3Lyrics.writeMp3(file, listOf(MediaConvertLyric("", "[00:00.00]Start\n")))
        val bytes = file.readBytes()
        assertEquals(0, bytes[5].toInt())
        val tag = decodeTag(bytes)
        assertFrameEquals(title, tag.frames.first())
        assertEquals("Lyrics", decodeUslt(tag.frames[1]).descriptor)
    }

    @Test fun wavPreservesDataUnknownChunksPaddingAndExistingMetadata() {
        val format = chunk("fmt ", ByteArray(16) { it.toByte() })
        val unknown = chunk("JUNK", byteArrayOf(4, 5, 6), pad = 0x7f)
        val audio = chunk("data", ByteArray(200_001) { (it * 17).toByte() }, pad = 0x52)
        val title = RawFrame("TIT2", byteArrayOf(0, 0), byteArrayOf(0) + "Wave title".toByteArray())
        val oldId3 = chunk("ID3 ", tagBytes(3, listOf(title, oldLyric("USLT"), oldLyric("SYLT"))))
        val file = newFile("song.wav", wavBytes(format, oldId3, unknown, audio))
        val lyric = "[00:00.00]海风 🌊\n[00:01.23]Second\n"

        MediaConvertId3Lyrics.writeWav(file, listOf(MediaConvertLyric("歌词", lyric), MediaConvertLyric("翻译", lyric)))

        val bytes = file.readBytes()
        assertEquals(bytes.size - 8L, uint32(bytes, 4, ByteOrder.LITTLE_ENDIAN))
        val chunks = decodeChunks(bytes)
        assertEquals(listOf("fmt ", "JUNK", "data", "id3 "), chunks.map { it.first })
        assertArrayEquals(format, chunks[0].second)
        assertArrayEquals(unknown, chunks[1].second)
        assertArrayEquals(audio, chunks[2].second)
        val tagChunk = chunks.last().second
        val tag = decodeTag(tagChunk.copyOfRange(8, tagChunk.size))
        assertEquals(listOf("TIT2", "USLT", "USLT"), tag.frames.map { it.id })
        assertFrameEquals(title, tag.frames.first())
        assertEquals(listOf("歌词", "翻译"), tag.frames.drop(1).map { decodeUslt(it).descriptor })
        assertEquals(listOf(lyric, lyric), tag.frames.drop(1).map { decodeUslt(it).text })
        assertEquals(0, tagChunk.size and 1)
        assertEquals(tag.end.toLong(), uint32(tagChunk, 4, ByteOrder.LITTLE_ENDIAN))
        assertEquals(1, tag.end and 1)
        assertEquals(0, tagChunk.last().toInt())
    }

    @Test fun wavWithoutId3GetsAUsltChunkWithoutUnnecessaryPadding() {
        val audio = chunk("data", byteArrayOf(1, 2, 3, 4))
        val file = newFile("plain.wav", wavBytes(audio))
        MediaConvertId3Lyrics.writeWav(file, listOf(MediaConvertLyric("", "[00:01.00]Line\n")))
        val bytes = file.readBytes()
        val chunks = decodeChunks(bytes)
        assertArrayEquals(audio, chunks.first().second)
        val id3Chunk = chunks.last().second
        val size = uint32(id3Chunk, 4, ByteOrder.LITTLE_ENDIAN).toInt()
        assertEquals(0, size and 1)
        assertEquals(8 + size, id3Chunk.size)
        assertEquals("Lyrics", decodeUslt(decodeTag(id3Chunk.copyOfRange(8, 8 + size)).frames.single()).descriptor)
    }

    @Test fun wavMergesMultipleId3ChunksWithoutDroppingUnrelatedFrames() {
        val title = RawFrame("TIT2", byteArrayOf(0, 0), byteArrayOf(0, 65))
        val artist = RawFrame("TPE1", byteArrayOf(0, 0), byteArrayOf(0, 66))
        val file = newFile("multiple.wav", wavBytes(
            chunk("id3 ", tagBytes(3, listOf(title, oldLyric("USLT")))),
            chunk("data", byteArrayOf(3, 2, 1)),
            chunk("id3 ", tagBytes(3, listOf(artist)))
        ))
        MediaConvertId3Lyrics.writeWav(file, listOf(MediaConvertLyric("New", "[00:01.00]New\n")))
        val chunks = decodeChunks(file.readBytes())
        assertEquals(listOf("data", "id3 "), chunks.map { it.first })
        val tag = decodeTag(chunks.last().second.copyOfRange(8, chunks.last().second.size))
        assertEquals(listOf("TIT2", "TPE1", "USLT"), tag.frames.map { it.id })
        assertFrameEquals(title, tag.frames[0])
        assertFrameEquals(artist, tag.frames[1])
    }

    @Test fun malformedOrUnsupportedMp3TagsLeaveOriginalFileUnchanged() {
        val valid = tagBytes(3, listOf(RawFrame("TIT2", byteArrayOf(0, 0), byteArrayOf(0, 65))))
        val cases = listOf(
            "ID3".toByteArray(),
            valid.copyOf(valid.size - 1),
            valid.copyOf().also { it[3] = 2 },
            valid.copyOf().also { it[5] = 0x80.toByte() },
            valid.copyOf().also { it[5] = 1 },
            valid.copyOf().also { it[6] = 0x80.toByte() },
            valid.copyOf().also { it[17] = 100 },
            valid.copyOf().also { it[10] = 'a'.code.toByte() }
        )
        cases.forEachIndexed { index, original ->
            val file = newFile("bad-$index.mp3", original)
            expectRejected { MediaConvertId3Lyrics.writeMp3(file, listOf(MediaConvertLyric("", "[00:00.00]Line"))) }
            assertArrayEquals(original, file.readBytes())
        }
        assertFalse(temporary.root.listFiles().orEmpty().any { it.name.startsWith(".lyrics-") })
    }

    @Test fun invalidLyricsLeaveTheAudioFileUnchanged() {
        for ((index, lyric) in listOf(
            MediaConvertLyric("plain", "Untimed text"),
            MediaConvertLyric("bad\u0000title", "[00:01.00]Line"),
            MediaConvertLyric("bad", "[00:01.00]NUL\u0000text")
        ).withIndex()) {
            val original = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 3, 4)
            val file = newFile("invalid-lyric-$index.mp3", original)
            expectRejected { MediaConvertId3Lyrics.writeMp3(file, listOf(lyric)) }
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test fun malformedWavChunksAndRf64LeaveOriginalFileUnchanged() {
        val valid = wavBytes(chunk("data", byteArrayOf(1, 2, 3)))
        val cases = listOf(
            valid.copyOf().also { it[0] = 'X'.code.toByte() },
            valid.copyOf().also { "RF64".toByteArray().copyInto(it) },
            valid.copyOf().also { it[4] = 0 },
            valid.copyOf().also { it[16] = 100 },
            wavBytes(chunk("id3 ", "not an id3 tag".toByteArray())),
            wavBytes(chunk("id3 ", tagBytes(3, emptyList()) + byteArrayOf(1)))
        )
        cases.forEachIndexed { index, original ->
            val file = newFile("bad-$index.wav", original)
            expectRejected { MediaConvertId3Lyrics.writeWav(file, listOf(MediaConvertLyric("", "[00:00.00]Line"))) }
            assertArrayEquals(original, file.readBytes())
        }
    }

    private data class RawFrame(val id: String, val flags: ByteArray, val body: ByteArray)
    private data class DecodedTag(val version: Int, val end: Int, val frames: List<RawFrame>)
    private data class PlainLyric(val descriptor: String, val text: String)

    // These readers use ByteBuffer/UTF_16 and do not call any writer implementation helpers.
    private fun decodeTag(bytes: ByteArray): DecodedTag {
        assertEquals("ID3", String(bytes, 0, 3, Charsets.US_ASCII))
        val version = bytes[3].toInt()
        val size = synchsafe(bytes, 6)
        val end = 10 + size
        val frames = mutableListOf<RawFrame>()
        var offset = 10
        while (offset < end && bytes[offset] != 0.toByte()) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = if (version == 4) synchsafe(bytes, offset + 4)
            else uint32(bytes, offset + 4, ByteOrder.BIG_ENDIAN).toInt()
            frames += RawFrame(id, bytes.copyOfRange(offset + 8, offset + 10), bytes.copyOfRange(offset + 10, offset + 10 + length))
            offset += 10 + length
        }
        assertTrue(offset <= end)
        return DecodedTag(version, end, frames)
    }

    private fun decodeUslt(frame: RawFrame): PlainLyric {
        assertEquals("USLT", frame.id)
        val bytes = frame.body
        assertEquals(1, bytes[0].toInt())
        assertEquals("und", String(bytes, 1, 3, Charsets.US_ASCII))
        val (descriptor, offset) = terminatedUtf16(bytes, 4)
        return PlainLyric(descriptor, String(bytes, offset, bytes.size - offset, Charsets.UTF_16))
    }

    private fun decodeSylt(frame: RawFrame): Pair<String, List<Pair<Long, String>>> {
        assertEquals("SYLT", frame.id)
        val bytes = frame.body
        assertEquals(1, bytes[0].toInt())
        assertEquals("und", String(bytes, 1, 3, Charsets.US_ASCII))
        assertEquals(2, bytes[4].toInt())
        assertEquals(1, bytes[5].toInt())
        val (descriptor, firstCue) = terminatedUtf16(bytes, 6)
        val cues = mutableListOf<Pair<Long, String>>()
        var offset = firstCue
        while (offset < bytes.size) {
            val (text, timestampOffset) = terminatedUtf16(bytes, offset)
            cues += uint32(bytes, timestampOffset, ByteOrder.BIG_ENDIAN) to text
            offset = timestampOffset + 4
        }
        assertEquals(bytes.size, offset)
        return descriptor to cues
    }

    private fun terminatedUtf16(bytes: ByteArray, start: Int): Pair<String, Int> {
        var end = start
        while (end + 1 < bytes.size && (bytes[end] != 0.toByte() || bytes[end + 1] != 0.toByte())) end += 2
        assertTrue(end + 1 < bytes.size)
        return String(bytes, start, end - start, Charsets.UTF_16) to end + 2
    }

    private fun decodeChunks(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val chunks = mutableListOf<Pair<String, ByteArray>>()
        var offset = 12
        while (offset < bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = uint32(bytes, offset + 4, ByteOrder.LITTLE_ENDIAN).toInt()
            val end = offset + 8 + length + (length and 1)
            chunks += id to bytes.copyOfRange(offset, end)
            offset = end
        }
        assertEquals(bytes.size, offset)
        return chunks
    }

    private fun newFile(name: String, bytes: ByteArray): File = temporary.newFile(name).apply { writeBytes(bytes) }

    private fun assertFrameEquals(expected: RawFrame, actual: RawFrame) {
        assertEquals(expected.id, actual.id)
        assertArrayEquals(expected.flags, actual.flags)
        assertArrayEquals(expected.body, actual.body)
    }

    private fun expectRejected(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Malformed or unsupported input was accepted")
        } catch (_: IllegalArgumentException) {
            // Rejected before replacing the original file.
        }
    }

    private fun oldLyric(id: String): RawFrame = RawFrame(id, byteArrayOf(0, 0), "old lyric bytes".toByteArray())

    private fun tagBytes(
        version: Int,
        frames: List<RawFrame>,
        padding: Int = 0,
        extended: Boolean = false,
        footer: Boolean = false
    ): ByteArray {
        val payload = ByteArrayOutputStream()
        if (extended) payload.write(if (version == 3) byteArrayOf(0, 0, 0, 6, 0, 0, 0, 0, 0, 0)
            else byteArrayOf(0, 0, 0, 6, 1, 0))
        frames.forEach { frame ->
            payload.write(frame.id.toByteArray(Charsets.US_ASCII))
            payload.write(if (version == 4) synchsafeBytes(frame.body.size) else intBytes(frame.body.size, ByteOrder.BIG_ENDIAN))
            payload.write(frame.flags)
            payload.write(frame.body)
        }
        payload.write(ByteArray(padding))
        val flags = (if (extended) 0x40 else 0) or (if (footer) 0x10 else 0)
        val header = "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, flags.toByte()) + synchsafeBytes(payload.size())
        val footerBytes = if (footer) "3DI".toByteArray() + header.copyOfRange(3, 10) else byteArrayOf()
        return header + payload.toByteArray() + footerBytes
    }

    private fun chunk(id: String, data: ByteArray, pad: Int = 0): ByteArray =
        id.toByteArray() + intBytes(data.size, ByteOrder.LITTLE_ENDIAN) + data +
            (if (data.size and 1 != 0) byteArrayOf(pad.toByte()) else byteArrayOf())

    private fun wavBytes(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray())
        chunks.forEach(body::write)
        return "RIFF".toByteArray() + intBytes(body.size(), ByteOrder.LITTLE_ENDIAN) + body.toByteArray()
    }

    private fun uint32(bytes: ByteArray, offset: Int, order: ByteOrder): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(order).int.toLong() and 0xffff_ffffL

    private fun intBytes(value: Int, order: ByteOrder): ByteArray = ByteBuffer.allocate(4).order(order).putInt(value).array()

    private fun synchsafe(bytes: ByteArray, offset: Int): Int = (0..3).fold(0) { result, index ->
        assertTrue(bytes[offset + index].toInt() and 0x80 == 0)
        (result shl 7) or (bytes[offset + index].toInt() and 0x7f)
    }

    private fun synchsafeBytes(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7f).toByte(), ((value ushr 14) and 0x7f).toByte(),
        ((value ushr 7) and 0x7f).toByte(), (value and 0x7f).toByte()
    )
}
