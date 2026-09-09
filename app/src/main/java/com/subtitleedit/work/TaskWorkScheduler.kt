package com.subtitleedit.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.subtitleedit.task.TaskProgress
import com.subtitleedit.task.TaskState
import com.subtitleedit.task.TaskStatus
import com.subtitleedit.task.TaskStateStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class TaskWorkScheduler(
    context: Context,
    private val taskStateStore: TaskStateStore
) {
    private val workManager = WorkManager.getInstance(context)
    private val enqueueMutex = Mutex()

    suspend fun enqueueGeneralModelDownload(): UUID = enqueueMutex.withLock {
        withContext(Dispatchers.IO) {
            findActiveModelDownload()?.let { return@withContext it }
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(ModelDownloadWorker.KEY_MODEL_KIND, ModelDownloadWorker.KIND_DEMIX_GENERAL)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10L,
                    TimeUnit.SECONDS
                )
                .addTag(ModelDownloadWorker.TAG_MODEL_DOWNLOAD)
                .build()
            workManager.enqueueUniqueWork(
                GENERAL_MODEL_WORK, ExistingWorkPolicy.KEEP, request
            ).result.get()
            request.id
        }
    }

    fun observeTask(workId: UUID): Flow<TaskState?> =
        workManager.getWorkInfoByIdFlow(workId).map { workInfo ->
            workInfo?.toTaskState()?.also(taskStateStore::updateState)
        }

    suspend fun findActiveModelDownload(preferredId: UUID? = null): UUID? = withContext(Dispatchers.IO) {
        if (preferredId != null) {
            workManager.getWorkInfoById(preferredId).get()?.let { return@withContext it.id }
        }
        workManager.getWorkInfosForUniqueWork(GENERAL_MODEL_WORK)
            .get()
            .firstOrNull { !it.state.isFinished }
            ?.id
    }

    suspend fun cancel(workId: UUID) = withContext(Dispatchers.IO) {
        workManager.cancelWorkById(workId).result.get()
        Unit
    }

    fun taskState(taskId: String) = taskStateStore.states
        .value[taskId]

    private fun WorkInfo.toTaskState(): TaskState {
        val data = if (state.isFinished) outputData else progress
        return TaskState(
            id = id.toString(),
            type = ModelDownloadWorker.KIND_DEMIX_GENERAL,
            status = when (state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> TaskStatus.QUEUED
                WorkInfo.State.RUNNING -> TaskStatus.RUNNING
                WorkInfo.State.SUCCEEDED -> TaskStatus.SUCCEEDED
                WorkInfo.State.FAILED -> TaskStatus.FAILED
                WorkInfo.State.CANCELLED -> TaskStatus.CANCELLED
            },
            progress = if (state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED) {
                TaskProgress(
                    message = if (runAttemptCount > 0) {
                        "等待网络连接或自动重试（已尝试 $runAttemptCount 次）"
                    } else {
                        "等待网络连接或任务调度"
                    }
                )
            } else TaskProgress(
                message = data.getString(ModelDownloadWorker.KEY_MESSAGE).orEmpty(),
                current = data.getLong(ModelDownloadWorker.KEY_CURRENT, 0L),
                total = data.getLong(ModelDownloadWorker.KEY_TOTAL, -1L)
            ),
            errorMessage = outputData.getString(ModelDownloadWorker.KEY_ERROR),
            outputPath = outputData.getString(ModelDownloadWorker.KEY_MODEL_FILE)
        )
    }

    companion object {
        private const val GENERAL_MODEL_WORK = "download-demix-general"
        fun progressData(message: String, current: Long, total: Long): Data = Data.Builder()
            .putString(ModelDownloadWorker.KEY_MESSAGE, message)
            .putLong(ModelDownloadWorker.KEY_CURRENT, current)
            .putLong(ModelDownloadWorker.KEY_TOTAL, total)
            .build()
    }
}
