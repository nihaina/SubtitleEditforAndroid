package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.LrcVariant
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.Charset

/** Reads lyric frames without loading audio data or taking ownership of the input stream. */
internal object MediaConvertId3LyricsReader {
    private const val MAX_TAG_BYTES = 16 * 1024 * 1024
    private const val MAX_LYRIC_FRAMES = 128

    fun read(input: InputStream, wav: Boolean): List<MediaConvertEmbeddedLyric> = try {
        if (wav) readWav(input) else readTag(input, Long.MAX_VALUE)
    } catch (_: EOFException) {
        emptyList()
    }

    private fun readWav(input: InputStream): List<MediaConvertEmbeddedLyric> {
        val header = readExactly(input, 12)
        if (ascii(header, 0, 4) != "RIFF" || ascii(header, 8, 4) != "WAVE") return emptyList()
        var remaining = littleEndian(header, 4) - 4
        val lyrics = mutableListOf<MediaConvertEmbeddedLyric>()
        while (remaining >= 8 && lyrics.size < MAX_LYRIC_FRAMES) {
            val chunkHeader = readExactly(input, 8)
            remaining -= 8
            val size = littleEndian(chunkHeader, 4)
            val paddedSize = size + (size and 1)
            if (paddedSize > remaining) break
            val id = ascii(chunkHeader, 0, 4)
            if ((id == "id3 " || id == "ID3 ") && size <= MAX_TAG_BYTES + 20L) {
                val bytes = readExactly(input, size.toInt())
                lyrics += readTag(ByteArrayInputStream(bytes), size).take(MAX_LYRIC_FRAMES - lyrics.size)
            } else {
                skipExactly(input, size)
            }
            if (size and 1L != 0L) skipExactly(input, 1)
            remaining -= paddedSize
        }
        return lyrics
    }

    private fun readTag(input: InputStream, available: Long): List<MediaConvertEmbeddedLyric> {
        if (available < 10) return emptyList()
        val header = readExactly(input, 10)
        if (ascii(header, 0, 3) != "ID3") return emptyList()
        val version = header[3].toInt() and 255
        if (version !in 2..4 || header[4] != 0.toByte()) return emptyList()
        val flags = header[5].toInt() and 255
        if (flags and (when (version) { 2 -> 0x3f; 3 -> 0x1f; else -> 0x0f }) != 0) return emptyList()
        if (version == 2 && flags and 0x40 != 0) return emptyList() // Compressed ID3v2.2 tags.
        val size = syncsafe(header, 6) ?: return emptyList()
        if (size > MAX_TAG_BYTES || size + 10L > available) return emptyList()
        val rawPayload = readExactly(input, size)
        // v2.2/v2.3 unsynchronization is applied to the whole tag, after frame sizes are written.
        // v2.4 sizes include unsynchronization bytes, so decode each frame after finding its boundary.
        val tagUnsynchronized = flags and 0x80 != 0
        val payload = if (tagUnsynchronized && version < 4) deunsynchronize(rawPayload) else rawPayload
        var cursor = 0
        if (version >= 3 && flags and 0x40 != 0) {
            if (payload.size < 4) return emptyList()
            val extended = if (version == 3) bigEndian(payload, 0, 4) + 4
                else syncsafe(payload, 0)?.toLong() ?: return emptyList()
            if (extended < (if (version == 3) 10 else 6) || extended > payload.size) return emptyList()
            cursor = extended.toInt()
        }
        val result = mutableListOf<MediaConvertEmbeddedLyric>()
        val frameHeaderSize = if (version == 2) 6 else 10
        val idSize = if (version == 2) 3 else 4
        while (payload.size - cursor >= frameHeaderSize && result.size < MAX_LYRIC_FRAMES) {
            if (payload[cursor] == 0.toByte()) break
            val id = ascii(payload, cursor, idSize)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val frameSize = when (version) {
                2 -> bigEndian(payload, cursor + 3, 3)
                3 -> bigEndian(payload, cursor + 4, 4)
                else -> syncsafe(payload, cursor + 4)?.toLong() ?: break
            }
            if (frameSize > payload.size - cursor - frameHeaderSize) break
            val bodyStart = cursor + frameHeaderSize
            val bodyEnd = bodyStart + frameSize.toInt()
            val field = when (id) { "ULT" -> "USLT"; "SLT" -> "SYLT"; else -> id }
            if (field == "USLT" || field == "SYLT") {
                val frameFlags = if (version == 2) 0 else payload[cursor + 9].toInt() and 255
                val body = prepareFrame(payload.copyOfRange(bodyStart, bodyEnd), version, frameFlags, tagUnsynchronized)
                if (body != null) parseLyric(field, body)?.let(result::add)
            }
            cursor = bodyEnd
        }
        return result
    }

    private fun prepareFrame(raw: ByteArray, version: Int, flags: Int, tagUnsynchronized: Boolean): ByteArray? {
        val unsupported = when (version) { 2 -> 0; 3 -> 0xdf; else -> 0xbc }
        // Compression/encryption and reserved format flags require decoding we cannot safely guess.
        if (flags and unsupported != 0) return null
        val body = if (version == 4 && (tagUnsynchronized || flags and 0x02 != 0)) deunsynchronize(raw) else raw
        var start = 0
        if ((version == 3 && flags and 0x20 != 0) || (version == 4 && flags and 0x40 != 0)) start++
        if (version == 4 && flags and 0x01 != 0) {
            if (body.size - start < 4 || syncsafe(body, start) == null) return null
            start += 4
        }
        return if (start <= body.size) body.copyOfRange(start, body.size) else null
    }

