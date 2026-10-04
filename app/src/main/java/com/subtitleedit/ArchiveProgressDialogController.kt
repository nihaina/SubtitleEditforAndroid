package com.subtitleedit

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.subtitleedit.util.ArchiveManager
import com.subtitleedit.util.ArchiveProgressPolicy
import com.subtitleedit.util.FileUtils

/** Owns archive, compression, and copy progress dialog presentation. */
internal class ArchiveProgressDialogController(
    private val activityProvider: () -> AppCompatActivity?
) {
    class ProgressUi internal constructor(
        val state: MutableState<ArchiveProgressDialogState>
    ) {
        lateinit var dialog: ComposeDialogHandle
            internal set

        private var cancelAction: (() -> Unit)? = null

        fun setCancelAction(action: () -> Unit) {
            cancelAction = action
        }

        fun requestCancel() {
            if (state.value.cancelEnabled) cancelAction?.invoke()
        }

        fun setCancelEnabled(enabled: Boolean) {
            state.value = state.value.copy(cancelEnabled = enabled)
        }

        fun setMessage(message: String) {
            state.value = state.value.copy(message = message)
        }

        fun showCancelling(message: String = "正在取消...") {
            state.value = state.value.copy(
                message = message,
                indeterminate = true,
                progress = null,
                percentLabel = null,
                leadingLabel = null,
                processedLabel = null,
                cancelEnabled = false
            )
        }
    }

    fun show(title: String, message: String, showCancel: Boolean = false): ProgressUi {
        val activity = activityProvider()
            ?: error("Archive progress UI is not attached to an Activity")
        val state = mutableStateOf(
            ArchiveProgressDialogState(
                title = title,
                message = message,
                showCancel = showCancel
            )
        )
        val progress = ProgressUi(state)
        progress.dialog = ComposeDialogHost.show(activity) { _ ->
            ArchiveProgressDialog(state.value, onCancel = progress::requestCancel)
        }
        return progress
    }

    /** Runs a UI update against the currently attached Activity. */
    fun runOnUiThread(action: () -> Unit) {
        activityProvider()?.runOnUiThread(action)
    }

    fun updateArchive(
        progress: ProgressUi,
        phase: ArchiveManager.ProgressPhase,
        completed: Long,
        total: Long
    ) {
        runOnUiThread {
            if (!progress.dialog.isShowing) return@runOnUiThread
            val message = ArchiveProgressPolicy.phaseLabel(phase)
            if (total > 0L) {
                val ratio = completed.coerceIn(0L, total).toDouble() / total.toDouble()
                val percent = (ratio * 100.0).toInt().coerceIn(0, 100)
                val label = if (phase == ArchiveManager.ProgressPhase.SCANNING) {
                    "$percent% · 已检查 $completed / $total 项"
                } else {
                    "$percent% · 已处理 ${FileUtils.formatFileSize(completed)} / ${FileUtils.formatFileSize(total)}"
                }
                progress.state.value = progress.state.value.copy(
                    message = message,
                    indeterminate = false,
                    progress = ratio.toFloat(),
                    percentLabel = label
                )
            } else {
                val label = if (phase == ArchiveManager.ProgressPhase.EXTRACTING) {
                    if (completed > 0L) "已处理 ${FileUtils.formatFileSize(completed)}" else "正在读取..."
                } else {
                    null
                }
                progress.state.value = progress.state.value.copy(
                    message = message,
                    indeterminate = true,
                    progress = null,
                    percentLabel = label
                )
            }
        }
    }

    fun updateCompression(
        progress: ProgressUi,
        compressionProgress: ArchiveManager.CompressionProgress
    ) {
        runOnUiThread {
            if (!progress.dialog.isShowing) return@runOnUiThread
            val percent = compressionProgress.percent
            val oldState = progress.state.value
            progress.state.value = oldState.copy(
                leadingLabel = "已生成 ${FileUtils.formatFileSize(compressionProgress.generatedBytes)}",
                processedLabel = if (compressionProgress.sourceBytes > 0L) {
                    "已处理 ${FileUtils.formatFileSize(compressionProgress.processedBytes)} / " +
                        FileUtils.formatFileSize(compressionProgress.sourceBytes)
                } else {
                    null
                },
                message = compressionProgress.currentFileName ?: oldState.message,
                indeterminate = percent == null,
                progress = percent?.coerceIn(0, 100)?.div(100f),
                percentLabel = percent?.let { "$it%" }
            )
        }
    }

    fun updateFileCopy(progress: ProgressUi, message: String, completed: Long, total: Long) {
        runOnUiThread {
            if (!progress.dialog.isShowing) return@runOnUiThread
            if (total > 0L) {
                val ratio = completed.coerceIn(0L, total).toDouble() / total.toDouble()
                progress.state.value = progress.state.value.copy(
                    message = message,
                    indeterminate = false,
                    progress = ratio.toFloat(),
                    percentLabel = "${FileUtils.formatFileSize(completed)} / ${FileUtils.formatFileSize(total)}"
                )
            } else {
                progress.state.value = progress.state.value.copy(
                    message = message,
                    indeterminate = true,
                    progress = null,
                    percentLabel = null
                )
            }
        }
    }

    fun showError(title: String, error: Throwable) {
        val message = error.message ?: "未知错误"
        val activity = activityProvider() ?: return
        ComposeDialogHost.show(activity) { dialog ->
            AlertDialog(
                onDismissRequest = dialog::dismiss,
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = dialog::dismiss) {
                        Text("确定")
                    }
                }
            )
        }
    }
}

internal data class ArchiveProgressDialogState(
    val title: String,
    val message: String,
    val indeterminate: Boolean = true,
    val progress: Float? = null,
    val percentLabel: String? = null,
    val leadingLabel: String? = null,
    val processedLabel: String? = null,
    val showCancel: Boolean = false,
    val cancelEnabled: Boolean = true
)
