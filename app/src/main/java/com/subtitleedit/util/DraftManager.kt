package com.subtitleedit.util

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.*

/**
 * 草稿箱管理器
 * 草稿存储结构：
 * drafts/
 *   ├── [audio filename]/
 *   │     ├── subtitle.srt
 *   │     ├── subtitle (1).srt
 *   │     └── ...
 *   └── ...
 *
 * Draft folders are named after the source audio file.  The old numbered layout is still
 * readable so existing drafts survive the storage change.
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
     * Saves a draft using the actual subtitle filename inside the source-audio folder.
     *
     * A collision follows the same naming rule as normal subtitle output: `name (1).ext`,
     * `name (2).ext`, and so on.  The returned value is the filename that was written.
     */
    fun saveDraft(
        context: Context,
        folderName: String,
        subtitleFileName: String,
        content: String
    ): String = saveDraft(getDraftsDir(context), folderName, subtitleFileName, content)

    @Synchronized
    internal fun saveDraft(
        draftsDir: File,
        folderName: String,
        subtitleFileName: String,
        content: String
    ): String {
        validateName(folderName)
        validateName(subtitleFileName)
        val draftFolder = File(draftsDir, folderName)
        if (!draftFolder.mkdirs() && !draftFolder.isDirectory) throw IOException("无法创建草稿文件夹")
        // Stage outside the visible folder, so opening drafts during a save cannot read a
        // partially written subtitle. Only replace the empty filename reservation we own.
        val temporary = File.createTempFile("draft_", ".tmp", draftsDir)
        var target: File? = null
        try {
            temporary.writeText(content, Charsets.UTF_8)
            var candidate: File
            do {
                candidate = File(draftFolder, uniqueDraftFileName(draftFolder, subtitleFileName))
            } while (!candidate.createNewFile())
            target = candidate
            try {
                Files.move(temporary.toPath(), candidate.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), candidate.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return candidate.name
        } catch (error: Throwable) {
            target?.delete()
            throw error
        } finally {
            temporary.delete()
        }
    }

    /** Returns a saved draft path for source-editor preview or loading. */
    fun getDraftFile(context: Context, folderName: String, fileName: String): File {
        validateName(folderName)
        validateName(fileName)
        return File(File(getDraftsDir(context), folderName), fileName)
    }

    private fun validateName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' }) { "草稿名称无效" }
    }

    /**
     * Resolves a non-conflicting file name in [directory], matching subtitle output naming.
     * Kept internal so the pure JVM test can exercise collision behavior without Android APIs.
     */
    internal fun uniqueDraftFileName(directory: File, requestedName: String): String {
        val extension = requestedName.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".${extension}"
        val stem = if (extension.isEmpty()) {
            requestedName
        } else {
            requestedName.removeSuffix(".$extension")
        }
        var candidate = requestedName
        var counter = 1
        while (File(directory, candidate).exists()) {
            candidate = "$stem ($counter)$suffix"
            counter++
        }
        return candidate
    }
    
    /**
     * 获取所有草稿文件夹列表
     */
    fun getAllDraftFolders(context: Context): List<File> {
        val draftsDir = getDraftsDir(context)
        removeEmptyDraftFolders(draftsDir)
        return draftsDir.listFiles { file ->
            file.isDirectory
        }?.toList() ?: emptyList()
    }
    
    /**
     * 获取指定文件夹中的所有草稿文件
     */
    fun getDraftsInFolder(context: Context, folderName: String): List<File> {
        val draftFolder = File(getDraftsDir(context), folderName)
        val drafts = draftFolder.listFiles { file ->
            file.isFile
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (drafts.isEmpty() && draftFolder.isDirectory) draftFolder.delete()
        return drafts
    }

    /** Removes stale draft folders left behind after their last file was deleted. */
    internal fun removeEmptyDraftFolders(draftsDir: File) {
        draftsDir.listFiles { file -> file.isDirectory }
            ?.filter { folder -> folder.listFiles().orEmpty().isEmpty() }
            ?.forEach(File::delete)
    }
    
    /**
     * 读取草稿文件内容
     */
    fun readDraft(context: Context, folderName: String, fileName: String): String {
        val draftFile = getDraftFile(context, folderName, fileName)
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
        val draftFile = getDraftFile(context, folderName, fileName)
        return if (draftFile.exists()) {
            val deleted = draftFile.delete()
            if (deleted) draftFile.parentFile?.let { parent ->
                if (parent.listFiles().orEmpty().isEmpty()) parent.delete()
            }
            deleted
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
