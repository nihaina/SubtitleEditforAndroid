package com.subtitleedit

import android.content.ClipData
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.util.DirectorySizeReader
import com.subtitleedit.util.FilePropertiesInfo
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.MediaFilePropertiesReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Displays file metadata while keeping filesystem reads outside the Activity UI. */
internal class FilePropertiesDialogController(
    private val activity: AppCompatActivity,
    private val showToast: (String) -> Unit
) {
    fun show(files: List<File>) {
        if (files.isEmpty()) return
        if (files.size == 1) showSingle(files.first()) else showMultiple(files)
    }

    private fun showSingle(file: File) {
        val initialProperties = basicProperties(file)
        val state = mutableStateOf<FilePropertiesDialogUiState>(
            FilePropertiesDialogUiState.Single(initialProperties, loading = true)
        )
        val dialog = ComposeDialogHost.show(activity) { handle ->
            FilePropertiesDialog(
                state = state.value,
                onDismiss = handle::dismiss,
                onCopyPath = ::copyPath
            )
        }
        val loadJob = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { MediaFilePropertiesReader.read(file) }
            }
            if (!dialog.isShowing) return@launch
            state.value = FilePropertiesDialogUiState.Single(
                properties = result.getOrElse {
                    initialProperties.copy(size = activity.getString(R.string.read_failed))
                },
                loading = false
            )
        }
        dialog.addOnDismissListener { loadJob.cancel() }
    }

    private fun showMultiple(files: List<File>) {
        val state = mutableStateOf<FilePropertiesDialogUiState>(
            FilePropertiesDialogUiState.Multiple(
                count = files.size,
                totalSize = activity.getString(R.string.loading),
                loading = true
            )
        )
        val dialog = ComposeDialogHost.show(activity) { handle ->
            FilePropertiesDialog(
                state = state.value,
                onDismiss = handle::dismiss,
                onCopyPath = ::copyPath
            )
        }
        val loadJob = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    FileUtils.formatFileSize(
                        files.sumOf { if (it.isDirectory) DirectorySizeReader.size(it) else it.length() }
                    )
                }
            }
            if (!dialog.isShowing) return@launch
            state.value = FilePropertiesDialogUiState.Multiple(
                count = files.size,
                totalSize = result.getOrElse { activity.getString(R.string.read_failed) },
                loading = false
            )
        }
        dialog.addOnDismissListener { loadJob.cancel() }
    }

    private fun basicProperties(file: File): FilePropertiesInfo = FilePropertiesInfo(
        name = file.name,
        path = file.absolutePath,
        type = if (file.isDirectory) "文件夹" else file.extension.uppercase().ifBlank { "未知" } + " 文件",
        size = activity.getString(R.string.loading),
        modifiedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(file.lastModified())),
        mediaInfoTitle = null,
        mediaDetails = emptyList()
    )

    private fun copyPath(path: String) {
        val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("文件路径", path))
        showToast("路径已复制")
    }
}
