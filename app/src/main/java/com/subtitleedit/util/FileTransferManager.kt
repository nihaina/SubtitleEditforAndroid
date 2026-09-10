package com.subtitleedit.util

import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 文件复制和移动的领域服务。UI 层只负责选择目标和呈现进度。
 */
object FileTransferManager {
    fun move(source: File, destination: File) {
        val sourcePath = source.canonicalFile
        val destinationPath = destination.canonicalFile
        if (source.isDirectory && destinationPath.path.startsWith(sourcePath.path + File.separator)) {
            throw IllegalArgumentException("不能将文件夹复制或移动到其自身内部")
        }
        val target = File(destination, uniqueFileName(destination, source.name))
        try {
            Files.move(source.toPath(), target.toPath())
        } catch (error: Exception) {
            if (!source.renameTo(target)) throw error
        }
    }

    suspend fun copy(
        sources: List<File>,
        destination: File,
        onProgress: (message: String, completed: Long, total: Long) -> Unit
    ) {
        var total = 0L
        sources.forEach { source ->
            coroutineContext.ensureActive()
            total = addFileSize(total, fileTreeSize(source))
        }

        var completed = 0L
        var lastProgressUpdate = 0L
        fun reportProgress(message: String, force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (force || now - lastProgressUpdate >= 100L || (total > 0L && completed >= total)) {
                lastProgressUpdate = now
                onProgress(message, completed, total)
            }
        }
        reportProgress("正在复制...", force = true)

        sources.forEach { source ->
            coroutineContext.ensureActive()
            val target = File(destination, uniqueFileName(destination, source.name))
            val temporaryTarget = temporaryCopyTarget(destination, target.name)
            try {
                copyRecursively(source, temporaryTarget) { bytesCopied ->
                    completed = addFileSize(completed, bytesCopied)
                    reportProgress("正在复制 ${source.name}...")
                }
                coroutineContext.ensureActive()
                Files.move(temporaryTarget.toPath(), target.toPath())
                reportProgress("正在复制 ${source.name}...", force = true)
            } catch (error: Throwable) {
                temporaryTarget.deleteRecursively()
                throw error
            }
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

    private fun uniqueFileName(directory: File, originalName: String): String {
        if (!File(directory, originalName).exists()) return originalName
        val separator = originalName.lastIndexOf('.')
        val base = if (separator > 0) originalName.substring(0, separator) else originalName
        val extension = if (separator > 0) originalName.substring(separator) else ""
        var index = 1
        var candidate: String
        do {
            candidate = "$base ($index)$extension"
            index++
        } while (File(directory, candidate).exists())
        return candidate
    }
}
