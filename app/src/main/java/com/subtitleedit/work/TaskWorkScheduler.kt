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
import com.subtitleedit.usecase.DownloadAsrModelUseCase
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

    suspend fun enqueueGeneralModelDownload(): UUID = enqueueModelDownload(
        GENERAL_MODEL_WORK, ModelDownloadWorker.KIND_DEMIX_GENERAL
    )

    suspend fun enqueueAsrModelDownload(kind: String, optionId: String): UUID {
        require(kind in DownloadAsrModelUseCase.KINDS) { "不支持的语音识别模型任务" }
        require(optionId.isNotBlank()) { "未指定模型版本" }
        return enqueueModelDownload(ASR_MODEL_WORK, kind, optionId)
    }

    suspend fun enqueueQwen3ForcedAlignerDownload(): UUID = enqueueModelDownload(
        ASR_MODEL_WORK, ModelDownloadWorker.KIND_QWEN3_FORCED_ALIGNER
    )

    suspend fun retryModelDownload(workId: UUID): UUID {
        val previous = withContext(Dispatchers.IO) { workManager.getWorkInfoById(workId).get() }
        requireNotNull(previous) { "下载任务不存在，请重新选择模型下载" }
        require(ModelDownloadWorker.TAG_MODEL_DOWNLOAD in previous.tags) { "不是模型下载任务" }
        if (!previous.state.isFinished) return workId
        require(previous.state == WorkInfo.State.FAILED || previous.state == WorkInfo.State.CANCELLED) {
            "下载任务已完成"
        }
        val kind = previous.outputData.getString(ModelDownloadWorker.KEY_MODEL_KIND)
            ?: previous.tags.firstOrNull { it.startsWith(MODEL_KIND_TAG_PREFIX) }?.removePrefix(MODEL_KIND_TAG_PREFIX)
            ?: ModelDownloadWorker.KIND_DEMIX_GENERAL
        if (kind == ModelDownloadWorker.KIND_DEMIX_GENERAL) return enqueueGeneralModelDownload()
        if (kind == ModelDownloadWorker.KIND_QWEN3_FORCED_ALIGNER) return enqueueQwen3ForcedAlignerDownload()
        val optionId = previous.outputData.getString(ModelDownloadWorker.KEY_MODEL_OPTION)
            ?: previous.tags.firstOrNull { it.startsWith(MODEL_OPTION_TAG_PREFIX) }?.removePrefix(MODEL_OPTION_TAG_PREFIX)
        requireNotNull(optionId) { "下载任务缺少模型版本，请重新选择模型下载" }
        return enqueueAsrModelDownload(kind, optionId)
    }

    private suspend fun enqueueModelDownload(
        uniqueName: String,
        kind: String,
        optionId: String? = null
    ): UUID = enqueueMutex.withLock {
        withContext(Dispatchers.IO) {
            findModelDownload(uniqueName)?.let { return@withContext it }
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(ModelDownloadWorker.KEY_MODEL_KIND, kind)
                        .putString(ModelDownloadWorker.KEY_MODEL_OPTION, optionId)
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
                .addTag(uniqueName)
                .addTag(MODEL_KIND_TAG_PREFIX + kind)
                .apply { optionId?.let { addTag(MODEL_OPTION_TAG_PREFIX + it) } }
                .build()
            workManager.enqueueUniqueWork(
                uniqueName, ExistingWorkPolicy.KEEP, request
            ).result.get()
            request.id
        }
    }

    fun observeTask(workId: UUID): Flow<TaskState?> =
        workManager.getWorkInfoByIdFlow(workId).map { workInfo ->
            workInfo?.toTaskState()?.also(taskStateStore::updateState)
        }

    suspend fun findActiveModelDownload(preferredId: UUID? = null): UUID? =
        findModelDownload(GENERAL_MODEL_WORK, preferredId)

    suspend fun findActiveAsrModelDownload(preferredId: UUID? = null): UUID? =
        findModelDownload(ASR_MODEL_WORK, preferredId)

    private suspend fun findModelDownload(uniqueName: String, preferredId: UUID? = null): UUID? =
        withContext(Dispatchers.IO) {
            val workInfos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
            // A previously observed task may have finished while its Activity was being recreated.
            if (preferredId != null) {
                workInfos.firstOrNull { it.id == preferredId }?.let { return@withContext it.id }
            }
            workInfos.firstOrNull { !it.state.isFinished }?.id
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
            type = tags.firstOrNull { it.startsWith(MODEL_KIND_TAG_PREFIX) }
                ?.removePrefix(MODEL_KIND_TAG_PREFIX)
                ?: ModelDownloadWorker.KIND_DEMIX_GENERAL,
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
        private const val ASR_MODEL_WORK = "download-asr-model"
        private const val MODEL_KIND_TAG_PREFIX = "model-kind:"
        private const val MODEL_OPTION_TAG_PREFIX = "model-option:"
        fun progressData(message: String, current: Long, total: Long): Data = Data.Builder()
            .putString(ModelDownloadWorker.KEY_MESSAGE, message)
            .putLong(ModelDownloadWorker.KEY_CURRENT, current)
            .putLong(ModelDownloadWorker.KEY_TOTAL, total)
            .build()
    }
}