    private fun parseLyric(field: String, body: ByteArray): MediaConvertEmbeddedLyric? {
        val synced = field == "SYLT"
        if (body.size < (if (synced) 6 else 4)) return null
        val encoding = body[0].toInt() and 255
        if (encoding !in 0..3) return null
        val decoder = TextDecoder(encoding)
        val language = ascii(body, 1, 3).trim('\u0000', ' ')
        val description = decoder.terminated(body, if (synced) 6 else 4) ?: return null
        if (!synced) {
            val text = decoder.decode(body, description.second, body.size).trimEnd('\u0000')
            if (text.isBlank()) return null
            return MediaConvertEmbeddedLyric(field, description.first, language, text,
                synced = SubtitleParser.parseLRC(text).isNotEmpty())
        }
        val timestampFormat = body[4].toInt() and 255
        if (timestampFormat !in 1..2) return null
        var cursor = description.second
        val cues = mutableListOf<Pair<Long, String>>()
        while (cursor < body.size) {
            val cue = decoder.terminated(body, cursor) ?: return null
            if (body.size - cue.second < 4) return null
            val timestamp = bigEndian(body, cue.second, 4)
            cues += timestamp to cue.first
            cursor = cue.second + 4
        }
        if (cues.none { it.second.isNotBlank() }) return null
        val text = if (timestampFormat == 2) {
            val sorted = cues.sortedBy { it.first }
            val entries = sorted.mapIndexed { index, (start, words) ->
                SubtitleEntry(index + 1, start, (sorted.getOrNull(index + 1)?.first ?: (start + 1_000)).coerceAtLeast(start + 1), words)
            }
            SubtitleParser.toLRC(entries, lrcVariant = LrcVariant.MILLISECONDS)
        } else {
            // MPEG-frame timestamps cannot become milliseconds without the audio frame rate.
            cues.joinToString("\n") { it.second }
        }
        return MediaConvertEmbeddedLyric(field, description.first, language, text, synced = true)
    }

    private class TextDecoder(private val encoding: Int) {
        private var utf16: Charset = Charsets.UTF_16BE

        fun terminated(bytes: ByteArray, start: Int): Pair<String, Int>? {
            val width = if (encoding == 1 || encoding == 2) 2 else 1
            var end = start
            while (end + width <= bytes.size) {
                if (bytes[end] == 0.toByte() && (width == 1 || bytes[end + 1] == 0.toByte())) {
                    return decode(bytes, start, end) to end + width
                }
                end += width
            }
            return null
        }

        fun decode(bytes: ByteArray, start: Int, end: Int): String {
            if (start >= end) return ""
            var contentStart = start
            val charset = when (encoding) {
                0 -> Charsets.ISO_8859_1
                1 -> {
                    if (end - start >= 2) {
                        when {
                            bytes[start] == 0xff.toByte() && bytes[start + 1] == 0xfe.toByte() -> {
                                utf16 = Charsets.UTF_16LE; contentStart += 2
                            }
                            bytes[start] == 0xfe.toByte() && bytes[start + 1] == 0xff.toByte() -> {
                                utf16 = Charsets.UTF_16BE; contentStart += 2
                            }
                        }
                    }
                    utf16
                }
                2 -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
            return String(bytes, contentStart, end - contentStart, charset).removePrefix("\uFEFF")
        }
    }

    private fun deunsynchronize(bytes: ByteArray): ByteArray {
        val decoded = ByteArray(bytes.size)
        var size = 0
        var cursor = 0
        while (cursor < bytes.size) {
            val byte = bytes[cursor++]
            decoded[size++] = byte
            if (byte == 0xff.toByte() && cursor < bytes.size && bytes[cursor] == 0.toByte()) cursor++
        }
        return decoded.copyOf(size)
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(bytes, offset, size - offset)
            if (count < 0) throw EOFException()
            if (count == 0) {
                val next = input.read()
                if (next < 0) throw EOFException()
                bytes[offset++] = next.toByte()
            } else offset += count
        }
        return bytes
    }

    private fun skipExactly(input: InputStream, size: Long) {
        var remaining = size
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped else {
                if (input.read() < 0) throw EOFException()
                remaining--
            }
        }
    }

    private fun ascii(bytes: ByteArray, start: Int, size: Int) = String(bytes, start, size, Charsets.US_ASCII)
    private fun bigEndian(bytes: ByteArray, start: Int, size: Int): Long =
        (0 until size).fold(0L) { value, index -> (value shl 8) or (bytes[start + index].toLong() and 255) }
    private fun littleEndian(bytes: ByteArray, start: Int): Long =
        (3 downTo 0).fold(0L) { value, index -> (value shl 8) or (bytes[start + index].toLong() and 255) }
    private fun syncsafe(bytes: ByteArray, start: Int): Int? {
        var result = 0
        repeat(4) { index ->
            val byte = bytes[start + index].toInt() and 255
            if (byte >= 128) return null
            result = (result shl 7) or byte
        }
        return result
    }
}
