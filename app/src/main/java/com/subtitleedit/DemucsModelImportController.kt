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
import com.subtitleedit.demix.VocalSeparationEngine
import com.subtitleedit.feature.ui.DemucsModelImportAction
import com.subtitleedit.feature.ui.DemucsModelImportUiState
import com.subtitleedit.util.ModelDownloader
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.UriDisplayName
import com.subtitleedit.task.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Vocal separation model import section of the model management page. Owned by
 * ModelManagementViewModel so its state and running download survive Activity
 * recreation; pickers/permissions/navigation go through [host] events.
 */
internal class DemucsModelImportController(
    private val host: ModelImportHost,
    private val savedState: SavedStateHandle
) : ModelImportDialogState(host) {
    private val _uiState = MutableStateFlow(DemucsModelImportUiState())
    val state: StateFlow<DemucsModelImportUiState> = _uiState.asStateFlow()
    private var uiState: DemucsModelImportUiState
        get() = _uiState.value
        set(value) { _uiState.value = value }

    private val context: Context get() = host.app
    private val settings: SettingsManager = SettingsManager.getInstance(host.app)
    private var accessWarningShown = false
    private var modelType = SettingsManager.DEMIX_MODEL_GENERAL
    private var modelDownloadJob: Job? = null

    /** Kept in SavedStateHandle so an active download is re-attached after process death. */
    private var modelDownloadWorkId: UUID?
        get() = savedState.get<String>(KEY_WORK_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        set(value) { savedState[KEY_WORK_ID] = value?.toString() }
    private var pendingGeneralModelDownload = false
    private var pendingNotificationPermission = false

    private val notificationPermissionPreferences by lazy {
        context.getSharedPreferences("task_notifications", Context.MODE_PRIVATE)
    }

    init {
        loadSettings()
        restoreActiveGeneralModelDownload()
    }

    fun onDocumentPicked(target: ModelPickTarget, uri: Uri) {
        when (target) {
            ModelPickTarget.DEMUCS_GENERAL -> handleSelectedGeneralModel(uri)
            ModelPickTarget.DEMUCS_VOCALS -> handleSelectedModel(VocalSeparationEngine.Stem.VOCALS, uri)
            ModelPickTarget.DEMUCS_DRUMS -> handleSelectedModel(VocalSeparationEngine.Stem.DRUMS, uri)
            ModelPickTarget.DEMUCS_BASS -> handleSelectedModel(VocalSeparationEngine.Stem.BASS, uri)
            ModelPickTarget.DEMUCS_OTHER -> handleSelectedModel(VocalSeparationEngine.Stem.OTHER, uri)
            else -> Unit
        }
    }

    /** Result of the storage permission flow requested by this controller. */
    fun onStorageAccessResult() = continuePendingGeneralModelDownload()

    /** The storage permission settings screen could not be opened. */
    fun onStorageAccessUnavailable() {
        pendingGeneralModelDownload = false
        host.showToast(R.string.model_mgmt_storage_settings_unavailable, duration = Toast.LENGTH_LONG)
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (!pendingNotificationPermission) return
        pendingNotificationPermission = false
        if (!granted) host.showToast(R.string.model_mgmt_notification_denied, duration = Toast.LENGTH_LONG)
        startGeneralModelDownload()
    }

    fun onAction(action: DemucsModelImportAction) {
        when (action) {
            DemucsModelImportAction.Configure -> host.sendEvent(
                ModelManagementEvent.OpenScreen(VocalSeparationSettingsActivity::class.java)
            )
            DemucsModelImportAction.SelectModelType -> showDemixModelPicker()
            DemucsModelImportAction.SelectGeneralModel -> launchPicker(ModelPickTarget.DEMUCS_GENERAL)
            DemucsModelImportAction.DownloadGeneralModel -> confirmGeneralModelDownload()
            DemucsModelImportAction.ResetGeneralModel -> confirmResetGeneralModelSelection()
            DemucsModelImportAction.SelectVocalsModel -> launchPicker(ModelPickTarget.DEMUCS_VOCALS)
            DemucsModelImportAction.SelectDrumsModel -> launchPicker(ModelPickTarget.DEMUCS_DRUMS)
            DemucsModelImportAction.SelectBassModel -> launchPicker(ModelPickTarget.DEMUCS_BASS)
            DemucsModelImportAction.SelectOtherModel -> launchPicker(ModelPickTarget.DEMUCS_OTHER)
            DemucsModelImportAction.ShowGuide -> showModelHelp()
        }
    }

    private fun launchPicker(target: ModelPickTarget) = host.sendEvent(ModelManagementEvent.PickDocument(target))

    private fun confirmGeneralModelDownload() {
        showMessageDialog(
            title = host.text(R.string.model_mgmt_demucs_download_title),
            message = host.text(R.string.model_mgmt_demucs_download_message),
            confirmLabel = host.text(R.string.model_mgmt_download_and_import),
            dismissLabel = host.text(R.string.cancel),
            onConfirm = ::startGeneralModelDownload
        )
    }

    private fun confirmResetGeneralModelSelection() {
        showMessageDialog(
            title = host.text(R.string.model_mgmt_reset_selection_title),
            message = host.text(R.string.model_mgmt_demucs_reset_message),
            confirmLabel = host.text(R.string.model_mgmt_reset),
            dismissLabel = host.text(R.string.cancel),
            onConfirm = ::resetGeneralModelSelection
        )
    }

    private fun resetGeneralModelSelection() {
        settings.setDemixModelUri("general", "")
        updateGeneralModelUi()
        host.showToast(R.string.model_mgmt_demucs_reset_done)
    }

    private fun startGeneralModelDownload() {
        if (modelDownloadJob?.isActive == true) return
        if (pendingNotificationPermission) return
        if (!ensureModelStorageAccess()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionPreferences.getBoolean("requested", false)
        ) {
            pendingNotificationPermission = true
            host.sendEvent(ModelManagementEvent.RequestNotificationPermission)
            return
        }
        observeGeneralModelDownload(enqueue = true)
    }

    private fun restoreActiveGeneralModelDownload() {
        observeGeneralModelDownload(enqueue = false)
    }

    private fun observeGeneralModelDownload(enqueue: Boolean) {
        if (modelDownloadJob?.isActive == true) return
        setModelDownloadActionsEnabled(false)
        modelDownloadJob = host.scope.launch {
            var progressToken: Long? = null
            try {
                val scheduler = (host.app as SubtitleEditApplication).dependencies.taskWorkScheduler
                val workId = if (enqueue) {
                    scheduler.enqueueGeneralModelDownload()
                } else {
                    scheduler.findActiveModelDownload(modelDownloadWorkId) ?: return@launch
                }
                modelDownloadWorkId = workId
                progressToken = showProgressDialog(host.text(R.string.model_mgmt_demucs_download_progress_title)) {
                    host.scope.launch {
                        try {
                            scheduler.cancel(workId)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            showError(host.text(R.string.model_mgmt_cancel_download_failed, error.message.toString()))
                        }
                    }
                }
                scheduler.observeTask(workId).takeWhile { taskState ->
                    if (taskState == null) {
                        modelDownloadWorkId = null
                        throw IllegalStateException(host.text(R.string.model_mgmt_task_missing))
                    }
                    taskState.progress.message.takeIf(String::isNotBlank)?.let { message ->
                        updateProgressDialog(
                            requireNotNull(progressToken),
                            ModelDownloader.Progress(
                                message,
                                taskState.progress.current,
                                taskState.progress.total
                            )
                        )
                    }
                    when (taskState.status) {
                        TaskStatus.SUCCEEDED -> {
                            modelDownloadWorkId = null
                            modelType = settings.getDemixModelType()
                            updateGeneralModelUi()
                            updateDemixModelUi()
                            host.showToast(R.string.model_mgmt_demucs_downloaded, duration = Toast.LENGTH_LONG)
                            false
                        }
                        TaskStatus.FAILED -> {
                            showModelDownloadFailure(
                                taskState.errorMessage ?: host.text(R.string.model_mgmt_task_failed)
                            )
                            false
                        }
                        TaskStatus.CANCELLED -> {
                            modelDownloadWorkId = null
                            false
                        }
                        else -> true
                    }
                }.collect { }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(host.text(R.string.model_mgmt_task_failed_with_reason, error.message.toString()))
            } finally {
                progressToken?.let(::dismissProgressDialog)
                setModelDownloadActionsEnabled(true)
                modelDownloadJob = null
            }
        }
    }

    private fun setModelDownloadActionsEnabled(enabled: Boolean) {
        uiState = uiState.copy(actionsEnabled = enabled)
    }


    private fun ensureModelStorageAccess(): Boolean {
        if (hasModelStorageAccess(context)) return true
        pendingGeneralModelDownload = true
        host.requestStorageAccess(StorageAccessRequester.DEMUCS_IMPORT)
        return false
    }

    private fun continuePendingGeneralModelDownload() {
        if (!pendingGeneralModelDownload) return
        pendingGeneralModelDownload = false
        if (hasModelStorageAccess(context)) {
            startGeneralModelDownload()
        } else {
            host.showToast(R.string.model_mgmt_storage_required_for_download, duration = Toast.LENGTH_LONG)
        }
    }

    private fun loadSettings() {
        discardInaccessibleModelUris()
        modelType = settings.getDemixModelType()
        updateGeneralModelUi()
        VocalSeparationEngine.Stem.entries.forEach(::updateModelUi)
        updateDemixModelUi()
    }

    private fun handleSelectedModel(stem: VocalSeparationEngine.Stem, uri: Uri) {
        val fileName = getFileName(uri)
        if (!fileName.endsWith(".onnx", ignoreCase = true) ||
            !fileName.contains(stem.fileSuffix, ignoreCase = true)) {
            host.showToast(
                R.string.model_mgmt_demucs_pick_specialist,
                stem.displayName,
                stem.fileSuffix,
                duration = Toast.LENGTH_LONG
            )
            return
        }
        try {
            persistAndVerifyModel(uri)
            settings.setDemixModelUri(stem.fileSuffix, uri.toString())
            updateModelUi(stem)
            host.showToast(R.string.model_mgmt_demucs_stem_selected, stem.displayName)
        } catch (e: Exception) {
            showError(host.text(R.string.model_mgmt_demucs_stem_select_failed, stem.displayName, e.message.toString()))
        }
    }

    private fun handleSelectedGeneralModel(uri: Uri) {
        val fileName = getFileName(uri)
        if (!fileName.endsWith(".onnx", ignoreCase = true) || fileName.contains("_ft_", ignoreCase = true)) {
            host.showToast(R.string.model_mgmt_demucs_pick_general, duration = Toast.LENGTH_LONG)
            return
        }
        try {
            persistAndVerifyModel(uri)
            settings.setDemixModelUri("general", uri.toString())
            updateGeneralModelUi()
            host.showToast(R.string.model_mgmt_demucs_general_selected)
        } catch (e: Exception) {
            showError(host.text(R.string.model_mgmt_demucs_general_select_failed, e.message.toString()))
        }
    }

    private fun persistAndVerifyModel(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.statSize == 0L) throw IllegalStateException(host.text(R.string.model_mgmt_demucs_model_empty))
        } ?: throw IllegalStateException(host.text(R.string.model_mgmt_demucs_model_unreadable))
    }

    private fun updateGeneralModelUi() {
        val uriString = settings.getDemixModelUri("general")
        val hasSelectedModel = uriString.isNotBlank()
        uiState = uiState.copy(
            generalModelValue = uriString.takeIf(String::isNotBlank)?.let {
                getFileName(Uri.parse(it))
            },
            hasGeneralModel = hasSelectedModel
        )
    }

    private fun updateModelUi(stem: VocalSeparationEngine.Stem) {
        val uriString = settings.getDemixModelUri(stem.fileSuffix)
        val value = uriString.takeIf(String::isNotBlank)?.let {
            getFileName(Uri.parse(it))
        }
        uiState = uiState.copy(ftModelValues = uiState.ftModelValues + (stem.fileSuffix to value))
    }

    private fun showDemixModelPicker() {
        val labels = listOf(
            host.text(R.string.model_mgmt_demucs_type_general),
            host.text(R.string.model_mgmt_demucs_type_ft)
        )
        val checked = if (modelType == SettingsManager.DEMIX_MODEL_FT) 1 else 0
        showOptionsDialog(host.text(R.string.model_mgmt_demucs_select_type_title), labels, selectedIndex = checked) { which ->
            val selectedType = if (which == 1) {
                SettingsManager.DEMIX_MODEL_FT
            } else {
                SettingsManager.DEMIX_MODEL_GENERAL
            }
            if (selectedType != modelType) {
                modelType = selectedType
                settings.setDemixModelType(selectedType)
                updateDemixModelUi()
            }
        }
    }

    private fun updateDemixModelUi() {
        val showFtModels = modelType == SettingsManager.DEMIX_MODEL_FT
        uiState = uiState.copy(useFtModels = showFtModels)
    }

    private fun discardInaccessibleModelUris() {
        var discarded = false
        val modelKeys = listOf("general") + VocalSeparationEngine.Stem.entries.map { it.fileSuffix }
        modelKeys.forEach { modelKey ->
            val uriString = settings.getDemixModelUri(modelKey)
            if (uriString.isNotBlank() && !isSavedUriReadable(uriString)) {
                settings.setDemixModelUri(modelKey, "")
                discarded = true
            }
        }
        if (discarded && !accessWarningShown) {
            accessWarningShown = true
            host.showToast(R.string.model_mgmt_access_expired, duration = Toast.LENGTH_LONG)
        }
    }

    private fun isSavedUriReadable(uriString: String): Boolean = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            uri.path?.let(::File)?.isFile == true
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }
    }.getOrDefault(false)

    private fun getFileName(uri: Uri): String = UriDisplayName.of(context, uri)

    private fun showModelHelp() {
        showMessageDialog(
            host.text(R.string.model_mgmt_demucs_help_title),
            host.text(R.string.model_mgmt_demucs_help_message),
            confirmLabel = host.text(R.string.model_mgmt_close)
        )
    }

    private fun showError(message: String) {
        showMessageDialog(host.text(R.string.model_mgmt_demucs_error_title), message)
    }

    fun refresh() {
        loadSettings()
    }

    private fun showModelDownloadFailure(error: String) {
        showMessageDialog(
            title = host.text(R.string.model_mgmt_demucs_download_failed_title),
            message = host.text(R.string.model_mgmt_download_retry_hint, error),
            confirmLabel = host.text(R.string.model_mgmt_retry),
            dismissLabel = host.text(R.string.model_mgmt_close),
            onConfirm = ::startGeneralModelDownload,
            onDismiss = { modelDownloadWorkId = null }
        )
    }

    /** Called from ViewModel.onCleared(): the page is finishing for real. */
    fun dispose() {
        modelDownloadJob?.cancel()
        setDialog(null)
    }

    private companion object {
        const val KEY_WORK_ID = "demix-model-download-work-id"
    }
}
