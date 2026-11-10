package com.subtitleedit

import java.io.InputStream
import java.io.PushbackInputStream

/** Identify the tag/container from its header, including files without a reliable extension. */
internal object MediaConvertEmbeddedLyricReader {
    fun read(input: InputStream): List<MediaConvertEmbeddedLyric> {
        val stream = PushbackInputStream(input, 12)
        val header = ByteArray(12)
        var count = 0
        while (count < header.size) {
            val next = stream.read()
            if (next < 0) break
            header[count++] = next.toByte()
        }
        stream.unread(header, 0, count)
        val signature = String(header, 0, minOf(count, 4), Charsets.US_ASCII)
        return when {
            count >= 3 && String(header, 0, 3, Charsets.US_ASCII) == "ID3" ->
                MediaConvertId3LyricsReader.read(stream, wav = false)
            count == 12 && signature in setOf("RIFF", "RF64", "BW64") &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE" -> MediaConvertId3LyricsReader.read(stream, wav = true)
            else -> emptyList()
        }
    }
}
