package com.subtitleedit.util

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

internal class ArchivePasswordRequiredException(message: String) : IOException(message)

internal object OfficialSevenZipArchive {
    data class Entry(
        val name: String,
        val size: Long,
        val compressedSize: Long,
        val isDirectory: Boolean,
        val isSymbolicLink: Boolean,
        val isEncrypted: Boolean,
        val modifiedTimeMillis: Long
    )

    fun list(file: File, password: CharArray?): List<Entry> {
        if (isVolume(file)) return withExpandedVolume(file, password) { list(it, password) }
        if (isCompressedTar(file)) return withExpandedTar(file, password) { list(it, null) }
        return listRaw(file, password)
    }

    private fun listRaw(file: File, password: CharArray?): List<Entry> {
        val capture = File.createTempFile("subtitleedit-7z-list-", ".txt")
        try {
            val result = run(listOf("l", "-slt", "-sccUTF-8", "-y") + passwordArg(password) + file.absolutePath, capture)
            if (result != 0) {
                if (isPasswordFailure(capture)) {
                    throw ArchivePasswordRequiredException(
                        passwordErrorMessage(password)
                    )
                }
                throw java.io.IOException("7-Zip 无法读取压缩包（错误码 $result）")
            }
            // BZip2 and XZ are single-file streams, rather than containers with an
            // embedded file name.  Their 7-Zip handlers therefore expose one item
            // without a Path field in technical-list mode.  Keep the fallback in the
            // adapter so callers still get a normal, safe archive entry.
            val entries = parseList(capture, file)
            if ((password == null || password.isEmpty()) && entries.any { it.isEncrypted }) {
                throw ArchivePasswordRequiredException("压缩包需要密码")
            }
            return entries
        } finally {
            capture.delete()
        }
    }

