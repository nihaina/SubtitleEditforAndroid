package com.subtitleedit.util

import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Copies and validates the graph/weights together before replacing a managed model directory. */
internal class Qwen3ForcedAlignerImporter(private val directory: File) {
    data class Source(val name: String, val size: Long?, val open: () -> InputStream)
    data class Progress(val message: String, val copied: Long = 0L, val total: Long = -1L)

    suspend fun install(
        sources: List<Source>,
        validate: (File) -> Unit,
        publish: (File) -> Unit,
        onProgress: suspend (Progress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val graphName = Qwen3ForcedAlignerModelFiles.graphName(sources.map { it.name })
            val parent = requireNotNull(directory.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "无法创建 ForcedAligner 模型目录" }
            val staging = File(parent, ".${directory.name}_importing_${UUID.randomUUID()}")
            val backup = File(parent, ".${directory.name}_backup")
            // Recover a previous interrupted directory swap before starting a new copy.
            if (backup.exists()) {
                if (!directory.exists()) {
                    check(backup.renameTo(directory)) { "无法恢复原 ForcedAligner 模型" }
                } else {
                    check(backup.deleteRecursively()) { "无法清理上次导入的模型备份" }
                }
            }
            check(staging.mkdir()) { "无法创建 ForcedAligner 暂存目录" }
            try {
                val total = if (sources.all { it.size != null && it.size > 0L }) {
                    sources.sumOf { requireNotNull(it.size) }
                } else -1L
                if (total > 0L && parent.usableSpace > 0L) {
                    check(parent.usableSpace >= total) { "存储空间不足，导入时需保留旧模型并复制两个新文件" }
                }
                var copied = 0L
                var lastUpdate = 0L
                for (source in sources) {
                    currentCoroutineContext().ensureActive()
                    onProgress(Progress("正在复制 ${source.name}", copied, total))
                    val target = File(staging, source.name)
                    source.open().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                output.write(buffer, 0, count)
                                copied += count
                                val now = System.nanoTime()
                                if (now - lastUpdate >= 200_000_000L) {
                                    onProgress(Progress("正在复制 ${source.name}", copied, total))
                                    lastUpdate = now
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    check(target.length() > 0L) { "${source.name} 文件为空" }
                    if (source.size != null && source.size >= 0L) {
                        check(target.length() == source.size) { "${source.name} 复制不完整，请重新导入" }
                    }
                }
                currentCoroutineContext().ensureActive()
                onProgress(Progress("正在校验模型和外部权重", copied, total))
                validate(File(staging, graphName))
                currentCoroutineContext().ensureActive()
                // The swap and preference update are one non-cancellable commit phase.
                withContext(NonCancellable) {
                    if (directory.exists()) {
                        check(directory.renameTo(backup)) { "无法备份现有 ForcedAligner 模型" }
                    }
                    var installed = false
                    try {
                        check(staging.renameTo(directory)) { "无法安装 ForcedAligner 模型" }
                        installed = true
                        publish(File(directory, graphName))
                    } catch (error: Exception) {
                        if (installed && !directory.deleteRecursively()) {
                            error.addSuppressed(IllegalStateException("无法清理失败的模型，旧模型保留在 $backup"))
                        }
                        if (backup.exists() && !backup.renameTo(directory)) {
                            error.addSuppressed(IllegalStateException("无法恢复旧模型，备份保留在 $backup"))
                        }
                        throw error
                    }
                    backup.deleteRecursively()
                }
                File(directory, graphName)
            } finally {
                withContext(NonCancellable) { staging.deleteRecursively() }
            }
        }
    }

    companion object {
        // Also prevents overlapping imports across activity recreation.
        private val mutex = Mutex()
    }
}
