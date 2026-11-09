package com.subtitleedit

import com.subtitleedit.util.SubtitleParser
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class MediaConvertLyric(val title: String, val lrc: String)

/** Writes lyrics after FFmpeg has finished a private staging file. Audio is copied as a stream. */
internal object MediaConvertId3Lyrics {
    private const val MAX_TAG_SIZE = 64 * 1024 * 1024
    private const val MAX_UINT32 = 0xffff_ffffL
    private val lyricFrameIds = setOf("USLT", "SYLT")

    private data class Frame(val id: String, val flags: ByteArray, val body: ByteArray)
    private data class Tag(val version: Int, val frames: List<Frame>, val size: Long)
    private data class Chunk(val offset: Long, val size: Long, val isId3: Boolean)
    private data class NamedLyric(val descriptor: String, val lrc: String)

    fun writeMp3(file: File, lyrics: List<MediaConvertLyric>) = writeId3Audio(file, lyrics)

    /** ADTS can carry a leading ID3v2 tag while keeping its AAC audio frames unchanged. */
    fun writeAac(file: File, lyrics: List<MediaConvertLyric>) = writeId3Audio(file, lyrics)

    private fun writeId3Audio(file: File, lyrics: List<MediaConvertLyric>) {
        val existing = RandomAccessFile(file, "r").use { input ->
            readTag(input, 0, input.length(), required = false)
        }
        val tag = buildTag(existing?.version ?: 3, existing?.frames.orEmpty(), lyrics, synced = true)
        val audioOffset = existing?.size ?: 0L
        rewrite(file) { output ->
            output.write(tag)
            RandomAccessFile(file, "r").use { input ->
                copyRange(input, output, audioOffset, input.length() - audioOffset)
            }
        }
    }

