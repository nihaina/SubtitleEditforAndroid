package com.subtitleedit.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import com.subtitleedit.util.ArchiveManager.ConflictPolicy
import com.subtitleedit.util.ArchiveManager.ConflictResolution
import com.subtitleedit.util.ArchiveManager.DestinationConflict
import com.subtitleedit.util.ArchiveManager.DestinationConflictException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 文件复制和移动的领域服务。UI 层只负责选择目标和呈现进度。
 */
object FileTransferManager {
    fun move(source: File, destination: File) {
        validateMove(listOf(source), destination)
        val target = File(destination, uniqueFileName(destination, source.name, source.isDirectory))
        try {
            Files.move(source.toPath(), target.toPath())
        } catch (error: Exception) {
            if (!source.renameTo(target)) throw error
        }
    }

    fun validateMove(sources: List<File>, destination: File) {
        val directory = destination.canonicalFile
        sources.forEach { source ->
            require(source.absoluteFile.parentFile?.canonicalFile != directory) { "不能将文件移动到源文件所在目录" }
            validateDestination(source, directory)
        }
    }

    suspend fun copy(
        sources: List<File>,
        destination: File,
        onConflict: suspend (DestinationConflict) -> ConflictResolution = { throw DestinationConflictException(it) },
        onProgress: (message: String, completed: Long, total: Long) -> Unit
    ) {
        // Validate the entire selection before creating any temporary copies.
        sources.forEach { validateDestination(it, destination) }
        var total = 0L
        sources.forEach { source ->
            coroutineContext.ensureActive()
            total = addFileSize(total, fileTreeSize(source))
        }

        var completed = 0L
        var lastProgressUpdate = 0L
        fun reportProgress(message: String, force: Boolean = false) {
            val now = System.nanoTime() / 1_000_000L
            if (force || now - lastProgressUpdate >= 100L || (total > 0L && completed >= total)) {
                lastProgressUpdate = now
                onProgress(message, completed, total)
            }
        }
        reportProgress("正在复制...", force = true)

        val targets = CopyTargets(destination, sources, onConflict)
        sources.forEach { source ->
            coroutineContext.ensureActive()
            copyEntry(source, File(destination, source.name), targets) { bytesCopied ->
                completed = addFileSize(completed, bytesCopied)
                reportProgress("正在复制 ${source.name}...")
            }
            reportProgress("正在复制 ${source.name}...", force = true)
        }
    }

    private data class CopyTarget(val file: File, val overwrite: Boolean = false)

    private class CopyTargets(
        private val destination: File,
        sources: List<File>,
        private val onConflict: suspend (DestinationConflict) -> ConflictResolution
    ) {
        private val sourcePaths = sources.map { it.canonicalFile.toPath() }
        private var allConflicts: ConflictPolicy? = null

        suspend fun resolve(source: File, target: File): CopyTarget? {
            if (!exists(target)) return CopyTarget(target)
            val conflict = DestinationConflict(
                entryName = target.relativeTo(destination).invariantSeparatorsPath,
                sourceSize = if (source.isFile) source.length() else -1L,
                sourceModifiedTimeMillis = source.lastModified(),
                sourceIsDirectory = source.isDirectory,
                existingSize = if (target.isFile) target.length() else -1L,
                existingModifiedTimeMillis = target.lastModified(),
                existingIsDirectory = target.isDirectory
            )
            val policy = allConflicts ?: onConflict(conflict).also {
                if (it.applyToAll) allConflicts = it.policy
            }.policy
            coroutineContext.ensureActive()
            return when (policy) {
                ConflictPolicy.FAIL -> throw DestinationConflictException(conflict)
                ConflictPolicy.SKIP -> null
                ConflictPolicy.RENAME -> {
                    val parent = checkNotNull(target.parentFile)
                    CopyTarget(File(parent, uniqueFileName(parent, target.name, source.isDirectory)))
                }
                ConflictPolicy.OVERWRITE -> {
                    val targetPath = target.canonicalFile.toPath()
                    require(sourcePaths.none { it.startsWith(targetPath) || targetPath.startsWith(it) }) {
                        "不能覆盖源文件或源文件所在目录"
                    }
                    CopyTarget(target, overwrite = true)
                }
            }
        }
    }

