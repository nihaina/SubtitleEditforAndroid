package com.subtitleedit.util

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

/**
 * A bounded, forward-only TAR reader.  The input stream remains owned by the caller.
 * By default entries are written beneath [staging] and are never overwritten. With [openOutput],
 * the caller resolves each final destination as its header arrives and owns conflict decisions.
 */
internal object StreamingTarExtractor {
    private const val BLOCK_SIZE = 512
    private const val IO_BUFFER_SIZE = 64 * 1024
    private const val MAX_METADATA_BYTES = 1024 * 1024L
    private const val MAX_PATH_BYTES = 4096
    private const val MAX_COMPONENT_BYTES = 255
    private const val TAR_OVERHEAD_ALLOWANCE = 64L * 1024L * 1024L
    private const val MAX_METADATA_HEADERS_MULTIPLIER = 4L
    private const val MAX_METADATA_HEADERS_EXTRA = 1024L
    private const val PROGRESS_INTERVAL_NANOS = 100_000_000L

    data class Entry(
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
        val modifiedTimeMillis: Long,
        val stagedFile: File? = null,
        val index: Int = -1
    )

    /**
     * [openOutput] receives directories as well as files; returning null skips a file's payload.
     * The returned stream is closed before [onEntryComplete], including on cancellation or failure.
     * A true completion means the whole entry was consumed, even when its output was skipped.
     * Direct-output callers handle destination validation, timestamps and failed-output cleanup.
     */
    fun extract(
        input: InputStream,
        staging: File,
        expectedTarBytes: Long,
        maxEntries: Int,
        maxBytes: Long,
        validateExpectedSize: Boolean = true,
        shouldExtract: (Entry) -> Boolean = { true },
        checkCancelled: () -> Unit = {},
        onEntry: (Entry) -> Unit = {},
        openOutput: ((Entry) -> OutputStream?)? = null,
        onEntryComplete: (Entry, Boolean) -> Unit = { _, _ -> },
        onProgress: (consumedTarBytes: Long, expectedTarBytes: Long) -> Unit = { _, _ -> }
    ): List<Entry> {
        require(expectedTarBytes >= 0L) { "expectedTarBytes 不能为负数" }
        require(maxEntries > 0) { "maxEntries 必须大于 0" }
        require(maxBytes > 0L) { "maxBytes 必须大于 0" }

        val maxTarBytes = if (maxBytes > Long.MAX_VALUE - TAR_OVERHEAD_ALLOWANCE) {
            Long.MAX_VALUE
        } else {
            maxBytes + TAR_OVERHEAD_ALLOWANCE
        }
        if (validateExpectedSize && expectedTarBytes > maxTarBytes) {
            throw IOException("TAR 数据超过安全大小限制")
        }
        val root = if (openOutput == null) prepareStaging(staging) else null
        val reader = TarReader(
            input,
            expectedTarBytes,
            validateExpectedSize,
            maxTarBytes,
            checkCancelled,
            onProgress
        )
        val entries = mutableListOf<Entry>()
        val globalPax = linkedMapOf<String, String?>()
        val localPax = linkedMapOf<String, String?>()
        var pendingLongName: String? = null
        var pendingLongNamePresent = false
        var zeroBlocks = 0
        var realEntryCount = 0L
        var metadataHeaderCount = 0L
        var accountedBytes = 0L

        fun accountPayload(size: Long) {
            if (size < 0L || size > maxBytes - accountedBytes) {
                throw IOException("TAR 数据超过安全大小限制")
            }
            accountedBytes += size
        }

        fun checkHeaderCount(isMetadata: Boolean) {
            if (isMetadata) {
                metadataHeaderCount++
                val allowance = maxEntries.toLong()
                    .coerceAtMost(Long.MAX_VALUE / MAX_METADATA_HEADERS_MULTIPLIER)
                    .times(MAX_METADATA_HEADERS_MULTIPLIER) + MAX_METADATA_HEADERS_EXTRA
                if (metadataHeaderCount > allowance) {
                    throw IOException("TAR 扩展头数量超过安全限制")
                }
            } else {
                realEntryCount++
                if (realEntryCount > maxEntries.toLong()) {
                    throw IOException("TAR 条目数量超过安全限制")
                }
            }
        }

        while (true) {
            checkCancelled()
            val header = reader.readBlock()
            if (isZeroBlock(header)) {
                zeroBlocks++
                if (zeroBlocks == 2) {
                    if (pendingLongNamePresent || localPax.isNotEmpty()) {
                        throw IOException("TAR 扩展头后缺少文件条目")
                    }
                    reader.drainZeroTail()
                    reader.finish()
                    return entries
                }
                continue
            }
            if (zeroBlocks != 0) {
                throw IOException("TAR 结束标记不完整")
            }

            if (!isHeaderChecksumValid(header)) {
                throw IOException("TAR 文件头校验失败")
            }

            val type = header[156].toInt().and(0xff).toChar()
            val headerSize = parseTarNumber(header, 124, 12, "TAR 文件大小字段无效")
            val metadata = type == 'x' || type == 'g' || type == 'L' || type == 'K'
            checkHeaderCount(metadata)

            if (type == 'K') {
                throw IOException("TAR 包含硬链接或符号链接")
            }
            if (type == 'S') {
                throw IOException("TAR 稀疏文件不受支持")
            }

            if (metadata) {
                if (headerSize > MAX_METADATA_BYTES) {
                    throw IOException("TAR 扩展元数据过大")
                }
                accountPayload(headerSize)
                val data = reader.readPayload(headerSize)
                when (type) {
                    'L' -> {
                        pendingLongName = decodeLongName(data)
                        pendingLongNamePresent = true
                    }
                    'g', 'x' -> {
                        val patch = parsePaxRecords(data)
                        val target = if (type == 'g') globalPax else localPax
                        patch.forEach { (key, value) -> target[key] = value }
                    }
                }
                continue
            }

            val effectivePax = linkedMapOf<String, String?>().apply {
                putAll(globalPax)
                localPax.forEach { (key, value) ->
                    if (value == null) remove(key) else put(key, value)
                }
            }
            val rawName = effectivePax["path"]?.let(::decodePaxPath)
                ?: if (pendingLongNamePresent) pendingLongName.orEmpty()
                else headerPath(header)
            val isDirectory = type == '5'
            val isRegular = type == '\u0000' || type == '0' || type == '7'
            if (type == '1' || type == '2') {
                throw IOException("TAR 包含硬链接或符号链接")
            }
            if (!isDirectory && !isRegular) {
                throw IOException("TAR 包含不支持的特殊文件类型")
            }

            val name = normalizePath(rawName)
            val size = effectivePax["size"]?.let(::parsePaxSize) ?: headerSize
            val modified = effectivePax["mtime"]?.let(::parsePaxMtime)
                ?: parseHeaderMtime(header, 136, 12)
            accountPayload(size)
            localPax.clear()
            pendingLongName = null
            pendingLongNamePresent = false
            val index = entries.size
            val candidate = Entry(name, size, isDirectory, modified, null, index)
            onEntry(candidate)
            var entry = candidate
            try {
                if (openOutput != null) {
                    val output = if (isDirectory || shouldExtract(candidate)) openOutput(candidate) else null
                    output.use {
                        // Directories also reach the resolver, but have no file payload to write.
                        reader.readPayload(size, if (isDirectory) null else it, null)
                    }
                } else {
                    val target = if (!isDirectory && shouldExtract(candidate)) {
                        checkNotNull(root).resolve("payload-$index")
                    } else {
                        null
                    }
                    entry = candidate.copy(stagedFile = target?.toFile())
                    if (target == null) {
                        reader.skipPayload(size)
                    } else {
                        writeFile(reader, target, size)
                        setLastModified(target, modified)
                    }
                }
            } catch (failure: Throwable) {
                runCatching { onEntryComplete(entry, false) }.onFailure(failure::addSuppressed)
                throw failure
            }
            onEntryComplete(entry, true)
            entries += entry
        }
    }