    /** RIFF/WAVE uses an ID3 chunk with USLT; synchronized MP3 frames are not needed here. */
    fun writeWav(file: File, lyrics: List<MediaConvertLyric>) {
        val chunks = mutableListOf<Chunk>()
        val frames = mutableListOf<Frame>()
        var version: Int? = null
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 12) { "Truncated WAV header" }
            val header = ByteArray(12).also(input::readFully)
            require(ascii(header, 0, 4) == "RIFF" && ascii(header, 8, 4) == "WAVE") {
                "Only RIFF/WAVE is supported for ID3 lyrics (RF64 is unsupported)"
            }
            val riffEnd = readLe32(header, 4) + 8L
            require(riffEnd == input.length()) { "Invalid WAV RIFF size" }
            var offset = 12L
            while (offset < riffEnd) {
                require(riffEnd - offset >= 8) { "Truncated WAV chunk header" }
                input.seek(offset)
                val chunkHeader = ByteArray(8).also(input::readFully)
                val dataSize = readLe32(chunkHeader, 4)
                val chunkSize = 8L + dataSize + (dataSize and 1L)
                require(chunkSize <= riffEnd - offset) { "Truncated WAV chunk" }
                val id = ascii(chunkHeader, 0, 4)
                val isId3 = id == "id3 " || id == "ID3 "
                if (isId3) {
                    val tag = requireNotNull(readTag(input, offset + 8L, dataSize, required = true))
                    require(version == null || version == tag.version) { "Mixed ID3 versions in WAV" }
                    version = tag.version
                    frames += tag.frames
                    // Extra ID3 chunk padding is allowed, but unknown trailing data must not be lost.
                    input.seek(offset + 8L + tag.size)
                    var remaining = dataSize - tag.size
                    val padding = ByteArray(8192)
                    while (remaining > 0) {
                        val count = minOf(remaining, padding.size.toLong()).toInt()
                        input.readFully(padding, 0, count)
                        require((0 until count).all { padding[it] == 0.toByte() }) {
                            "Unexpected data after WAV ID3 tag"
                        }
                        remaining -= count
                    }
                }
                chunks += Chunk(offset, chunkSize, isId3)
                offset += chunkSize
            }
        }
        val tag = buildTag(version ?: 3, frames, lyrics, synced = false)
        val riffSize = 4L + chunks.filterNot { it.isId3 }.sumOf { it.size } +
            8L + tag.size + (tag.size and 1)
        require(riffSize <= MAX_UINT32) { "WAV exceeds RIFF size limit" }
        rewrite(file) { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeLe32(output, riffSize)
            output.write("WAVE".toByteArray(Charsets.US_ASCII))
            RandomAccessFile(file, "r").use { input ->
                chunks.filterNot { it.isId3 }.forEach { chunk ->
                    copyRange(input, output, chunk.offset, chunk.size)
                }
            }
            output.write("id3 ".toByteArray(Charsets.US_ASCII))
            writeLe32(output, tag.size.toLong())
            output.write(tag)
            if (tag.size and 1 != 0) output.write(0)
        }
    }

    private fun buildTag(
        version: Int,
        existing: List<Frame>,
        lyrics: List<MediaConvertLyric>,
        synced: Boolean
    ): ByteArray {
        val frames = existing.filterNot { it.id in lyricFrameIds }.toMutableList()
        nameLyrics(lyrics).forEach { lyric ->
            val uslt = ByteArrayOutputStream()
            writeTextHeader(uslt, lyric.descriptor)
            uslt.write(utf16(lyric.lrc))
            frames += Frame("USLT", byteArrayOf(0, 0), uslt.toByteArray())
            if (synced) {
                val cues = SubtitleParser.parseLRC(lyric.lrc)
                require(cues.isNotEmpty()) { "The lyric file has no timed lines" }
                val sylt = ByteArrayOutputStream()
                sylt.write(1) // UTF-16 with BOM
                sylt.write("und".toByteArray(Charsets.US_ASCII))
                sylt.write(2) // Absolute milliseconds, independent of MPEG frame rates.
                sylt.write(1) // Lyrics
                writeTerminatedText(sylt, lyric.descriptor)
                cues.forEach { cue ->
                    require(cue.startTime in 0..MAX_UINT32) { "ID3 lyric timestamp is out of range" }
                    writeTerminatedText(sylt, cue.text)
                    writeBe32(sylt, cue.startTime)
                    require(sylt.size() <= MAX_TAG_SIZE) { "ID3 lyrics are too large" }
                }
                frames += Frame("SYLT", byteArrayOf(0, 0), sylt.toByteArray())
            }
        }
        val payloadSize = frames.sumOf { 10L + it.body.size }
        require(payloadSize <= MAX_TAG_SIZE) { "ID3 tag is too large" }
        return ByteArrayOutputStream(payloadSize.toInt() + 10).apply {
            write("ID3".toByteArray(Charsets.US_ASCII))
            write(version)
            write(0)
            write(0) // Rebuilt without extended header, padding, footer, or tag unsynchronization.
            writeSyncsafe(this, payloadSize.toInt())
            frames.forEach { frame ->
                write(frame.id.toByteArray(Charsets.US_ASCII))
                if (version == 4) writeSyncsafe(this, frame.body.size)
                else writeBe32(this, frame.body.size.toLong())
                write(frame.flags)
                write(frame.body)
            }
        }.toByteArray()
    }

    private fun nameLyrics(lyrics: List<MediaConvertLyric>): List<NamedLyric> {
        val used = mutableSetOf<String>()
        return lyrics.map { lyric ->
            require('\u0000' !in lyric.title && '\u0000' !in lyric.lrc) { "NUL is invalid in ID3 lyrics" }
            require(lyric.title.length <= MAX_TAG_SIZE / 2 - 2 && lyric.lrc.length <= MAX_TAG_SIZE / 2 - 2) {
                "ID3 lyrics are too large"
            }
            val base = lyric.title.trim().ifEmpty { "Lyrics" }
            var descriptor = base
            var number = 2
            while (!used.add(descriptor)) descriptor = "$base (${number++})"
            NamedLyric(descriptor, lyric.lrc)
        }
    }

    private fun readTag(input: RandomAccessFile, offset: Long, available: Long, required: Boolean): Tag? {
        input.seek(offset)
        if (available < 3) {
            require(!required) { "Truncated ID3 header" }
            return null
        }
        val signature = ByteArray(3).also(input::readFully)
        if (ascii(signature, 0, 3) != "ID3") {
            require(!required) { "Missing ID3 header" }
            return null
        }
        require(available >= 10) { "Truncated ID3 header" }
        input.seek(offset)
        val header = ByteArray(10).also(input::readFully)
        val version = header[3].toInt() and 0xff
        require(version == 3 || version == 4) { "Only ID3v2.3 and ID3v2.4 are supported" }
        require(header[4] == 0.toByte()) { "Unsupported ID3 revision" }
        val flags = header[5].toInt() and 0xff
        require(flags and 0x80 == 0) { "Globally unsynchronized ID3 tags are unsupported" }
        require(flags and (if (version == 3) 0x1f else 0x0f) == 0) { "Invalid ID3 header flags" }
        val payloadSize = readSyncsafe(header, 6)
        require(payloadSize <= MAX_TAG_SIZE) { "ID3 tag is too large" }
        val footerSize = if (version == 4 && flags and 0x10 != 0) 10 else 0
        val totalSize = 10L + payloadSize + footerSize
        require(totalSize <= available) { "Truncated ID3 tag" }
        val payload = ByteArray(payloadSize).also(input::readFully)
        if (footerSize != 0) {
            val footer = ByteArray(10).also(input::readFully)
            require(ascii(footer, 0, 3) == "3DI" && footer.copyOfRange(3, 10).contentEquals(header.copyOfRange(3, 10))) {
                "Invalid ID3 footer"
            }
        }
        var cursor = 0
        if (flags and 0x40 != 0) {
            require(payload.size >= 4) { "Truncated ID3 extended header" }
            val extendedSize = if (version == 3) readBe32(payload, 0) + 4L
            else readSyncsafe(payload, 0).toLong()
            require(extendedSize >= (if (version == 3) 10 else 6) && extendedSize <= payload.size) {
                "Invalid ID3 extended header size"
            }
            cursor = extendedSize.toInt()
        }
        val frames = mutableListOf<Frame>()
        while (cursor < payload.size) {
            if (payload[cursor] == 0.toByte()) {
                require((cursor until payload.size).all { payload[it] == 0.toByte() }) { "Invalid ID3 padding" }
                break
            }
            require(payload.size - cursor >= 10) { "Truncated ID3 frame header" }
            val id = ascii(payload, cursor, 4)
            require(id.all { it in 'A'..'Z' || it in '0'..'9' }) { "Invalid ID3 frame identifier" }
            val size = if (version == 3) readBe32(payload, cursor + 4)
            else readSyncsafe(payload, cursor + 4).toLong()
            require(size <= payload.size - cursor - 10L) { "Truncated ID3 frame" }
            val bodyStart = cursor + 10
            val bodyEnd = bodyStart + size.toInt()
            frames += Frame(id, payload.copyOfRange(cursor + 8, cursor + 10), payload.copyOfRange(bodyStart, bodyEnd))
            cursor = bodyEnd
        }
        return Tag(version, frames, totalSize)
    }

    private fun writeTextHeader(output: OutputStream, descriptor: String) {
        output.write(1)
        output.write("und".toByteArray(Charsets.US_ASCII))
        writeTerminatedText(output, descriptor)
    }

    private fun utf16(text: String): ByteArray = byteArrayOf(0xff.toByte(), 0xfe.toByte()) +
        text.toByteArray(Charsets.UTF_16LE)

    private fun writeTerminatedText(output: OutputStream, text: String) {
        output.write(utf16(text))
        output.write(byteArrayOf(0, 0))
    }

    private fun readSyncsafe(bytes: ByteArray, offset: Int): Int {
        var value = 0
        repeat(4) { index ->
            val part = bytes[offset + index].toInt() and 0xff
            require(part < 0x80) { "Invalid syncsafe ID3 size" }
            value = (value shl 7) or part
        }
        return value
    }

    private fun writeSyncsafe(output: OutputStream, value: Int) {
        for (shift in 21 downTo 0 step 7) output.write((value ushr shift) and 0x7f)
    }

    private fun readBe32(bytes: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 0xff) }

    private fun readLe32(bytes: ByteArray, offset: Int): Long =
        (3 downTo 0).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 0xff) }

    private fun writeBe32(output: OutputStream, value: Long) {
        for (shift in 24 downTo 0 step 8) output.write(((value ushr shift) and 0xff).toInt())
    }

    private fun writeLe32(output: OutputStream, value: Long) {
        for (shift in 0..24 step 8) output.write(((value ushr shift) and 0xff).toInt())
    }

    private fun ascii(bytes: ByteArray, offset: Int, count: Int): String =
        String(bytes, offset, count, Charsets.US_ASCII)

    private fun copyRange(input: RandomAccessFile, output: OutputStream, offset: Long, count: Long) {
        input.seek(offset)
        var remaining = count
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val size = minOf(remaining, buffer.size.toLong()).toInt()
            input.readFully(buffer, 0, size)
            output.write(buffer, 0, size)
            remaining -= size
        }
    }

    private fun rewrite(file: File, writer: (OutputStream) -> Unit) {
        val target = file.absoluteFile
        val temporary = File.createTempFile(".lyrics-", ".tmp", target.parentFile)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                BufferedOutputStream(fileOutput).use { output ->
                    writer(output)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}