    private fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private suspend fun copyEntry(source: File, requested: File, targets: CopyTargets, onBytesCopied: (Long) -> Unit) {
        var target = targets.resolve(source, requested) ?: run {
            onBytesCopied(fileTreeSize(source))
            return
        }
        if (target.overwrite && source.isDirectory && Files.isDirectory(target.file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            mergeDirectory(source, target.file, targets, onBytesCopied)
            return
        }
        val temporaryTarget = temporaryCopyTarget(checkNotNull(target.file.parentFile), target.file.name)
        try {
            copyRecursively(source, temporaryTarget, onBytesCopied)
            while (true) {
                coroutineContext.ensureActive()
                if (!target.overwrite && exists(target.file)) {
                    // A file can appear while a large source is being copied.
                    target = targets.resolve(source, target.file) ?: return
                }
                if (target.overwrite && source.isDirectory && Files.isDirectory(target.file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    mergeDirectory(source, target.file, targets) { }
                    return
                }
                try {
                    publishCopy(temporaryTarget, target)
                    return
                } catch (error: FileAlreadyExistsException) {
                    if (target.overwrite) throw error
                    target = targets.resolve(source, target.file) ?: return
                }
            }
        } finally {
            temporaryTarget.deleteRecursively()
        }
    }

    private suspend fun mergeDirectory(source: File, target: File, targets: CopyTargets, onBytesCopied: (Long) -> Unit) {
        val children = source.listFiles() ?: throw IllegalStateException("无法读取目录：${source.name}")
        children.forEach { child ->
            coroutineContext.ensureActive()
            copyEntry(child, File(target, child.name), targets, onBytesCopied)
        }
    }

    private fun publishCopy(temporary: File, target: CopyTarget) {
        if (!target.overwrite) {
            Files.move(temporary.toPath(), target.file.toPath())
        } else if (!Files.isDirectory(target.file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.move(temporary.toPath(), target.file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            // Replacing a directory with a file needs a rollback path. Keep the
            // original until the complete staged copy has been published.
            val backup = temporaryCopyTarget(checkNotNull(target.file.parentFile), target.file.name)
            Files.move(target.file.toPath(), backup.toPath())
            try {
                Files.move(temporary.toPath(), target.file.toPath())
            } catch (error: Throwable) {
                try {
                    Files.move(backup.toPath(), target.file.toPath())
                } catch (restoreError: Throwable) {
                    error.addSuppressed(restoreError)
                }
                throw error
            }
            check(backup.deleteRecursively()) { "无法清理覆盖备份：${backup.name}" }
        }
    }

    private fun validateDestination(source: File, destination: File) {
        if (!source.isDirectory) return
        val sourcePath = source.canonicalFile.toPath()
        val destinationPath = destination.canonicalFile.toPath()
        require(!destinationPath.startsWith(sourcePath)) {
            "不能将文件夹复制或移动到其自身内部"
        }
    }

    private suspend fun copyRecursively(
        source: File,
        target: File,
        onBytesCopied: (Long) -> Unit
    ) {
        coroutineContext.ensureActive()
        if (source.isDirectory) {
            if (!target.mkdirs() && !target.isDirectory) {
                throw IllegalStateException("无法创建目录：${target.name}")
            }
            val children = source.listFiles()
                ?: throw IllegalStateException("无法读取目录：${source.name}")
            children.forEach { child ->
                copyRecursively(child, File(target, child.name), onBytesCopied)
            }
            return
        }

        target.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IllegalStateException("无法创建目录：${parent.name}")
            }
        }
        val buffer = ByteArray(1024 * 1024)
        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    onBytesCopied(count.toLong())
                }
            }
        }
    }

    private suspend fun fileTreeSize(file: File): Long {
        coroutineContext.ensureActive()
        if (file.isFile) return file.length().coerceAtLeast(0L)
        if (!file.isDirectory) return 0L
        val children = file.listFiles() ?: return 0L
        var total = 0L
        children.forEach { child ->
            total = addFileSize(total, fileTreeSize(child))
        }
        return total
    }

    private fun addFileSize(current: Long, amount: Long): Long =
        if (amount > 0L && current > Long.MAX_VALUE - amount) Long.MAX_VALUE else current + amount

    private fun temporaryCopyTarget(destination: File, targetName: String): File {
        var index = 0
        var candidate: File
        do {
            val suffix = if (index == 0) "" else "-$index"
            candidate = File(destination, ".${targetName}.copying-${System.nanoTime()}$suffix")
            index++
        } while (candidate.exists())
        return candidate
    }

    private fun uniqueFileName(directory: File, originalName: String, isDirectory: Boolean = false): String {
        if (!exists(File(directory, originalName))) return originalName
        val separator = if (isDirectory) -1 else originalName.lastIndexOf('.')
        val base = if (separator > 0) originalName.substring(0, separator) else originalName
        val extension = if (separator > 0) originalName.substring(separator) else ""
        var index = 1
        var candidate: String
        do {
            candidate = "$base ($index)$extension"
            index++
        } while (exists(File(directory, candidate)))
        return candidate
    }
}
