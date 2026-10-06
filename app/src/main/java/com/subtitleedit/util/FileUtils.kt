package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

// 延迟初始化，避免循环依赖
private var settingsManagerInstance: SettingsManager? = null

private fun getSettingsManager(context: Context): SettingsManager {
    if (settingsManagerInstance == null) {
        settingsManagerInstance = SettingsManager.getInstance(context)
    }
    return settingsManagerInstance!!
}

/**
 * 文件工具类
 */
object FileUtils {
    
    // 常见的字幕文件扩展名
    val SUBTITLE_EXTENSIONS = setOf("srt", "lrc", "ass", "ssa", "sub", "txt", "vtt")
    
    // 音频文件扩展名
    val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "ape")
    
    // 常见的编码方式 - 使用带显示名称的数据类
    data class EncodingInfo(
        val charset: Charset,
        val displayName: String,
        val isAuto: Boolean = false
    ) {
        /** Stable value used by settings state and choice dialogs. */
        val id: String
            get() = if (isAuto) SettingsManager.AUTO_ENCODING else charset.name()
    }
    
    val SUPPORTED_ENCODINGS = listOf(
        EncodingInfo(StandardCharsets.UTF_8, "自动", isAuto = true),
        EncodingInfo(StandardCharsets.UTF_8, "UTF-8"),
        EncodingInfo(StandardCharsets.UTF_16, "UTF-16"),
        EncodingInfo(StandardCharsets.ISO_8859_1, "ISO-8859-1"),
        EncodingInfo(Charset.forName("GBK"), "GBK"),
        EncodingInfo(Charset.forName("GB2312"), "GB2312"),
        EncodingInfo(Charset.forName("BIG5"), "BIG5 (繁体中文)"),
        EncodingInfo(Charset.forName("Shift_JIS"), "Shift_JIS (日文)"),
        EncodingInfo(Charset.forName("EUC-JP"), "EUC-JP (日文)"),
        EncodingInfo(Charset.forName("EUC-KR"), "EUC-KR (韩文)"),
        EncodingInfo(Charset.forName("windows-1252"), "Windows-1252 (西欧)")
    )
    
    /**
     * 检测文件编码
     * 使用简单启发式方法检测常见编码
     */
    fun detectEncoding(file: File, context: Context? = null): Charset {
        // A manually selected encoding always wins. Automatic mode is represented by a
        // separate setting and falls through to byte-level detection below.
        if (context != null) {
            val settingsManager = getSettingsManager(context)
            if (!settingsManager.isDefaultEncodingAutomatic()) {
                return settingsManager.getDefaultEncoding()
            }
        }
        return runCatching {
            FileInputStream(file).use { detectEncoding(it) }
        }.getOrDefault(StandardCharsets.UTF_8)
    }

    /** Detect the encoding of a content URI without requiring a temporary local file. */
    fun detectEncoding(context: Context, uri: Uri): Charset = runCatching {
        context.contentResolver.openInputStream(uri)?.use { detectEncoding(it) }
            ?: StandardCharsets.UTF_8
    }.getOrDefault(StandardCharsets.UTF_8)

    private fun detectEncoding(input: InputStream): Charset {
        val bytes = input.readSample()
        return detectEncoding(bytes)
    }

    private fun detectEncoding(bytes: ByteArray): Charset {
        if (bytes.hasPrefix(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))) {
            return StandardCharsets.UTF_8
        }
        if (bytes.hasPrefix(byteArrayOf(0xFE.toByte(), 0xFF.toByte()))) {
            return StandardCharsets.UTF_16BE
        }
        if (bytes.hasPrefix(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))) {
            return StandardCharsets.UTF_16LE
        }

        // UTF-16 files without a BOM still have a strong zero-byte pattern for ordinary
        // Latin and CJK text. Check this before UTF-8 because NUL bytes are legal UTF-8.
        if (bytes.size >= 4 && bytes.size % 2 == 0) {
            val evenZeroes = bytes.indices.step(2).count { bytes[it].toInt() == 0 }
            val oddZeroes = (1 until bytes.size step 2).count { bytes[it].toInt() == 0 }
            val threshold = bytes.size / 4
            if (oddZeroes >= threshold && oddZeroes > evenZeroes) return StandardCharsets.UTF_16LE
            if (evenZeroes >= threshold && evenZeroes > oddZeroes) return StandardCharsets.UTF_16BE
        }

        // UTF-8 is preferred for valid Unicode text. For legacy files, strict decoding
        // rejects malformed byte sequences before trying the common regional encodings.
        if (canDecode(bytes, StandardCharsets.UTF_8)) return StandardCharsets.UTF_8
        return listOf(
            "GBK",
            "GB2312",
            "BIG5",
            "Shift_JIS",
            "EUC-JP",
            "EUC-KR",
            "windows-1252",
            "ISO-8859-1"
        )
            .asSequence()
            .map(Charset::forName)
            .firstOrNull { canDecode(bytes, it) }
            ?: StandardCharsets.UTF_8
    }

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun canDecode(bytes: ByteArray, charset: Charset): Boolean = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
        true
    }.getOrDefault(false)

    private fun InputStream.readSample(maxBytes: Int = 64 * 1024): ByteArray {
        val sample = ByteArray(maxBytes)
        var offset = 0
        while (offset < sample.size) {
            val count = read(sample, offset, sample.size - offset)
            if (count <= 0) break
            offset += count
        }
        return if (offset == sample.size) sample else sample.copyOf(offset)
    }
    
    /**
     * 读取文件内容
     */
    fun readFile(file: File, charset: Charset? = null, context: Context? = null): String {
        val encoding = charset ?: detectEncoding(file, context)
        return FileInputStream(file).use { fis ->
            fis.bufferedReader(encoding).readText()
        }
    }
    
    /**
     * 读取 URI 内容
     */
    fun readUri(context: Context, uri: Uri, charset: Charset? = null): String {
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            if (charset != null) {
                inputStream.bufferedReader(charset).readText()
            } else {
                // A null charset means automatic detection. Decode the same bytes that were
                // inspected so content providers with one-shot streams are handled safely.
                val bytes = inputStream.readBytes()
                String(bytes, detectEncoding(bytes))
            }
        } ?: ""
    }
    
    /**
     * 写入文件
     */
    fun writeFile(file: File, content: String, charset: Charset = StandardCharsets.UTF_8) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("无法创建文件目录")
        }
        val temporary = File(
            parent ?: throw IOException("文件目录无效"),
            ".${file.name}.${UUID.randomUUID()}.tmp"
        )
        try {
            FileOutputStream(temporary).use { fos ->
                fos.write(content.toByteArray(charset))
                fos.flush()
                fos.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
    
    /**
     * 检查是否为字幕文件
     */
    fun isSubtitleFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        return extension in SUBTITLE_EXTENSIONS
    }
    
    /**
     * 检查是否为音频文件
     */
    fun isAudioFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        return extension in AUDIO_EXTENSIONS
    }
    
    /**
     * 获取音频文件对应的可能字幕文件名
     */
    fun getPossibleSubtitleFiles(audioFile: File): List<File> {
        val directory = audioFile.parentFile ?: return emptyList()
        val baseName = audioFile.nameWithoutExtension
        
        // 可能的字幕扩展名
        val subtitleExts = listOf("srt", "lrc", "ass", "ssa", "sub", "vtt", "txt")
        
        // 查找同名字幕文件
        val possibleFiles = mutableListOf<File>()
        for (ext in subtitleExts) {
            val subtitleFile = File(directory, "$baseName.$ext")
            if (subtitleFile.exists()) {
                possibleFiles.add(subtitleFile)
            }
        }
        
        return possibleFiles
    }
    
    /**
     * 获取文件扩展名
     */
    fun getExtension(file: File): String {
        return file.extension.lowercase()
    }
    
    /**
     * 获取不带扩展的文件名
     */
    fun getFileNameWithoutExtension(file: File): String {
        return file.nameWithoutExtension
    }
    
    /**
     * 获取公共下载目录
     */
    fun getDownloadDirectory(): File {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }
    
    /**
     * 列出目录中的所有字幕文件
     */
    fun listSubtitleFiles(directory: File): List<File> {
        if (!directory.exists() || !directory.isDirectory) {
            return emptyList()
        }
        
        return directory.listFiles { file ->
            file.isFile && isSubtitleFile(file)
        }?.sortedBy { it.name } ?: emptyList()
    }
    
    /**
     * 获取所有可访问的字幕文件目录
     */
    fun getAccessibleSubtitleDirectories(): List<File> {
        val directories = mutableListOf<File>()
        
        // 下载目录
        val downloadDir = getDownloadDirectory()
        if (downloadDir.exists() && downloadDir.isDirectory) {
            directories.add(downloadDir)
        }
        
        // 内部存储根目录
        val internalStorage = Environment.getExternalStorageDirectory()
        if (internalStorage.exists() && internalStorage.isDirectory) {
            directories.add(internalStorage)
        }
        
        // Movies 目录
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        if (moviesDir.exists() && moviesDir.isDirectory) {
            directories.add(moviesDir)
        }
        
        return directories
    }
    
    /**
     * 创建备份文件
     */
    fun createBackup(originalFile: File): File {
        val timestamp = System.currentTimeMillis()
        val backupFile = File(
            originalFile.parent,
            "${originalFile.nameWithoutExtension}_backup_${timestamp}.${originalFile.extension}"
        )
        originalFile.copyTo(backupFile)
        return backupFile
    }
    
    /**
     * 删除文件
     */
    fun deleteFile(file: File): Boolean {
        return file.exists() && file.delete()
    }
    
    /**
     * 获取文件大小 (格式化)
     */
    fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
        }
    }
}
