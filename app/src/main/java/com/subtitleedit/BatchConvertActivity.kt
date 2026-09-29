package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.feature.ui.BatchConvertDialogUi
import com.subtitleedit.feature.ui.BatchConvertFileUi
import com.subtitleedit.feature.ui.BatchConvertScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleFormatConverter
import com.subtitleedit.util.SubtitleOutputWriter
import com.subtitleedit.util.SubtitleParser
import java.io.File

/** 批量转换界面 */
class BatchConvertActivity : AppCompatActivity() {

    private companion object {
        const val OUTPUT_DIRECTORY_KEY = "batch_convert"
    }

    data class ConvertFile(val uri: Uri, val fileName: String, val fileSize: Long)

    private lateinit var settingsManager: SettingsManager
    private var convertFiles by mutableStateOf<List<ConvertFile>>(emptyList())
    private var outputDirectoryUri: Uri? = null
    private var outputDirectoryLabel by mutableStateOf<String?>(null)
    private var targetFormat by mutableStateOf(SubtitleParser.SubtitleFormat.LRC)
    private var dialogState by mutableStateOf<BatchConvertDialogUi?>(null)
    private var pendingOutputUri: Uri? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            if (convertFiles.none { it.uri == uri }) {
                val fileName = getFileNameFromUri(uri) ?: "未知文件"
                convertFiles = convertFiles + ConvertFile(uri, fileName, getFileSizeFromUri(uri))
            }
        }
    }

    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            outputDirectoryUri = uri
            outputDirectoryLabel = DirectoryDisplayPath.fromUri(this, uri)
            settingsManager.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager.getInstance(this)
        restoreOutputDirectory()

        setContent {
            SubtitleEditComposeTheme {
                BatchConvertScreen(
                    files = convertFiles.map {
                        BatchConvertFileUi(
                            key = it.uri.toString(),
                            fileName = it.fileName,
                            fileSizeLabel = formatFileSize(it.fileSize)
                        )
                    },
                    formats = SubtitleFormatConverter.supportedTargetFormats.map(
                        SubtitleFormatConverter::displayName
                    ),
                    selectedFormatIndex = SubtitleFormatConverter.supportedTargetFormats
                        .indexOf(targetFormat).coerceAtLeast(0),
                    outputDirectoryLabel = outputDirectoryLabel,
                    dialog = dialogState,
                    onNavigateBack = { finish() },
                    onSelectFiles = {
                        filePickerLauncher.launch(arrayOf("text/*", "application/*"))
                    },
                    onRemoveFile = { key ->
                        convertFiles = convertFiles.filterNot { it.uri.toString() == key }
                    },
                    onSelectFormat = { index ->
                        targetFormat = SubtitleFormatConverter.supportedTargetFormats.getOrElse(index) {
                            SubtitleParser.SubtitleFormat.LRC
                        }
                    },
                    onSelectOutputDirectory = {
                        directoryPickerLauncher.launch(outputDirectoryUri)
                    },
                    onStartConversion = ::startConversion,
                    onDismissDialog = ::dismissDialog,
                    onOverwriteConflicts = ::overwriteConflicts,
                    onRenameConflicts = ::renameConflicts
                )
            }
        }
    }

    private fun restoreOutputDirectory() {
        val savedUri = settingsManager.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)
            ?.let(Uri::parse)
            ?: return
        outputDirectoryUri = savedUri
        outputDirectoryLabel = DirectoryDisplayPath.fromUri(this, savedUri)
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getFileSizeFromUri(uri: Uri): Long {
        var size = 0L
        try {
            val projection = arrayOf(OpenableColumns.SIZE)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)
                    if (!cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return size
    }

    private fun executeConversionLogic(finalOutputUri: Uri, overwriteOutput: Boolean = false) {
        var successCount = 0
        var failCount = 0
        var skippedCount = 0
        val successFiles = mutableListOf<String>()
        val failFiles = mutableListOf<String>()
        val skippedFiles = mutableListOf<String>()

        convertFiles.forEach { convertFile ->
            try {
                val source = SubtitleFormatConverter.readUri(this, convertFile.uri, convertFile.fileName)
                if (source.format == targetFormat) {
                    skippedCount++
                    skippedFiles.add(convertFile.fileName)
                    return@forEach
                }

                val convertedContent = SubtitleFormatConverter.convert(source, targetFormat)
                val targetExtension = SubtitleFormatConverter.extension(targetFormat)
                val nameWithoutExt = convertFile.fileName.substringBeforeLast(".")

                SubtitleOutputWriter.writeText(
                    this,
                    finalOutputUri,
                    nameWithoutExt,
                    targetExtension,
                    convertedContent,
                    overwrite = overwriteOutput
                )
                successCount++
                successFiles.add(convertFile.fileName)
            } catch (e: Exception) {
                failCount++
                failFiles.add(convertFile.fileName)
                e.printStackTrace()
            }
        }

        val message = buildString {
            appendLine("转换完成！")
            appendLine("成功：$successCount")
            appendLine("跳过（格式相同）：$skippedCount")
            appendLine("失败：$failCount")
            if (successFiles.isNotEmpty()) {
                appendLine("\n成功文件:")
                successFiles.forEach { appendLine("  - $it") }
            }
            if (skippedFiles.isNotEmpty()) {
                appendLine("\n跳过文件（格式已为目标格式）:")
                skippedFiles.forEach { appendLine("  - $it") }
            }
            if (failFiles.isNotEmpty()) {
                appendLine("\n失败文件:")
                failFiles.forEach { appendLine("  - $it") }
            }
            val outputPath = outputDirectoryUri?.let {
                DirectoryDisplayPath.fromUri(this@BatchConvertActivity, it)
            } ?: getConvertOutputDirectory().absolutePath
            appendLine("\n输出目录：$outputPath")
        }
        dialogState = BatchConvertDialogUi.Result(message)
    }

    private fun startConversion() {
        if (convertFiles.isEmpty()) {
            OverwritingToast.makeText(this, "请先添加要转换的文件", Toast.LENGTH_SHORT).show()
            return
        }

        val finalOutputUri = outputDirectoryUri ?: Uri.fromFile(getConvertOutputDirectory())
        val hasConflict = convertFiles.any { convertFile ->
            val targetExtension = SubtitleFormatConverter.extension(targetFormat)
            val nameWithoutExt = convertFile.fileName.substringBeforeLast(".")
            SubtitleOutputWriter.exists(this, finalOutputUri, nameWithoutExt, targetExtension)
        }

        if (hasConflict) {
            pendingOutputUri = finalOutputUri
            dialogState = BatchConvertDialogUi.Conflict
        } else {
            executeConversionLogic(finalOutputUri)
        }
    }

    private fun dismissDialog() {
        dialogState = null
        pendingOutputUri = null
    }

    private fun overwriteConflicts() {
        val outputUri = pendingOutputUri ?: return dismissDialog()
        dismissDialog()
        executeConversionLogic(outputUri, overwriteOutput = true)
    }

    private fun renameConflicts() {
        val outputUri = pendingOutputUri ?: return dismissDialog()
        dismissDialog()
        executeConversionLogic(outputUri, overwriteOutput = false)
    }

    private fun formatFileSize(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
        else -> "${size / (1024 * 1024 * 1024)} GB"
    }

    /** 获取转换输出目录：Download/SubtitleEdit/Convert，如果目录不存在则创建。 */
    private fun getConvertOutputDirectory(): File {
        val subtitleEditDir = File(FileUtils.getDownloadDirectory(), "SubtitleEdit")
        val convertDir = File(subtitleEditDir, "Convert")
        if (!convertDir.exists()) convertDir.mkdirs()
        return convertDir
    }
}
