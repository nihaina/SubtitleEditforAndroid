package com.subtitleedit.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.subtitleedit.PageTranslationSettingsActivity
import com.subtitleedit.R
import com.subtitleedit.translation.FoxletPageTranslation
import com.subtitleedit.translation.PageTranslationCatalog
import com.subtitleedit.translation.PageTranslationModel
import io.github.yinvoker.foxlet.DownloadStage
import io.github.yinvoker.foxlet.NetworkException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class PageTranslationDownloadWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        if (!FoxletPageTranslation.isSupported) return Result.failure()
        val modelId = inputData.getString(KEY_MODEL) ?: return Result.failure()
        return try {
            val model = FoxletPageTranslation.catalog(applicationContext).model(modelId) ?: return Result.failure()
            try {
                setForeground(downloadNotification())
            } catch (restricted: IllegalStateException) {
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "Foreground service unavailable; continuing scheduled download", restricted)
            }
            var lastStage: DownloadStage? = null
            var lastUpdate = 0L
            FoxletPageTranslation.download(applicationContext, model) { progress ->
                val now = SystemClock.elapsedRealtime()
                if (progress.stage != lastStage || now - lastUpdate >= 500L) {
                    lastStage = progress.stage
                    lastUpdate = now
                    setProgressAsync(workDataOf(
                        KEY_DOWNLOADED to progress.downloadedBytes,
                        KEY_TOTAL to progress.totalBytes,
                        KEY_VERIFYING to (progress.stage == DownloadStage.Verifying || progress.stage == DownloadStage.Ready),
                    ))
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Download failed for $modelId", error)
            if (error is NetworkException && shouldRetry(error)) Result.retry() else Result.failure()
        }
    }

    private fun shouldRetry(error: NetworkException): Boolean {
        if (runAttemptCount >= ModelDownloadRetryPolicy.MAX_ATTEMPTS - 1) return false
        val status = error.httpStatus ?: return true
        return status == 408 || status == 429 || status in 500..599
    }

    private fun downloadNotification(): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.page_translation_models), NotificationManager.IMPORTANCE_LOW,
        ))
        val openSettings = PendingIntent.getActivity(
            context, id.hashCode(), Intent(context, PageTranslationSettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.page_translation_models))
            .setContentText(context.getString(R.string.page_translation_model_downloading))
            .setContentIntent(openSettings)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(
                android.R.drawable.ic_delete, context.getString(R.string.cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(id),
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id.hashCode(), notification)
        }
    }

    companion object {
        private const val TAG = "PageTranslationDownload"
        private const val CHANNEL_ID = "page-translation-downloads"
        const val KEY_MODEL = "translation_model"
        const val KEY_DOWNLOADED = "downloaded_bytes"
        const val KEY_TOTAL = "total_bytes"
        const val KEY_VERIFYING = "verifying"
    }
}

internal class PageTranslationDownloadScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun enqueue(model: PageTranslationModel) = withContext(Dispatchers.IO) {
        val request = OneTimeWorkRequestBuilder<PageTranslationDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setInputData(workDataOf(PageTranslationDownloadWorker.KEY_MODEL to model.id))
            .addTag(workName(model))
            .build()
        workManager.enqueueUniqueWork(workName(model), ExistingWorkPolicy.KEEP, request).result.get()
        Unit
    }

    fun cancel(model: PageTranslationModel) {
        workManager.cancelUniqueWork(workName(model))
    }

    fun observe(catalog: PageTranslationCatalog): Flow<Map<PageTranslationModel, WorkInfo>> {
        val tagged = workManager.getWorkInfosByTagFlow(PageTranslationDownloadWorker::class.java.name)
        val legacy = combine(LEGACY_MODEL_IDS.map { modelId ->
            val name = WORK_NAME_PREFIX + modelId
            workManager.getWorkInfosForUniqueWorkFlow(name).map { jobs ->
                val model = catalog.model(modelId)
                val work = jobs.firstOrNull { name !in it.tags }
                if (model != null && work != null) model to work else null
            }
        }) { entries -> entries.filterNotNull().toMap() }
        return combine(legacy, tagged) { oldWork, jobs ->
            buildMap {
                putAll(oldWork)
                for (work in jobs) {
                    val tag = work.tags.firstOrNull { it.startsWith(WORK_NAME_PREFIX) } ?: continue
                    val model = catalog.model(tag.removePrefix(WORK_NAME_PREFIX)) ?: continue
                    if (get(model)?.state?.isFinished != false || !work.state.isFinished) put(model, work)
                }
            }
        }
    }

    private fun workName(model: PageTranslationModel) = WORK_NAME_PREFIX + model.id

    private companion object {
        const val WORK_NAME_PREFIX = "page-translation-download-"
        val LEGACY_MODEL_IDS = listOf("ja-en", "en-zh-Hans", "zh-Hans-en", "en-ja")
    }
}
