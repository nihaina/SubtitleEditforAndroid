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

    enum class ProgressPhase { SCANNING, EXTRACTING, TESTING }

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
        limits: ExtractionLimits = ExtractionLimits(),
        checkCancelled: () -> Unit = {},
        onProgress: (completed: Long, total: Long) -> Unit = { _, _ -> }
    ): TestResult {
        checkCancelled()
        val counter = Counter(limits)
        val callback = object : SevenZipTestCallback {
            override fun onEntry(name: String, size: Long, isDirectory: Boolean, isSymbolicLink: Boolean) {
                checkCancelled()
                if (isSymbolicLink) throw IOException("压缩包包含不支持的链接：$name")
                validateEntryName(name)
                counter.addEntry()
                if (!isDirectory) counter.addBytes(size.coerceAtLeast(0L))
            }

            override fun onProgress(completed: Long, total: Long) {
                checkCancelled()
                onProgress(completed, total)
            }
        }
        val format = readFormat(archive)
        if (format !in setOf(ReadFormat.TAR, ReadFormat.TAR_GZIP, ReadFormat.TAR_BZIP2, ReadFormat.TAR_XZ)) {
            return OfficialSevenZip.test(archive.absolutePath, password, limits, callback).also { checkCancelled() }
        }
        val readStream: (InputStream, Long) -> Unit = { input, expectedBytes ->
            StreamingTarExtractor.extract(
                input = input,
                staging = archive.absoluteFile.parentFile ?: throw IOException("找不到压缩包所在目录"),
                expectedTarBytes = expectedBytes,
                maxEntries = limits.maxEntries,
                maxBytes = limits.maxBytes,
                validateExpectedSize = format != ReadFormat.TAR_GZIP,
                checkCancelled = checkCancelled,
                openOutput = { entry ->
                    callback.onEntry(entry.name, entry.size, entry.isDirectory, false)
                    null
                },
                onProgress = callback::onProgress
            )
            Unit
        }
        if (format == ReadFormat.TAR) {
            FileInputStream(archive).buffered().use { readStream(it, archive.length()) }
        } else {
            OfficialSevenZipArchive.withCompressedTarStream(archive, password, checkCancelled, readStream)
        }
        checkCancelled()
        return TestResult(counter.entries, counter.bytes)
    }

    fun extractArchive(
        archive: File,
        destination: File,
        password: CharArray? = null,
        conflictPolicy: ConflictPolicy = ConflictPolicy.FAIL,
        conflictPolicies: Map<String, ConflictPolicy> = emptyMap(),
        limits: ExtractionLimits = ExtractionLimits(),
        onProgress: ((ProgressPhase, Long, Long) -> Unit)? = null,
        onConflict: ((DestinationConflict) -> ConflictResolution)? = null,
        onCurrentFile: (File) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ): ExtractResult {
        checkCancelled()
        require(
            existsNoFollow(destination) &&
                Files.isDirectory(destination.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) { "目标目录不存在或不可用" }
        val resolver = ExtractionTargetResolver(destination, conflictPolicy, conflictPolicies, onConflict, archive)
        var lastProgressNanos = 0L
        var completedBytes = 0L
        var totalBytes = 0L
        var currentFile: File? = null
        var reportedFile: File? = null
        fun reportProgress(completed: Long, total: Long, force: Boolean = false) {
            checkCancelled()
            completedBytes = maxOf(completedBytes, completed.coerceAtLeast(0L))
            totalBytes = total.coerceAtLeast(0L)
            val now = System.nanoTime()
            if (force || lastProgressNanos == 0L || now - lastProgressNanos >= EXTRACT_PROGRESS_INTERVAL_NANOS) {
                currentFile?.takeIf { it != reportedFile }?.let { file ->
                    onCurrentFile(file)
                    reportedFile = file
                }
                onProgress?.invoke(ProgressPhase.EXTRACTING, completedBytes, totalBytes)
                lastProgressNanos = now
            }
        }
        reportProgress(0L, 0L, force = true)
        try {
            ArchiveExtractionOutput(
                limits = limits,
                resolve = { entry ->
                    resolver.resolve(entry.name, entry.isDirectory, entry.size, entry.modifiedTimeMillis)
                },
                protectSourceVolumes = resolver::protectSourceVolumes,
                consumeOverwriteApproval = resolver::consumeOverwriteApproval,
                verifyParent = resolver::verifyTargetParent,
                checkCancelled = checkCancelled,
                reportProgress = { completed, total -> reportProgress(completed, total) },
                reportCurrentFile = { file ->
                    currentFile = file
                    reportProgress(completedBytes, totalBytes)
                }
            ).use { output ->
                val format = readFormat(archive)
                if (format in setOf(ReadFormat.TAR, ReadFormat.TAR_GZIP, ReadFormat.TAR_BZIP2, ReadFormat.TAR_XZ)) {
                    val readStream: (InputStream, Long) -> Unit = { input, expectedBytes ->
                        StreamingTarExtractor.extract(
                            input = input,
                            staging = destination,
                            expectedTarBytes = expectedBytes,
                            maxEntries = limits.maxEntries,
                            maxBytes = limits.maxBytes,
                            validateExpectedSize = format != ReadFormat.TAR_GZIP,
                            checkCancelled = checkCancelled,
                            openOutput = output::openTarEntry,
                            onEntryComplete = { _, success -> output.onEntryComplete(success) },
                            onProgress = { completed, total -> reportProgress(completed, total) }
                        )
                        Unit
                    }
                    if (format == ReadFormat.TAR) {
                        FileInputStream(archive).buffered().use { readStream(it, archive.length()) }
                    } else {
                        OfficialSevenZipArchive.withCompressedTarStream(archive, password, checkCancelled, readStream)
                    }
                } else {
                    OfficialSevenZip.extract(archive.absolutePath, password, output)
                }
                checkCancelled()
                reportProgress(completedBytes, totalBytes, force = true)
                resolver.commitDirectoryReplacements()
                return output.result()
            }
        } finally {
            resolver.restoreDirectoryReplacements()
        }
    }

    private class ExtractionTargetResolver(
        private val destination: File,
        private val policy: ConflictPolicy,
        private val entryPolicies: Map<String, ConflictPolicy>,
        private val onConflict: ((DestinationConflict) -> ConflictResolution)?,
        sourceArchive: File
    ) {
        private val directoryTargets = mutableMapOf<String, File?>()
        private val directoryOwners = mutableMapOf<String, String>()
        private val streamingPolicies = mutableMapOf<String, ConflictPolicy>()
        private val overwriteTargets = mutableSetOf<File>()
        private val directoryReplacements = mutableListOf<Pair<File, File>>()
        private val sourceVolumes = mutableSetOf(sourceArchive.canonicalFile)
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
                    verifyTargetParent(target)
                    verifyNotSourceArchive(target, relativeName)
                    invalidateDirectoryTargets(target)
                    val backup = File(target.parentFile, ".subtitleedit-overwrite-${UUID.randomUUID()}")
                    Files.move(target.toPath(), backup.toPath())
                    directoryReplacements += target to backup
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
                    verifyTargetParent(target)
                    verifyNotSourceArchive(target, relativeName)
                    invalidateDirectoryTargets(target)
                    overwriteTargets.add(target.absoluteFile)
                    target
                }
            }
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

        fun consumeOverwriteApproval(target: File): Boolean = overwriteTargets.remove(target.absoluteFile)

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
                return target
            }
            throw IOException("无法创建目录：$relativeName")
        }

        private fun verifyNotSourceArchive(target: File, relativeName: String) {
            val output = target.canonicalFile
            if (sourceVolumes.any { it.toPath().startsWith(output.toPath()) }) {
                throw IOException("不能覆盖正在读取的压缩包：$relativeName")
            }
        }

        fun protectSourceVolumes(paths: Array<String>) {
            paths.forEach { sourceVolumes += File(it).canonicalFile }
        }

        fun commitDirectoryReplacements() {
            directoryReplacements.forEach { (_, backup) ->
                if (!deleteTreeNoFollow(backup)) throw IOException("无法清理解压覆盖备份：${backup.absolutePath}")
            }
            directoryReplacements.clear()
        }

        fun restoreDirectoryReplacements() {
            directoryReplacements.asReversed().forEach { (target, backup) ->
                if (existsNoFollow(backup)) {
                    verifyTargetParent(target)
                    if (!deleteTreeNoFollow(target)) throw IOException("无法清理解压失败的目录：${target.absolutePath}")
                    try {
                        Files.move(backup.toPath(), target.toPath())
                    } catch (error: IOException) {
                        throw IOException("未能恢复覆盖前的文件，备份保留在：${backup.absolutePath}", error)
                    }
                }
            }
            directoryReplacements.clear()
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

        private fun pathIdentity(file: File): String = runCatching {
            file.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS).toString()
        }.getOrElse {
            file.absoluteFile.normalize().path
        }
    }

    internal fun deleteTreeNoFollow(file: File): Boolean {
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
    private const val EXTRACT_PROGRESS_INTERVAL_NANOS = 100_000_000L
    private const val COMPRESS_PROGRESS_INTERVAL_MS = 100L
    private const val COMPRESS_CANCEL_WAIT_MS = 2_000L
}
