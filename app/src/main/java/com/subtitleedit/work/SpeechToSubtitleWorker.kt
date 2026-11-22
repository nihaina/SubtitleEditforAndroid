package com.subtitleedit.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.subtitleedit.R
import com.subtitleedit.SpeechToSubtitleActivity
import com.subtitleedit.SubtitleEditApplication
import com.subtitleedit.util.TaskLogBuffer
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SpeechToSubtitleWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private data class Progress(val message: String, val percent: Int)

    private val store = SpeechToSubtitleTaskStore(applicationContext, id)
    private val progress = AtomicReference(Progress(string(R.string.task_preparing), 0))
    private val log = TaskLogBuffer()

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(progress.get())

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? SubtitleEditApplication
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to string(R.string.error_unknown)))
        try {
            setForeground(getForegroundInfo())
            executionMutex.withLock {
                removeReplacedTaskFiles()
                runTask(app)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val message = error.message.orEmpty().ifBlank { string(R.string.error_unknown) }
            appendLog(string(R.string.task_failed_with_reason, message))
            runCatching { store.writeLog(log.text()) }
            Result.failure(workDataOf(KEY_ERROR to message.take(MAX_DATA_TEXT_CHARS)))
        }
    }

    private suspend fun runTask(app: SubtitleEditApplication): Result = coroutineScope {
        val request = store.readRequest()
        require(request.files.isNotEmpty()) { string(R.string.speech_to_subtitle_missing_inputs) }
        val previousLog = store.readLog().removeSuffix("\n")
        if (previousLog.isNotEmpty()) log.appendRaw(previousLog)

        val directory = cacheDirectory(id)
        directory.deleteRecursively()
        val operation = app.dependencies.nativeMediaEngine.openOperation()
        val runner = SpeechToSubtitleRunner(app, request, operation, ::updateProgress, ::appendLog)
        val publisher = launch { publishProgress() }
        try {
            if (previousLog.isEmpty()) runner.logConfiguration()
            val succeeded = processFiles(request, runner, directory)
            val total = request.files.size
            val resultMessage = if (succeeded == total) {
                string(R.string.speech_to_subtitle_summary, succeeded, total)
            } else {
                string(R.string.speech_to_subtitle_summary_with_failures, succeeded, total, total - succeeded)
            }
            val completed = string(R.string.task_all_done)
            updateProgress(completed, 100)
            appendLog(resultMessage)
            val resultData = workDataOf(KEY_SUMMARY to resultMessage, KEY_MESSAGE to completed, KEY_PROGRESS to 100)
            if (succeeded > 0) Result.success(resultData) else Result.failure(resultData)
        } finally {
            withContext(NonCancellable) {
                publisher.cancelAndJoin()
                operation.cancel()
                directory.deleteRecursively()
                runCatching { store.writeLog(log.text()) }
            }
        }
    }

    private suspend fun processFiles(
        request: SpeechToSubtitleRequest,
        runner: SpeechToSubtitleRunner,
        directory: File,
    ): Int {
        var successCount = 0
        val usedBaseNames = mutableSetOf<Pair<String, String>>()
        request.files.forEachIndexed { index, file ->
            currentCoroutineContext().ensureActive()
            val overwrite = request.overwriteOutput && usedBaseNames.add(
                file.outputDirectory.toString() to file.fileName.substringBeforeLast(".").lowercase()
            )
            val recorded = store.readOutcome(index)
            if (recorded != null) {
                if (recorded.output != null) successCount++
                return@forEachIndexed
            }

            val output = try {
                store.recoverOutput(index) ?: run {
                    val content = runner.convertFile(file, index + 1, directory)
                    currentCoroutineContext().ensureActive()
                    store.export(index, file, request.format, content, overwrite)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                store.recoverOutput(index) ?: run {
                    val message = error.message.orEmpty().ifBlank { string(R.string.error_unknown) }
                    store.writeFailure(index, message)
                    appendLog(string(R.string.task_file_failed_with_reason, "${file.fileName}：$message"))
                    return@forEachIndexed
                }
            }
            successCount++
            appendLog(string(R.string.speech_to_subtitle_log_saved, output.fileName))
            val prefix = string(R.string.task_file_progress_prefix, index + 1, request.files.size)
            updateProgress(string(R.string.speech_to_subtitle_progress_done, prefix), 100)
        }
        return successCount
    }

    private fun appendLog(message: String) {
        if (message.startsWith('\n')) log.appendRaw(message) else log.append(message)
    }

    private fun updateProgress(message: String, percent: Int) {
        val old = progress.getAndSet(Progress(message, percent.coerceIn(0, 100)))
        if (old.message != message) appendLog(message)
    }

    private suspend fun publishProgress() {
        var previousProgress: Progress? = null
        var previousLog: String? = null
        var revision = 0
        while (currentCoroutineContext().isActive) {
            val current = progress.get()
            val currentLog = log.text()
            val logChanged = currentLog != previousLog
            if (logChanged) {
                store.writeLog(currentLog)
                previousLog = currentLog
                revision++
            }
            if (previousProgress != current || logChanged) {
                setProgress(workDataOf(
                    KEY_MESSAGE to current.message.take(MAX_DATA_TEXT_CHARS),
                    KEY_PROGRESS to current.percent,
                    KEY_LOG_REVISION to revision,
                ))
            }
            if (previousProgress != current) {
                setForeground(createForegroundInfo(current))
                previousProgress = current
            }
            delay(PROGRESS_INTERVAL_MS)
        }
    }

    private fun removeReplacedTaskFiles() {
        inputData.getStringArray(KEY_REPLACED_WORK).orEmpty().forEach { previous ->
            val previousId = UUID.fromString(previous)
            SpeechToSubtitleTaskStore(applicationContext, previousId).delete()
            cacheDirectory(previousId).deleteRecursively()
        }
    }

    private fun cacheDirectory(workId: UUID): File = File(applicationContext.cacheDir, "speech-to-subtitle/$workId")

    private fun createForegroundInfo(current: Progress): ForegroundInfo {
        val title = string(R.string.speech_to_subtitle_title)
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, title, NotificationManager.IMPORTANCE_LOW))
        val openScreen = Intent(applicationContext, SpeechToSubtitleActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notificationId = id.hashCode().takeUnless { it == 0 } ?: 1
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(current.message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, current.percent, current.percent == 0)
            .setContentIntent(PendingIntent.getActivity(
                applicationContext, notificationId, openScreen,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                string(R.string.cancel_recognition),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notificationId, notification)
    }

    private fun string(id: Int, vararg args: Any): String = applicationContext.getString(id, *args)

    companion object {
        const val KEY_MESSAGE = "message"
        const val KEY_PROGRESS = "progress"
        const val KEY_SUMMARY = "summary"
        const val KEY_ERROR = "error"
        const val KEY_REPLACED_WORK = "replaced_work"
        private const val KEY_LOG_REVISION = "log_revision"
        private const val CHANNEL_ID = "speech-to-subtitle-tasks"
        private const val MAX_DATA_TEXT_CHARS = 500
        private const val PROGRESS_INTERVAL_MS = 250L
        private val executionMutex = Mutex()
    }
}
