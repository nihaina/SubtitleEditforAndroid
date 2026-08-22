package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.nio.charset.StandardCharsets

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
            if (!dir.exists()) dir.mkdirs()
            val outputFile = if (overwrite) {
                File(dir, fileName).apply { if (exists()) delete() }
            } else {
                File(dir, uniqueFileName(dir, fileName))
            }
            outputFile.writeText(content, StandardCharsets.UTF_8)
            return outputFile.name
        }

        val dir = DocumentFile.fromTreeUri(context, directoryUri)
            ?: throw IllegalArgumentException("无法访问输出目录")
        val finalName = if (overwrite) {
            deleteIfExists(dir, fileName)
            fileName
        } else {
            uniqueFileName(dir, fileName)
        }
        val outputFile = dir.createFile(mimeTypeForExtension(extension), finalName)
            ?: throw IllegalStateException("创建文件失败")
        val normalizedFile = normalizeCreatedName(dir, outputFile, finalName)

        context.contentResolver.openOutputStream(normalizedFile.uri, "wt")?.use { output ->
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        } ?: throw IllegalStateException("无法写入文件")

        return finalName
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

    private fun deleteIfExists(dir: DocumentFile, fileName: String) {
        dir.findFile(fileName)?.delete()
    }

    private fun normalizeCreatedName(
        dir: DocumentFile,
        createdFile: DocumentFile,
        expectedName: String
    ): DocumentFile {
        if (createdFile.name == expectedName) return createdFile

        dir.findFile(expectedName)?.delete()
        if (createdFile.renameTo(expectedName)) {
            return dir.findFile(expectedName) ?: createdFile
        }

        throw IllegalStateException("创建的文件名为 ${createdFile.name}，无法修正为 $expectedName")
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
