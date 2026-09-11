package com.subtitleedit

import android.content.ClipData
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.util.DirectorySizeReader
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.MediaFilePropertiesReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Displays file metadata while keeping filesystem reads outside MainActivity. */
internal class FilePropertiesDialogController(
    private val activity: AppCompatActivity,
    private val showToast: (String) -> Unit
) {
    fun show(files: List<File>) {
        if (files.isEmpty()) return
        if (files.size == 1) showSingle(files.first()) else showMultiple(files)
    }

    private fun showSingle(file: File) {
        val content = activity.layoutInflater.inflate(R.layout.dialog_file_properties, null)
        content.findViewById<TextView>(R.id.tvPropertyName).text = file.name
        content.findViewById<TextView>(R.id.tvPropertyType).text =
            if (file.isDirectory) "文件夹" else file.extension.uppercase().ifBlank { "未知" } + " 文件"
        content.findViewById<TextView>(R.id.tvPropertySize).text = activity.getString(R.string.loading)
        content.findViewById<TextView>(R.id.tvPropertyModifiedTime).text =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
        content.findViewById<TextView>(R.id.tvPropertyPath).apply {
            text = file.absolutePath
            contentDescription = "点击复制路径：${file.absolutePath}"
            setOnClickListener {
                val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("文件路径", file.absolutePath))
                showToast("路径已复制")
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(content)
            .setPositiveButton("确定", null)
            .show()

        val loadJob = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { MediaFilePropertiesReader.read(file) } }
            if (!dialog.isShowing) return@launch
            content.findViewById<android.view.View>(R.id.propertyLoadingIndicator).visibility = android.view.View.GONE
            result.onSuccess { properties ->
                content.findViewById<TextView>(R.id.tvPropertyType).text = properties.type
                content.findViewById<TextView>(R.id.tvPropertySize).text = properties.size
                content.findViewById<TextView>(R.id.tvPropertyModifiedTime).text = properties.modifiedTime
                content.findViewById<TextView>(R.id.tvMediaInfoTitle).apply {
                    text = properties.mediaInfoTitle
                    visibility = if (properties.mediaInfoTitle == null) android.view.View.GONE else android.view.View.VISIBLE
                }
                content.findViewById<LinearLayout>(R.id.mediaPropertiesContainer).apply {
                    removeAllViews()
                    properties.mediaDetails.forEach { detail ->
                        val row = activity.layoutInflater.inflate(R.layout.item_file_property, this, false)
                        row.findViewById<TextView>(R.id.tvPropertyLabel).text = detail.label
                        row.findViewById<TextView>(R.id.tvPropertyValue).text = detail.value
                        addView(row)
                    }
                    visibility = if (properties.mediaDetails.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                }
            }.onFailure {
                content.findViewById<TextView>(R.id.tvPropertySize).text = activity.getString(R.string.read_failed)
            }
        }
        dialog.setOnDismissListener { loadJob.cancel() }
    }

    private fun showMultiple(files: List<File>) {
        val content = activity.layoutInflater.inflate(R.layout.dialog_multiple_file_properties, null)
        content.findViewById<TextView>(R.id.tvSelectedItemCount).text = "${files.size} 项"
        val totalSizeView = content.findViewById<TextView>(R.id.tvSelectedTotalSize).apply {
            text = activity.getString(R.string.loading)
        }
        val dialog = AlertDialog.Builder(activity)
            .setView(content)
            .setPositiveButton("确定", null)
            .show()

        val loadJob = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { files.sumOf { if (it.isDirectory) DirectorySizeReader.size(it) else it.length() } }
            }
            if (!dialog.isShowing) return@launch
            totalSizeView.text = result.fold(
                onSuccess = FileUtils::formatFileSize,
                onFailure = { activity.getString(R.string.read_failed) }
            )
        }
        dialog.setOnDismissListener { loadJob.cancel() }
    }
}
