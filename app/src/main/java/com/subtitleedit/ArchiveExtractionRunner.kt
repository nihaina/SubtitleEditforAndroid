package com.subtitleedit

import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import com.subtitleedit.repository.ArchiveRepository
import com.subtitleedit.util.ArchiveManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs one extraction attempt and reports its outcome to the orchestration layer. */
internal class ArchiveExtractionRunner(
    private val activity: AppCompatActivity,
    private val scope: LifecycleCoroutineScope,
    private val repository: ArchiveRepository,
    private val progressController: ArchiveProgressDialogController,
) {
    fun run(
        archive: File,
        destination: File,
        password: String?,
        conflictPolicy: ArchiveManager.ConflictPolicy,
        conflictPolicies: Map<String, ArchiveManager.ConflictPolicy>,
        onCompleted: (ArchiveManager.ExtractResult) -> Unit,
        onCancelled: () -> Unit,
        onFailure: (Throwable) -> Unit,
        onConflict: ((ArchiveManager.DestinationConflict) -> ArchiveManager.ConflictResolution)? = null
    ) {
        val progress = progressController.show("正在解压", "目标：${destination.absolutePath}", showCancel = true)
        val cancelledByUser = AtomicBoolean(false)
        val job = scope.launch {
            try {
                val chars = password?.toCharArray()
                val result = try {
                    withContext(Dispatchers.IO) {
                        val worker = currentCoroutineContext()
                        runCatching {
                            repository.extractArchive(
                                archive = archive,
                                destination = destination,
                                password = chars,
                                conflictPolicy = conflictPolicy,
                                conflictPolicies = conflictPolicies,
                                conflictsPrechecked = true,
                                onProgress = { phase, completed, total -> progressController.updateArchive(progress, phase, completed, total) },
                                onConflict = onConflict,
                                checkCancelled = worker::ensureActive
                            )
                        }
                    }
                } finally {
                    chars?.fill('\u0000')
                    progress.dialog.dismiss()
                }
                result.onSuccess(onCompleted).onFailure(onFailure)
            } catch (error: CancellationException) {
                if (cancelledByUser.get()) onCancelled() else throw error
            }
        }
        progress.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { button ->
            button.isEnabled = false
            progress.binding.tvProgressMessage.text = "正在取消..."
            progress.binding.progressBar.isIndeterminate = true
            progress.binding.tvProgressPercent.visibility = View.GONE
            cancelledByUser.set(true)
            job.cancel(CancellationException("用户取消解压"))
        }
    }
}
