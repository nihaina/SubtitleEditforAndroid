package com.subtitleedit

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaConvertEmbeddedLyricReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun waveAndId3HeadersAreRecognizedWithoutAFilenameOrMimeType() {
        val lyrics = listOf(MediaConvertLyric("中文", "[00:00.125]海风 🌊\n"),
            MediaConvertLyric("English", "[00:00.125]Sea breeze\n"))
        val wave = temporary.newFile("audio.bin").apply {
            writeBytes("RIFF".toByteArray() + byteArrayOf(16, 0, 0, 0) + "WAVEdata".toByteArray() +
                byteArrayOf(4, 0, 0, 0, 1, 2, 3, 4))
        }
        MediaConvertId3Lyrics.writeWav(wave, lyrics)
        val original = wave.readBytes()
        val detected = wave.inputStream().buffered().use(MediaConvertEmbeddedLyricReader::read)
        assertEquals(listOf("USLT", "USLT"), detected.map { it.field })
        assertEquals(lyrics.map { it.title }, detected.map { it.title })
        assertEquals(lyrics.map { it.lrc }, detected.map { it.text })
        assertArrayEquals(original, wave.readBytes())
        val aac = temporary.newFile("audio-with-no-extension").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xf1.toByte())) }
        MediaConvertId3Lyrics.writeAac(aac, lyrics)
        val id3 = aac.inputStream().use(MediaConvertEmbeddedLyricReader::read)
        assertEquals(listOf("USLT", "SYLT", "USLT", "SYLT"), id3.map { it.field })
        assertEquals(lyrics.map { it.lrc }, id3.filter { it.field == "USLT" }.map { it.text })
    }

    @Test fun otherMediaOnlyReadsItsHeaderAndLeavesStreamOwnershipWithCaller() {
        val input = object : ByteArrayInputStream("fLaC".toByteArray() + ByteArray(1024)) {
            var closed = false
            override fun close() { closed = true }
        }
        assertTrue(MediaConvertEmbeddedLyricReader.read(input).isEmpty())
        assertEquals(1016, input.available())
        assertFalse(input.closed)
        for (bytes in listOf(byteArrayOf(), "RIFF".toByteArray(), "Not a media file".toByteArray())) {
            assertTrue(MediaConvertEmbeddedLyricReader.read(ByteArrayInputStream(bytes)).isEmpty())
        }
    }
}
