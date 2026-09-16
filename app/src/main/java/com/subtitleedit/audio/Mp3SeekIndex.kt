package com.subtitleedit.audio

import java.io.File
import java.io.RandomAccessFile

/** Reads the first MPEG Layer III frame, skipping ID3 tags without loading them into memory. */
internal object Mp3SeekIndex {
    private const val SEARCH_BYTES = 64 * 1024
    private val MPEG1_BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_BITRATES = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /** null means no complete supported frame could be identified, not a confirmed missing index. */
    fun hasSeekIndex(file: File): Boolean? = RandomAccessFile(file, "r").use { input ->
        val audioStart = skipId3Tags(input) ?: return@use null
        input.seek(audioStart)
        val bytes = ByteArray(minOf(input.length() - audioStart, SEARCH_BYTES + 4096L).toInt())
        input.readFully(bytes)
        for (offset in 0 until minOf(SEARCH_BYTES, bytes.size - 3)) {
            val header = readHeader(bytes, offset) ?: continue
            val end = offset + header.frameSize
            if (end > bytes.size) continue
            // Confirm frame alignment instead of accepting a sync pattern in arbitrary metadata.
            val next = readHeader(bytes, end)
            val isLastFrame = audioStart + end == input.length() ||
                (audioStart + end + 128 == input.length() && matches(bytes, end, "TAG"))
            if (!isLastFrame && (next == null || next.version != header.version || next.sampleRate != header.sampleRate)) {
                continue
            }
            val frame = bytes.copyOfRange(offset, end)
            val sideInfoSize = if (header.version == 3) {
                if (header.mono) 17 else 32
            } else {
                if (header.mono) 9 else 17
            }
            val xingOffset = 4 + sideInfoSize
            return@use hasXingIndex(frame, xingOffset) ||
                (header.hasCrc && hasXingIndex(frame, xingOffset + 2)) ||
                hasVbriIndex(frame)
        }
        null
    }

    private fun skipId3Tags(input: RandomAccessFile): Long? {
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

    private data class Header(
        val frameSize: Int,
        val version: Int,
        val sampleRate: Int,
        val mono: Boolean,
        val hasCrc: Boolean
    )

    private fun readHeader(bytes: ByteArray, offset: Int): Header? {
        val bits = readUInt(bytes, offset)?.toInt() ?: return null
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

    private fun hasXingIndex(frame: ByteArray, offset: Int): Boolean {
        if (!matches(frame, offset, "Xing") && !matches(frame, offset, "Info")) return false
        val flags = readUInt(frame, offset + 4)?.toInt() ?: return false
        // A marker alone is not an index: seeking needs frame/byte counts and the TOC.
        if (flags and 7 != 7) return false
        if ((readUInt(frame, offset + 8) ?: 0) == 0L || (readUInt(frame, offset + 12) ?: 0) == 0L) return false
        val tocStart = offset + 16
        if (tocStart + 100 > frame.size) return false
        var previous = 0
        for (index in tocStart until tocStart + 100) {
            val value = frame[index].toInt() and 0xff
            if (value < previous) return false
            previous = value
        }
        return previous > 0
    }

    private fun hasVbriIndex(frame: ByteArray): Boolean {
        // VBRI is always 32 bytes after the four-byte MPEG header.
        val offset = 36
        if (!matches(frame, offset, "VBRI") || readUShort(frame, offset + 4) != 1) return false
        if ((readUInt(frame, offset + 10) ?: 0) == 0L || (readUInt(frame, offset + 14) ?: 0) == 0L) return false
        val entries = readUShort(frame, offset + 18) ?: return false
        val scale = readUShort(frame, offset + 20) ?: return false
        val bytesPerEntry = readUShort(frame, offset + 22) ?: return false
        val framesPerEntry = readUShort(frame, offset + 24) ?: return false
        if (entries == 0 || scale == 0 || bytesPerEntry !in 1..4 || framesPerEntry == 0) return false
        val tableStart = offset + 26
        val tableEnd = tableStart + entries * bytesPerEntry
        return tableEnd <= frame.size && (tableStart until tableEnd).any { frame[it] != 0.toByte() }
    }

    private fun matches(bytes: ByteArray, offset: Int, text: String): Boolean =
        offset >= 0 && offset + text.length <= bytes.size &&
            text.indices.all { bytes[offset + it].toInt() == text[it].code }

    private fun readUShort(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 2 > bytes.size) return null
        return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }

    private fun readUInt(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        var value = 0L
        for (index in offset until offset + 4) value = (value shl 8) or (bytes[index].toLong() and 0xff)
        return value
    }
}
