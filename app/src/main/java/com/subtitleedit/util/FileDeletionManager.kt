package com.subtitleedit.util

import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 删除文件树并按已删除的文件/目录数量报告进度。
 *
 * 删除目录时先删除其子项，再删除目录本身，因此目录也会计入总项数。
 */
object FileDeletionManager {
    data class Result(
        val allDeleted: Boolean,
        val deletedSources: List<File>
    )

    suspend fun delete(
        sources: List<File>,
        onProgress: (message: String, completed: Long, total: Long) -> Unit
    ): Result {
        var total = 0L
        sources.forEach { source ->
            total += countEntries(source)
        }
        total = total.coerceAtLeast(1L)
        var completed = 0L
        var lastReportAt = 0L
        var lastMessage = "正在准备删除..."

        fun report(message: String, force: Boolean = false) {
            val now = System.nanoTime() / NANOS_PER_MILLISECOND
            if (force || now - lastReportAt >= PROGRESS_UPDATE_INTERVAL_MS || completed >= total) {
                lastReportAt = now
                lastMessage = message
                onProgress(message, completed, total)
            }
        }

        report(lastMessage, force = true)
        var allDeleted = true
        val deletedSources = mutableListOf<File>()
        sources.forEach { source ->
            coroutineContext.ensureActive()
            val sourceDeleted = deleteEntry(source) { deletedEntry ->
                completed++
                report("正在删除 ${deletedEntry.absolutePath}")
            }
            if (sourceDeleted) {
                deletedSources += source
            } else {
                allDeleted = false
            }
        }
        report(lastMessage, force = true)
        return Result(allDeleted = allDeleted, deletedSources = deletedSources)
    }

    private suspend fun countEntries(file: File): Long {
        coroutineContext.ensureActive()
        if (!file.exists() || !file.isDirectory) return 1L
        val children = file.listFiles() ?: return 1L
        return children.fold(1L) { count, child ->
            count + countEntries(child)
        }
    }

    private suspend fun deleteEntry(file: File, onDeleted: (File) -> Unit): Boolean {
        coroutineContext.ensureActive()
        var deleted = true
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children == null) {
                deleted = false
            } else {
                children.forEach { child ->
                    if (!deleteEntry(child, onDeleted)) deleted = false
                }
            }
        }
        if (file.exists()) {
            if (file.delete()) {
                onDeleted(file)
            } else {
                deleted = false
            }
        } else if (deleted) {
            // Keep progress consistent when an item disappeared between the
            // counting pass and the deletion pass.
            onDeleted(file)
        }
        return deleted
    }

    private const val PROGRESS_UPDATE_INTERVAL_MS = 100L
    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
