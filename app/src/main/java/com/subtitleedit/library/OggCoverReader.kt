package com.subtitleedit.library

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Base64

/** Reads opening Vorbis/Opus/Speex comment packets, including audio multiplexed with Theora. */
internal object OggCoverReader {
    private const val MAX_PACKET_BYTES = 24 * 1024 * 1024
    private const val MAX_HEADER_BYTES = 32L * 1024 * 1024
    private const val MAX_COVER_BYTES = 16 * 1024 * 1024
    private data class Stream(val packet: ByteArrayOutputStream = ByteArrayOutputStream(), var codec: String? = null,
                              var packetIndex: Int = 0, var done: Boolean = false)

    fun read(file: File): ByteArray? = try {
        RandomAccessFile(file, "r").use { input -> read(input) }
    } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null } catch (_: SecurityException) { null }

    private fun read(input: RandomAccessFile): ByteArray? {
        val streams = linkedMapOf<Long, Stream>()
        var total = 0L
        repeat(4096) {
            if (input.length() - input.filePointer < 27 || total >= MAX_HEADER_BYTES) return null
            val header = ByteArray(27).also(input::readFully)
            if (String(header, 0, 4, Charsets.US_ASCII) != "OggS" || header[4] != 0.toByte()) return null
            val flags = header[5].toInt() and 255
            val serial = little(header, 14)
            val lacing = ByteArray(header[26].toInt() and 255).also(input::readFully)
            total += 27 + lacing.size
            val stream = streams[serial] ?: if (flags and 2 != 0 && streams.size < 16) {
                Stream().also { streams[serial] = it }
            } else return null
            if (flags and 1 == 0 && stream.packet.size() != 0) return null
            if (flags and 1 != 0 && stream.packet.size() == 0 && !stream.done) return null
            for (lace in lacing) {
                val size = lace.toInt() and 255
                total += size
                if (total > MAX_HEADER_BYTES || input.length() - input.filePointer < size) return null
                if (stream.done) input.seek(input.filePointer + size)
                else {
                    if (stream.packet.size() > MAX_PACKET_BYTES - size) return null
                    val bytes = ByteArray(size).also(input::readFully)
                    stream.packet.write(bytes)
                }
                if (size < 255 && !stream.done) {
                    val packet = stream.packet.toByteArray()
                    stream.packet.reset()
                    if (stream.packetIndex++ == 0) {
                        stream.codec = when {
                            packet.startsWith(byteArrayOf(1) + "vorbis".toByteArray()) -> "vorbis"
                            packet.startsWith("OpusHead".toByteArray()) -> "opus"
                            packet.startsWith("Speex   ".toByteArray()) -> "speex"
                            else -> { stream.done = true; null }
                        }
                    } else {
                        val offset = when (stream.codec) {
                            "vorbis" -> if (packet.startsWith(byteArrayOf(3) + "vorbis".toByteArray())) 7 else -1
                            "opus" -> if (packet.startsWith("OpusTags".toByteArray())) 8 else -1
                            "speex" -> 0
                            else -> -1
                        }
                        if (offset >= 0) comments(packet, offset)?.let { return it }
                        stream.done = true
                    }
                }
            }
            // All logical headers have been seen before the first non-BOS page. Once their
            // comments are read, never continue into the movie/audio packets looking for images.
            if (flags and 2 == 0 && streams.values.all { it.done }) return null
        }
        return null
    }

    private fun comments(packet: ByteArray, start: Int): ByteArray? {
        var cursor = start
        fun unsigned(): Int? {
            if (packet.size - cursor < 4) return null
            val result = little(packet, cursor)
            cursor += 4
            return result.takeIf { it <= Int.MAX_VALUE }?.toInt()
        }
        val vendor = unsigned() ?: return null
        if (vendor > packet.size - cursor) return null
        cursor += vendor
        val count = unsigned() ?: return null
        if (count > 16_384) return null
        var best: ByteArray? = null
        var bestRank = Int.MAX_VALUE
        repeat(count) {
            val length = unsigned() ?: return best
            if (length > packet.size - cursor) return best
            val prefix = "METADATA_BLOCK_PICTURE="
            if (length >= prefix.length && String(packet, cursor, prefix.length, Charsets.US_ASCII).equals(prefix, true)) {
                val encoded = String(packet, cursor + prefix.length, length - prefix.length, Charsets.US_ASCII)
                val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
                decoded?.let(::picture)?.let { (rank, image) ->
                    if (rank < bestRank) { best = image; bestRank = rank }
                }
            }
            cursor += length
        }
        return best
    }

    private fun picture(block: ByteArray): Pair<Int, ByteArray>? {
        var cursor = 0
        fun unsigned(): Long? {
            if (block.size - cursor < 4) return null
            var value = 0L
            repeat(4) { value = (value shl 8) or (block[cursor++].toLong() and 255) }
            return value
        }
        fun skipString(): String? {
            val size = unsigned() ?: return null
            if (size > block.size - cursor) return null
            return String(block, cursor, size.toInt(), Charsets.UTF_8).also { cursor += size.toInt() }
        }
        val type = unsigned() ?: return null
        val mime = skipString() ?: return null
        if (!mime.startsWith("image/", ignoreCase = true)) return null
        skipString() ?: return null
        repeat(4) { unsigned() ?: return null }
        val size = unsigned() ?: return null
        if (size !in 1L..MAX_COVER_BYTES || size != (block.size - cursor).toLong()) return null
        return (if (type == 3L) 0 else 1) to block.copyOfRange(cursor, block.size)
    }

    private fun little(bytes: ByteArray, start: Int): Long = (3 downTo 0).fold(0L) { value, index ->
        (value shl 8) or (bytes[start + index].toLong() and 255)
    }
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
