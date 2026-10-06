package com.subtitleedit

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import com.subtitleedit.feature.ui.LlmModelImportAction
import com.subtitleedit.feature.ui.LlmModelFamily
import com.subtitleedit.feature.ui.LlmModelImportUiState
import com.subtitleedit.task.TaskStatus
import com.subtitleedit.util.ModelDownloader
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.UriDisplayName
import com.subtitleedit.work.ModelDownloadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** LLM file selection and download UI; inference is handled by the chat/translation backend. */
internal class LlmModelImportController(
    private val host: ModelImportHost,
    private val savedState: SavedStateHandle,
    private val onModelsChanged: () -> Unit = {}
) : ModelImportDialogState(host) {
    private val _state = MutableStateFlow(LlmModelImportUiState())
    val state: StateFlow<LlmModelImportUiState> = _state.asStateFlow()

    private val context: Context get() = host.app
    private val settings = SettingsManager.getInstance(host.app)
    private var downloadJob: Job? = null
    private var pendingStorageOption: String?
        get() = savedState[KEY_PENDING_STORAGE_OPTION]
        set(value) { savedState[KEY_PENDING_STORAGE_OPTION] = value }
    private var pendingNotificationOption: String?
        get() = savedState[KEY_PENDING_NOTIFICATION_OPTION]
        set(value) { savedState[KEY_PENDING_NOTIFICATION_OPTION] = value }
    private val notificationPermissionPreferences by lazy {
        context.getSharedPreferences("task_notifications", Context.MODE_PRIVATE)
    }
    private var workId: UUID?
        get() = savedState.get<String>(KEY_WORK_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        set(value) { savedState[KEY_WORK_ID] = value?.toString() }
    private var workOptionId: String?
        get() = savedState[KEY_WORK_OPTION]
        set(value) { savedState[KEY_WORK_OPTION] = value }

    init {
        refresh()
        observeLlmModelDownload(enqueue = false)
    }

    fun refresh() {
        val path = settings.getLlmModelPath()
        val uri = path.takeIf(String::isNotBlank)?.let(Uri::parse)
        val familyId = settings.getLlmModelFamily()
        val family = familyFromId(familyId)
        val detectedFamily = settings.inferLlmModelFamily(path)
        val modelMatchesFamily = familyId == SettingsManager.LLM_MODEL_CUSTOM ||
            detectedFamily == null || detectedFamily == familyId
        _state.value = _state.value.copy(
            modelValue = uri?.let(::displayName).orEmpty(),
            hasModel = uri != null,
            modelFamily = family,
            showModelDownload = uri == null || !modelMatchesFamily,
            showModelReset = uri != null
        )
    }

    fun onDocumentPicked(target: ModelPickTarget, uri: Uri) {
        if (target != ModelPickTarget.LLM_MODEL) return
        val name = displayName(uri)
        if (!name.endsWith(".gguf", ignoreCase = true)) {
            host.showToast(R.string.model_mgmt_llm_pick_gguf, duration = Toast.LENGTH_LONG)
            return
        }
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                check(descriptor.statSize != 0L) { host.text(R.string.model_mgmt_llm_model_empty) }
            } ?: error(host.text(R.string.model_mgmt_llm_model_unreadable))
            settings.setLlmModelPath(uri.toString())
            refresh()
            host.showToast(R.string.model_mgmt_llm_model_selected)
        } catch (error: Exception) {
            host.showToast(
                R.string.model_mgmt_select_file_failed,
                error.message ?: host.text(R.string.model_mgmt_llm_model_unreadable),
                duration = Toast.LENGTH_LONG
            )
        }
    }

    fun onAction(action: LlmModelImportAction) {
        when (action) {
            LlmModelImportAction.SelectModelType -> showLlmModelPicker()
            LlmModelImportAction.SelectModel -> host.sendEvent(
                ModelManagementEvent.PickDocument(ModelPickTarget.LLM_MODEL)
            )
            LlmModelImportAction.DownloadModel -> showDownloadOptions()
            LlmModelImportAction.ResetModel -> confirmResetModel()
            LlmModelImportAction.Configure -> host.sendEvent(ModelManagementEvent.OpenLlmSettings)
            LlmModelImportAction.ShowGuide -> showModelGuide()
        }
    }

    private fun showModelGuide() {
        val messageRes = when (settings.getLlmModelFamily()) {
            SettingsManager.LLM_MODEL_INDEX_TRANSLATE -> R.string.model_mgmt_llm_help_index_translate
            SettingsManager.LLM_MODEL_GEMMA4 -> R.string.model_mgmt_llm_help_gemma4
            else -> R.string.model_mgmt_llm_help_custom
        }
        showMessageDialog(
            title = host.text(R.string.model_mgmt_llm_help_title),
            message = host.text(messageRes),
            confirmLabel = host.text(R.string.model_mgmt_ok)
        )
    }

    fun onStorageAccessResult() {
        val optionId = pendingStorageOption ?: return
        pendingStorageOption = null
        if (hasModelStorageAccess(context)) startModelDownload(optionId)
        else host.showToast(R.string.model_mgmt_storage_required_for_download, duration = Toast.LENGTH_LONG)
    }

    fun onStorageAccessUnavailable() {
        pendingStorageOption = null
        host.showToast(R.string.model_mgmt_storage_settings_unavailable, duration = Toast.LENGTH_LONG)
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        val optionId = pendingNotificationOption ?: return
        pendingNotificationOption = null
        if (!granted) host.showToast(R.string.model_mgmt_notification_denied, duration = Toast.LENGTH_LONG)
        observeLlmModelDownload(optionId = optionId, enqueue = true)
    }

    private fun showDownloadOptions() {
        if (downloadJob?.isActive == true) return
        if (settings.getLlmModelFamily() == SettingsManager.LLM_MODEL_CUSTOM) {
            showCustomModelOptions()
            return
        }
        val options = currentModelOptions()
        if (options.isEmpty()) return
        val labels = options.map { option ->
            host.text(
                R.string.model_mgmt_option_with_size,
                option.displayName,
                option.sizeLabel
            )
        }
        showOptionsDialog(host.text(R.string.model_mgmt_llm_download_title), labels) { index ->
            options.getOrNull(index)?.let(::confirmDownload)
        }
    }

    private fun showCustomModelOptions() {
        val directory = File(ModelDownloader.modelsDirectory(), ModelDownloader.LLM_DIRECTORY_NAME)
        val files = directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) && it.length() > 0L }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        if (files.isEmpty()) {
            showMessageDialog(
                title = host.text(R.string.model_mgmt_llm_custom_select_title),
                message = host.text(
                    R.string.model_mgmt_llm_custom_empty,
                    directory.absolutePath
                ),
                confirmLabel = host.text(R.string.model_mgmt_ok),
                dismissLabel = host.text(R.string.cancel),
                auxiliaryLabel = host.text(R.string.model_mgmt_llm_select_file),
                onAuxiliary = {
                    host.sendEvent(ModelManagementEvent.PickDocument(ModelPickTarget.LLM_MODEL))
                }
            )
            return
        }
        val labels = files.map { file ->
            host.text(
                R.string.model_mgmt_option_with_size,
                file.name,
                formatModelSize(file.length())
            )
        }
        showOptionsDialog(
            host.text(R.string.model_mgmt_llm_custom_select_title),
            labels
        ) { index -> files.getOrNull(index)?.let(::importCustomModel) }
    }

    private fun importCustomModel(file: File) {
        if (!file.isFile || file.length() <= 0L) {
            showMessageDialog(
                title = host.text(R.string.model_mgmt_llm_custom_select_title),
                message = host.text(R.string.model_mgmt_llm_custom_file_unavailable),
                confirmLabel = host.text(R.string.model_mgmt_ok)
            )
            return
        }
        settings.setLlmModelFamily(SettingsManager.LLM_MODEL_CUSTOM)
        settings.setLlmModelPath(Uri.fromFile(file).toString())
        refresh()
        onModelsChanged()
        host.showToast(R.string.model_mgmt_llm_model_selected)
    }

    private fun showLlmModelPicker() {
        val families = LlmModelFamily.entries
        val labels = families.map { host.text(it.labelRes) }
        val selected = families.indexOf(_state.value.modelFamily).coerceAtLeast(0)
        showOptionsDialog(
            host.text(R.string.model_mgmt_llm_select_type_title),
            labels,
            selectedIndex = selected
        ) { index ->
            families.getOrNull(index)?.let { family ->
                if (family != _state.value.modelFamily) {
                    settings.setLlmModelFamily(family.toSettingsId())
                    refresh()
                }
            }
        }
    }

    private fun currentModelOptions(): List<ModelDownloader.LlmModelOption> {
        val familyId = settings.getLlmModelFamily()
        return ModelDownloader.LLM_MODELS.filter { it.familyId == familyId }
    }

    private fun familyFromId(id: String): LlmModelFamily = when (id) {
        SettingsManager.LLM_MODEL_GEMMA4 -> LlmModelFamily.GEMMA4
        SettingsManager.LLM_MODEL_CUSTOM -> LlmModelFamily.CUSTOM
        else -> LlmModelFamily.INDEX_TRANSLATE
    }

    private fun LlmModelFamily.toSettingsId(): String = when (this) {
        LlmModelFamily.GEMMA4 -> SettingsManager.LLM_MODEL_GEMMA4
        LlmModelFamily.CUSTOM -> SettingsManager.LLM_MODEL_CUSTOM
        LlmModelFamily.INDEX_TRANSLATE -> SettingsManager.LLM_MODEL_INDEX_TRANSLATE
    }

    private fun confirmDownload(option: ModelDownloader.LlmModelOption) {
        val directory = "${ModelDownloader.modelsDirectory().path}/${ModelDownloader.LLM_DIRECTORY_NAME}"
        showMessageDialog(
            title = host.text(R.string.model_mgmt_llm_download_title),
            message = host.text(
                R.string.model_mgmt_llm_download_message,
                option.displayName,
                directory,
                option.sizeLabel
            ),
            confirmLabel = host.text(R.string.model_mgmt_download_and_import),
            dismissLabel = host.text(R.string.cancel),
            onConfirm = { startModelDownload(option.id) }
        )
    }

    private fun startModelDownload(optionId: String) {
        if (downloadJob?.isActive == true) return
        if (!hasModelStorageAccess(context)) {
            pendingStorageOption = optionId
            host.requestStorageAccess(StorageAccessRequester.LLM_IMPORT)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionPreferences.getBoolean("requested", false)
        ) {
            pendingNotificationOption = optionId
            host.sendEvent(ModelManagementEvent.RequestNotificationPermission)
            return
        }
        observeLlmModelDownload(optionId = optionId, enqueue = true)
    }

    private fun observeLlmModelDownload(
        optionId: String? = workOptionId,
        enqueue: Boolean,
        retryWorkId: UUID? = null
    ) {
        if (downloadJob?.isActive == true) return
        if (!enqueue && optionId.isNullOrBlank()) return
        _state.value = _state.value.copy(actionsEnabled = false)
        downloadJob = host.scope.launch {
            var progressToken: Long? = null
            try {
                val scheduler = (host.app as SubtitleEditApplication).dependencies.taskWorkScheduler
                val resolvedOption = optionId ?: workOptionId
                    ?: error(host.text(R.string.model_mgmt_task_missing))
                workOptionId = resolvedOption
                val taskId = when {
                    retryWorkId != null -> scheduler.retryModelDownload(retryWorkId)
                    enqueue -> scheduler.enqueueLlmModelDownload(resolvedOption)
                    else -> scheduler.findActiveLlmModelDownload(resolvedOption, workId) ?: return@launch
                }
                workId = taskId
                progressToken = showProgressDialog(host.text(R.string.model_mgmt_llm_download_title)) {
                    host.scope.launch {
                        try {
                            scheduler.cancel(taskId)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            host.showToast(
                                R.string.model_mgmt_cancel_download_failed,
                                error.message ?: host.text(R.string.model_mgmt_task_failed),
                                duration = Toast.LENGTH_LONG
                            )
                        }
                    }
                }
                scheduler.observeTask(taskId).takeWhile { taskState ->
                    if (taskState == null) {
                        workId = null
                        throw IllegalStateException(host.text(R.string.model_mgmt_task_missing))
                    }
                    taskState.progress.message.takeIf(String::isNotBlank)?.let { message ->
                        updateProgressDialog(
                            requireNotNull(progressToken),
                            ModelDownloader.Progress(message, taskState.progress.current, taskState.progress.total)
                        )
                    }
                    when (taskState.status) {
                        TaskStatus.SUCCEEDED -> {
                            workId = null
                            workOptionId = null
                            refresh()
                            onModelsChanged()
                            host.showToast(R.string.model_mgmt_llm_downloaded, duration = Toast.LENGTH_LONG)
                            false
                        }
                        TaskStatus.FAILED -> {
                            showDownloadFailure(taskId, resolvedOption, taskState.errorMessage)
                            false
                        }
                        TaskStatus.CANCELLED -> {
                            workId = null
                            workOptionId = null
                            false
                        }
                        else -> true
                    }
                }.collect { }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                host.showToast(
                    R.string.model_mgmt_task_failed_with_reason,
                    error.message ?: host.text(R.string.model_mgmt_task_failed),
                    duration = Toast.LENGTH_LONG
                )
            } finally {
                progressToken?.let(::dismissProgressDialog)
                downloadJob = null
                _state.value = _state.value.copy(actionsEnabled = true)
            }
        }
    }

    private fun showDownloadFailure(workId: UUID, optionId: String, error: String?) {
        showMessageDialog(
            title = host.text(R.string.model_mgmt_llm_download_failed),
            message = host.text(
                R.string.model_mgmt_download_retry_hint,
                error ?: host.text(R.string.model_mgmt_task_failed)
            ),
            confirmLabel = host.text(R.string.model_mgmt_retry),
            dismissLabel = host.text(R.string.model_mgmt_close),
            onConfirm = { startRetry(workId, optionId) },
            onDismiss = {
                this.workId = null
                workOptionId = null
            }
        )
    }

    /** Called from ViewModel.onCleared(): stop observers that capture the Activity host. */
    fun dispose() {
        downloadJob?.cancel()
        downloadJob = null
        setDialog(null)
        pendingStorageOption = null
        pendingNotificationOption = null
    }

    private fun startRetry(workId: UUID, optionId: String) {
        if (!hasModelStorageAccess(context)) {
            pendingStorageOption = optionId
            host.requestStorageAccess(StorageAccessRequester.LLM_IMPORT)
            return
        }
        observeLlmModelDownload(optionId = optionId, enqueue = false, retryWorkId = workId)
    }

    private fun confirmResetModel() {
        showMessageDialog(
            title = host.text(R.string.model_mgmt_reset_selection_title),
            message = host.text(R.string.model_mgmt_llm_reset_message),
            confirmLabel = host.text(R.string.model_mgmt_reset),
            dismissLabel = host.text(R.string.cancel),
            onConfirm = {
                val oldUri = settings.getLlmModelPath().takeIf(String::isNotBlank)?.let(Uri::parse)
                settings.clearLlmModelPath()
                oldUri?.takeIf { it.scheme == "content" }?.let { uri ->
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
                refresh()
                host.showToast(R.string.model_mgmt_llm_reset_done)
            }
        )
    }

    private fun displayName(uri: Uri): String = UriDisplayName.of(
        context,
        uri,
        uri.lastPathSegment?.substringAfterLast('/') ?: host.text(R.string.model_mgmt_unknown_file)
    )

    private companion object {
        const val KEY_WORK_ID = "llm_model_download_work_id"
        const val KEY_WORK_OPTION = "llm_model_download_option"
        const val KEY_PENDING_STORAGE_OPTION = "llm_model_pending_storage_option"
        const val KEY_PENDING_NOTIFICATION_OPTION = "llm_model_pending_notification_option"
    }
}