    /**
     * Decodes only the compression layer of a compressed TAR and exposes the TAR bytes as a
     * pipe. The consumer runs on the calling thread while 7-Zip produces data on a dedicated
     * thread, so TAR headers can be handled before the remaining payload is decoded.
     */
    fun <T> withCompressedTarStream(
        file: File,
        password: CharArray?,
        block: (InputStream, Long) -> T
    ): T {
        require(isCompressedTar(file)) { "文件不是受支持的 TAR 压缩流" }
        // Listing a compressed TAR first forces 7-Zip to decode the entire outer stream.  That
        // delays both the first progress callback and the first TAR header/conflict decision.
        // The size hints below only inspect container metadata; zero means that the UI should use
        // an indeterminate progress bar while still reporting bytes consumed.
        val expectedTarBytes = compressedTarSizeHint(file)
        val (readDescriptor, writeDescriptor) = createPipeAboveStandardDescriptors()
        val producerFailure = AtomicReference<Throwable?>()
        val producer = Thread({
            try {
                writeDescriptor.use { output ->
                    streamCompressedTar(file, password, output.fd)
                }
            } catch (error: Throwable) {
                producerFailure.set(error)
            }
        }, "subtitleedit-7zip-tar")

        try {
            producer.start()
        } catch (error: Throwable) {
            readDescriptor.close()
            writeDescriptor.close()
            throw error
        }

        val consumerResult = try {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(readDescriptor).use { input ->
                    block(input, expectedTarBytes)
                }
            }
        } finally {
            runCatching { readDescriptor.close() }
            var interrupted = false
            while (producer.isAlive) {
                try {
                    producer.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        val consumerError = consumerResult.exceptionOrNull()
        val producerError = producerFailure.get()
        if (consumerError != null) {
            if (producerError is ArchivePasswordRequiredException) {
                producerError.addSuppressed(consumerError)
                throw producerError
            }
            if (producerError != null) consumerError.addSuppressed(producerError)
            throw consumerError
        }
        if (producerError != null) throw producerError
        return consumerResult.getOrThrow()
    }

    private fun streamCompressedTar(file: File, password: CharArray?, outputFd: Int) {
        val capture = File.createTempFile("subtitleedit-7z-stream-", ".txt")
        try {
            val result = OfficialSevenZip.executeToFd(
                arguments = listOf(
                    "x", "-so", "-y", "-bd", "-bso0", "-bsp0", "-sccUTF-8"
                ) + passwordArg(password) + file.absolutePath,
                stdoutFd = outputFd,
                capturePath = capture.absolutePath
            )
            if (result != 0) {
                if (isPasswordFailure(capture)) {
                    throw ArchivePasswordRequiredException(passwordErrorMessage(password))
                }
                throw IOException("7-Zip 无法读取 TAR 压缩流（错误码 $result）")
            }
        } finally {
            capture.delete()
        }
    }

    private fun createPipeAboveStandardDescriptors(): Pair<ParcelFileDescriptor, ParcelFileDescriptor> {
        val raw = ParcelFileDescriptor.createPipe()
        var readDescriptor: ParcelFileDescriptor? = null
        try {
            readDescriptor = duplicateAboveStandard(raw[0])
            val writeDescriptor = duplicateAboveStandard(raw[1])
            return readDescriptor to writeDescriptor
        } catch (error: Throwable) {
            runCatching { readDescriptor?.close() }.onFailure(error::addSuppressed)
            raw.forEach { descriptor ->
                runCatching { descriptor.close() }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun duplicateAboveStandard(
        descriptor: ParcelFileDescriptor
    ): ParcelFileDescriptor {
        val duplicate = OfficialSevenZip.duplicateFdAboveStandard(descriptor.fd)
        if (duplicate < 3) {
            if (duplicate >= 0) runCatching { ParcelFileDescriptor.adoptFd(duplicate).close() }
            throw IOException("无法为 TAR 管道分配安全文件描述符")
        }
        val elevated = try {
            ParcelFileDescriptor.adoptFd(duplicate)
        } catch (error: Throwable) {
            runCatching { ParcelFileDescriptor.adoptFd(duplicate).close() }
                .onFailure(error::addSuppressed)
            throw error
        }
        try {
            descriptor.close()
        } catch (error: Throwable) {
            runCatching { elevated.close() }.onFailure(error::addSuppressed)
            throw error
        }
        return elevated
    }

    fun test(file: File, password: CharArray?, entries: List<ArchiveManager.EntryInfo>): Long {
        if (isVolume(file)) return withExpandedVolume(file, password) { test(it, password, entries) }
        if (isCompressedTar(file)) return withExpandedTar(file, password) { test(it, null, entries) }
        val capture = File.createTempFile("subtitleedit-7z-test-", ".txt")
        try {
            val result = run(
                listOf("t", "-y", "-bd", "-bso0", "-bsp0", "-sccUTF-8") +
                    passwordArg(password) + file.absolutePath,
                capture
            )
            if (result != 0) {
                if (isPasswordFailure(capture)) {
                    throw ArchivePasswordRequiredException(passwordErrorMessage(password))
                }
                throw java.io.IOException("7-Zip 压缩包测试失败（错误码 $result）")
            }
            return entries.sumOf { it.size.coerceAtLeast(0L) }
        } finally {
            capture.delete()
        }
    }

    fun extractTo(file: File, staging: File, password: CharArray?) {
        if (isVolume(file)) {
            withExpandedVolume(file, password) { extractTo(it, staging, password) }
            return
        }
        if (isCompressedTar(file)) {
            withExpandedTar(file, password) { extractTo(it, staging, null) }
            return
        }
        extractRaw(file, staging, password)
        if (isBareBzip2OrXz(file)) {
            // The BZip2/XZ handlers use CArc::DefaultName while extracting.  The
            // exact name is reconstructed here as well, so it remains identical to
            // the synthetic entry returned by listRaw even when 7-Zip changes the
            // technical-list output format.
            normalizeSingleStreamOutput(file, staging)
        }
    }

    private fun extractRaw(file: File, staging: File, password: CharArray?, archiveType: String? = null) {
        staging.mkdirs()
        val capture = File.createTempFile("subtitleedit-7z-extract-", ".txt")
        try {
            val result = run(
                listOf("x", "-y", "-aoa", "-bd", "-bso0", "-bsp0", "-sccUTF-8", "-o${staging.absolutePath}") +
                    listOfNotNull(archiveType) +
                    passwordArg(password) + file.absolutePath,
                capture
            )
            if (result != 0) {
                if (isPasswordFailure(capture)) {
                    throw ArchivePasswordRequiredException(passwordErrorMessage(password))
                }
                throw java.io.IOException("7-Zip 解压失败（错误码 $result）")
            }
        } finally {
            capture.delete()
        }
    }

    private fun <T> withExpandedTar(file: File, password: CharArray?, block: (File) -> T): T {
        val directory = Files.createTempDirectory("subtitleedit-7z-tar-").toFile()
        try {
            extractRaw(file, directory, password)
            val tar = directory.walkTopDown().firstOrNull { it.isFile }
                ?: throw java.io.IOException("压缩流中未找到 TAR 数据")
            return block(tar)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun <T> withExpandedVolume(file: File, password: CharArray?, block: (File) -> T): T {
        val directory = Files.createTempDirectory("subtitleedit-7z-volume-").toFile()
        try {
            // Split 7z archives can be encrypted as well; keep the caller's password while
            // asking 7-Zip to reassemble the volume set.
            extractRaw(file, directory, password, "-tsplit")
            val archive = directory.walkTopDown().firstOrNull { it.isFile }
                ?: throw java.io.IOException("分卷中未找到完整压缩包")
            return block(archive)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun isVolume(file: File): Boolean = file.name.endsWith(".001", ignoreCase = true)

    private fun isCompressedTar(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".tar.gz") || name.endsWith(".tgz") ||
            name.endsWith(".tar.bz2") || name.endsWith(".tbz") || name.endsWith(".tbz2") ||
            name.endsWith(".tar.xz") || name.endsWith(".txz")
    }

    /**
     * Returns a cheap uncompressed-size hint for the outer compression stream.  Gzip stores the
     * low 32 bits in its trailer and XZ stores exact block sizes in its index.  BZip2 has no
     * equivalent footer, so it deliberately returns zero.  A failed/ambiguous parse also returns
     * zero rather than delaying extraction or imposing an incorrect length check.
     */
    private fun compressedTarSizeHint(file: File): Long = when {
        file.name.endsWith(".tar.gz", ignoreCase = true) ||
            file.name.endsWith(".tgz", ignoreCase = true) -> gzipSizeHint(file)
        file.name.endsWith(".tar.xz", ignoreCase = true) ||
            file.name.endsWith(".txz", ignoreCase = true) -> xzSizeHint(file)
        else -> 0L
    }

    private fun gzipSizeHint(file: File): Long = runCatching {
        if (file.length() < 18L) return@runCatching 0L
        RandomAccessFile(file, "r").use { input ->
            if (input.readUnsignedByte() != 0x1f || input.readUnsignedByte() != 0x8b ||
                input.readUnsignedByte() != 8
            ) return@use 0L
            input.seek(input.length() - 4L)
            readLittleEndian32(input)
        }
    }.getOrDefault(0L)

    private fun xzSizeHint(file: File): Long = runCatching {
        val length = file.length()
        if (length < XZ_FOOTER_SIZE + XZ_HEADER_SIZE) return@runCatching 0L
        RandomAccessFile(file, "r").use { input ->
            var footerEnd = length
            var paddingBytes = 0L
            while (footerEnd >= XZ_FOOTER_SIZE) {
                input.seek(footerEnd - XZ_FOOTER_SIZE)
                val footer = ByteArray(XZ_FOOTER_SIZE.toInt())
                input.readFully(footer)
                if (footer[10] == 'Y'.code.toByte() && footer[11] == 'Z'.code.toByte()) break
                if (footerEnd < XZ_FOOTER_SIZE + 4L || paddingBytes >= XZ_MAX_PADDING_BYTES) {
                    return@use 0L
                }
                input.seek(footerEnd - 4L)
                repeat(4) {
                    if (input.readUnsignedByte() != 0) return@use 0L
                }
                footerEnd -= 4L
                paddingBytes += 4L
            }
            if (footerEnd < XZ_FOOTER_SIZE) return@use 0L

            input.seek(0L)
            val header = ByteArray(XZ_HEADER_SIZE.toInt())
            input.readFully(header)
            if (!header.copyOfRange(0, 6).contentEquals(XZ_MAGIC) ||
                header[6] != 0.toByte() ||
                header[6] != footerByteAt(input, footerEnd - 4L) ||
                header[7] != footerByteAt(input, footerEnd - 3L)
            ) return@use 0L

            input.seek(footerEnd - XZ_FOOTER_SIZE)
            val footer = ByteArray(XZ_FOOTER_SIZE.toInt())
            input.readFully(footer)
            if (readLittleEndian32(footer, 0) != crc32(footer, 4, 6)) return@use 0L
            val backwardSize = readLittleEndian32(footer, 4)
            val indexSize = (backwardSize + 1L) * 4L
            if (indexSize < 8L || indexSize > XZ_MAX_INDEX_BYTES ||
                indexSize > footerEnd - XZ_FOOTER_SIZE
            ) return@use 0L

            val index = ByteArray(indexSize.toInt())
            input.seek(footerEnd - XZ_FOOTER_SIZE - indexSize)
            input.readFully(index)
            if (index[0].toInt() != 0 ||
                readLittleEndian32(index, index.size - 4) != crc32(index, 0, index.size - 4)
            ) return@use 0L

            var offset = 1
            val recordCount = readXzVli(index, offset, index.size - 4).also { offset = it.second }.first
            if (recordCount < 0L || recordCount > XZ_MAX_RECORDS) return@use 0L
            var total = 0L
            var paddedBlocksSize = 0L
            repeat(recordCount.toInt()) {
                val unpadded = readXzVli(index, offset, index.size - 4).also { offset = it.second }.first
                if (unpadded <= 0L) return@use 0L
                val padded = if (unpadded > Long.MAX_VALUE - 3L) {
                    return@use 0L
                } else {
                    (unpadded + 3L) and -4L
                }
                if (paddedBlocksSize > Long.MAX_VALUE - padded) return@use 0L
                paddedBlocksSize += padded
                val uncompressed = readXzVli(index, offset, index.size - 4).also { offset = it.second }.first
                if (uncompressed < 0L || total > Long.MAX_VALUE - uncompressed) return@use 0L
                total += uncompressed
            }
            while (offset < index.size - 4) {
                if (index[offset++].toInt() != 0) return@use 0L
            }
            val fixedStreamSize = XZ_HEADER_SIZE + indexSize + XZ_FOOTER_SIZE
            if (paddedBlocksSize > Long.MAX_VALUE - fixedStreamSize) return@use 0L
            val streamSize = fixedStreamSize + paddedBlocksSize
            if (offset != index.size - 4 || streamSize != footerEnd || total <= 0L) 0L else total
        }
    }.getOrDefault(0L)

    private fun readXzVli(bytes: ByteArray, start: Int, endExclusive: Int): Pair<Long, Int> {
        var offset = start
        var value = 0L
        var shift = 0
        while (offset < endExclusive) {
            val current = bytes[offset++].toInt() and 0xff
            val part = (current and 0x7f).toLong()
            if (shift >= 63 || part > (Long.MAX_VALUE ushr shift)) {
                throw IOException("XZ 索引大小无效")
            }
            value = value or (part shl shift)
            if ((current and 0x80) == 0) {
                if (shift > 0 && part == 0L) throw IOException("XZ 索引 VLI 非规范")
                return value to offset
            }
            shift += 7
        }
        throw IOException("XZ 索引 VLI 无效")
    }

    private fun footerByteAt(input: RandomAccessFile, offset: Long): Byte {
        input.seek(offset)
        return input.readByte()
    }

    private fun crc32(bytes: ByteArray, offset: Int, length: Int): Long =
        CRC32().apply { update(bytes, offset, length) }.value

    private fun readLittleEndian32(input: RandomAccessFile): Long =
        (input.readUnsignedByte().toLong()) or
            (input.readUnsignedByte().toLong() shl 8) or
            (input.readUnsignedByte().toLong() shl 16) or
            (input.readUnsignedByte().toLong() shl 24)

    private fun readLittleEndian32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toInt() and 0xff).toLong() or
            ((bytes[offset + 1].toInt() and 0xff).toLong() shl 8) or
            ((bytes[offset + 2].toInt() and 0xff).toLong() shl 16) or
            ((bytes[offset + 3].toInt() and 0xff).toLong() shl 24)

    private fun passwordErrorMessage(password: CharArray?): String =
        if (password == null || password.isEmpty()) "压缩包需要密码" else "压缩包密码错误"

    private fun isPasswordFailure(capture: File): Boolean = runCatching {
        val output = capture.readText(StandardCharsets.UTF_8)
        output.contains("password", ignoreCase = true) ||
            output.contains("passphrase", ignoreCase = true) ||
            output.contains("decrypt", ignoreCase = true)
    }.getOrDefault(false)

    fun create(
        destination: File,
        sources: List<File>,
        format: ArchiveManager.CreateFormat,
        method: ArchiveManager.CompressionMethod,
        password: CharArray?,
        encryptionMethod: ArchiveManager.EncryptionMethod?,
        splitSizeBytes: Long?,
        progressCapture: File? = null
    ) {
        require(
            encryptionMethod == null ||
                encryptionMethod in ArchiveManager.encryptionMethods(format)
        ) { "加密方式与格式不匹配" }
        if (format == ArchiveManager.CreateFormat.TAR && method != ArchiveManager.CompressionMethod.TAR_STORE) {
            val temporaryTar = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tar")
            try {
                create(
                    temporaryTar,
                    sources,
                    ArchiveManager.CreateFormat.TAR,
                    ArchiveManager.CompressionMethod.TAR_STORE,
                    null,
                    null,
                    null,
                    progressCapture
                )
                val compressor = when (method) {
                    ArchiveManager.CompressionMethod.TAR_GZIP -> "-tgzip"
                    ArchiveManager.CompressionMethod.TAR_BZIP2 -> "-tbzip2"
                    ArchiveManager.CompressionMethod.TAR_XZ -> "-txz"
                    else -> error("Unsupported TAR method")
                }
                val volume = splitSizeBytes?.let { listOf("-v${it}b") }.orEmpty()
                val result = run(
                    listOf("a", "-y", "-bb1", "-bsp1", "-sccUTF-8", compressor) +
                        volume + listOf(destination.absolutePath, temporaryTar.name),
                    workingDirectory = temporaryTar.absoluteFile.parentFile,
                    capture = progressCapture
                )
                if (result != 0) throw java.io.IOException("7-Zip 创建压缩包失败（错误码 $result）")
            } finally {
                temporaryTar.delete()
            }
            return
        }
        val sourceDirectory = sources.first().absoluteFile.parentFile
        require(sources.all { it.absoluteFile.parentFile == sourceDirectory }) { "待压缩文件必须位于同一目录" }
        val args = buildCreateArguments(
            destination = destination,
            sourceNames = sources.map(File::getName),
            format = format,
            method = method,
            password = password,
            encryptionMethod = encryptionMethod,
            splitSizeBytes = splitSizeBytes
        )
        val result = run(args, capture = progressCapture, workingDirectory = sourceDirectory)
        if (result != 0) throw java.io.IOException("7-Zip 创建压缩包失败（错误码 $result）")
    }

    internal fun buildCreateArguments(
        destination: File,
        sourceNames: List<String>,
        format: ArchiveManager.CreateFormat,
        method: ArchiveManager.CompressionMethod,
        password: CharArray?,
        encryptionMethod: ArchiveManager.EncryptionMethod?,
        splitSizeBytes: Long?
    ): List<String> {
        val args = mutableListOf("a", "-y", "-bb1", "-bsp1", "-sccUTF-8")
        args += when (format) {
            ArchiveManager.CreateFormat.ZIP -> "-tzip"
            ArchiveManager.CreateFormat.SEVEN_Z -> "-t7z"
            ArchiveManager.CreateFormat.TAR -> "-ttar"
        }
        args += methodArg(method)
        if (password != null && password.isNotEmpty()) {
            args += "-p${String(password)}"
            if (format == ArchiveManager.CreateFormat.ZIP) {
                args += encryptionMethodArg(
                    encryptionMethod ?: ArchiveManager.EncryptionMethod.ZIP_CRYPTO
                )
            }
            if (format == ArchiveManager.CreateFormat.SEVEN_Z) args += "-mhe=on"
        }
        splitSizeBytes?.let { args += "-v${it}b" }
        args += destination.absolutePath
        args += sourceNames
        return args
    }

    private fun methodArg(method: ArchiveManager.CompressionMethod): String = when (method) {
        ArchiveManager.CompressionMethod.ZIP_DEFLATE,
        ArchiveManager.CompressionMethod.SEVEN_Z_DEFLATE -> "-m0=Deflate"
        ArchiveManager.CompressionMethod.ZIP_STORE,
        ArchiveManager.CompressionMethod.SEVEN_Z_COPY,
        ArchiveManager.CompressionMethod.TAR_STORE -> "-mx=0"
        ArchiveManager.CompressionMethod.SEVEN_Z_LZMA2 -> "-m0=LZMA2:d=2m"
        ArchiveManager.CompressionMethod.SEVEN_Z_BZIP2 -> "-m0=BZip2"
        ArchiveManager.CompressionMethod.TAR_GZIP,
        ArchiveManager.CompressionMethod.TAR_BZIP2,
        ArchiveManager.CompressionMethod.TAR_XZ -> "-mx=0"
    }

    private fun encryptionMethodArg(method: ArchiveManager.EncryptionMethod): String = when (method) {
        ArchiveManager.EncryptionMethod.ZIP_CRYPTO -> "-mem=ZipCrypto"
        ArchiveManager.EncryptionMethod.ZIP_AES_256 -> "-mem=AES256"
        ArchiveManager.EncryptionMethod.SEVEN_Z_AES_256 ->
            throw IllegalArgumentException("7Z 不支持 ZIP 加密方式参数")
    }

    private fun passwordArg(password: CharArray?): List<String> =
        listOf(if (password == null) "-p" else "-p${String(password)}")

    private fun run(arguments: List<String>, capture: File? = null, workingDirectory: File? = null): Int =
        OfficialSevenZip.execute(arguments, capture?.absolutePath, workingDirectory?.absolutePath)

    private fun parseList(capture: File, archive: File): List<Entry> {
        val result = mutableListOf<Entry>()
        var fields = linkedMapOf<String, String>()
        var anonymousFields: Map<String, String>? = null

        fun flush() {
            if (fields.isEmpty()) return
            val name = fields["Path"]?.takeIf { it.isNotBlank() }
            if (fields.containsKey("Type")) {
                // The archive-property block has a Type and no item Path.  Some
                // stream handlers also put Size in that same block; retain it as a
                // possible fallback instead of losing the only size metadata.
                if (name == null && fields.containsKey("Size") && anonymousFields == null) {
                    anonymousFields = fields.toMap()
                }
                fields = linkedMapOf()
                return
            }
            if (name != null) {
                result += entryFromFields(name, fields)
            } else if (fields.containsKey("Size") && anonymousFields == null) {
                // BZip2/XZ item records intentionally have no Path property.
                anonymousFields = fields.toMap()
            }
            fields = linkedMapOf()
        }
        capture.readLines(StandardCharsets.UTF_8).forEach { line ->
            if (line.isBlank()) flush()
            else line.indexOf(" = ").takeIf { it > 0 }?.let { at ->
                fields[line.substring(0, at)] = line.substring(at + 3)
            }
        }
        flush()

        if (result.isEmpty()) {
            singleStreamEntryName(archive)?.let { name ->
                result += entryFromFields(
                    name = name,
                    fields = anonymousFields.orEmpty(),
                    fallbackSize = singleStreamSize(archive, anonymousFields),
                    fallbackPackedSize = archive.length()
                )
            }
        }
        return result
    }

    private fun entryFromFields(
        name: String,
        fields: Map<String, String>,
        fallbackSize: Long = -1L,
        fallbackPackedSize: Long = -1L
    ): Entry = Entry(
        name = name,
        size = fields["Size"]?.toLongOrNull()?.takeIf { it >= 0L } ?: fallbackSize,
        compressedSize = fields["Packed Size"]?.toLongOrNull()?.takeIf { it >= 0L }
            ?: fallbackPackedSize,
        isDirectory = fields["Folder"] == "+" || name.endsWith("/"),
        isSymbolicLink = !fields["Symbolic Link"].isNullOrBlank(),
        isEncrypted = fields["Encrypted"] == "+",
        modifiedTimeMillis = parseTime(fields["Modified"])
    )

    /**
     * Returns the one output name used by 7-Zip for a raw BZip2/XZ stream.
     *
     * These formats do not carry an original filename.  7-Zip consequently strips
     * the compression suffix from the input filename (the same rule as 7-Zip's
     * `CArc::GetItem_DefaultPath`); a filename that would become empty receives a
     * trailing '~'.
     */
    internal fun singleStreamEntryName(file: File): String? {
        if (!isBareBzip2OrXz(file)) return null
        val original = file.name
        val lower = original.lowercase()
        val suffix = when {
            lower.endsWith(".bz2") -> ".bz2"
            lower.endsWith(".bz") -> ".bz"
            lower.endsWith(".xz") -> ".xz"
            else -> return null
        }
        val base = original.substring(0, original.length - suffix.length).trimEnd()
        return if (base.isNotEmpty()) base else "$original~"
    }

    private fun isBareBzip2OrXz(file: File): Boolean {
        val lower = file.name.lowercase()
        if (isCompressedTar(file)) return false
        return lower.endsWith(".bz") || lower.endsWith(".bz2") || lower.endsWith(".xz")
    }

    private fun singleStreamSize(file: File, fields: Map<String, String>?): Long {
        fields?.get("Size")?.toLongOrNull()?.takeIf { it >= 0L }?.let { return it }
        // XZ stores the uncompressed size in its index.  BZip2 has no footer size
        // and remains unknown until the stream is decoded.
        return if (file.name.endsWith(".xz", ignoreCase = true)) xzSizeHint(file) else -1L
    }

    private fun normalizeSingleStreamOutput(file: File, staging: File) {
        val expectedName = singleStreamEntryName(file) ?: return
        val outputs = staging.listFiles()?.toList()
            ?: throw IOException("无法读取压缩流暂存目录")
        if (outputs.size != 1) {
            throw IOException("压缩流中应包含一个文件，实际找到 ${outputs.size} 个")
        }
        val source = outputs.single()
        if (Files.isSymbolicLink(source.toPath())) {
            throw IOException("压缩流输出了不支持的符号链接")
        }
        if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("压缩流未输出普通文件")
        }
        val target = File(staging, expectedName)
        val sourcePath = source.toPath().toAbsolutePath().normalize()
        val targetPath = target.toPath().toAbsolutePath().normalize()
        if (sourcePath == targetPath) return
        if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("压缩流输出文件名冲突：$expectedName")
        }
        Files.move(sourcePath, targetPath)
    }

    private fun parseTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching {
            LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    private const val XZ_HEADER_SIZE = 12L
    private const val XZ_FOOTER_SIZE = 12L
    private const val XZ_MAX_INDEX_BYTES = 16L * 1024L * 1024L
    private const val XZ_MAX_RECORDS = 1_000_000L
    private const val XZ_MAX_PADDING_BYTES = 1024L
    private val XZ_MAGIC = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
}
