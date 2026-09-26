package com.subtitleedit.util

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Complete SenseVoice NPU BIN pairs exported from the app's private model storage to Downloads. */
internal object InternalModelExport {
    private val exportMutex = Mutex()

    enum class Kind(val directoryName: String) {
        SENSEVOICE_NPU_5("sensevoice-npu-5s-bin-${SenseVoiceNpuModelImporter.QNN_RUNTIME_VERSION}"),
        SENSEVOICE_NPU_10("sensevoice-npu-10s-bin-${SenseVoiceNpuModelImporter.QNN_RUNTIME_VERSION}")
    }

    fun directory(modelsRoot: File, kind: Kind): File = File(modelsRoot, kind.directoryName)

    fun completeSenseVoiceFiles(directory: File): Pair<File, File>? {
        val binary = File(directory, "model.bin")
        val tokens = File(directory, "tokens.txt")
        return if (binary.isFile && binary.length() > 0L && tokens.isFile && tokens.length() > 0L) {
            binary to tokens
        } else null
    }

    private fun sourceFiles(directory: File): List<File> {
        val (binary, tokens) = completeSenseVoiceFiles(directory)
            ?: error("SenseVoice NPU BIN 模型或 tokens 文件不完整")
        return listOf(binary, tokens)
    }

    suspend fun export(
        sourceDirectory: File,
        modelsRoot: File,
        kind: Kind,
        onProgress: suspend (copied: Long, total: Long) -> Unit
    ): File = exportMutex.withLock {
        val files = sourceFiles(sourceDirectory)
        val target = directory(modelsRoot, kind)
        require(sourceDirectory.canonicalFile != target.canonicalFile) { "导出目录与模型目录相同" }
        if (!modelsRoot.isDirectory && !modelsRoot.mkdirs()) throw IOException("无法创建下载模型目录")
        val backup = File(modelsRoot, ".${kind.directoryName}.backup")
        if (backup.exists()) {
            if (!target.exists()) {
                if (!backup.renameTo(target)) throw IOException("无法恢复上次导出的模型")
            } else if (!backup.deleteRecursively()) {
                throw IOException("无法清理旧导出备份")
            }
        }
        modelsRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(".${kind.directoryName}.exporting-") }
            .forEach { if (!it.deleteRecursively()) throw IOException("无法清理上次导出暂存文件") }
        val total = files.sumOf { it.length() }
        if (modelsRoot.usableSpace > 0L && modelsRoot.usableSpace < total) {
            throw IOException("下载目录空间不足，导出还需约 ${total / (1024 * 1024)} MB")
        }
        val staging = File(modelsRoot, ".${kind.directoryName}.exporting-${UUID.randomUUID()}")
        if (!staging.mkdir()) throw IOException("无法创建模型导出暂存目录")
        try {
            var copied = 0L
            var lastProgress = 0L
            for (source in files) {
                val destination = File(staging, source.name)
                source.inputStream().buffered().use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            val now = System.nanoTime()
                            if (now - lastProgress > 200_000_000L) {
                                onProgress(copied, total)
                                lastProgress = now
                            }
                        }
                    }
                }
                if (destination.length() != source.length()) throw IOException("${source.name} 导出不完整")
            }
            if (sourceFiles(staging).sumOf { it.length() } != total) {
                throw IOException("导出模型校验失败")
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                if (backup.exists()) {
                    if (!target.exists()) {
                        if (!backup.renameTo(target)) throw IOException("无法恢复上次导出的模型")
                    } else if (!backup.deleteRecursively()) {
                        throw IOException("无法清理旧导出备份")
                    }
                }
                if (target.exists() && !target.renameTo(backup)) throw IOException("无法备份原有导出模型")
                try {
                    if (!staging.renameTo(target)) throw IOException("无法完成模型导出")
                } catch (error: Exception) {
                    if (backup.exists() && !backup.renameTo(target)) {
                        error.addSuppressed(IOException("无法恢复原有导出模型"))
                    }
                    throw error
                }
                backup.deleteRecursively()
            }
            onProgress(total, total)
            target
        } finally {
            withContext(NonCancellable) { staging.deleteRecursively() }
        }
    }
}
