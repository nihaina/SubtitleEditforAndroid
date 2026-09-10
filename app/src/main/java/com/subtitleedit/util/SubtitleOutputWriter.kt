package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

object SubtitleOutputWriter {
    fun writeText(
        context: Context,
        directoryUri: Uri,
        baseName: String,
        extension: String,
        content: String,
        overwrite: Boolean = false
    ): String {
        val fileName = buildFileName(baseName, extension)

        if (directoryUri.scheme == "file") {
            val dir = File(directoryUri.path ?: throw IllegalArgumentException("输出目录无效"))
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                throw IOException("无法创建输出目录")
            }
            val outputFile = File(dir, if (overwrite) fileName else uniqueFileName(dir, fileName))
            writeFileAtomically(outputFile, content, overwrite)
            return outputFile.name
        }

        val dir = DocumentFile.fromTreeUri(context, directoryUri)
            ?: throw IllegalArgumentException("无法访问输出目录")
        val existing = dir.findFile(fileName)
        if (overwrite && existing != null) {
            writeExistingDocumentAtomically(context, dir, existing, fileName, content, extension)
            return fileName
        }

        val finalName = uniqueFileName(dir, fileName)
        val outputFile = dir.createFile(mimeTypeForExtension(extension), finalName)
            ?: throw IllegalStateException("创建文件失败")
        try {
            writeDocument(context, outputFile, content)
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        }
        return outputFile.name ?: finalName
    }

    fun exists(context: Context, directoryUri: Uri, baseName: String, extension: String): Boolean {
        return exists(context, directoryUri, buildFileName(baseName, extension))
    }

    private fun exists(context: Context, directoryUri: Uri, fileName: String): Boolean {
        if (directoryUri.scheme == "file") {
            val dir = File(directoryUri.path ?: return false)
            return File(dir, fileName).exists()
        }

        val dir = DocumentFile.fromTreeUri(context, directoryUri) ?: return false
        return dir.findFile(fileName) != null
    }

    private fun buildFileName(baseName: String, extension: String): String {
        return "$baseName.${extension.lowercase()}"
    }

    private fun uniqueFileName(dir: File, fileName: String): String {
        val nameWithoutExt = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".", "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        var candidate = fileName
        var counter = 1

        while (File(dir, candidate).exists()) {
            candidate = "$nameWithoutExt ($counter)$suffix"
            counter++
        }
        return candidate
    }

    private fun uniqueFileName(dir: DocumentFile, fileName: String): String {
        val nameWithoutExt = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".", "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        var candidate = fileName
        var counter = 1

        while (dir.findFile(candidate) != null) {
            candidate = "$nameWithoutExt ($counter)$suffix"
            counter++
        }
        return candidate
    }

    private fun writeFileAtomically(file: File, content: String, replaceExisting: Boolean) {
        val parent = file.parentFile ?: throw IOException("输出文件目录无效")
        val temporary = File(parent, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            val options = if (replaceExisting) {
                arrayOf(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } else {
                arrayOf(StandardCopyOption.ATOMIC_MOVE)
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), *options)
            } catch (_: AtomicMoveNotSupportedException) {
                if (replaceExisting) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } else {
                    Files.move(temporary.toPath(), file.toPath())
                }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun writeExistingDocumentAtomically(
        context: Context,
        dir: DocumentFile,
        existing: DocumentFile,
        fileName: String,
        content: String,
        extension: String
    ) {
        val resolver = context.contentResolver
        val backupName = ".${fileName}.${UUID.randomUUID()}.bak"
        val backup = dir.createFile(mimeTypeForExtension(extension), backupName)
            ?: throw IllegalStateException("无法创建覆盖备份")
        try {
            resolver.openInputStream(existing.uri)?.use { input ->
                resolver.openOutputStream(backup.uri, "wt")?.use { output ->
                    input.copyTo(output)
                    output.flush()
                } ?: throw IllegalStateException("无法写入覆盖备份")
            } ?: throw IllegalStateException("无法读取原文件")

            try {
                writeDocument(context, existing, content)
            } catch (writeError: Throwable) {
                runCatching {
                    resolver.openInputStream(backup.uri)?.use { input ->
                        resolver.openOutputStream(existing.uri, "wt")?.use { output ->
                            input.copyTo(output)
                            output.flush()
                        } ?: throw IllegalStateException("无法恢复原文件")
                    } ?: throw IllegalStateException("无法读取覆盖备份")
                }.onFailure(writeError::addSuppressed)
                throw writeError
            }
        } finally {
            backup.delete()
        }
    }

    private fun writeDocument(context: Context, file: DocumentFile, content: String) {
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        } ?: throw IllegalStateException("无法写入文件")
    }

    private fun mimeTypeForExtension(extension: String): String {
        return when (extension.lowercase()) {
            "srt" -> "application/x-subrip"
            "lrc" -> "application/x-lrc"
            "vtt" -> "text/vtt"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
