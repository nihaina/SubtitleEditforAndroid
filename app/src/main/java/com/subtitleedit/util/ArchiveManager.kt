package com.subtitleedit.util

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object ArchiveManager {

    enum class CreateFormat(val displayName: String) {
        ZIP("ZIP"),
        SEVEN_Z("7Z"),
        TAR("TAR")
    }

    enum class CompressionMethod(val displayName: String) {
        ZIP_DEFLATE("Deflate（标准）"),
        ZIP_STORE("仅存储"),
        SEVEN_Z_LZMA2("LZMA2（较高压缩率）"),
        SEVEN_Z_BZIP2("BZip2"),
        SEVEN_Z_DEFLATE("Deflate（快速，推荐）"),
        SEVEN_Z_COPY("仅存储"),
        TAR_STORE("仅归档"),
        TAR_GZIP("GZip"),
        TAR_BZIP2("BZip2"),
        TAR_XZ("XZ")
    }

    /**
     * Encryption supported by each archive format.  A password is still required before an
     * encryption method has any effect; keeping that distinction prevents a selected UI option
     * from encrypting an archive when the password field is empty.
     */
    enum class EncryptionMethod(val displayName: String) {
        ZIP_CRYPTO("ZipCrypto（兼容性优先）"),
        ZIP_AES_256("AES-256（更安全）"),
        SEVEN_Z_AES_256("AES-256（7Z）")
    }

    data class EntryInfo(
        val name: String,
        val size: Long,
        val compressedSize: Long,
        val isDirectory: Boolean,
        val modifiedTimeMillis: Long = 0L
    )

    data class TestResult(val entryCount: Int, val totalBytes: Long)

    data class ExtractResult(val entryCount: Int, val totalBytes: Long, val skippedCount: Int)

    data class CompressionProgress(
        val generatedBytes: Long,
        val sourceBytes: Long,
        val processedBytes: Long,
        val percent: Int?,
        val currentFileName: String?
    )

    enum class ConflictPolicy { FAIL, OVERWRITE, RENAME, SKIP }

    /**
     * Decision returned by [extractArchive]'s optional streaming conflict callback.
     *
     * The callback is invoked synchronously, before the conflicting entry is opened.  A caller
     * can set [applyToAll] to carry the selected policy to subsequent conflicts in the same
     * extraction.  [ConflictPolicy.FAIL] is allowed so callers can abort by returning it (the
     * usual cancellation path is to throw from the callback).
     */
    data class ConflictResolution(
        val policy: ConflictPolicy,
        val applyToAll: Boolean = false
    )

    enum class ProgressPhase { SCANNING, EXTRACTING }

    data class DestinationConflict(
        val entryName: String,
        val sourceSize: Long = -1L,
        val sourceModifiedTimeMillis: Long = 0L,
        val sourceIsDirectory: Boolean = false,
        val existingSize: Long = -1L,
        val existingModifiedTimeMillis: Long = 0L,
        val existingIsDirectory: Boolean? = null,
        val archiveInternal: Boolean = false
    )

    class DestinationConflictException(val conflict: DestinationConflict) :
        IOException("目标已存在：${conflict.entryName}") {
        constructor(entryName: String) : this(DestinationConflict(entryName))

        val entryName: String
            get() = conflict.entryName
    }

    data class ExtractionLimits(
        val maxEntries: Int = 100_000,
        val maxBytes: Long = 20L * 1024 * 1024 * 1024
    ) {
        init {
            require(maxEntries > 0) { "压缩包条目限制必须大于 0" }
            require(maxBytes > 0L) { "解压大小限制必须大于 0" }
        }
    }

    private enum class ReadFormat {
        ZIP,
        SEVEN_Z,
        TAR,
        TAR_GZIP,
        TAR_BZIP2,
        TAR_XZ,
        GZIP,
        BZIP2,
        XZ
    }

    val recognizedExtensions: Set<String> = setOf(
        "zip", "7z", "rar", "tar", "gz", "tgz", "bz", "bz2", "tbz", "tbz2", "xz", "txz", "001"
    )

    fun compressionMethods(format: CreateFormat): List<CompressionMethod> = when (format) {
        CreateFormat.ZIP -> listOf(CompressionMethod.ZIP_DEFLATE, CompressionMethod.ZIP_STORE)
        CreateFormat.SEVEN_Z -> listOf(
            CompressionMethod.SEVEN_Z_DEFLATE,
            CompressionMethod.SEVEN_Z_LZMA2,
            CompressionMethod.SEVEN_Z_BZIP2,
            CompressionMethod.SEVEN_Z_COPY
        )
        CreateFormat.TAR -> listOf(
            CompressionMethod.TAR_STORE,
            CompressionMethod.TAR_GZIP,
            CompressionMethod.TAR_BZIP2,
            CompressionMethod.TAR_XZ
        )
    }

    fun encryptionMethods(format: CreateFormat): List<EncryptionMethod> = when (format) {
        CreateFormat.ZIP -> listOf(
            EncryptionMethod.ZIP_CRYPTO,
            EncryptionMethod.ZIP_AES_256
        )
        CreateFormat.SEVEN_Z -> listOf(EncryptionMethod.SEVEN_Z_AES_256)
        CreateFormat.TAR -> emptyList()
    }

    fun defaultEncryptionMethod(format: CreateFormat): EncryptionMethod? =
        encryptionMethods(format).firstOrNull()

    fun outputExtension(format: CreateFormat, method: CompressionMethod): String = when (format) {
        CreateFormat.ZIP -> "zip"
        CreateFormat.SEVEN_Z -> "7z"
        CreateFormat.TAR -> when (method) {
            CompressionMethod.TAR_STORE -> "tar"
            CompressionMethod.TAR_GZIP -> "tar.gz"
            CompressionMethod.TAR_BZIP2 -> "tar.bz2"
            CompressionMethod.TAR_XZ -> "tar.xz"
            else -> throw IllegalArgumentException("压缩方式与 TAR 不匹配")
        }
    }

    fun isRecognizedArchive(file: File): Boolean =
        file.isFile && archiveExtension(file) in recognizedExtensions

    fun isSupportedArchive(file: File): Boolean = runCatching {
        readFormat(file)
        true
    }.getOrDefault(false)

    /**
     * Returns true for TAR variants whose headers are consumed sequentially. Conflicts can then
     * be resolved as each header arrives without first expanding or listing the complete TAR.
     */
    fun requiresStreamingConflictResolution(file: File): Boolean = when (readFormat(file)) {
        ReadFormat.TAR,
        ReadFormat.TAR_GZIP,
        ReadFormat.TAR_BZIP2,
        ReadFormat.TAR_XZ -> true
        else -> false
    }

    fun createArchive(
        sources: List<File>,
        destination: File,
        format: CreateFormat,
        method: CompressionMethod,
        password: CharArray? = null,
        encryptionMethod: EncryptionMethod? = null,
        splitSizeBytes: Long? = null,
        checkCancelled: () -> Unit = {},
        onProgress: (generatedBytes: Long, sourceBytes: Long) -> Unit = { _, _ -> },
        onDetailedProgress: ((CompressionProgress) -> Unit)? = null,
        onCommitted: () -> Unit = {}
    ) {
        checkCancelled()
        require(sources.isNotEmpty()) { "没有可压缩的文件" }
        require(method in compressionMethods(format)) { "压缩方式与格式不匹配" }
        require(sources.all { it.exists() }) { "部分源文件不存在" }
        require(
            encryptionMethod == null || encryptionMethod in encryptionMethods(format)
        ) { "加密方式与格式不匹配" }
        val passwordEnabled = password != null && password.isNotEmpty()
        if (passwordEnabled && encryptionMethods(format).isEmpty()) {
            throw IllegalArgumentException("密码仅支持 ZIP 和 7Z 格式")
        }
        // Keep the historical/default choice deterministic for callers that only provide a
        // password.  The value is ignored when no password is enabled.
        val effectiveEncryptionMethod = if (passwordEnabled) {
            encryptionMethod ?: defaultEncryptionMethod(format)
        } else {
            null
        }
        if (splitSizeBytes != null) {
            require(format == CreateFormat.ZIP || format == CreateFormat.SEVEN_Z) { "分卷仅支持 ZIP 和 7Z 格式" }
            require(splitSizeBytes >= 64L * 1024) { "分卷大小不能小于 64 KB" }
        }
        val destinationPath = destination.canonicalFile.path
        sources.filter { it.isDirectory }.forEach { source ->
            val sourcePath = source.canonicalFile.path
            if (destinationPath.startsWith(sourcePath + File.separator)) {
                throw IOException("不能在被压缩文件夹内部创建压缩包：${source.name}")
            }
        }
        if (destination.exists()) throw IOException("目标文件已存在：${destination.name}")
        destination.parentFile?.mkdirs()

        validateSources(sources, checkCancelled)
        var sourceBytes = 0L
        sources.forEach { source ->
            sourceBytes = addArchiveBytes(sourceBytes, sourceTreeBytes(source, checkCancelled))
        }
        val temp = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.${outputExtension(format, method)}")
        try {
            checkCancelled()
            onProgress(0L, sourceBytes)
            createArchiveWithProgress(
                destination = temp,
                sources = sources,
                format = format,
                method = method,
                password = password,
                encryptionMethod = effectiveEncryptionMethod,
                splitSizeBytes = splitSizeBytes,
                sourceBytes = sourceBytes,
                onProgress = onProgress,
                onDetailedProgress = onDetailedProgress,
                checkCancelled = checkCancelled
            )
            checkCancelled()
            if (splitSizeBytes == null) commitSingleArchive(temp, destination, checkCancelled)
            else commitSplitSevenZip(temp, destination, checkCancelled)
            onCommitted()
        } catch (error: Throwable) {
            runCatching { deleteSplitFiles(temp) }
                .onFailure(error::addSuppressed)
            throw error
        }
    }

    fun listEntries(
        archive: File,
        password: CharArray? = null,
        limits: ExtractionLimits = ExtractionLimits()
    ): List<EntryInfo> {
        val entries = OfficialSevenZipArchive.list(archive, password)
        entries.firstOrNull { it.isSymbolicLink }?.let { throw IOException("压缩包包含不支持的符号链接：${it.name}") }
        val counter = Counter(limits)
        return entries.map {
            counter.addEntry(); if (!it.isDirectory) counter.addBytes(it.size.coerceAtLeast(0L))
            validateEntryName(it.name)
            EntryInfo(it.name, it.size, it.compressedSize, it.isDirectory, it.modifiedTimeMillis)
        }
    }

    fun testArchive(
        archive: File,
        password: CharArray? = null,
        limits: ExtractionLimits = ExtractionLimits()
    ): TestResult {
        val entries = listEntries(archive, password, limits)
        val total = OfficialSevenZipArchive.test(archive, password, entries)
        return TestResult(entries.size, total)
    }

    fun extractArchive(
        archive: File,
        destination: File,
        password: CharArray? = null,
        conflictPolicy: ConflictPolicy = ConflictPolicy.FAIL,
        conflictPolicies: Map<String, ConflictPolicy> = emptyMap(),
        conflictsPrechecked: Boolean = false,
        limits: ExtractionLimits = ExtractionLimits(),
        onProgress: ((ProgressPhase, Long, Long) -> Unit)? = null,
        onConflict: ((DestinationConflict) -> ConflictResolution)? = null,
        checkCancelled: () -> Unit = {}
    ): ExtractResult {
        checkCancelled()
        require(
            existsNoFollow(destination) &&
                Files.isDirectory(destination.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) { "目标目录不存在或不可用" }
        if (isSequentialTarArchive(archive)) {
            return extractSequentialTarArchive(
                archive = archive,
                destination = destination,
                password = password,
                conflictPolicy = conflictPolicy,
                conflictPolicies = conflictPolicies,
                limits = limits,
                onProgress = onProgress,
                onConflict = onConflict,
                checkCancelled = checkCancelled
            )
        }
        val entries = OfficialSevenZipArchive.list(archive, password)
        checkCancelled()
        entries.firstOrNull { it.isSymbolicLink }?.let { throw IOException("压缩包包含不支持的符号链接：${it.name}") }
        val validationCounter = Counter(limits)
        entries.forEach { entry ->
            checkCancelled()
            validationCounter.addEntry()
            if (!entry.isDirectory) validationCounter.addBytes(entry.size.coerceAtLeast(0L))
            validateEntryName(entry.name)
        }
        if (!conflictsPrechecked && conflictPolicy == ConflictPolicy.FAIL) {
            findDestinationConflicts(archive, destination, password, limits, onProgress)
                .firstOrNull { conflict -> conflict.entryName !in conflictPolicies }
                ?.let { conflict ->
                    throw DestinationConflictException(conflict)
                }
        }

        val totalBytes = entries.sumOf { it.size.coerceAtLeast(0L) }
        onProgress?.invoke(ProgressPhase.EXTRACTING, 0L, totalBytes)
        val counter = Counter(limits)
        val resolver = ExtractionTargetResolver(
            destination = destination,
            policy = conflictPolicy,
            entryPolicies = conflictPolicies,
            onConflict = onConflict
        )
        val staging = File(destination, ".subtitleedit-extract-${UUID.randomUUID()}")
        try {
            extractToStagingWithProgress(
                archive = archive,
                staging = staging,
                password = password,
                totalBytes = totalBytes,
                onProgress = onProgress,
                checkCancelled = checkCancelled
            )
            entries.sortedBy { it.name.count { character -> character == '/' } }.forEach { entry ->
                checkCancelled()
                counter.addEntry()
                validateEntryName(entry.name)
                val source = File(staging, entry.name.replace('/', File.separatorChar))
                if (Files.isSymbolicLink(source.toPath())) throw IOException("压缩包包含不支持的符号链接：${entry.name}")
                val target = resolver.resolve(entry.name, entry.isDirectory, entry.size, entry.modifiedTimeMillis)
                if (target == null) {
                    counter.addSkippedEntry()
                } else if (entry.isDirectory) {
                    if (entry.modifiedTimeMillis > 0L) target.setLastModified(entry.modifiedTimeMillis)
                } else {
                    if (!source.isFile) throw IOException("压缩包条目未正确解压：${entry.name}")
                    counter.addBytes(source.length())
                    target.parentFile?.mkdirs()
                    Files.move(source.toPath(), target.toPath())
                    resolver.registerCreatedOutput(target)
                    if (entry.modifiedTimeMillis > 0L) target.setLastModified(entry.modifiedTimeMillis)
                }
                onProgress?.invoke(ProgressPhase.EXTRACTING, counter.bytes, totalBytes)
            }
        } catch (error: Throwable) {
            resolver.cleanupCreatedOutputs()
            throw error
        } finally {
            deleteTreeNoFollow(staging)
        }
        return ExtractResult(
            entryCount = counter.entries - counter.skippedEntries,
            totalBytes = counter.bytes,
            skippedCount = counter.skippedEntries
        )
    }

    fun findDestinationConflicts(
        archive: File,
        destination: File,
        password: CharArray?,
        limits: ExtractionLimits = ExtractionLimits(),
        onProgress: ((ProgressPhase, Long, Long) -> Unit)? = null
    ): List<DestinationConflict> {
        require(
            existsNoFollow(destination) &&
                Files.isDirectory(destination.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) { "目标目录不存在或不可用" }
        onProgress?.invoke(ProgressPhase.SCANNING, 0L, 0L)
        val scanner = DestinationConflictScanner(destination)
        val entries = OfficialSevenZipArchive.list(archive, password)
        val counter = Counter(limits)
        entries.forEach { entry ->
            counter.addEntry()
            if (!entry.isDirectory) counter.addBytes(entry.size.coerceAtLeast(0L))
            validateEntryName(entry.name)
            scanner.inspect(entry.name, entry.isDirectory, entry.size, entry.modifiedTimeMillis)
            onProgress?.invoke(ProgressPhase.SCANNING, counter.entries.toLong(), entries.size.toLong())
        }
        return scanner.conflicts
    }

    private fun extractSequentialTarArchive(
        archive: File,
        destination: File,
        password: CharArray?,
        conflictPolicy: ConflictPolicy,
        conflictPolicies: Map<String, ConflictPolicy>,
        limits: ExtractionLimits,
        onProgress: ((ProgressPhase, Long, Long) -> Unit)?,
        onConflict: ((DestinationConflict) -> ConflictResolution)?,
        checkCancelled: () -> Unit
    ): ExtractResult {
        checkCancelled()
        val staging = File(destination, ".subtitleedit-extract-${UUID.randomUUID()}")
        val counter = Counter(limits)
        val planner = StreamingConflictPlanner(
            destination = destination,
            initialPolicy = conflictPolicy,
            initialPolicies = conflictPolicies,
            onConflict = onConflict
        )
        var resolver: ExtractionTargetResolver? = null
        try {
            val format = readFormat(archive)
            val readStream: (InputStream, Long) -> List<StreamingTarExtractor.Entry> =
                { input, expectedTarBytes ->
                    StreamingTarExtractor.extract(
                        input = input,
                        staging = staging,
                        expectedTarBytes = expectedTarBytes,
                        maxEntries = limits.maxEntries,
                        maxBytes = limits.maxBytes,
                        validateExpectedSize = format != ReadFormat.TAR_GZIP,
                        checkCancelled = checkCancelled,
                        onEntry = { entry ->
                            checkCancelled()
                            counter.addEntry()
                            validateEntryName(entry.name)
                            planner.inspect(
                                name = entry.name,
                                directory = entry.isDirectory,
                                size = entry.size,
                                modifiedTimeMillis = entry.modifiedTimeMillis
                            )
                        },
                        onProgress = { completed, total ->
                            checkCancelled()
                            onProgress?.invoke(ProgressPhase.EXTRACTING, completed, total)
                        }
                    )
                }

            val entries = when (format) {
                ReadFormat.TAR -> FileInputStream(archive).buffered().use { input ->
                    readStream(input, archive.length())
                }
                ReadFormat.TAR_GZIP,
                ReadFormat.TAR_BZIP2,
                ReadFormat.TAR_XZ -> OfficialSevenZipArchive.withCompressedTarStream(
                    archive,
                    password,
                    readStream
                )
                else -> error("非 TAR 格式进入顺序解压分支")
            }

            val targetResolver = ExtractionTargetResolver(
                destination = destination,
                policy = planner.policy(),
                entryPolicies = planner.policies(),
                onConflict = onConflict
            )
            resolver = targetResolver
            entries.sortedBy { it.name.count { character -> character == '/' } }.forEach { entry ->
                checkCancelled()
                val target = targetResolver.resolve(
                    entry.name,
                    entry.isDirectory,
                    entry.size,
                    entry.modifiedTimeMillis
                )
                if (target == null) {
                    counter.addSkippedEntry()
                } else if (entry.isDirectory) {
                    if (entry.modifiedTimeMillis > 0L) target.setLastModified(entry.modifiedTimeMillis)
                } else {
                    val source = entry.stagedFile
                        ?: throw IOException("TAR 条目缺少暂存文件：${entry.name}")
                    if (Files.isSymbolicLink(source.toPath()) || !source.isFile) {
                        throw IOException("TAR 条目未正确解压：${entry.name}")
                    }
                    counter.addBytes(source.length())
                    target.parentFile?.mkdirs()
                    Files.move(source.toPath(), target.toPath())
                    targetResolver.registerCreatedOutput(target)
                    if (entry.modifiedTimeMillis > 0L) target.setLastModified(entry.modifiedTimeMillis)
                }
            }
            return ExtractResult(
                entryCount = counter.entries - counter.skippedEntries,
                totalBytes = counter.bytes,
                skippedCount = counter.skippedEntries
            )
        } catch (error: Throwable) {
            resolver?.cleanupCreatedOutputs()
            throw error
        } finally {
            deleteTreeNoFollow(staging)
        }
    }

    private class DestinationConflictScanner(private val destination: File) {
        private val archivePaths = mutableMapOf<String, Boolean>()
        private val conflictsByName = linkedMapOf<String, DestinationConflict>()
        val conflicts: List<DestinationConflict>
            get() = conflictsByName.values.toList()

        fun inspectNew(
            rawName: String,
            directory: Boolean,
            size: Long = -1L,
            modifiedTimeMillis: Long = 0L
        ): List<DestinationConflict> {
            val existingNames = conflictsByName.keys.toSet()
            inspect(rawName, directory, size, modifiedTimeMillis)
            return conflictsByName
                .filterKeys { it !in existingNames }
                .values
                .toList()
        }

        fun inspect(
            rawName: String,
            directory: Boolean,
            size: Long = -1L,
            modifiedTimeMillis: Long = 0L
        ) {
            val normalized = validateEntryName(rawName)
            val parts = normalized.split('/')
            var prefix = ""
            for (index in 0 until parts.lastIndex) {
                prefix = if (prefix.isEmpty()) parts[index] else "$prefix/${parts[index]}"
                when (archivePaths[prefix]) {
                    false -> recordArchiveCollision(prefix, sourceDirectory = true)
                    null -> archivePaths[prefix] = true
                    true -> Unit
                }
            }

            val previousType = archivePaths[normalized]
            if (previousType != null && !(directory && previousType)) {
                recordArchiveCollision(normalized, directory, size, modifiedTimeMillis)
            } else if (previousType == null) {
                archivePaths[normalized] = directory
            }

            var current = destination
            prefix = ""
            parts.forEachIndexed { index, part ->
                prefix = if (prefix.isEmpty()) part else "$prefix/$part"
                current = File(current, part)
                if (!existsNoFollow(current)) return
                val requiresDirectory = index < parts.lastIndex || directory
                if (requiresDirectory) {
                    if (!Files.isDirectory(current.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        recordConflict(
                            prefix,
                            sourceDirectory = true,
                            modifiedTimeMillis = modifiedTimeMillis,
                            existing = current
                        )
                        return
                    }
                } else {
                    recordConflict(normalized, directory, size, modifiedTimeMillis, current)
                    return
                }
            }
        }

        private fun recordArchiveCollision(
            name: String,
            sourceDirectory: Boolean,
            sourceSize: Long = -1L,
            modifiedTimeMillis: Long = 0L
        ) {
            val existing = File(destination, name)
            if (!existsNoFollow(existing)) {
                conflictsByName.putIfAbsent(
                    name,
                    DestinationConflict(
                        entryName = name,
                        sourceSize = if (sourceDirectory) -1L else sourceSize,
                        sourceModifiedTimeMillis = modifiedTimeMillis,
                        sourceIsDirectory = sourceDirectory,
                        archiveInternal = true
                    )
                )
                return
            }
            recordConflict(
                name = name,
                sourceDirectory = sourceDirectory,
                sourceSize = sourceSize,
                modifiedTimeMillis = modifiedTimeMillis,
                existing = existing
            )
        }

        private fun recordConflict(
            name: String,
            sourceDirectory: Boolean,
            sourceSize: Long = -1L,
            modifiedTimeMillis: Long = 0L,
            existing: File? = null
        ) {
            val existingIsDirectory = existing?.let {
                Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS)
            }
            val conflict = DestinationConflict(
                entryName = name,
                sourceSize = if (sourceDirectory) -1L else sourceSize,
                sourceModifiedTimeMillis = modifiedTimeMillis,
                sourceIsDirectory = sourceDirectory,
                existingSize = if (existing != null && existingIsDirectory == false) existing.length() else -1L,
                existingModifiedTimeMillis = existing?.lastModified() ?: 0L,
                existingIsDirectory = existingIsDirectory,
                archiveInternal = false
            )
            val previous = conflictsByName[name]
            if (previous == null || (previous.existingIsDirectory == null && existing != null)) {
                conflictsByName[name] = conflict
            }
        }
    }

    /**
     * Plans conflicts while a TAR stream is being consumed.  It deliberately never mutates the
     * destination; the normal resolver is still used only after the complete stream validates.
     */
    private class StreamingConflictPlanner(
        destination: File,
        initialPolicy: ConflictPolicy,
        initialPolicies: Map<String, ConflictPolicy>,
        private val onConflict: ((DestinationConflict) -> ConflictResolution)?
    ) {
        private val scanner = DestinationConflictScanner(destination)
        private val plannedPolicies = initialPolicies.toMutableMap()
        private var defaultPolicy = initialPolicy

        fun inspect(name: String, directory: Boolean, size: Long, modifiedTimeMillis: Long) {
            val conflicts = scanner.inspectNew(name, directory, size, modifiedTimeMillis)
            if (defaultPolicy != ConflictPolicy.FAIL) return
            conflicts.forEach { conflict ->
                if (defaultPolicy != ConflictPolicy.FAIL) return@forEach
                plannedPolicies[conflict.entryName]?.let { planned ->
                    if (planned == ConflictPolicy.FAIL) {
                        throw DestinationConflictException(conflict)
                    }
                    return@forEach
                }
                val resolution = onConflict?.invoke(conflict)
                    ?: throw DestinationConflictException(conflict)
                if (resolution.policy == ConflictPolicy.FAIL) {
                    throw DestinationConflictException(conflict)
                }
                if (resolution.applyToAll) {
                    defaultPolicy = resolution.policy
                } else {
                    plannedPolicies[conflict.entryName] = resolution.policy
                }
            }
        }

        fun policy(): ConflictPolicy = defaultPolicy

        fun policies(): Map<String, ConflictPolicy> = plannedPolicies.toMap()
    }

    private fun isSequentialTarArchive(file: File): Boolean = when (readFormat(file)) {
        ReadFormat.TAR,
        ReadFormat.TAR_GZIP,
        ReadFormat.TAR_BZIP2,
        ReadFormat.TAR_XZ -> true
        else -> false
    }

    private class ExtractionTargetResolver(
        private val destination: File,
        private val policy: ConflictPolicy,
        private val entryPolicies: Map<String, ConflictPolicy>,
        private val onConflict: ((DestinationConflict) -> ConflictResolution)?
    ) {
        private val directoryTargets = mutableMapOf<String, File?>()
        private val directoryOwners = mutableMapOf<String, String>()
        private val createdPaths = mutableListOf<File>()
        private val preservedOnFailureRoots = mutableListOf<Path>()
        private val streamingPolicies = mutableMapOf<String, ConflictPolicy>()
        private var streamingDefaultPolicy: ConflictPolicy? = null

        fun resolve(
            rawName: String,
            directory: Boolean,
            sourceSize: Long = -1L,
            sourceModifiedTimeMillis: Long = 0L
        ): File? {
            val normalized = validateEntryName(rawName)
            val parts = normalized.split('/')
            var current = destination
            var prefix = ""

            parts.forEachIndexed { index, part ->
                prefix = if (prefix.isEmpty()) part else "$prefix/$part"
                val requiresDirectory = index < parts.lastIndex || directory
                if (!requiresDirectory) {
                    return resolveFile(
                        target = File(current, part),
                        relativeName = normalized,
                        sourceSize = sourceSize,
                        sourceModifiedTimeMillis = sourceModifiedTimeMillis
                    )
                }

                val resolved = if (directoryTargets.containsKey(prefix)) {
                    directoryTargets[prefix]
                } else {
                    resolveDirectory(
                        target = File(current, part),
                        relativeName = prefix,
                        sourceModifiedTimeMillis = sourceModifiedTimeMillis
                    ).also {
                        directoryTargets[prefix] = it
                    }
                } ?: return null

                if (!Files.isDirectory(resolved.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("目标目录在解压过程中已变化：$prefix")
                }
                current = resolved
            }
            return current
        }

        private fun resolveDirectory(
            target: File,
            relativeName: String,
            sourceModifiedTimeMillis: Long
        ): File? {
            val targetKey = pathIdentity(target)
            val owner = directoryOwners[targetKey]
            val ownedByAnotherEntry = owner != null && owner != relativeName
            if (!existsNoFollow(target)) {
                return createDirectory(target, relativeName).also {
                    directoryOwners[pathIdentity(it)] = relativeName
                }
            }
            if (!ownedByAnotherEntry &&
                Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                directoryOwners.putIfAbsent(pathIdentity(target), relativeName)
                return target
            }
            val resolved = when (
                policyFor(
                    relativeName = relativeName,
                    sourceDirectory = true,
                    sourceSize = -1L,
                    sourceModifiedTimeMillis = sourceModifiedTimeMillis,
                    existing = target
                )
            ) {
                ConflictPolicy.FAIL -> throw DestinationConflictException(
                    conflictFor(
                        name = relativeName,
                        sourceDirectory = true,
                        sourceSize = -1L,
                        sourceModifiedTimeMillis = sourceModifiedTimeMillis,
                        existing = target
                    )
                )
                ConflictPolicy.SKIP -> null
                ConflictPolicy.RENAME -> createDirectory(uniqueRenamedTarget(target, true), relativeName)
                ConflictPolicy.OVERWRITE -> {
                    deleteTarget(target, relativeName)
                    createDirectory(target, relativeName)
                }
            }
            if (resolved != null) directoryOwners[pathIdentity(resolved)] = relativeName
            return resolved
        }

        private fun resolveFile(
            target: File,
            relativeName: String,
            sourceSize: Long,
            sourceModifiedTimeMillis: Long
        ): File? {
            if (!existsNoFollow(target)) {
                return target
            }
            return when (
                policyFor(
                    relativeName = relativeName,
                    sourceDirectory = false,
                    sourceSize = sourceSize,
                    sourceModifiedTimeMillis = sourceModifiedTimeMillis,
                    existing = target
                )
            ) {
                ConflictPolicy.FAIL -> throw DestinationConflictException(
                    conflictFor(
                        name = relativeName,
                        sourceDirectory = false,
                        sourceSize = sourceSize,
                        sourceModifiedTimeMillis = sourceModifiedTimeMillis,
                        existing = target
                    )
                )
                ConflictPolicy.SKIP -> null
                ConflictPolicy.RENAME -> uniqueRenamedTarget(target, false)
                ConflictPolicy.OVERWRITE -> {
                    deleteTarget(target, relativeName)
                    target
                }
            }
        }

        /**
         * Registers a file only after this extraction successfully creates or moves it, so
         * rollback cannot delete a path another process created after conflict scanning.
         */
        fun registerCreatedOutput(target: File) {
            createdPaths += target
        }

        private fun policyFor(
            relativeName: String,
            sourceDirectory: Boolean,
            sourceSize: Long,
            sourceModifiedTimeMillis: Long,
            existing: File
        ): ConflictPolicy {
            entryPolicies[relativeName]?.let { return it }
            if (policy != ConflictPolicy.FAIL) return policy
            streamingPolicies[relativeName]?.let { return it }
            streamingDefaultPolicy?.let { return it }
            val callback = onConflict ?: return ConflictPolicy.FAIL
            val resolution = callback(
                conflictFor(
                    name = relativeName,
                    sourceDirectory = sourceDirectory,
                    sourceSize = sourceSize,
                    sourceModifiedTimeMillis = sourceModifiedTimeMillis,
                    existing = existing
                )
            )
            if (resolution.applyToAll) {
                streamingDefaultPolicy = resolution.policy
            } else {
                streamingPolicies[relativeName] = resolution.policy
            }
            return resolution.policy
        }

        private fun conflictFor(
            name: String,
            sourceDirectory: Boolean,
            sourceSize: Long,
            sourceModifiedTimeMillis: Long,
            existing: File
        ): DestinationConflict {
            val existingIsDirectory = if (existsNoFollow(existing)) {
                Files.isDirectory(existing.toPath(), LinkOption.NOFOLLOW_LINKS)
            } else {
                null
            }
            return DestinationConflict(
                entryName = name,
                sourceSize = if (sourceDirectory) -1L else sourceSize,
                sourceModifiedTimeMillis = sourceModifiedTimeMillis,
                sourceIsDirectory = sourceDirectory,
                existingSize = if (existingIsDirectory == false) existing.length() else -1L,
                existingModifiedTimeMillis = existing.lastModified(),
                existingIsDirectory = existingIsDirectory
            )
        }

        private fun createDirectory(target: File, relativeName: String): File {
            if (target.mkdir()) {
                createdPaths += target
                return target
            }
            throw IOException("无法创建目录：$relativeName")
        }

        private fun deleteTarget(target: File, relativeName: String) {
            verifyTargetParent(target)
            invalidateDirectoryTargets(target)
            if (!deleteTreeNoFollow(target)) {
                throw IOException("无法覆盖目标：$relativeName")
            }
            // No backup is kept by design. If a later entry fails, retain this replacement
            // instead of deleting the only remaining copy of an originally overwritten target.
            preservedOnFailureRoots.add(target.toPath().toAbsolutePath().normalize())
        }

        fun verifyTargetParent(target: File) {
            val parents = mutableListOf<File>()
            var current = target.parentFile
            while (current != null && current != destination) {
                parents += current
                current = current.parentFile
            }
            if (current != destination ||
                !Files.isDirectory(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("解压目标路径已变化")
            }
            parents.asReversed().forEach { parent ->
                if (!Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("解压目标路径已变化：${parent.name}")
                }
            }
        }

        private fun invalidateDirectoryTargets(target: File) {
            val path = pathIdentity(target)
            val prefix = path + File.separator
            directoryTargets.entries.removeAll { (_, mapped) ->
                if (mapped == null || !existsNoFollow(mapped)) {
                    false
                } else {
                    val mappedPath = pathIdentity(mapped)
                    mappedPath == path || mappedPath.startsWith(prefix)
                }
            }
            directoryOwners.keys.removeAll { it == path || it.startsWith(prefix) }
        }

        fun cleanupCreatedOutputs() {
            createdPaths.asReversed().distinctBy(::pathIdentity).forEach { path ->
                val normalizedPath = path.toPath().toAbsolutePath().normalize()
                if (preservedOnFailureRoots.any { root ->
                        normalizedPath == root || normalizedPath.startsWith(root)
                    }) return@forEach
                when {
                    Files.isRegularFile(path.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                        Files.isSymbolicLink(path.toPath()) ->
                        runCatching { Files.deleteIfExists(path.toPath()) }
                    Files.isDirectory(path.toPath(), LinkOption.NOFOLLOW_LINKS) ->
                        runCatching { Files.deleteIfExists(path.toPath()) }
                }
            }
        }

        private fun pathIdentity(file: File): String = runCatching {
            file.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS).toString()
        }.getOrElse {
            file.absoluteFile.normalize().path
        }
    }

    private fun deleteTreeNoFollow(file: File): Boolean {
        val path = file.toPath()
        if (!existsNoFollow(file)) return true
        return runCatching {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                    throw error
                }

                override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            })
        }.isSuccess
    }

    private fun createArchiveWithProgress(
        destination: File,
        sources: List<File>,
        format: CreateFormat,
        method: CompressionMethod,
        password: CharArray?,
        encryptionMethod: EncryptionMethod?,
        splitSizeBytes: Long?,
        sourceBytes: Long,
        onProgress: (generatedBytes: Long, sourceBytes: Long) -> Unit,
        onDetailedProgress: ((CompressionProgress) -> Unit)?,
        checkCancelled: () -> Unit
    ) {
        val finished = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val progressCapture = File.createTempFile("subtitleedit-7z-progress-", ".txt")
        val worker = Thread({
            try {
                OfficialSevenZipArchive.create(
                    destination = destination,
                    sources = sources,
                    format = format,
                    method = method,
                    password = password,
                    encryptionMethod = encryptionMethod,
                    splitSizeBytes = splitSizeBytes,
                    progressCapture = progressCapture
                )
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.set(true)
            }
        }, "subtitleedit-archive-create")
        worker.start()
        var currentFileName: String? = null
        try {
            while (!finished.get()) {
                checkCancelled()
                val generatedBytes = compressionOutputBytes(destination)
                val nativeProgress = readCompressionProgress(progressCapture)
                nativeProgress.currentFileName?.let { currentFileName = it }
                val percent = nativeProgress.percent
                val processedBytes = if (percent != null && sourceBytes > 0L) {
                    (sourceBytes * percent.toLong() / 100L).coerceIn(0L, sourceBytes)
                } else 0L
                onProgress(generatedBytes, sourceBytes)
                onDetailedProgress?.invoke(
                    CompressionProgress(
                        generatedBytes = generatedBytes,
                        sourceBytes = sourceBytes,
                        processedBytes = processedBytes,
                        percent = percent,
                        currentFileName = currentFileName
                    )
                )
                try {
                    Thread.sleep(COMPRESS_PROGRESS_INTERVAL_MS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("压缩线程被中断", error)
                }
            }
            checkCancelled()
            val generatedBytes = compressionOutputBytes(destination)
            val nativeProgress = readCompressionProgress(progressCapture)
            nativeProgress.currentFileName?.let { currentFileName = it }
            onProgress(generatedBytes, sourceBytes)
            onDetailedProgress?.invoke(
                CompressionProgress(
                    generatedBytes = generatedBytes,
                    sourceBytes = sourceBytes,
                    processedBytes = sourceBytes,
                    percent = 100,
                    currentFileName = currentFileName
                )
            )
            failure.get()?.let { throw it }
        } catch (error: Throwable) {
            if (!finished.get()) {
                worker.interrupt()
                deleteCompressionOutputFiles(destination)
                runCatching { worker.join(COMPRESS_CANCEL_WAIT_MS) }
            }
            throw error
        } finally {
            progressCapture.delete()
        }
    }

    private data class NativeCompressionProgress(val percent: Int?, val currentFileName: String?)

    private fun readCompressionProgress(capture: File): NativeCompressionProgress {
        if (!capture.isFile) return NativeCompressionProgress(null, null)
        val text = runCatching {
            RandomAccessFile(capture, "r").use { file ->
                val length = file.length()
                val start = (length - 64L * 1024L).coerceAtLeast(0L)
                file.seek(start)
                val bytes = ByteArray((length - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                file.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        }.getOrDefault("")
        val percent = Regex("(?:^|[\\r\\n\\u0008])\\s*(\\d{1,3})%")
            .findAll(text)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in 0..100 }
        val currentFile = Regex("(?:^|[\\r\\n\\u0008])(?:\\+|U)\\s+([^\\r\\n\\u0008]+)")
            .findAll(text)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return NativeCompressionProgress(percent, currentFile)
    }

    private fun sourceTreeBytes(file: File, checkCancelled: () -> Unit): Long {
        checkCancelled()
        if (file.isFile) return file.length().coerceAtLeast(0L)
        if (!file.isDirectory) return 0L
        val children = file.listFiles() ?: throw IOException("无法读取文件夹内容：${file.name}")
        var total = 0L
        children.forEach { child ->
            total = addArchiveBytes(total, sourceTreeBytes(child, checkCancelled))
        }
        return total
    }

    private fun compressionOutputBytes(destination: File): Long {
        val prefix = ".${destination.name}."
        val splitPrefix = "${destination.name}."
        var total = 0L
        destination.parentFile?.listFiles { file ->
            file == destination || file.name.startsWith(prefix) || file.name.startsWith(splitPrefix)
        }?.forEach { file ->
            total = addArchiveBytes(total, file.length().coerceAtLeast(0L))
        }
        return total
    }

    private fun deleteCompressionOutputFiles(destination: File) {
        val prefix = ".${destination.name}."
        val splitPrefix = "${destination.name}."
        destination.parentFile?.listFiles { file ->
            file == destination || file.name.startsWith(prefix) || file.name.startsWith(splitPrefix)
        }?.forEach { file ->
            runCatching { file.deleteRecursively() }
        }
    }

    private fun addArchiveBytes(current: Long, amount: Long): Long =
        if (amount > 0L && current > Long.MAX_VALUE - amount) Long.MAX_VALUE else current + amount

    private fun extractToStagingWithProgress(
        archive: File,
        staging: File,
        password: CharArray?,
        totalBytes: Long,
        onProgress: ((ProgressPhase, Long, Long) -> Unit)?,
        checkCancelled: () -> Unit
    ) {
        val finished = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread({
            try {
                OfficialSevenZipArchive.extractTo(archive, staging, password)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.set(true)
            }
        }, "subtitleedit-archive-extract")
        worker.start()
        try {
            while (!finished.get()) {
                checkCancelled()
                onProgress?.invoke(
                    ProgressPhase.EXTRACTING,
                    stagedFileBytes(staging),
                    totalBytes
                )
                try {
                    Thread.sleep(EXTRACT_PROGRESS_INTERVAL_MS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("解压线程被中断", error)
                }
            }
            checkCancelled()
            onProgress?.invoke(
                ProgressPhase.EXTRACTING,
                stagedFileBytes(staging),
                totalBytes
            )
            failure.get()?.let { throw it }
        } catch (error: Throwable) {
            if (!finished.get()) {
                worker.interrupt()
                deleteTreeNoFollow(staging)
                runCatching { worker.join(EXTRACT_CANCEL_WAIT_MS) }
            }
            throw error
        }
    }

    private fun stagedFileBytes(staging: File): Long {
        var total = 0L
        staging.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val size = file.length().coerceAtLeast(0L)
            total = if (size > 0L && total > Long.MAX_VALUE - size) {
                Long.MAX_VALUE
            } else {
                total + size
            }
        }
        return total
    }

    private fun existsNoFollow(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun uniqueRenamedTarget(target: File, directory: Boolean): File {
        val name = target.name
        val separator = if (directory) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val base = if (separator > 0) name.substring(0, separator) else name
        val extension = if (separator > 0) name.substring(separator) else ""
        var index = 1
        while (true) {
            val candidate = File(target.parentFile, "$base（$index）$extension")
            if (!existsNoFollow(candidate)) return candidate
            index++
        }
    }

    private fun validateSources(
        sources: List<File>,
        checkCancelled: () -> Unit
    ) {
        val usedNames = mutableSetOf<String>()
        sources.sortedBy { it.name.lowercase() }.forEach { source ->
            checkCancelled()
            if (!usedNames.add(source.name)) throw IOException("存在同名源文件：${source.name}")
            validateSource(source, source.name, checkCancelled)
        }
    }

    private fun validateSource(
        file: File,
        archiveName: String,
        checkCancelled: () -> Unit
    ) {
        checkCancelled()
        if (Files.isSymbolicLink(file.toPath())) throw IOException("不支持压缩符号链接：$archiveName")
        if (file.isDirectory) {
            val children = file.listFiles()
                ?: throw IOException("无法读取文件夹内容：$archiveName")
            children.sortedBy { it.name.lowercase() }.forEach { child ->
                checkCancelled()
                validateSource(child, archiveName.trimEnd('/') + "/" + child.name, checkCancelled)
            }
        } else if (!file.isFile) {
            throw IOException("不支持的文件类型：$archiveName")
        }
    }

    private fun commitSingleArchive(
        temp: File,
        destination: File,
        checkCancelled: () -> Unit
    ) {
        checkCancelled()
        try {
            Files.move(temp.toPath(), destination.toPath())
            return
        } catch (moveError: IOException) {
            if (existsNoFollow(destination)) {
                throw IOException("目标文件已存在：${destination.name}", moveError)
            }
        }
        var destinationCreated = false
        try {
            Files.newOutputStream(
                destination.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            ).buffered().use { output ->
                destinationCreated = true
                temp.inputStream().buffered().use { input ->
                    copyWithCancellation(input, output, checkCancelled)
                }
            }
            checkCancelled()
            if (!temp.delete()) throw IOException("无法清理临时压缩文件")
        } catch (error: Throwable) {
            if (destinationCreated) {
                runCatching {
                    Files.deleteIfExists(destination.toPath())
                    if (existsNoFollow(destination)) throw IOException("无法清理失败的压缩文件：${destination.name}")
                }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun commitSplitSevenZip(
        temp: File,
        destination: File,
        checkCancelled: () -> Unit
    ) {
        val parts = temp.parentFile?.listFiles { file ->
            file.name.startsWith("${temp.name}.") && file.name.substringAfterLast('.').matches(Regex("\\d{3,}"))
        }?.sortedBy { it.name }.orEmpty()
        if (parts.isEmpty()) throw IOException("7-Zip 分卷文件缺失")

        val targets = parts.associateWith { part ->
            File(destination.parentFile, "${destination.name}.${part.name.substringAfterLast('.')}")
        }
        targets.values.firstOrNull { it.exists() }?.let { existing ->
            throw IOException("目标分卷已存在：${existing.name}")
        }
        val committed = mutableListOf<File>()
        try {
            targets.forEach { (part, target) ->
                checkCancelled()
                try {
                    Files.move(part.toPath(), target.toPath())
                    committed += target
                } catch (moveError: IOException) {
                    if (target.exists()) throw IOException("目标分卷已存在：${target.name}", moveError)
                    Files.newOutputStream(
                        target.toPath(),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                    ).buffered().use { output ->
                        committed += target
                        part.inputStream().buffered().use { input ->
                            copyWithCancellation(input, output, checkCancelled)
                        }
                    }
                    checkCancelled()
                    if (!part.delete()) throw IOException("无法清理临时分卷：${part.name}")
                }
            }
        } catch (error: Throwable) {
            committed.forEach { target ->
                runCatching {
                    Files.deleteIfExists(target.toPath())
                    if (existsNoFollow(target)) throw IOException("无法清理失败的分卷：${target.name}")
                }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun deleteSplitFiles(temp: File) {
        val parts = buildList {
            add(temp)
            temp.parentFile?.listFiles { file ->
                file != temp && file.name.startsWith("${temp.name}.")
            }?.let(::addAll)
        }
        var firstFailure: Throwable? = null
        parts.forEach { part ->
            try {
                Files.deleteIfExists(part.toPath())
                if (existsNoFollow(part)) throw IOException("无法清理临时压缩文件：${part.name}")
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error else firstFailure?.addSuppressed(error)
            }
        }
        firstFailure?.let { throw it }
    }

    private fun validateEntryName(rawName: String): String {
        val name = rawName.replace('\\', '/').trimEnd('/')
        if (name.isBlank() || name.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(name)) {
            throw IOException("压缩包包含无效路径：$rawName")
        }
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." || it.contains('\u0000') }) {
            throw IOException("压缩包包含危险路径：$rawName")
        }
        return parts.joinToString("/")
    }

    private fun readFormat(file: File): ReadFormat {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".zip") -> ReadFormat.ZIP
            name.endsWith(".7z") || name.endsWith(".001") -> ReadFormat.SEVEN_Z
            name.endsWith(".tar") -> ReadFormat.TAR
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> ReadFormat.TAR_GZIP
            name.endsWith(".tar.bz2") || name.endsWith(".tbz") || name.endsWith(".tbz2") -> ReadFormat.TAR_BZIP2
            name.endsWith(".tar.xz") || name.endsWith(".txz") -> ReadFormat.TAR_XZ
            name.endsWith(".gz") -> ReadFormat.GZIP
            name.endsWith(".bz") || name.endsWith(".bz2") -> ReadFormat.BZIP2
            name.endsWith(".xz") -> ReadFormat.XZ
            name.endsWith(".rar") -> ReadFormat.SEVEN_Z
            else -> throw IOException("不支持的压缩格式")
        }
    }

    private fun archiveExtension(file: File): String = file.extension.lowercase()

    private fun copyWithCancellation(
        input: InputStream,
        output: OutputStream,
        checkCancelled: () -> Unit
    ) = copyWithCancellation(input, checkCancelled) { buffer, count ->
        output.write(buffer, 0, count)
    }

    private fun copyWithCancellation(
        input: InputStream,
        checkCancelled: () -> Unit,
        write: (ByteArray, Int) -> Unit
    ) {
        val buffer = ByteArray(ARCHIVE_IO_BUFFER_SIZE)
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) {
                write(buffer, read)
                checkCancelled()
            }
        }
    }

    private class Counter(private val limits: ExtractionLimits) {
        var entries: Int = 0
            private set
        var bytes: Long = 0L
            private set
        var skippedEntries: Int = 0
            private set

        @Synchronized
        fun addEntry() {
            entries++
            if (entries > limits.maxEntries) throw IOException("压缩包条目数量超过安全限制")
        }

        @Synchronized
        fun addSkippedEntry() {
            skippedEntries++
        }

        @Synchronized
        fun addBytes(count: Long) {
            bytes += count
            if (bytes < 0L || bytes > limits.maxBytes) throw IOException("解压数据超过安全限制")
        }
    }

    private const val ARCHIVE_IO_BUFFER_SIZE = 64 * 1024
    private const val EXTRACT_PROGRESS_INTERVAL_MS = 100L
    private const val EXTRACT_CANCEL_WAIT_MS = 2_000L
    private const val COMPRESS_PROGRESS_INTERVAL_MS = 100L
    private const val COMPRESS_CANCEL_WAIT_MS = 2_000L
}
