package com.subtitleedit.audio

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/** Measures MPEG Layer III bytes and sample counts without relying on bitrate-based duration. */
internal object Mp3FrameStats {
    data class AudioData(val byteCount: Long, val sampleCount: Long, val sampleRate: Int) {
        val durationSeconds: Double
            get() = sampleCount.toDouble() / sampleRate
    }

    private val MPEG1_BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_BITRATES = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /** Unknown, truncated, or unsupported frame sequences return null instead of guessed rates. */
    fun read(file: File): AudioData? {
        val (start, end) = RandomAccessFile(file, "r").use { input ->
            val start = skipLeadingId3(input) ?: return null
            val end = stripTrailingTags(input, start) ?: return null
            start to end
        }
        if (start >= end) return null

        return file.inputStream().buffered(64 * 1024).use { input ->
            if (!input.skipFully(start)) return@use null
            var remaining = end - start
            var audioBytes = 0L
            var samples = 0L
            var firstHeader: Header? = null
            val bytes = ByteArray(4)
            while (remaining > 0L) {
                if (remaining < 4 || !input.readFully(bytes)) return@use null
                val header = readHeader(bytes) ?: return@use null
                if (header.frameSize > remaining) return@use null
                val first = firstHeader
                if (first != null && (header.version != first.version || header.sampleRate != first.sampleRate)) {
                    return@use null
                }
                val isMetadataFrame = if (first == null) {
                    firstHeader = header
                    val frame = ByteArray(header.frameSize)
                    bytes.copyInto(frame)
                    if (!input.readFully(frame, 4)) return@use null
                    isMetadataFrame(frame, header)
                } else {
                    if (!input.skipFully((header.frameSize - 4).toLong())) return@use null
                    false
                }
                if (!isMetadataFrame) {
                    audioBytes += header.frameSize
                    samples += if (header.version == 3) 1152L else 576L
                }
                remaining -= header.frameSize
            }
            if (samples > 0L) AudioData(audioBytes, samples, firstHeader!!.sampleRate) else null
        }
    }

    private fun skipLeadingId3(input: RandomAccessFile): Long? {
        var offset = 0L
        val header = ByteArray(10)
        while (offset + header.size <= input.length()) {
            input.seek(offset)
            input.readFully(header)
            if (!matches(header, 0, "ID3")) return offset
            val version = header[3].toInt() and 0xff
            if (version !in 2..4 || (6..9).any { header[it].toInt() and 0x80 != 0 }) return null
            var size = 0L
            for (index in 6..9) size = (size shl 7) or header[index].toLong()
            val footerSize = if (version == 4 && header[5].toInt() and 0x10 != 0) 10 else 0
            offset += 10 + size + footerSize
            if (offset > input.length()) return null
        }
        return offset
    }

    private fun stripTrailingTags(input: RandomAccessFile, start: Long): Long? {
        var end = input.length()
        val footer = ByteArray(32)
        while (end > start) {
            if (end - start >= 128) {
                input.seek(end - 128)
                if (input.read() == 'T'.code && input.read() == 'A'.code && input.read() == 'G'.code) {
                    end -= 128
                    continue
                }
            }
            if (end - start < footer.size) break
            input.seek(end - footer.size)
            input.readFully(footer)
            if (!matches(footer, 0, "APETAGEX")) break
            val size = readUIntLe(footer, 12)
            if (size < footer.size || size > end - start) return null
            end -= size
            if (readUIntLe(footer, 20) and 0x80000000L != 0L) {
                if (end - start < footer.size) return null
                input.seek(end - footer.size)
                input.readFully(footer)
                if (!matches(footer, 0, "APETAGEX")) return null
                end -= footer.size
            }
        }
        return end
    }

    private data class Header(
        val frameSize: Int,
        val version: Int,
        val sampleRate: Int,
        val mono: Boolean,
        val hasCrc: Boolean
    )

    private fun readHeader(bytes: ByteArray): Header? {
        var bits = 0
        for (byte in bytes) bits = (bits shl 8) or (byte.toInt() and 0xff)
        if (bits ushr 21 != 0x7ff) return null
        val version = (bits ushr 19) and 3
        val layer = (bits ushr 17) and 3
        val bitrateIndex = (bits ushr 12) and 15
        val rateIndex = (bits ushr 10) and 3
        if (version == 1 || layer != 1 || bitrateIndex !in 1..14 || rateIndex == 3) return null
        val sampleRate = intArrayOf(44100, 48000, 32000)[rateIndex] /
            when (version) { 3 -> 1; 2 -> 2; else -> 4 }
        val bitrate = if (version == 3) MPEG1_BITRATES[bitrateIndex] else MPEG2_BITRATES[bitrateIndex]
        val frameSize = (if (version == 3) 144000 else 72000) * bitrate / sampleRate + ((bits ushr 9) and 1)
        return Header(frameSize, version, sampleRate, (bits ushr 6) and 3 == 3, bits and 0x10000 == 0)
    }

    private fun isMetadataFrame(frame: ByteArray, header: Header): Boolean {
        val sideInfoSize = if (header.version == 3) {
            if (header.mono) 17 else 32
        } else {
            if (header.mono) 9 else 17
        }
        val offset = 4 + sideInfoSize
        // An optional first frame can hold metadata instead of audio. Its presence or absence
        // is not an issue; exclude it so it cannot inflate the measured audio byte/sample count.
        return matches(frame, offset, "Xing") || matches(frame, offset, "Info") ||
            (header.hasCrc && (matches(frame, offset + 2, "Xing") || matches(frame, offset + 2, "Info"))) ||
            matches(frame, 36, "VBRI")
    }

    private fun matches(bytes: ByteArray, offset: Int, text: String): Boolean =
        offset >= 0 && offset + text.length <= bytes.size &&
            text.indices.all { bytes[offset + it].toInt() == text[it].code }

    private fun readUIntLe(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 3 downTo 0) value = (value shl 8) or (bytes[offset + index].toLong() and 0xff)
        return value
    }

    private fun InputStream.readFully(bytes: ByteArray, start: Int = 0): Boolean {
        var offset = start
        while (offset < bytes.size) {
            val count = read(bytes, offset, bytes.size - offset)
            if (count <= 0) return false
            offset += count
        }
        return true
    }

    private fun InputStream.skipFully(count: Long): Boolean {
        var remaining = count
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped > 0L) remaining -= skipped
            else if (read() == -1) return false
            else remaining--
        }
        return true
    }
}