    private class TarReader(
        private val input: InputStream,
        private val expectedBytes: Long,
        private val validateExpectedSize: Boolean,
        private val maxConsumedBytes: Long,
        private val checkCancelled: () -> Unit,
        private val onProgress: (Long, Long) -> Unit
    ) {
        private val buffer = ByteArray(IO_BUFFER_SIZE)
        var consumedBytes: Long = 0L
            private set
        private var lastProgressBytes = 0L
        private var lastProgressAt = System.nanoTime()
        private var progressTotal = expectedBytes

        init {
            onProgress(0L, expectedBytes)
        }

        fun readBlock(): ByteArray {
            val block = ByteArray(BLOCK_SIZE)
            readFully(block, 0, block.size)
            return block
        }

        fun readPayload(size: Long): ByteArray {
            if (size > Int.MAX_VALUE) throw IOException("TAR 元数据过大")
            val result = ByteArray(size.toInt())
            readPayload(size, null, result)
            return result
        }

        fun skipPayload(size: Long) {
            readPayload(size, null, null)
        }

        fun readPayload(size: Long, output: OutputStream?, capture: ByteArray?) {
            var remaining = size
            var captured = 0
            while (remaining > 0L) {
                val count = minOf(remaining, buffer.size.toLong()).toInt()
                readFully(buffer, 0, count)
                if (capture != null) {
                    buffer.copyInto(capture, captured, 0, count)
                    captured += count
                }
                output?.write(buffer, 0, count)
                remaining -= count.toLong()
            }
            val padding = ((BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE).toInt()
            if (padding > 0) readFully(buffer, 0, padding)
        }

        fun drainZeroTail() {
            while (true) {
                val read = readSome(buffer, 0, buffer.size)
                if (read < 0) return
                for (index in 0 until read) {
                    if (buffer[index].toInt() != 0) {
                        throw IOException("TAR 结束标记后包含非零数据")
                    }
                }
            }
        }

        fun finish() {
            if (validateExpectedSize && expectedBytes > 0L && consumedBytes != expectedBytes) {
                throw IOException("TAR 数据长度与预期不符")
            }
            reportProgress(force = true)
        }

        private fun readFully(destination: ByteArray, offset: Int, length: Int) {
            var position = offset
            val end = offset + length
            while (position < end) {
                val count = readSome(destination, position, end - position)
                if (count < 0) throw IOException("TAR 数据提前结束")
                if (count == 0) continue
                position += count
            }
        }

        private fun readSome(destination: ByteArray, offset: Int, length: Int): Int {
            checkCancelled()
            var count = input.read(destination, offset, length)
            if (count == 0) {
                val one = input.read()
                if (one < 0) return -1
                destination[offset] = one.toByte()
                count = 1
            }
            if (count > 0) {
                if (consumedBytes > Long.MAX_VALUE - count.toLong()) {
                    throw IOException("TAR 数据长度溢出")
                }
                consumedBytes += count.toLong()
                if (consumedBytes > maxConsumedBytes) {
                    throw IOException("TAR 数据超过安全大小限制")
                }
                if (validateExpectedSize && expectedBytes > 0L && consumedBytes > expectedBytes) {
                    throw IOException("TAR 数据超过预期长度")
                }
                var totalChanged = false
                if (!validateExpectedSize && progressTotal > 0L && consumedBytes > progressTotal) {
                    // gzip stores only a 32-bit unpack size.  Archives larger than 4 GiB wrap;
                    // keep decoding and switch the UI to indeterminate instead of failing.
                    progressTotal = 0L
                    totalChanged = true
                }
                reportProgress(force = totalChanged)
                checkCancelled()
            }
            return count
        }

        private fun reportProgress(force: Boolean) {
            val now = System.nanoTime()
            val complete = progressTotal > 0L && consumedBytes == progressTotal
            if (!force && !complete && now - lastProgressAt < PROGRESS_INTERVAL_NANOS) return
            if (force || consumedBytes != lastProgressBytes) {
                onProgress(consumedBytes, progressTotal)
                lastProgressBytes = consumedBytes
            }
            lastProgressAt = now
        }
    }

    private fun prepareStaging(staging: File): Path {
        val path = staging.toPath().toAbsolutePath().normalize()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw IOException("TAR staging 目录不能是符号链接")
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(path)
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("TAR staging 不是目录")
        }
        return path
    }

