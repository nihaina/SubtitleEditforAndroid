package com.subtitleedit.work

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.subtitleedit.SubtitleEditApplication
import com.subtitleedit.VocalSeparationSettingsActivity
import com.subtitleedit.task.TaskProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(
        "正在准备模型下载"
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val application = applicationContext as? SubtitleEditApplication
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "应用依赖未初始化"))
        var lastProgress = TaskProgress()
        try {
            require(inputData.getString(KEY_MODEL_KIND) == KIND_DEMIX_GENERAL) {
                "不支持的模型任务"
            }
            setForeground(getForegroundInfo())
            setProgress(TaskWorkScheduler.progressData("正在准备模型下载", 0L, -1L))
            val workerContext = currentCoroutineContext()
            var lastProgressAt = 0L
            val modelFile = application.dependencies.downloadGeneralModel { progress ->
                workerContext.ensureActive()
                val now = SystemClock.elapsedRealtime()
                val messageChanged = lastProgress.message != progress.message
                lastProgress = TaskProgress(
                    progress.message, progress.downloadedBytes, progress.totalBytes
                )
                if (messageChanged || now - lastProgressAt >= 500L) {
                    lastProgressAt = now
                    setProgressAsync(TaskWorkScheduler.progressData(
                        lastProgress.message, lastProgress.current, lastProgress.total
                    ))
                }
            }
            Result.success(
                workDataOf(
                    KEY_MODEL_FILE to modelFile.absolutePath,
                    KEY_MESSAGE to "下载完成",
                    KEY_CURRENT to lastProgress.current,
                    KEY_TOTAL to lastProgress.total
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (ModelDownloadRetryPolicy.shouldRetry(error, runAttemptCount)) {
                return@withContext Result.retry()
            }
            Result.failure(workDataOf(
                KEY_ERROR to (error.message ?: "模型任务失败").take(500),
                KEY_MESSAGE to lastProgress.message,
                KEY_CURRENT to lastProgress.current,
                KEY_TOTAL to lastProgress.total
            ))
        }
    }

    private fun createForegroundInfo(message: String): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "模型任务",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Subtitle Edit")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(
                applicationContext,
                id.hashCode(),
                Intent(applicationContext, VocalSeparationSettingsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .addAction(
                android.R.drawable.ic_delete,
                "取消下载",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
            )
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id.hashCode(), notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "model-tasks"
        const val TAG_MODEL_DOWNLOAD = "model-download"
        const val KIND_DEMIX_GENERAL = "demix-general"
        const val KEY_MODEL_KIND = "model_kind"
        const val KEY_MESSAGE = "message"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_MODEL_FILE = "model_file"
        const val KEY_ERROR = "error"
    }
}
