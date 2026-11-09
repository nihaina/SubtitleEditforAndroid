package com.subtitleedit

import com.subtitleedit.util.SubtitleParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaConvertId3LyricsReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun generatedMp3FramesExposeEveryLyricAndMillisecondTimestamps() {
        val file = temporary.newFile("song.mp3").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val original = listOf(MediaConvertLyric("中文 🌊", "[offset:+125]\n[00:01.000]海风\n[00:03.250]Second\n"),
            MediaConvertLyric("English", "[00:01.00]Sea breeze\n"))
        MediaConvertId3Lyrics.writeMp3(file, original)
        val bytes = file.readBytes()

        val detected = file.inputStream().use { MediaConvertId3LyricsReader.read(it, wav = false) }

        assertEquals(listOf("USLT", "SYLT", "USLT", "SYLT"), detected.map { it.field })
        assertEquals(listOf("中文 🌊", "中文 🌊", "English", "English"), detected.map { it.title })
        assertEquals(original.map { it.lrc }, detected.filter { it.field == "USLT" }.map { it.text })
        assertEquals(listOf(1125L, 3375L), SubtitleParser.parseLRC(detected[1].text).map { it.startTime })
        assertTrue(detected.all { it.synced })
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun generatedWavLyricsAreReadAfterTheAudioWithoutChangingTheFile() {
        val file = temporary.newFile("song.wav").apply { writeBytes(wav(chunk("fmt ", ByteArray(16)), chunk("data", ByteArray(200_001)))) }
        MediaConvertId3Lyrics.writeWav(file, listOf(MediaConvertLyric("歌词", "[00:01.125]你好 🎵\n")))
        val bytes = file.readBytes()

        val detected = file.inputStream().use { MediaConvertId3LyricsReader.read(it, wav = true) }

        assertEquals("USLT", detected.single().field)
        assertEquals("歌词", detected.single().title)
        assertEquals("[00:01.125]你好 🎵\n", detected.single().text)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun allFourTextEncodingsAndMultipleLanguagesAreRecognized() {
        val frames = (0..3).map { encoding ->
            val title = if (encoding == 0) "François" else "中文 🎵"
            val text = if (encoding == 0) "Un café\nDeux" else "纯文本歌词 🌊\nSecond"
            frame(3, "USLT", uslt(encoding, "fra", title, text))
        }
        val detected = read(tag(3, frames))
        assertEquals(4, detected.size)
        assertEquals("François", detected.first().title)
        assertEquals("Un café\nDeux", detected.first().text)
        assertTrue(detected.all { it.language == "fra" && !it.synced })
        assertTrue(detected.drop(1).all { it.title == "中文 🎵" && it.text == "纯文本歌词 🌊\nSecond" })
    }

    @Test fun utf16TextCanInheritTheDescriptorsByteOrderWithoutRepeatingTheBom() {
        val body = byteArrayOf(1) + "zho".toByteArray() + text("说明", 1, terminated = true) +
            "海风 🌊".toByteArray(Charsets.UTF_16LE)
        val lyric = read(tag(3, listOf(frame(3, "USLT", body)))).single()
        assertEquals("说明", lyric.title)
        assertEquals("海风 🌊", lyric.text)
    }

    @Test fun v24SynchsafeFrameSizesAndUtf8SyltRetainTimingBeyondOneByteLengths() {
        val words = "海风 🌊".repeat(50)
        val body = sylt(3, "eng", "Timed", 2, listOf(1234L to words, 9876L to "End"))
        val detected = read(tag(4, listOf(frame(4, "SYLT", body)))).single()
        assertEquals("SYLT", detected.field)
        assertEquals("Timed", detected.title)
        assertTrue(detected.synced)
        val entries = SubtitleParser.parseLRC(detected.text)
        assertEquals(listOf(1234L, 9876L), entries.map { it.startTime })
        assertEquals(listOf(words, "End"), entries.map { it.text })
    }

    @Test fun mpegFrameTimestampsAreDetectedWithoutInventingMillisecondTiming() {
        val detected = read(tag(3, listOf(frame(3, "SYLT",
            sylt(0, "eng", "MPEG", 1, listOf(100L to "First", 200L to "Second")))))).single()
        assertTrue(detected.synced)
        assertEquals("First\nSecond", detected.text)
    }

    @Test fun id3v22UltAndSltFramesAreDetected() {
        val detected = read(tag(2, listOf(frame(2, "ULT", uslt(0, "eng", "Old lyrics", "Text")),
            frame(2, "SLT", sylt(0, "eng", "Old timed", 2, listOf(250L to "Word"))))))
        assertEquals(listOf("USLT", "SYLT"), detected.map { it.field })
        assertEquals(listOf("Old lyrics", "Old timed"), detected.map { it.title })
    }

    @Test fun v23ExtendedHeadersAndGlobalUnsynchronizationAreHandled() {
        val payload = byteArrayOf(0, 0, 0, 6, 0, 0, 0, 0, 0, 0) +
            frame(3, "USLT", uslt(0, "eng", "Latin", "ÿà and ÿ\u0000"))
        val detected = read(tagPayload(3, unsynchronize(payload), 0xc0)).single()
        assertEquals("ÿà and ÿ", detected.text)
    }

    @Test fun v24FrameUnsynchronizationGroupingAndDataLengthPrefixAreHandled() {
        val raw = uslt(0, "eng", "Grouped", "ÿà lyrics")
        val encoded = unsynchronize(byteArrayOf(7) + synchsafe(raw.size) + raw)
        val detected = read(tag(4, listOf(frame(4, "USLT", encoded, 0x43)))).single()
        assertEquals("Grouped", detected.title)
        assertEquals("ÿà lyrics", detected.text)
    }

    @Test fun compressedEncryptedUnknownAndEmptyFramesDoNotBecomeLyrics() {
        val frames = listOf(
            frame(3, "TIT2", byteArrayOf(0) + "Song".toByteArray()),
            frame(3, "USLT", uslt(0, "eng", "Compressed", "Wrong"), 0x80),
            frame(3, "SYLT", sylt(0, "eng", "Encrypted", 2, listOf(0L to "Wrong")), 0x40),
            frame(3, "USLT", uslt(0, "eng", "Empty", "")),
            frame(3, "USLT", uslt(0, "eng", "Good", "Visible"))
        )
        assertEquals(listOf("Good"), read(tag(3, frames)).map { it.title })
    }

    @Test fun malformedTagsAndFramesAreRejectedWithoutReadingUnboundedPayloads() {
        val valid = tag(3, listOf(frame(3, "USLT", uslt(0, "eng", "Good", "Visible"))))
        for (bytes in listOf(
            byteArrayOf(), "ID3".toByteArray(), valid.copyOf(15),
            valid.copyOf().also { it[3] = 5 },
            valid.copyOf().also { it[6] = 0x80.toByte() },
            valid.copyOf().also { it[17] = 0x7f },
            tag(3, listOf(frame(3, "USLT", byteArrayOf(5, 0, 0, 0, 0))))
        )) assertTrue(read(bytes).isEmpty())
        val oversized = "ID3".toByteArray() + byteArrayOf(3, 0, 0) + synchsafe(100_000_000)
        val input = CountingStream(oversized + ByteArray(100))
        assertTrue(MediaConvertId3LyricsReader.read(input, wav = false).isEmpty())
        assertEquals(10, input.readCount)
        assertFalse(input.closed)
    }

    @Test fun wavSkipsAudioAndUnknownChunksAndAcceptsUppercaseId3WithOddPadding() {
        val id3 = tag(3, listOf(frame(3, "USLT", uslt(0, "eng", "Wave", "Lyrics"))))
        val bytes = wav(chunk("fmt ", ByteArray(16)), chunk("JUNK", byteArrayOf(1, 2, 3)),
            chunk("data", ByteArray(1_000_001)), chunk("ID3 ", id3))
        val input = CountingStream(bytes)
        val detected = MediaConvertId3LyricsReader.read(input, wav = true)
        assertEquals("Wave", detected.single().title)
        assertTrue("Audio must be skipped instead of buffered", input.readCount < 200)
        assertFalse(input.closed)
    }

    @Test fun malformedWavContainersAndMissingTagsHaveNoLyrics() {
        val plain = wav(chunk("data", ByteArray(8)))
        for (bytes in listOf(plain, plain.copyOf(15), "RF64".toByteArray() + plain.copyOfRange(4, plain.size),
            plain.copyOf().also { it[16] = 100 }, wav(chunk("id3 ", "not a tag".toByteArray())))) {
            assertTrue(MediaConvertId3LyricsReader.read(ByteArrayInputStream(bytes), wav = true).isEmpty())
        }
    }

    private class CountingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var readCount = 0
        var closed = false
        override fun read(): Int = super.read().also { if (it >= 0) readCount++ }
        override fun read(buffer: ByteArray, offset: Int, size: Int): Int =
            super.read(buffer, offset, size).also { if (it > 0) readCount += it }
        override fun close() { closed = true; super.close() }
    }

    private fun read(bytes: ByteArray) = MediaConvertId3LyricsReader.read(ByteArrayInputStream(bytes), wav = false)
    private fun uslt(encoding: Int, language: String, title: String, words: String) =
        byteArrayOf(encoding.toByte()) + language.toByteArray() + text(title, encoding, true) + text(words, encoding, false)
    private fun sylt(encoding: Int, language: String, title: String, timestampFormat: Int, cues: List<Pair<Long, String>>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(encoding.toByte()) + language.toByteArray() + byteArrayOf(timestampFormat.toByte(), 1))
        output.write(text(title, encoding, true))
        cues.forEach { (time, words) -> output.write(text(words, encoding, true)); output.write(integer(time, 4)) }
        return output.toByteArray()
    }
    private fun text(value: String, encoding: Int, terminated: Boolean): ByteArray {
        val bytes = when (encoding) {
            0 -> value.toByteArray(Charsets.ISO_8859_1)
            1 -> byteArrayOf(0xff.toByte(), 0xfe.toByte()) + value.toByteArray(Charsets.UTF_16LE)
            2 -> value.toByteArray(Charsets.UTF_16BE)
            else -> value.toByteArray(Charsets.UTF_8)
        }
        return bytes + if (terminated) ByteArray(if (encoding in 1..2) 2 else 1) else byteArrayOf()
    }
    private fun frame(version: Int, id: String, body: ByteArray, flags: Int = 0): ByteArray =
        id.toByteArray() + when (version) {
            2 -> integer(body.size.toLong(), 3)
            3 -> integer(body.size.toLong(), 4) + byteArrayOf(0, flags.toByte())
            else -> synchsafe(body.size) + byteArrayOf(0, flags.toByte())
        } + body
    private fun tag(version: Int, frames: List<ByteArray>): ByteArray =
        tagPayload(version, frames.fold(byteArrayOf()) { all, frame -> all + frame })
    private fun tagPayload(version: Int, payload: ByteArray, flags: Int = 0) =
        "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, flags.toByte()) + synchsafe(payload.size) + payload
    private fun integer(value: Long, size: Int) = ByteArray(size) { index -> (value ushr ((size - 1 - index) * 8)).toByte() }
    private fun synchsafe(value: Int) = ByteArray(4) { index -> ((value ushr ((3 - index) * 7)) and 127).toByte() }
    private fun unsynchronize(bytes: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        bytes.forEachIndexed { index, byte ->
            write(byte.toInt() and 255)
            val next = bytes.getOrNull(index + 1)?.toInt()?.and(255)
            if (byte == 0xff.toByte() && (next == null || next == 0 || next >= 0xe0)) write(0)
        }
    }.toByteArray()
    private fun chunk(id: String, bytes: ByteArray) =
        id.toByteArray() + integer(bytes.size.toLong(), 4).reversedArray() + bytes + ByteArray(bytes.size and 1)
    private fun wav(vararg chunks: ByteArray): ByteArray {
        val body = "WAVE".toByteArray() + chunks.fold(byteArrayOf()) { all, chunk -> all + chunk }
        return "RIFF".toByteArray() + integer(body.size.toLong(), 4).reversedArray() + body
    }
}
