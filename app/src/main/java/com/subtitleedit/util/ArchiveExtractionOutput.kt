package com.subtitleedit.util

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Owns only the current output; files are opened at their final, resolved path. */
internal class ArchiveExtractionOutput(
    private val limits: ArchiveManager.ExtractionLimits,
    private val resolve: (Entry) -> File?,
    private val protectSourceVolumes: (Array<String>) -> Unit,
    private val consumeOverwriteApproval: (File) -> Boolean,
    private val verifyParent: (File) -> Unit,
    private val checkCancelled: () -> Unit,
    private val reportProgress: (Long, Long) -> Unit,
    private val reportCurrentFile: (File) -> Unit
) : SevenZipExtractCallback, Closeable {
    data class Entry(
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
        val modifiedTimeMillis: Long
    )

    private data class ActiveOutput(
        val entry: Entry,
        val file: File,
        val backup: File?,
        var resource: Closeable? = null,
        var created: Boolean = false
    )

    private var active: ActiveOutput? = null
    private var entries = 0
    private var skipped = 0
    private var declaredBytes = 0L
    private var writtenBytes = 0L

    override fun onArchiveOpened(volumePaths: Array<String>) = protectSourceVolumes(volumePaths)

    private fun prepareEntry(entry: Entry, link: Boolean): ActiveOutput? {
        checkCancelled()
        check(active == null) { "上一解压文件尚未关闭" }
        if (link) throw IOException("压缩包包含不支持的链接：${entry.name}")
        if (++entries > limits.maxEntries) throw IOException("压缩包条目数量超过安全限制")
        if (!entry.isDirectory) {
            val bytes = entry.size.coerceAtLeast(0L)
            if (bytes > limits.maxBytes - declaredBytes) throw IOException("解压数据超过安全限制")
            declaredBytes += bytes
        }
        val target = resolve(entry)
        checkCancelled()
        if (target == null) {
            skipped++
            return null
        }
        if (entry.isDirectory) {
            if (entry.modifiedTimeMillis > 0L) target.setLastModified(entry.modifiedTimeMillis)
            return null
        }
        verifyParent(target)
        // Preserve an explicitly overwritten target until this entry has passed its CRC check.
        // The new payload still goes straight to its final path; only an existing target is renamed.
        val backup = if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!consumeOverwriteApproval(target)) throw IOException("解压目标在写入前已变化：${entry.name}")
            File(target.parentFile, ".subtitleedit-overwrite-${UUID.randomUUID()}").also {
                Files.move(target.toPath(), it.toPath())
            }
        } else null
        return ActiveOutput(entry, target, backup).also { active = it }
    }

    override fun openEntry(
        name: String,
        size: Long,
        isDirectory: Boolean,
        isSymbolicLink: Boolean,
        modifiedTimeMillis: Long
    ): Int {
        val output = prepareEntry(Entry(name, size, isDirectory, modifiedTimeMillis), isSymbolicLink) ?: return -1
        // CREATE_NEW prevents following a link or overwriting a file created after resolution.
        val raw = Os.open(
            output.file.absolutePath,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,
            0x1b6 // 0666, subject to the process umask
        )
        output.created = true
        val descriptor = try {
            ParcelFileDescriptor.dup(raw)
        } finally {
            Os.close(raw)
        }
        output.resource = descriptor
        reportCurrentFile(output.file)
        return descriptor.fd
    }

    fun openTarEntry(entry: StreamingTarExtractor.Entry): OutputStream? {
        val output = prepareEntry(Entry(entry.name, entry.size, entry.isDirectory, entry.modifiedTimeMillis), false)
            ?: return null
        return Files.newOutputStream(
            output.file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS
        ).also {
            output.resource = it
            output.created = true
            reportCurrentFile(output.file)
        }
    }

    override fun onEntryComplete(success: Boolean) {
        val output = active ?: return
        active = null
        var committed = false
        try {
            output.resource?.close()
            if (!success) {
                restoreFailedOutput(output)
                return
            }
            checkCancelled()
            val size = output.file.length()
            if (size > limits.maxBytes - writtenBytes) throw IOException("解压数据超过安全限制")
            if (output.entry.modifiedTimeMillis > 0L) output.file.setLastModified(output.entry.modifiedTimeMillis)
            committed = true
            if (output.backup != null && !ArchiveManager.deleteTreeNoFollow(output.backup)) {
                throw IOException("无法清理解压覆盖备份：${output.backup.absolutePath}")
            }
            writtenBytes += size
        } catch (failure: Throwable) {
            if (!committed) runCatching { restoreFailedOutput(output) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun restoreFailedOutput(output: ActiveOutput) {
        // If opening failed because another process created the path, that path belongs to it.
        if (output.created) {
            Files.deleteIfExists(output.file.toPath())
            output.created = false
        }
        output.backup?.let { backup ->
            if (Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.move(backup.toPath(), output.file.toPath())
                } catch (error: IOException) {
                    throw IOException("未能恢复覆盖前的文件，备份保留在：${backup.absolutePath}", error)
                }
            }
        }
    }

    override fun onProgress(completed: Long, total: Long) {
        checkCancelled()
        if (completed > limits.maxBytes) throw IOException("解压数据超过安全限制")
        reportProgress(completed, total)
    }

    fun result() = ArchiveManager.ExtractResult(entries - skipped, writtenBytes, skipped)

    override fun close() = onEntryComplete(false)
}
