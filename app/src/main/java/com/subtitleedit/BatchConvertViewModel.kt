package com.subtitleedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.subtitleedit.feature.ui.BatchConvertDialogUi
import com.subtitleedit.feature.ui.BatchConvertFileUi
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleFormatConverter
import com.subtitleedit.util.SubtitleOutputWriter
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.UriDisplayName
import java.io.File

internal data class BatchConvertUiState(
    val files: List<BatchConvertFileUi> = emptyList(),
    val targetFormat: SubtitleParser.SubtitleFormat = SubtitleParser.SubtitleFormat.LRC,
    val outputDirectoryLabel: String? = null,
    val dialog: BatchConvertDialogUi? = null
)

internal class BatchConvertViewModel(
    application: Application
) : AppViewModel<BatchConvertUiState, Nothing>(application, BatchConvertUiState()) {
    private companion object {
        const val OUTPUT_DIRECTORY_KEY = "batch_convert"
    }

    private data class ConvertFile(val uri: Uri, val fileName: String, val fileSize: Long)

    private val settingsManager = SettingsManager.getInstance(application)
    private var convertFiles: List<ConvertFile> = emptyList()
    private var pendingOutputUri: Uri? = null

    /** Current output directory, used as the picker's initial location. */
    var outputDirectoryUri: Uri? = null
        private set

    init {
        restoreOutputDirectory()
    }

    fun addFiles(uris: List<Uri>) {
        uris.forEach { uri ->
            if (convertFiles.none { it.uri == uri }) {
                val fileName = UriDisplayName.of(app, uri, fallback = string(R.string.unknown_file))
                convertFiles = convertFiles + ConvertFile(uri, fileName, getFileSizeFromUri(uri))
            }
        }
        publishFiles()
    }

    fun removeFile(key: String) {
        convertFiles = convertFiles.filterNot { it.uri.toString() == key }
        publishFiles()
    }

    fun selectFormat(format: SubtitleParser.SubtitleFormat) = setState { copy(targetFormat = format) }

    fun selectOutputDirectory(uri: Uri) {
        app.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        outputDirectoryUri = uri
        setState { copy(outputDirectoryLabel = DirectoryDisplayPath.fromUri(app, uri)) }
        settingsManager.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
    }

    fun startConversion() {
        if (convertFiles.isEmpty()) {
            toast(R.string.batch_convert_no_files)
            return
        }

        val targetFormat = currentState.targetFormat
        val finalOutputUri = outputDirectoryUri ?: Uri.fromFile(getConvertOutputDirectory())
        val hasConflict = convertFiles.any { convertFile ->
            val targetExtension = SubtitleFormatConverter.extension(targetFormat)
            val nameWithoutExt = convertFile.fileName.substringBeforeLast(".")
            SubtitleOutputWriter.exists(app, finalOutputUri, nameWithoutExt, targetExtension)
        }

        if (hasConflict) {
            pendingOutputUri = finalOutputUri
            setState { copy(dialog = BatchConvertDialogUi.Conflict) }
        } else {
            executeConversionLogic(finalOutputUri)
        }
    }

    fun dismissDialog() {
        setState { copy(dialog = null) }
        pendingOutputUri = null
    }

    fun overwriteConflicts() {
        val outputUri = pendingOutputUri ?: return dismissDialog()
        dismissDialog()
        executeConversionLogic(outputUri, overwriteOutput = true)
    }

    fun renameConflicts() {
        val outputUri = pendingOutputUri ?: return dismissDialog()
        dismissDialog()
        executeConversionLogic(outputUri, overwriteOutput = false)
    }

    private fun publishFiles() {
        val files = convertFiles.map {
            BatchConvertFileUi(
                key = it.uri.toString(),
                fileName = it.fileName,
                fileSizeLabel = formatFileSize(it.fileSize)
            )
        }
        setState { copy(files = files) }
    }

    private fun restoreOutputDirectory() {
        val savedUri = settingsManager.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)
            ?.let(Uri::parse)
            ?: return
        outputDirectoryUri = savedUri
        setState { copy(outputDirectoryLabel = DirectoryDisplayPath.fromUri(app, savedUri)) }
    }

    private fun getFileSizeFromUri(uri: Uri): Long {
        var size = 0L
        try {
            val projection = arrayOf(OpenableColumns.SIZE)
            app.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
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
        val targetFormat = currentState.targetFormat
        var successCount = 0
        var failCount = 0
        var skippedCount = 0
        val successFiles = mutableListOf<String>()
        val failFiles = mutableListOf<String>()
        val skippedFiles = mutableListOf<String>()

        convertFiles.forEach { convertFile ->
            try {
                val source = SubtitleFormatConverter.readUri(app, convertFile.uri, convertFile.fileName)
                if (source.format == targetFormat) {
                    skippedCount++
                    skippedFiles.add(convertFile.fileName)
                    return@forEach
                }

                val convertedContent = SubtitleFormatConverter.convert(source, targetFormat)
                val targetExtension = SubtitleFormatConverter.extension(targetFormat)
                val nameWithoutExt = convertFile.fileName.substringBeforeLast(".")

                SubtitleOutputWriter.writeText(
                    app,
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
            appendLine(string(R.string.batch_convert_result_done))
            appendLine(string(R.string.batch_convert_result_success_count, successCount))
            appendLine(string(R.string.batch_convert_result_skipped_count, skippedCount))
            appendLine(string(R.string.batch_convert_result_failed_count, failCount))
            appendFileList(R.string.batch_convert_result_success_files, successFiles)
            appendFileList(R.string.batch_convert_result_skipped_files, skippedFiles)
            appendFileList(R.string.batch_convert_result_failed_files, failFiles)
            val outputPath = outputDirectoryUri?.let {
                DirectoryDisplayPath.fromUri(app, it)
            } ?: getConvertOutputDirectory().absolutePath
            append('\n').appendLine(string(R.string.task_output_directory, outputPath))
        }
        setState { copy(dialog = BatchConvertDialogUi.Result(message)) }
    }

    private fun StringBuilder.appendFileList(headerId: Int, files: List<String>) {
        if (files.isEmpty()) return
        append('\n').appendLine(string(headerId))
        files.forEach { appendLine(string(R.string.batch_convert_result_file_item, it)) }
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
