package com.subtitleedit

import com.subtitleedit.repository.ArchiveRepository
import com.subtitleedit.util.ArchiveManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps the progress dialog visible until the native test has actually stopped. */
internal class ArchiveTestRunner(
    private val scope: CoroutineScope,
    private val repository: ArchiveRepository,
    private val progressController: ArchiveProgressDialogController
) {
    fun run(
        archive: File,
        password: String?,
        onCompleted: (ArchiveManager.TestResult) -> Unit,
        onCancelled: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val progress = progressController.show("正在测试压缩包", archive.name, showCancel = true)
        val cancelledByUser = AtomicBoolean(false)
        val job = scope.launch {
            val chars = password?.toCharArray()
            try {
                val result = withContext(Dispatchers.IO) {
                    val worker = currentCoroutineContext()
                    repository.testArchive(
                        archive = archive,
                        password = chars,
                        checkCancelled = worker::ensureActive,
                        onProgress = { completed, total ->
                            progressController.updateArchive(progress, ArchiveManager.ProgressPhase.TESTING, completed, total)
                        }
                    )
                }
                currentCoroutineContext().ensureActive()
                onCompleted(result)
            } catch (error: CancellationException) {
                if (cancelledByUser.get()) onCancelled() else throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                onFailure(error)
            } finally {
                chars?.fill('\u0000')
                progress.dialog.dismiss()
            }
        }
        progress.setCancelAction {
            cancelledByUser.set(true)
            progress.showCancelling()
            job.cancel(CancellationException("用户取消压缩包测试"))
        }
    }
}
