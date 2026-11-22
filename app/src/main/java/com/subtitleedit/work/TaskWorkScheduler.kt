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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class TaskWorkScheduler(
    context: Context,
    private val taskStateStore: TaskStateStore,
    private val canImportLocally: (String, String?) -> Boolean,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(context)
    private val enqueueMutex = Mutex()
    private val speechEnqueueMutex = Mutex()

    suspend fun enqueueGeneralModelDownload(): UUID = enqueueModelDownload(
        GENERAL_MODEL_WORK, ModelDownloadWorker.KIND_DEMIX_GENERAL
    )

    suspend fun enqueueAsrModelDownload(kind: String, optionId: String): UUID {
        require(kind in DownloadAsrModelUseCase.KINDS) { "不支持的语音识别模型任务" }
        require(optionId.isNotBlank()) { "未指定模型版本" }
        return enqueueModelDownload(ASR_MODEL_WORK, kind, optionId)
    }

    suspend fun enqueueLlmModelDownload(optionId: String): UUID {
        require(optionId.isNotBlank()) { "未指定 LLM 模型版本" }
        return enqueueModelDownload("$LLM_MODEL_WORK_PREFIX-$optionId", ModelDownloadWorker.KIND_LLM, optionId)
    }

    suspend fun enqueueQwen3ForcedAlignerDownload(): UUID = enqueueModelDownload(
        ASR_MODEL_WORK, ModelDownloadWorker.KIND_QWEN3_FORCED_ALIGNER
    )

    suspend fun enqueueAiSubtitleProcessing(targetSessionId: String? = null): UUID =
        withContext(Dispatchers.IO) {
            val request = OneTimeWorkRequestBuilder<AiSubtitleWorker>()
                .setInputData(
                    Data.Builder().apply {
                        targetSessionId?.let { putString(AiSubtitleWorker.KEY_TARGET_SESSION_ID, it) }
                    }.build()
                )
                .addTag(AiSubtitleWorker.TAG_AI_SUBTITLE)
                .build()
            workManager.enqueueUniqueWork(
                AI_SUBTITLE_WORK,
                // Keep a start requested during worker shutdown; KEEP would discard it.
                if (targetSessionId == null) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
                request
            ).result.get()
            request.id
        }

    fun cancelAiSubtitleProcessing() {
        workManager.cancelUniqueWork(AI_SUBTITLE_WORK)
    }

    suspend fun enqueueSpeechToSubtitle(task: SpeechToSubtitleRequest): UUID = speechEnqueueMutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            val previous = workManager.getWorkInfosForUniqueWork(SPEECH_TO_SUBTITLE_WORK).get()
            previous.firstOrNull { !it.state.isFinished }?.let { return@withContext it.id }
            val request = OneTimeWorkRequestBuilder<SpeechToSubtitleWorker>()
                .setInputData(Data.Builder()
                    .putStringArray(SpeechToSubtitleWorker.KEY_REPLACED_WORK, previous.map { it.id.toString() }.toTypedArray())
                    .build())
                .build()
            val store = SpeechToSubtitleTaskStore(appContext, request.id)
            try {
                store.writeRequest(task)
                workManager.enqueueUniqueWork(SPEECH_TO_SUBTITLE_WORK, ExistingWorkPolicy.KEEP, request).result.get()
                request.id
            } catch (error: Exception) {
                store.delete()
                throw error
            }
        }
    }

    suspend fun findSpeechToSubtitleWork(preferredId: UUID? = null): UUID? = withContext(Dispatchers.IO) {
        val workInfos = workManager.getWorkInfosForUniqueWork(SPEECH_TO_SUBTITLE_WORK).get()
        workInfos.firstOrNull { !it.state.isFinished }?.id
            ?: workInfos.firstOrNull { it.id == preferredId }?.id
            ?: workInfos.firstOrNull { !SpeechToSubtitleTaskStore(appContext, it.id).isAcknowledged() }?.id
    }

    fun observeSpeechToSubtitle(workId: UUID): Flow<WorkInfo?> = workManager.getWorkInfoByIdFlow(workId)

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
        if (kind == ModelDownloadWorker.KIND_LLM) return enqueueLlmModelDownload(optionId)
        return enqueueAsrModelDownload(kind, optionId)
    }

    private suspend fun enqueueModelDownload(
        uniqueName: String,
        kind: String,
        optionId: String? = null
    ): UUID = enqueueMutex.withLock {
        withContext(Dispatchers.IO) {
            val local = canImportLocally(kind, optionId)
            val active = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                .firstOrNull { !it.state.isFinished }
            val replaceWaitingDownload = active != null && local &&
                active.state == WorkInfo.State.ENQUEUED && active.constraints.requiredNetworkType == NetworkType.CONNECTED &&
                MODEL_KIND_TAG_PREFIX + kind in active.tags &&
                active.tags.firstOrNull { it.startsWith(MODEL_OPTION_TAG_PREFIX) }?.removePrefix(MODEL_OPTION_TAG_PREFIX) == optionId
            if (active != null && !replaceWaitingDownload) return@withContext active.id
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(ModelDownloadWorker.KEY_MODEL_KIND, kind)
                        .putString(ModelDownloadWorker.KEY_MODEL_OPTION, optionId)
                        .putBoolean(ModelDownloadWorker.KEY_LOCAL_ONLY, local)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (local) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED)
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
                uniqueName, if (replaceWaitingDownload) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request
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

    suspend fun findActiveLlmModelDownload(optionId: String, preferredId: UUID? = null): UUID? =
        findModelDownload("$LLM_MODEL_WORK_PREFIX-$optionId", preferredId)

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
                    message = when {
                        constraints.requiredNetworkType == NetworkType.NOT_REQUIRED -> "等待本地模型导入任务调度"
                        runAttemptCount > 0 -> "等待网络连接或自动重试（已尝试 $runAttemptCount 次）"
                        else -> "等待网络连接或任务调度"
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
        private const val LLM_MODEL_WORK_PREFIX = "download-llm-model"
        private const val AI_SUBTITLE_WORK = "ai-subtitle-processing"
        private const val SPEECH_TO_SUBTITLE_WORK = "speech-to-subtitle"
        private const val MODEL_KIND_TAG_PREFIX = "model-kind:"
        private const val MODEL_OPTION_TAG_PREFIX = "model-option:"
        fun progressData(message: String, current: Long, total: Long): Data = Data.Builder()
            .putString(ModelDownloadWorker.KEY_MESSAGE, message)
            .putLong(ModelDownloadWorker.KEY_CURRENT, current)
            .putLong(ModelDownloadWorker.KEY_TOTAL, total)
            .build()
    }
}