    private fun writeFile(reader: TarReader, target: Path, size: Long) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("TAR 目标文件已存在：${target.fileName}")
        }
        try {
            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { raw ->
                BufferedOutputStream(raw, IO_BUFFER_SIZE).use { output ->
                    reader.readPayload(size, output, null)
                }
            }
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(target) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun setLastModified(path: Path, millis: Long) {
        if (millis <= 0L) return
        runCatching { Files.setLastModifiedTime(path, FileTime.fromMillis(millis)) }
            .getOrElse { throw IOException("无法设置 TAR 条目时间：${path.fileName}", it) }
    }

    private fun isZeroBlock(block: ByteArray): Boolean = block.all { it.toInt() == 0 }

    private fun isHeaderChecksumValid(header: ByteArray): Boolean {
        val stored = parseTarNumber(header, 148, 8, "TAR 校验和字段无效")
        var unsigned = 0L
        var signed = 0L
        header.forEachIndexed { index, byte ->
            val value = if (index in 148..155) ' '.code else byte.toInt() and 0xff
            unsigned += value.toLong()
            signed += if (value >= 128) (value - 256).toLong() else value.toLong()
        }
        return stored == unsigned || signed >= 0L && stored == signed
    }

    private fun parseTarNumber(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        errorMessage: String
    ): Long {
        if ((bytes[offset].toInt() and 0xff) and 0x80 != 0) {
            if ((bytes[offset].toInt() and 0xff) and 0x40 != 0) throw IOException(errorMessage)
            var value = (bytes[offset].toInt() and 0x7f).toLong()
            for (index in 1 until length) {
                val next = bytes[offset + index].toInt() and 0xff
                if (value > (Long.MAX_VALUE ushr 8)) throw IOException(errorMessage)
                value = (value shl 8) or next.toLong()
            }
            return value
        }
        var index = 0
        while (index < length && ((bytes[offset + index].toInt() and 0xff) == 0 || bytes[offset + index] == ' '.code.toByte())) index++
        var value = 0L
        var found = false
        while (index < length) {
            val current = bytes[offset + index].toInt() and 0xff
            if (current == 0 || current == ' '.code) {
                while (index < length) {
                    val tail = bytes[offset + index].toInt() and 0xff
                    if (tail != 0 && tail != ' '.code) throw IOException(errorMessage)
                    index++
                }
                break
            }
            if (current !in '0'.code..'7'.code) throw IOException(errorMessage)
            val digit = (current - '0'.code).toLong()
            if (value > (Long.MAX_VALUE - digit) / 8L) throw IOException(errorMessage)
            value = value * 8L + digit
            found = true
            index++
        }
        return if (found) value else 0L
    }

    private fun headerPath(header: ByteArray): String {
        val name = decodeHeaderString(header.copyOfRange(0, 100))
        val magic = header.copyOfRange(257, 262)
        if (!magic.contentEquals("ustar".toByteArray(StandardCharsets.US_ASCII))) return name
        val prefix = decodeHeaderString(header.copyOfRange(345, 500))
        return if (prefix.isEmpty()) name else if (name.isEmpty()) prefix else "$prefix/$name"
    }

    private fun decodeHeaderString(bytes: ByteArray): String {
        var end = bytes.indexOfFirst { it.toInt() == 0 }
        if (end < 0) end = bytes.size
        return decodeUtf8(bytes.copyOf(end))
    }

    private fun decodeLongName(bytes: ByteArray): String {
        var end = bytes.indexOfFirst { it.toInt() == 0 }
        if (end < 0) end = bytes.size
        while (end > 0 && (bytes[end - 1].toInt() == '\n'.code || bytes[end - 1].toInt() == '\r'.code)) end--
        return decodeUtf8(bytes.copyOf(end))
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, StandardCharsets.ISO_8859_1)
        }
    }

    private fun decodePaxPath(value: String): String = value

    private fun normalizePath(raw: String): String {
        if (raw.isEmpty()) throw IOException("TAR 条目路径为空")
        val path = raw.replace('\\', '/')
        if (path.startsWith('/')) throw IOException("TAR 包含绝对路径")
        val parts = path.split('/')
        val normalized = mutableListOf<String>()
        parts.forEach { part ->
            if (part.isEmpty() || part == ".") return@forEach
            if (part == "..") throw IOException("TAR 包含路径穿越内容")
            if (part.any { Character.isISOControl(it) }) throw IOException("TAR 路径包含控制字符")
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_COMPONENT_BYTES) throw IOException("TAR 路径分段过长")
            if (normalized.isEmpty() && part.length >= 2 && part[1] == ':' &&
                ((part[0] in 'a'..'z') || (part[0] in 'A'..'Z'))
            ) throw IOException("TAR 包含 Windows 绝对路径")
            normalized += part
        }
        if (normalized.isEmpty()) throw IOException("TAR 条目路径为空")
        val result = normalized.joinToString("/")
        if (result.toByteArray(StandardCharsets.UTF_8).size > MAX_PATH_BYTES) {
            throw IOException("TAR 条目路径过长")
        }
        return result
    }

    private fun parsePaxRecords(data: ByteArray): Map<String, String?> {
        val result = linkedMapOf<String, String?>()
        var position = 0
        while (position < data.size) {
            val space = data.indexOfByte(' '.code.toByte(), position)
            if (space <= position) throw IOException("PAX 记录格式无效")
            val lengthText = String(data, position, space - position, StandardCharsets.US_ASCII)
            val recordLength = lengthText.toLongOrNull()
                ?: throw IOException("PAX 记录长度无效")
            if (recordLength < 5L || recordLength > data.size - position) {
                throw IOException("PAX 记录长度无效")
            }
            val end = position + recordLength.toInt()
            if (data[end - 1].toInt() != '\n'.code) throw IOException("PAX 记录不完整")
            val equals = data.indexOfByte('='.code.toByte(), space + 1, end - 1)
            if (equals <= space + 1) throw IOException("PAX 键值无效")
            val key = String(data, space + 1, equals - space - 1, StandardCharsets.US_ASCII)
            val valueBytes = data.copyOfRange(equals + 1, end - 1)
            if (key.startsWith("GNU.sparse") || key == "SCHILY.realsize") {
                if (valueBytes.isNotEmpty()) throw IOException("TAR 稀疏文件不受支持")
            } else if (key == "path" || key == "size" || key == "mtime") {
                result[key] = if (valueBytes.isEmpty()) null else decodeUtf8(valueBytes)
            }
            position = end
        }
        return result
    }

    private fun parsePaxSize(value: String): Long {
        if (!value.matches(Regex("[0-9]+"))) throw IOException("PAX 文件大小字段无效")
        return value.toLongOrNull() ?: throw IOException("PAX 文件大小字段溢出")
    }

    private fun parsePaxMtime(value: String): Long {
        if (!value.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) throw IOException("PAX 修改时间字段无效")
        val seconds = try { BigDecimal(value) } catch (_: NumberFormatException) {
            throw IOException("PAX 修改时间字段无效")
        }
        val millis = seconds.movePointRight(3).setScale(0, RoundingMode.DOWN)
        if (millis < BigDecimal.ZERO || millis > BigDecimal.valueOf(Long.MAX_VALUE)) {
            throw IOException("PAX 修改时间字段溢出")
        }
        return millis.longValueExact()
    }

    private fun parseHeaderMtime(header: ByteArray, offset: Int, length: Int): Long {
        val seconds = parseTarNumber(header, offset, length, "TAR 修改时间字段无效")
        if (seconds > Long.MAX_VALUE / 1000L) throw IOException("TAR 修改时间字段溢出")
        return seconds * 1000L
    }

    private fun ByteArray.indexOfByte(value: Byte, start: Int, endExclusive: Int = size): Int {
        for (index in start until endExclusive) {
            if (this[index] == value) return index
        }
        return -1
    }
}
