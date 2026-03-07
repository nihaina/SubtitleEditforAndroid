package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

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
    data class EncodingInfo(val charset: Charset, val displayName: String)
    
    val SUPPORTED_ENCODINGS = listOf(
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
        // 首先检查用户设置的默认编码
        if (context != null) {
            val settingsManager = getSettingsManager(context)
            return settingsManager.getDefaultEncoding()
        }
        try {
            FileInputStream(file).use { fis ->
                val bytes = ByteArray(minOf(4096, file.length().toInt()))
                val read = fis.read(bytes)
                if (read > 0) {
                    // 检查 BOM
                    if (bytes.size >= 3 && 
                        bytes[0].toInt() == 0xEF && 
                        bytes[1].toInt() == 0xBB && 
                        bytes[2].toInt() == 0xBF) {
                        return StandardCharsets.UTF_8
                    }
                    if (bytes.size >= 2 && 
                        bytes[0].toInt() == 0xFE && 
                        bytes[1].toInt() == 0xFF) {
                        return StandardCharsets.UTF_16BE
                    }
                    if (bytes.size >= 2 && 
                        bytes[0].toInt() == 0xFF && 
                        bytes[1].toInt() == 0xFE) {
                        return StandardCharsets.UTF_16LE
                    }
                    
                    // 简单检测 GBK/GB2312
                    val content = String(bytes, 0, read, StandardCharsets.UTF_8)
                    if (content.contains(Regex("[\\u4e00-\\u9fa5]"))) {
                        // 包含中文字符，尝试 GBK
                        try {
                            String(bytes, 0, read, Charset.forName("GBK"))
                            return Charset.forName("GBK")
                        } catch (e: Exception) {
                            // 如果 GBK 失败，使用 UTF-8
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return StandardCharsets.UTF_8
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
        val encoding = charset ?: StandardCharsets.UTF_8
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader(encoding).readText()
        } ?: ""
    }
    
    /**
     * 写入文件
     */
    fun writeFile(file: File, content: String, charset: Charset = StandardCharsets.UTF_8) {
        FileOutputStream(file).use { fos ->
            fos.write(content.toByteArray(charset))
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
