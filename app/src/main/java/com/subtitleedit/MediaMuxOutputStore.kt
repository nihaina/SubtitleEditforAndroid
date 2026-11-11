package com.subtitleedit

import android.content.Context
import android.net.Uri
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import com.subtitleedit.util.PickedUriAccess
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A queue entry owns a folder for as long as that entry remains in the video column. */
internal class MediaMuxOutputStore(private val context: Context) {
    private data class Binding(val directory: DocumentFile, val owned: MutableMap<String, Uri> = mutableMapOf())
    private val bindings = mutableMapOf<Pair<String, String>, Binding>()

    fun forget(videoId: String) { bindings.keys.removeAll { it.second == videoId } }

    /** Input files are finalized and verified before any user-visible output directories are created. */
    suspend fun publish(root: Uri, video: MediaMuxFile, mode: MediaMuxMode,
                        files: List<Pair<String, File>>): List<MediaMuxResult> {
        require(files.isNotEmpty() && files.map { it.first }.distinct().size == files.size)
        files.forEach { (name, file) ->
            require(safeName(name) && file.isFile && file.length() > 0L) { "输出文件无效" }
        }
        val rootDocument = requireNotNull(PickedUriAccess.directory(context, root)) { "无法读取输出目录" }
        check(rootDocument.isDirectory && rootDocument.canWrite()) { "输出目录不可写" }
        val key = root.toString() to video.id
        val prior = bindings[key]?.takeIf { it.directory.isDirectory }
        val binding = prior ?: Binding(reserveDirectory(rootDocument, video.fileName))
        val operationName = if (mode == MediaMuxMode.MERGE) "合并" else "分离"
        var operation: DocumentFile? = null
        var createdOperation = false
        try {
            val existingOperation = binding.directory.findFile(operationName)
            check(existingOperation == null || existingOperation.isDirectory) { "输出子目录与现有文件冲突" }
            val target = existingOperation ?: requireNotNull(binding.directory.createDirectory(operationName)) { "无法创建输出子目录" }
            operation = target
            createdOperation = existingOperation == null
            val results = if (PickedUriAccess.file(target.uri) != null) {
                publishLocal(binding, operationName, requireNotNull(PickedUriAccess.file(target.uri)), files)
            } else publishDocuments(binding, operationName, target, files)
            bindings[key] = binding
            return results
        } catch (error: Throwable) {
            if (createdOperation && operation?.listFiles()?.isEmpty() == true) operation.delete()
            if (prior == null && binding.directory.listFiles().isEmpty()) binding.directory.delete()
            throw error
        }
    }

    private suspend fun publishLocal(binding: Binding, operation: String, directory: File,
                                     files: List<Pair<String, File>>): List<MediaMuxResult> {
        val staged = mutableListOf<Pair<File, File>>()
        val backups = mutableListOf<Pair<File, File>>()
        val published = mutableListOf<File>()
        var committed = false
        try {
            for ((name, source) in files) {
                val target = File(directory, name)
                check(!target.exists() || (target.isFile && binding.owned["$operation/$name"] == Uri.fromFile(target))) {
                    "输出文件与其他文件冲突：$name"
                }
                val stage = File.createTempFile(".media_mux_", ".part", directory)
                staged += stage to target
                copy(source, stage)
            }
            currentCoroutineContext().ensureActive()
            // Commit/rollback must finish even when cancellation arrives between two renames.
            return withContext(NonCancellable) {
                try {
                    for ((_, target) in staged) {
                        if (target.exists()) {
                            val backup = File(directory, ".media_mux_${UUID.randomUUID()}.backup")
                            Os.rename(target.path, backup.path)
                            backups += backup to target
                        }
                    }
                    for ((stage, target) in staged) {
                        Os.rename(stage.path, target.path)
                        published += target
                    }
                    files.map { (name, _) ->
                        val uri = Uri.fromFile(File(directory, name))
                        binding.owned["$operation/$name"] = uri
                        MediaMuxResult(uri.toString(), name)
                    }.also { committed = true }
                } catch (error: Throwable) {
                    published.forEach { it.delete() }
                    backups.asReversed().forEach { (backup, target) -> Os.rename(backup.path, target.path) }
                    throw error
                } finally { if (committed) backups.forEach { it.first.delete() } }
            }
        } finally { staged.forEach { it.first.delete() } }
    }

    /** SAF providers lack an atomic replace API; stage every file and roll back the whole row on errors. */
    private suspend fun publishDocuments(binding: Binding, operationName: String, operation: DocumentFile,
                                         files: List<Pair<String, File>>): List<MediaMuxResult> {
        val stages = mutableListOf<Pair<DocumentFile, String>>()
        val backups = mutableListOf<Pair<DocumentFile, String>>()
        val published = mutableListOf<DocumentFile>()
        var committed = false
        try {
            for ((name, source) in files) {
                val existing = operation.findFile(name)
                check(existing == null || (existing.isFile && binding.owned["$operationName/$name"] == existing.uri)) {
                    "输出文件与其他文件冲突：$name"
                }
                val stage = requireNotNull(operation.createFile("application/octet-stream", ".media_mux_${UUID.randomUUID()}.part")) {
                    "无法创建暂存输出"
                }
                stages += stage to name
                requireNotNull(context.contentResolver.openOutputStream(stage.uri, "wt")) { "无法写入输出目录" }.use { output ->
                    source.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                check(stage.length() == source.length()) { "输出文件写入不完整" }
            }
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable) {
                try {
                    for ((_, name) in stages) {
                        operation.findFile(name)?.let { old ->
                            check(old.renameTo(".media_mux_${UUID.randomUUID()}.backup")) { "无法备份现有输出：$name" }
                            backups += old to name
                        }
                    }
                    for ((stage, name) in stages) {
                        check(stage.renameTo(name) && stage.name == name) { "无法发布输出：$name" }
                        published += stage
                    }
                    stages.map { (stage, name) ->
                        binding.owned["$operationName/$name"] = stage.uri
                        MediaMuxResult(stage.uri.toString(), name)
                    }.also { committed = true }
                } catch (error: Throwable) {
                    published.forEach { it.delete() }
                    backups.asReversed().forEach { (backup, name) ->
                        check(backup.renameTo(name)) { "无法恢复已有输出：$name" }
                        binding.owned["$operationName/$name"] = backup.uri
                    }
                    throw error
                } finally { if (committed) backups.forEach { it.first.delete() } }
            }
        } finally { stages.filterNot { it.first in published }.forEach { it.first.delete() } }
    }

    private suspend fun copy(source: File, destination: File) {
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
    }

    private fun reserveDirectory(root: DocumentFile, fileName: String): DocumentFile {
        require(safeName(fileName)) { "视频文件名无效" }
        val local = PickedUriAccess.file(root.uri)
        for (index in 0..100_000) {
            val name = folderName(fileName, index)
            if (local != null) {
                val directory = File(local, name)
                if (directory.mkdir()) return DocumentFile.fromFile(directory)
                check(directory.exists()) { "无法创建输出目录" }
            } else if (root.findFile(name) == null) {
                val directory = requireNotNull(root.createDirectory(name)) { "无法创建输出目录" }
                check(directory.name == name) { "输出目录名称发生冲突" }
                return directory
            }
        }
        error("输出目录重名过多")
    }

    companion object {
        fun folderName(original: String, collision: Int): String = if (collision == 0) original else "$original（$collision）"
        private fun safeName(name: String) = name.isNotBlank() && name !in setOf(".", "..") &&
            '/' !in name && '\\' !in name && '\u0000' !in name
    }
}
