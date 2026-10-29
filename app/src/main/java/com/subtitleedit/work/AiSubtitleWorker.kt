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
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.subtitleedit.AutoTranslateActivity
import com.subtitleedit.AutoTranslateQueueStore
import com.subtitleedit.AutoTranslateViewModel
import com.subtitleedit.SubtitleEditApplication
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class AiSubtitleWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo("正在处理 AI 字幕")

    override suspend fun doWork(): Result {
        val application = applicationContext as? SubtitleEditApplication
            ?: return Result.failure()
        // A worker restart must be able to continue the file that was persisted as RUNNING.
        // The Activity ViewModel uses the default and presents such a file as paused after exit.
        val viewModel = AutoTranslateViewModel(application, restoreRunningAsPaused = false)
        setForeground(getForegroundInfo())
        val targetSessionId = inputData.getString(KEY_TARGET_SESSION_ID)
        return coroutineScope {
            val progressReporter = launch {
                reportProgress()
            }
            try {
                viewModel.runPersistedQueueForWork(targetSessionId)
                Result.success()
            } catch (error: kotlinx.coroutines.CancellationException) {
                viewModel.markPersistedWorkInterrupted(targetSessionId)
                throw error
            } catch (_: Exception) {
                viewModel.markPersistedWorkInterrupted(targetSessionId)
                Result.failure()
            } finally {
                progressReporter.cancel()
            }
        }
    }

    private suspend fun reportProgress() {
        val store = AutoTranslateQueueStore(applicationContext)
        while (currentCoroutineContext().isActive) {
            val snapshot = store.load()
            val active = snapshot?.files?.firstOrNull { it.status == "RUNNING" }
            val message = active?.message?.takeIf(String::isNotBlank) ?: "正在处理 AI 字幕"
            val current = active?.processedLines ?: 0
            val total = active?.totalLines ?: -1
            setProgress(workDataOf(
                KEY_MESSAGE to message,
                KEY_CURRENT to current.toLong(),
                KEY_TOTAL to total.toLong()
            ))
            setForegroundAsync(createForegroundInfo(message, current, total))
            delay(PROGRESS_POLL_INTERVAL_MS)
        }
    }

    private fun createForegroundInfo(message: String, current: Int = 0, total: Int = -1): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI 字幕任务", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("TsumugiSub")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(
                100,
                if (total > 0) ((current * 100L) / total).coerceIn(0L, 100L).toInt() else 0,
                total <= 0
            )
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(
                PendingIntent.getActivity(
                    applicationContext,
                    id.hashCode(),
                    Intent(applicationContext, AutoTranslateActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "暂停",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id.hashCode(), notification)
        }
    }

    companion object {
        const val TAG_AI_SUBTITLE = "ai-subtitle"
        const val KEY_TARGET_SESSION_ID = "target_session_id"
        private const val KEY_MESSAGE = "message"
        private const val KEY_CURRENT = "current"
        private const val KEY_TOTAL = "total"
        private const val PROGRESS_POLL_INTERVAL_MS = 250L
        private const val CHANNEL_ID = "ai-subtitle-tasks"
    }
}
