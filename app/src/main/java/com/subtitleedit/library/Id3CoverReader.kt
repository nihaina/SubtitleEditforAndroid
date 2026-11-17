package com.subtitleedit.library

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Leading ID3 pictures and RIFF/RF64 ID3 chunks, without reading the audio payload. */
internal object Id3CoverReader {
    private const val MAX_TAG_BYTES = 32 * 1024 * 1024
    private const val MAX_COVER_BYTES = 16 * 1024 * 1024

    fun read(file: File): ByteArray? = try {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 10) return null
            val header = ByteArray(minOf(12L, input.length()).toInt()).also(input::readFully)
            when {
                ascii(header, 0, 3) == "ID3" -> tag(input, 0, input.length())
                header.size == 12 && ascii(header, 8, 4) == "WAVE" &&
                    ascii(header, 0, 4) in setOf("RIFF", "RF64", "BW64") -> riff(input, header)
                else -> null
            }
        }
    } catch (_: IOException) { null } catch (_: SecurityException) { null }

    private fun riff(input: RandomAccessFile, header: ByteArray): ByteArray? {
        val large = ascii(header, 0, 4) != "RIFF"
        var end = if (large) input.length() else (little(header, 4, 4) + 8).coerceAtMost(input.length())
        var offset = 12L
        var dataSize: Long? = null
        val sizes = mutableMapOf<String, ArrayDeque<Long>>()
        repeat(16_384) {
            if (end - offset < 8) return null
            input.seek(offset)
            val chunk = ByteArray(8).also(input::readFully)
            val name = ascii(chunk, 0, 4)
            val size32 = little(chunk, 4, 4)
            val size = if (size32 != 0xffff_ffffL) size32 else if (name == "data" && dataSize != null) {
                requireNotNull(dataSize).also { dataSize = null }
            } else sizes[name]?.removeFirstOrNull() ?: return null
            val start = offset + 8
            if (size > end - start || size < 0) return null
            if (name == "ds64" && large) {
                if (size < 28) return null
                val fixed = ByteArray(28).also(input::readFully)
                val riffSize = unsignedLong(fixed, 0) ?: return null
                dataSize = unsignedLong(fixed, 8) ?: return null
                if (riffSize > input.length() - 8 || riffSize + 8 < start + size) return null
                end = riffSize + 8
                val count = little(fixed, 24, 4)
                if (count > 4096 || count > (size - 28) / 12) return null
                repeat(count.toInt()) {
                    val entry = ByteArray(12).also(input::readFully)
                    sizes.getOrPut(ascii(entry, 0, 4)) { ArrayDeque() }
                        .addLast(unsignedLong(entry, 4) ?: return null)
                }
            } else if (name.equals("id3 ", ignoreCase = true)) {
                tag(input, start, start + size)?.let { return it }
            }
            val padded = size + (size and 1)
            if (padded > end - start) return null
            offset = start + padded
        }
        return null
    }

    private fun tag(input: RandomAccessFile, start: Long, end: Long): ByteArray? {
        if (end - start < 10) return null
        input.seek(start)
        val header = ByteArray(10).also(input::readFully)
        val version = header[3].toInt() and 255
        if (ascii(header, 0, 3) != "ID3" || version !in 2..4 || header[4] != 0.toByte()) return null
        val flags = header[5].toInt() and 255
        if (flags and (if (version == 2) 0x7f else if (version == 3) 0x1f else 0x0f) != 0) return null
        val length = syncsafe(header, 6) ?: return null
        if (length > MAX_TAG_BYTES || length > end - start - 10) return null
        val raw = ByteArray(length).also(input::readFully)
        val unsync = flags and 0x80 != 0
        val payload = if (unsync && version < 4) deunsynchronize(raw) else raw
        var position = 0
        if (version >= 3 && flags and 0x40 != 0) {
            if (payload.size < 4) return null
            val extended = if (version == 3) big(payload, 0, 4) + 4 else syncsafe(payload, 0)?.toLong() ?: return null
            if (extended < (if (version == 3) 10 else 6) || extended > payload.size) return null
            position = extended.toInt()
        }
        var first: ByteArray? = null
        val frameHeader = if (version == 2) 6 else 10
        val idLength = if (version == 2) 3 else 4
        repeat(4096) {
            if (payload.size - position < frameHeader || payload[position] == 0.toByte()) return first
            val id = ascii(payload, position, idLength)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) return first
            val size = when (version) {
                2 -> big(payload, position + 3, 3)
                3 -> big(payload, position + 4, 4)
                else -> syncsafe(payload, position + 4)?.toLong() ?: return first
            }
            if (size > payload.size - position - frameHeader) return first
            val bodyStart = position + frameHeader
            position = bodyStart + size.toInt()
            if (id == "APIC" || id == "PIC") {
                val frameFlags = if (version == 2) 0 else payload[bodyStart - 1].toInt() and 255
                if (frameFlags and (if (version == 3) 0xdf else if (version == 4) 0xbc else 0) != 0) return@repeat
                var body = payload.copyOfRange(bodyStart, position)
                if (version == 4 && (unsync || frameFlags and 2 != 0)) body = deunsynchronize(body)
                var prefix = if (version == 3 && frameFlags and 0x20 != 0 || version == 4 && frameFlags and 0x40 != 0) 1 else 0
                if (version == 4 && frameFlags and 1 != 0) {
                    if (body.size - prefix < 4 || syncsafe(body, prefix) == null) return@repeat
                    prefix += 4
                }
                if (prefix > body.size) return@repeat
                picture(body, prefix, version == 2)?.let { (type, bytes) ->
                    if (type == 3) return bytes
                    if (first == null) first = bytes
                }
            }
        }
        return first
    }

    private fun picture(body: ByteArray, start: Int, old: Boolean): Pair<Int, ByteArray>? {
        if (body.size - start < 6) return null
        val encoding = body[start].toInt() and 255
        if (encoding !in 0..3) return null
        val mimeEnd = if (old) start + 3 else (start + 1 until body.size).firstOrNull { body[it] == 0.toByte() } ?: return null
        val mime = ascii(body, start + 1, mimeEnd - start - if (old) 0 else 1)
        if (if (old) mime !in setOf("JPG", "PNG", "GIF", "BMP") else !mime.startsWith("image/", ignoreCase = true)) return null
        val typeIndex = mimeEnd + 1
        if (typeIndex >= body.size) return null
        val type = body[typeIndex].toInt() and 255
        val width = if (encoding == 1 || encoding == 2) 2 else 1
        var cursor = typeIndex + 1
        while (body.size - cursor >= width) {
            if (body[cursor] == 0.toByte() && (width == 1 || body[cursor + 1] == 0.toByte())) {
                cursor += width
                val length = body.size - cursor
                if (length !in 1..MAX_COVER_BYTES) return null
                return type to body.copyOfRange(cursor, body.size)
            }
            cursor += width
        }
        return null
    }

    private fun deunsynchronize(bytes: ByteArray): ByteArray {
        val result = ByteArray(bytes.size)
        var source = 0
        var target = 0
        while (source < bytes.size) {
            val byte = bytes[source++]
            result[target++] = byte
            if (byte == 0xff.toByte() && source < bytes.size && bytes[source] == 0.toByte()) source++
        }
        return result.copyOf(target)
    }

    private fun syncsafe(bytes: ByteArray, start: Int): Int? {
        if (bytes.size - start < 4) return null
        var value = 0
        repeat(4) {
            val byte = bytes[start + it].toInt() and 255
            if (byte > 127) return null
            value = (value shl 7) or byte
        }
        return value
    }
    private fun unsignedLong(bytes: ByteArray, start: Int): Long? =
        if (bytes[start + 7].toInt() < 0) null else little(bytes, start, 8)
    private fun little(bytes: ByteArray, start: Int, count: Int): Long = (count - 1 downTo 0).fold(0L) { value, index ->
        (value shl 8) or (bytes[start + index].toLong() and 255)
    }
    private fun big(bytes: ByteArray, start: Int, count: Int): Long = (0 until count).fold(0L) { value, index ->
        (value shl 8) or (bytes[start + index].toLong() and 255)
    }
    private fun ascii(bytes: ByteArray, start: Int, count: Int) = String(bytes, start, count, Charsets.ISO_8859_1)
}
