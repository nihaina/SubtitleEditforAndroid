package com.subtitleedit.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 草稿箱管理器
 * 草稿存储结构：
 * drafts/
 *   ├── [filename].lrc/
 *   │     ├── 01_[filename].lrc
 *   │     ├── 02_[filename].lrc
 *   │     └── ...
 *   └── [filename].srt/
 *         └── ...
 */
object DraftManager {
    
    private const val DRAFTS_DIR = "drafts"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    
    /**
     * 获取草稿箱根目录
     */
    fun getDraftsDir(context: Context): File {
        val dir = File(context.filesDir, DRAFTS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    /**
     * 获取指定文件的草稿文件夹
     */
    fun getDraftFolder(context: Context, fileName: String): File {
        val draftsDir = getDraftsDir(context)
        val draftFolder = File(draftsDir, fileName)
        if (!draftFolder.exists()) {
            draftFolder.mkdirs()
        }
        return draftFolder
    }
    
    /**
     * 获取下一个草稿编号
     */
    fun getNextDraftNumber(context: Context, fileName: String): Int {
        val draftFolder = getDraftFolder(context, fileName)
        val existingFiles = draftFolder.listFiles { file ->
            file.isFile && file.name.endsWith(fileName)
        } ?: return 1
        
        if (existingFiles.isEmpty()) {
            return 1
        }
        
        // 从文件名中提取编号
        val numbers = existingFiles.mapNotNull { file ->
            val name = file.nameWithoutExtension
            if (name.startsWith("0") || name.startsWith("1") || name.startsWith("2") || 
                name.startsWith("3") || name.startsWith("4") || name.startsWith("5") ||
                name.startsWith("6") || name.startsWith("7") || name.startsWith("8") ||
                name.startsWith("9")) {
                name.split("_").firstOrNull()?.toIntOrNull()
            } else {
                null
            }
        }
        
        return (numbers.maxOrNull() ?: 0) + 1
    }
    
    /**
     * 保存草稿
     * @return 保存的草稿文件名
     */
    fun saveDraft(context: Context, fileName: String, content: String): String {
        val draftFolder = getDraftFolder(context, fileName)
        val number = getNextDraftNumber(context, fileName)
        val draftFileName = String.format("%02d_%s", number, fileName)
        val draftFile = File(draftFolder, draftFileName)
        
        draftFile.writeText(content)
        
        return draftFileName
    }
    
    /**
     * 获取所有草稿文件夹列表
     */
    fun getAllDraftFolders(context: Context): List<File> {
        val draftsDir = getDraftsDir(context)
        return draftsDir.listFiles { file ->
            file.isDirectory
        }?.toList() ?: emptyList()
    }
    
    /**
     * 获取指定文件夹中的所有草稿文件
     */
    fun getDraftsInFolder(context: Context, folderName: String): List<File> {
        val draftFolder = File(getDraftsDir(context), folderName)
        return draftFolder.listFiles { file ->
            file.isFile
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    
    /**
     * 读取草稿文件内容
     */
    fun readDraft(context: Context, folderName: String, fileName: String): String {
        val draftFile = File(getDraftsDir(context), "$folderName/$fileName")
        return if (draftFile.exists()) {
            draftFile.readText()
        } else {
            ""
        }
    }
    
    /**
     * 删除草稿文件
     */
    fun deleteDraft(context: Context, folderName: String, fileName: String): Boolean {
        val draftFile = File(getDraftsDir(context), "$folderName/$fileName")
        return if (draftFile.exists()) {
            draftFile.delete()
        } else {
            false
        }
    }
    
    /**
     * 删除整个草稿文件夹
     */
    fun deleteDraftFolder(context: Context, folderName: String): Boolean {
        val draftFolder = File(getDraftsDir(context), folderName)
        return if (draftFolder.exists() && draftFolder.isDirectory) {
            draftFolder.deleteRecursively()
        } else {
            false
        }
    }
    
    /**
     * 获取文件最后修改时间的格式化字符串
     */
    fun getFormattedDate(file: File): String {
        return dateFormat.format(Date(file.lastModified()))
    }
    
    /**
     * 获取所有草稿文件（扁平列表，用于主页草稿箱）
     */
    data class DraftFileInfo(
        val folderName: String,
        val fileName: String,
        val displayName: String,
        val lastModified: Long,
        val formattedDate: String
    )
    
    fun getAllDrafts(context: Context): List<DraftFileInfo> {
        val result = mutableListOf<DraftFileInfo>()
        val draftFolders = getAllDraftFolders(context)
        
        for (folder in draftFolders) {
            val drafts = getDraftsInFolder(context, folder.name)
            for (draft in drafts) {
                result.add(DraftFileInfo(
                    folderName = folder.name,
                    fileName = draft.name,
                    displayName = draft.name,
                    lastModified = draft.lastModified(),
                    formattedDate = getFormattedDate(draft)
                ))
            }
        }
        
        return result.sortedByDescending { it.lastModified }
    }
}
