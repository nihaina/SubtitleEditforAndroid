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

internal object MediaConvertId3Limits {
    const val MAX_TAG_BYTES = 64 * 1024 * 1024
}

/** Writes lyrics after FFmpeg has finished a private staging file. Audio is copied as a stream. */
internal object MediaConvertId3Lyrics {
    private const val MAX_TAG_SIZE = MediaConvertId3Limits.MAX_TAG_BYTES
    private const val MAX_UINT32 = 0xffff_ffffL
    private val lyricFrameIds = setOf("USLT", "SYLT")

    private data class Frame(val id: String, val flags: ByteArray, val body: ByteArray)
    private data class Tag(val version: Int, val frames: List<Frame>, val size: Long)
    private data class Chunk(val offset: Long, val size: Long, val isId3: Boolean)
    private data class WavTag(val version: Int?, val frames: List<Frame>, val chunks: List<Chunk>,
                              val container: String, val ds64Offset: Long? = null)
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
        val existing = readWav(file)
        val tag = buildTag(existing.version ?: 3, existing.frames, lyrics, synced = false)
        writeWavTag(file, existing, tag)
    }

    /** FFmpeg writes WAV INFO tags but drops ID3, including lyrics and private frames. */
    fun restoreWavMetadata(source: File, target: File, metadata: MediaConvertMetadata,
                           sourceMetadata: MediaConvertMetadata) {
        val original = readWav(source, allowLarge = true)
        val output = readWav(target, allowLarge = true)
        val version = original.version ?: 3
        val frames = original.frames.toMutableList()
        for (field in MediaConvertMetadata.audioFields) {
            val ids = when (field) {
                MediaConvertMetadataField.TITLE -> setOf("TIT2")
                MediaConvertMetadataField.ARTIST -> setOf("TPE1")
                MediaConvertMetadataField.ALBUM -> setOf("TALB")
                MediaConvertMetadataField.GENRE -> setOf("TCON")
                MediaConvertMetadataField.YEAR -> setOf("TYER", "TDRC", "TDAT", "TIME")
                else -> continue
            }
            val edited = field in metadata.editedFields
            if (!edited && frames.any { it.id in ids }) continue
            val value = if (edited) metadata.value(field) else sourceMetadata.value(field)
            if (edited) frames.removeAll { it.id in ids }
            if (value.isBlank()) continue
            require('\u0000' !in value && value.length <= MAX_TAG_SIZE / 2 - 2) { "Invalid ID3 text value" }
            val id = if (field == MediaConvertMetadataField.YEAR) {
                if (version == 4) "TDRC" else "TYER"
            } else ids.first()
            frames += Frame(id, byteArrayOf(0, 0), byteArrayOf(1) + utf16(value))
        }
        writeWavTag(target, output, serializeTag(version, frames))
    }

    private fun readWav(file: File, allowLarge: Boolean = false): WavTag {
        val chunks = mutableListOf<Chunk>()
        val frames = mutableListOf<Frame>()
        var version: Int? = null
        var container = "RIFF"
        var ds64Offset: Long? = null
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 12) { "Truncated WAV header" }
            val header = ByteArray(12).also(input::readFully)
            container = ascii(header, 0, 4)
            val large = allowLarge && container in setOf("RF64", "BW64")
            require((container == "RIFF" || large) && ascii(header, 8, 4) == "WAVE") {
                "Only RIFF/WAVE is supported for ID3 lyrics (RF64 is unsupported)"
            }
            var riffEnd = readLe32(header, 4) + 8L
            var largeDataSize: Long? = null
            val largeChunks = mutableMapOf<String, ArrayDeque<Long>>()
            if (large) {
                require(readLe32(header, 4) == MAX_UINT32 && input.length() >= 48L) { "Invalid RF64 header" }
                val dsHeader = ByteArray(8).also(input::readFully)
                val dsSize = readLe32(dsHeader, 4)
                require(ascii(dsHeader, 0, 4) == "ds64" && dsSize >= 28 && dsSize <= input.length() - 20) {
                    "Missing or truncated RF64 size table"
                }
                val fixed = ByteArray(28).also(input::readFully)
                val size64 = readLe64(fixed, 0)
                require(size64 <= Long.MAX_VALUE - 8) { "RF64 size is out of range" }
                riffEnd = size64 + 8
                largeDataSize = readLe64(fixed, 8)
                val count = readLe32(fixed, 24)
                require(count <= 10_000 && count <= (dsSize - 28) / 12) { "Invalid RF64 size table" }
                repeat(count.toInt()) {
                    val entry = ByteArray(12).also(input::readFully)
                    largeChunks.getOrPut(ascii(entry, 0, 4)) { ArrayDeque() }.addLast(readLe64(entry, 4))
                }
                ds64Offset = 12L
            }
            require(riffEnd == input.length()) { "Invalid WAV RIFF size" }
            var offset = 12L
            while (offset < riffEnd) {
                require(riffEnd - offset >= 8) { "Truncated WAV chunk header" }
                input.seek(offset)
                val chunkHeader = ByteArray(8).also(input::readFully)
                val id = ascii(chunkHeader, 0, 4)
                val size32 = readLe32(chunkHeader, 4)
                val dataSize = if (size32 != MAX_UINT32) size32 else if (id == "data" && largeDataSize != null) {
                    requireNotNull(largeDataSize).also { largeDataSize = null }
                } else requireNotNull(largeChunks[id]?.removeFirstOrNull()) { "Missing RF64 chunk size" }
                require(dataSize <= riffEnd - offset - 8) { "Truncated WAV chunk" }
                val chunkSize = 8L + dataSize + (dataSize and 1L)
                require(chunkSize <= riffEnd - offset) { "Truncated WAV chunk" }
                val isId3 = id.equals("id3 ", ignoreCase = true)
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
        return WavTag(version, frames, chunks, container, ds64Offset)
    }

    private fun writeWavTag(file: File, wav: WavTag, tag: ByteArray) {
        val chunks = wav.chunks
        val riffSize = 4L + chunks.filterNot { it.isId3 }.sumOf { it.size } +
            8L + tag.size + (tag.size and 1)
        require(riffSize >= 4 && (wav.ds64Offset != null || riffSize <= MAX_UINT32)) { "WAV exceeds RIFF size limit" }
        rewrite(file) { output ->
            output.write(wav.container.toByteArray(Charsets.US_ASCII))
            writeLe32(output, if (wav.ds64Offset == null) riffSize else MAX_UINT32)
            output.write("WAVE".toByteArray(Charsets.US_ASCII))
            RandomAccessFile(file, "r").use { input ->
                chunks.filterNot { it.isId3 }.forEach { chunk ->
                    if (chunk.offset == wav.ds64Offset) {
                        copyRange(input, output, chunk.offset, 8)
                        writeLe64(output, riffSize)
                        copyRange(input, output, chunk.offset + 16, chunk.size - 16)
                    } else copyRange(input, output, chunk.offset, chunk.size)
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
        return serializeTag(version, frames)
    }

    private fun serializeTag(version: Int, frames: List<Frame>): ByteArray {
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

    private fun readLe64(bytes: ByteArray, offset: Int): Long {
        require(bytes[offset + 7] >= 0) { "RF64 size is out of range" }
        return (7 downTo 0).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 0xff) }
    }

    private fun writeLe64(output: OutputStream, value: Long) {
        for (shift in 0..56 step 8) output.write(((value ushr shift) and 0xff).toInt())
    }

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
