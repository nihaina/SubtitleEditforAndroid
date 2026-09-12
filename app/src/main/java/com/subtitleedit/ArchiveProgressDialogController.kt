package com.subtitleedit

import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.DialogArchiveProgressBinding
import com.subtitleedit.util.ArchiveManager
import com.subtitleedit.util.ArchiveProgressPolicy
import com.subtitleedit.util.FileUtils

/** Owns archive, compression, and copy progress dialog presentation. */
internal class ArchiveProgressDialogController(
    private val activity: AppCompatActivity
) {
    data class ProgressUi(
        val dialog: AlertDialog,
        val binding: DialogArchiveProgressBinding
    )

    fun show(title: String, message: String, showCancel: Boolean = false): ProgressUi {
        val binding = DialogArchiveProgressBinding.inflate(activity.layoutInflater)
        binding.tvProgressMessage.text = message
        binding.tvProgressLeading.visibility = View.GONE
        binding.tvProgressProcessed.visibility = View.GONE
        binding.progressBar.isIndeterminate = true
        binding.tvProgressPercent.visibility = View.GONE
        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(binding.root)
            .setCancelable(false)
        if (showCancel) builder.setNegativeButton("取消", null)
        val dialog = builder.create().also(AlertDialog::show)
        return ProgressUi(dialog, binding)
    }

    fun updateArchive(
        progress: ProgressUi,
        phase: ArchiveManager.ProgressPhase,
        completed: Long,
        total: Long
    ) {
        activity.runOnUiThread {
            if (!progress.dialog.isShowing) return@runOnUiThread
            progress.binding.tvProgressMessage.text = ArchiveProgressPolicy.phaseLabel(phase)
            if (total > 0L) {
                val ratio = completed.coerceIn(0L, total).toDouble() / total.toDouble()
                val percent = (ratio * 100.0).toInt().coerceIn(0, 100)
                progress.binding.progressBar.isIndeterminate = false
                progress.binding.progressBar.max = 1000
                progress.binding.progressBar.progress = (ratio * 1000.0).toInt().coerceIn(0, 1000)
                progress.binding.tvProgressPercent.text = if (phase == ArchiveManager.ProgressPhase.SCANNING) {
                    "$percent% · 已检查 $completed / $total 项"
                } else {
                    "$percent% · 已处理 ${FileUtils.formatFileSize(completed)} / ${FileUtils.formatFileSize(total)}"
                }
                progress.binding.tvProgressPercent.visibility = View.VISIBLE
            } else {
                progress.binding.progressBar.isIndeterminate = true
                if (phase == ArchiveManager.ProgressPhase.EXTRACTING) {
                    progress.binding.tvProgressPercent.text = if (completed > 0L) {
                        "已处理 ${FileUtils.formatFileSize(completed)}"
                    } else {
                        "正在读取..."
                    }
                    progress.binding.tvProgressPercent.visibility = View.VISIBLE
                } else {
                    progress.binding.tvProgressPercent.visibility = View.GONE
                }
            }
        }
    }

    fun updateCompression(
        progress: ProgressUi,
        compressionProgress: ArchiveManager.CompressionProgress
    ) {
        activity.runOnUiThread {
            if (!progress.dialog.isShowing) return@runOnUiThread
            progress.binding.tvProgressLeading.text =
                "已生成 ${FileUtils.formatFileSize(compressionProgress.generatedBytes)}"
            progress.binding.tvProgressLeading.visibility = View.VISIBLE
            if (compressionProgress.sourceBytes > 0L) {
                progress.binding.tvProgressProcessed.text =
                    "已处理 ${FileUtils.formatFileSize(compressionProgress.processedBytes)} / " +
                        FileUtils.formatFileSize(compressionProgress.sourceBytes)
                progress.binding.tvProgressProcessed.visibility = View.VISIBLE
            } else {
                progress.binding.tvProgressProcessed.visibility = View.GONE
            }
            progress.binding.tvProgressMessage.text = compressionProgress.currentFileName?.let {
                it
            } ?: progress.binding.tvProgressMessage.text
            val percent = compressionProgress.percent
            if (percent != null) {
                progress.binding.progressBar.isIndeterminate = false
                progress.binding.progressBar.max = 100
                progress.binding.progressBar.progress = percent
                progress.binding.tvProgressPercent.text = "$percent%"
                progress.binding.tvProgressPercent.visibility = View.VISIBLE
            } else {
                progress.binding.progressBar.isIndeterminate = true
                progress.binding.tvProgressPercent.visibility = View.GONE
            }
        }
    }

    fun updateFileCopy(progress: ProgressUi, message: String, completed: Long, total: Long) {
        activity.runOnUiThread {
            if (!progress.dialog.isShowing) return@runOnUiThread
            progress.binding.tvProgressMessage.text = message
            if (total > 0L) {
                val ratio = completed.coerceIn(0L, total).toDouble() / total.toDouble()
                progress.binding.progressBar.isIndeterminate = false
                progress.binding.progressBar.max = 1000
                progress.binding.progressBar.progress = (ratio * 1000.0).toInt().coerceIn(0, 1000)
                progress.binding.tvProgressPercent.text =
                    "${FileUtils.formatFileSize(completed)} / ${FileUtils.formatFileSize(total)}"
                progress.binding.tvProgressPercent.visibility = View.VISIBLE
            } else {
                progress.binding.progressBar.isIndeterminate = true
                progress.binding.tvProgressPercent.visibility = View.GONE
            }
        }
    }

    fun showError(title: String, error: Throwable) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(error.message ?: "未知错误")
            .setPositiveButton("确定", null)
            .show()
    }
}
