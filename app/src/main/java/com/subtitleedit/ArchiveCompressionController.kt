package com.subtitleedit

import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import com.subtitleedit.repository.ArchiveRepository
import com.subtitleedit.util.ArchiveManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Executes archive creation while keeping coroutine and progress handling out of the Activity. */
internal class ArchiveCompressionController(
    private val activity: AppCompatActivity,
    private val scope: LifecycleCoroutineScope,
    private val repository: ArchiveRepository,
    private val progressController: ArchiveProgressDialogController,
    private val onExitSelection: () -> Unit,
    private val onRefreshDirectory: (File) -> Unit,
    private val onToast: (String) -> Unit,
    private val onError: (String, Throwable) -> Unit,
    private val onDeleteFailures: (File, Int) -> Unit
) {
    fun create(
        sources: List<File>,
        output: File,
        format: ArchiveManager.CreateFormat,
        method: ArchiveManager.CompressionMethod,
        password: String,
        encryptionMethod: ArchiveManager.EncryptionMethod?,
        splitSizeBytes: Long?,
        deleteSources: Boolean
    ) {
        val progress = progressController.show(
            title = "正在压缩：",
            message = sources.firstOrNull()?.name ?: output.name,
            showCancel = true
        )
        val committed = AtomicBoolean(false)
        val job = scope.launch {
            val passwordChars = password.takeIf(String::isNotEmpty)?.toCharArray()
            try {
                val failures = withContext(Dispatchers.IO) {
                    val workerContext = coroutineContext
                    repository.createArchive(
                        sources = sources,
                        destination = output,
                        format = format,
                        method = method,
                        password = passwordChars,
                        encryptionMethod = encryptionMethod,
                        splitSizeBytes = splitSizeBytes,
                        checkCancelled = workerContext::ensureActive,
                        onDetailedProgress = { progressController.updateCompression(progress, it) },
                        onCommitted = {
                            committed.set(true)
                            activity.runOnUiThread {
                                if (progress.dialog.isShowing) {
                                    progress.dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
                                    if (deleteSources) progress.binding.tvProgressMessage.text = "正在删除源文件..."
                                }
                            }
                        }
                    )
                    if (deleteSources) sources.filterNot { it.deleteRecursively() } else emptyList()
                }
                onExitSelection()
                output.parentFile?.let(onRefreshDirectory)
                if (failures.isEmpty()) onToast("压缩完成：${output.name}")
                else onDeleteFailures(output, failures.size)
            } catch (_: CancellationException) {
                onExitSelection()
                output.parentFile?.let(onRefreshDirectory)
                onToast(if (committed.get()) "压缩完成：${output.name}" else "已取消压缩")
            } catch (error: Throwable) {
                onError("压缩失败", error)
                output.parentFile?.let(onRefreshDirectory)
            } finally {
                passwordChars?.fill('\u0000')
                progress.dialog.dismiss()
            }
        }
        progress.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { button ->
            if (committed.get()) {
                button.isEnabled = false
                return@setOnClickListener
            }
            button.isEnabled = false
            progress.binding.tvProgressMessage.text = "正在取消..."
            progress.binding.progressBar.isIndeterminate = true
            progress.binding.tvProgressPercent.visibility = android.view.View.GONE
            progress.binding.tvProgressLeading.visibility = android.view.View.GONE
            progress.binding.tvProgressProcessed.visibility = android.view.View.GONE
            job.cancel(CancellationException("用户取消压缩"))
        }
    }
}
