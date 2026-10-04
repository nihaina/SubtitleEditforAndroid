package com.subtitleedit

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.SavedStateHandle
import com.subtitleedit.feature.ui.AsrModelImportAction
import com.subtitleedit.feature.ui.AsrModelImportUiState
import com.subtitleedit.feature.ui.ForcedAlignerImportStatus
import com.subtitleedit.repository.ModelRepository
import com.subtitleedit.task.TaskStatus
import com.subtitleedit.usecase.DownloadAsrModelUseCase
import com.subtitleedit.util.InternalModelExport
import com.subtitleedit.util.ModelDownloader
import com.subtitleedit.util.ModelDirectoryManager
import com.subtitleedit.util.QnnRuntimeAvailability
import com.subtitleedit.util.Qwen3ForcedAlignerModelFiles
import com.subtitleedit.util.Qwen3ForcedAlignerPathResolver
import com.subtitleedit.util.Qwen3ForcedAlignerReleaseDownloader
import com.subtitleedit.util.SenseVoiceNpuModelImporter
import com.subtitleedit.util.SenseVoiceNpuModelPathPolicy
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.UriDisplayName
import com.subtitleedit.work.ModelDownloadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * ASR model import section of the model management page. Owned by
 * ModelManagementViewModel so its state, dialogs and running imports survive
 * Activity recreation; pickers/permissions/navigation go through [host] events.
 */
internal class AsrModelImportController(
    private val host: ModelImportHost,
    private val savedState: SavedStateHandle,
    private val onModelsChanged: () -> Unit = {}
) : ModelImportDialogState(host) {

    private val _uiState = MutableStateFlow(AsrModelImportUiState())
    val state: StateFlow<AsrModelImportUiState> = _uiState.asStateFlow()
    private var uiState: AsrModelImportUiState
        get() = _uiState.value
        set(value) { _uiState.value = value }

    private val context: Context get() = host.app
    private val settingsManager: SettingsManager = SettingsManager.getInstance(host.app)
    private val modelRepository: ModelRepository
        get() = (host.app as SubtitleEditApplication).dependencies.modelRepository

    private var encoderPath: String = ""
    private var decoderPath: String = ""
    private var joinerPath: String = ""
    private var tokensPath: String = ""
    private var vadModelPath: String = ""
    private var modelType: String = SettingsManager.ASR_MODEL_SENSEVOICE
    private var accessWarningShown = false
    private var modelDownloadJob: Job? = null

    /** Kept in SavedStateHandle so an active download is re-attached after process death. */
    private var modelDownloadWorkId: UUID?
        get() = savedState.get<String>(KEY_WORK_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        set(value) { savedState[KEY_WORK_ID] = value?.toString() }
    private var pendingNotificationAction: (() -> Unit)? = null
    private val notificationPermissionPreferences by lazy {
        context.getSharedPreferences("task_notifications", Context.MODE_PRIVATE)
    }
    private var pendingStorageAction: (() -> Unit)? = null

    private data class ForcedAlignerUi(
        val complete: Boolean,
        val status: ForcedAlignerImportStatus,
        val graphName: String? = null,
        val dataName: String? = null
    )

    init {
        observeAsrModelDownload()
        loadSavedSettings()
    }

    fun onDocumentPicked(target: ModelPickTarget, uri: Uri) {
        when (target) {
            ModelPickTarget.ASR_ENCODER -> handleSelectedEncoder(uri)
            ModelPickTarget.ASR_DECODER -> handleSelectedDecoder(uri)
            ModelPickTarget.ASR_JOINER -> handleSelectedJoiner(uri)
            ModelPickTarget.ASR_TOKENS -> handleSelectedTokens(uri)
            ModelPickTarget.ASR_VAD -> handleSelectedVad(uri)
            else -> Unit
        }
    }

    fun onTokenizerFolderPicked(uri: Uri) = handleSelectedQwen3Tokenizer(uri)

    fun onForcedAlignerPicked(uris: List<Uri>) = importQwen3ForcedAligner(uris)

    /** Result of the storage permission flow requested by this controller. */
    fun onStorageAccessResult() = continuePendingModelDownload()

    /** The storage permission settings screen could not be opened. */
    fun onStorageAccessUnavailable() {
        pendingStorageAction = null
        host.showToast(R.string.model_mgmt_storage_settings_unavailable, duration = Toast.LENGTH_LONG)
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        val action = pendingNotificationAction ?: return
        pendingNotificationAction = null
        if (!granted) host.showToast(R.string.model_mgmt_notification_denied, duration = Toast.LENGTH_LONG)
        action()
    }

    private fun launchPicker(target: ModelPickTarget) = host.sendEvent(ModelManagementEvent.PickDocument(target))

    fun onAction(action: AsrModelImportAction) {
        when (action) {
            AsrModelImportAction.SelectModelType -> showAsrModelPicker()
            AsrModelImportAction.SelectEncoder -> {
                if (isSenseVoiceNpu() && !ensureQnnRuntimeAvailable()) return
                if (isSenseVoiceNpu() && restoreAvailableModelPathsIfMissing(includeQwen = false)) {
                    loadModelPaths()
                    updateAsrModelUi()
                    host.showToast(R.string.model_mgmt_asr_npu_found)
                    return
                }
                launchPicker(ModelPickTarget.ASR_ENCODER)
            }
            AsrModelImportAction.DownloadModel -> showAsrDownloadOptions()
            AsrModelImportAction.ResetModel -> confirmResetCurrentAsrModel()
            AsrModelImportAction.ConfigureWhisper -> host.sendEvent(ModelManagementEvent.OpenAsrSettings)
            AsrModelImportAction.SelectDecoder -> launchPicker(ModelPickTarget.ASR_DECODER)
            AsrModelImportAction.SelectJoiner -> launchPicker(ModelPickTarget.ASR_JOINER)
            AsrModelImportAction.SelectTokens -> {
                if (isSenseVoiceNpu() && !ensureQnnRuntimeAvailable()) return
                if (isQwen3Asr()) host.sendEvent(ModelManagementEvent.PickQwen3Tokenizer)
                else launchPicker(ModelPickTarget.ASR_TOKENS)
            }
            AsrModelImportAction.SelectForcedAligner -> {
                if (modelDownloadJob?.isActive == true) return
                runWithModelStorageAccess {
                    host.sendEvent(ModelManagementEvent.PickForcedAligner)
                }
            }
            AsrModelImportAction.DownloadForcedAligner -> runWithModelStorageAccess {
                confirmQwen3ForcedAlignerDownload()
            }
            AsrModelImportAction.ResetForcedAligner -> confirmResetQwen3ForcedAligner()
            AsrModelImportAction.ConfigureVad -> host.sendEvent(
                ModelManagementEvent.OpenScreen(VadModelSettingsActivity::class.java)
            )
            AsrModelImportAction.SelectVad -> launchPicker(ModelPickTarget.ASR_VAD)
            AsrModelImportAction.SelectSenseVoiceCpu -> selectSenseVoiceProvider(
                SettingsManager.SENSEVOICE_PROVIDER_CPU
            )
            AsrModelImportAction.SelectSenseVoiceNpu -> selectSenseVoiceProvider(
                SettingsManager.SENSEVOICE_PROVIDER_NPU
            )
            AsrModelImportAction.SelectParakeetTdt -> selectParakeetVariant(
                SettingsManager.ASR_MODEL_PARAKEET_TDT
            )
            AsrModelImportAction.SelectParakeetCtc -> selectParakeetVariant(
                SettingsManager.ASR_MODEL_PARAKEET_CTC_JA
            )
            AsrModelImportAction.ShowGuide -> showModelGuide()
        }
    }

    fun onBuiltInVadChanged(checked: Boolean) {
        settingsManager.setVadUseBuiltInModel(checked)
        updateVadModelUi()
    }

    private fun downloadLocation(directoryName: String) =
        "${ModelDirectoryManager.modelsDirectory().path}/$directoryName"

    private fun optionLabel(displayName: String, sizeLabel: String) =
        host.text(R.string.model_mgmt_option_with_size, displayName, sizeLabel)

    private fun showAsrDownloadOptions() {
        if (modelDownloadJob?.isActive == true) {
            host.showToast(R.string.model_mgmt_asr_downloading)
            return
        }
        if (isSenseVoiceNpu() && !ensureQnnRuntimeAvailable()) return
        when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE -> showSenseVoiceDownloadOptions()
            SettingsManager.ASR_MODEL_PARAKEET_TDT ->
                confirmParakeetDownload(modelRepository.parakeetTdtModel)
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA ->
                confirmParakeetDownload(modelRepository.parakeetCtcJaModel)
            SettingsManager.ASR_MODEL_QWEN3_ASR ->
                showQwen3AsrDownloadModelPicker()
            else -> showWhisperDownloadModelPicker()
        }
    }

    private fun showSenseVoiceDownloadOptions() {
        if (settingsManager.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU) {
            if (!ensureQnnRuntimeAvailable()) return
            val options = modelRepository.senseVoiceNpuModels
            showOptionsDialog(
                title = host.text(R.string.model_mgmt_asr_select_npu_model),
                options = options.map { optionLabel(it.displayName, it.sizeLabel) }
            ) { confirmSenseVoiceDownload(options[it]) }
        } else {
            confirmSenseVoiceDownload(modelRepository.senseVoiceCpuModel)
        }
    }

    private fun confirmSenseVoiceDownload(option: ModelDownloader.SenseVoiceModelOption) {
        val isNpu = option.architecture == ModelDownloader.SenseVoiceArchitecture.QNN
        val compatibility = if (isNpu) host.text(R.string.model_mgmt_asr_npu_download_note) else ""
        showMessageDialog(
            title = host.text(R.string.model_mgmt_asr_sensevoice_download_title, option.displayName),
            message = host.text(
                R.string.model_mgmt_asr_download_message,
                downloadLocation(option.directoryName),
                option.sizeLabel,
                compatibility
            ),
            confirmLabel = host.text(R.string.model_mgmt_download_and_import),
            dismissLabel = host.text(R.string.cancel),
            onConfirm = {
                runWithModelStorageAccess { startSenseVoiceDownload(option) }
            }
        )
    }

    private fun confirmParakeetDownload(option: ModelDownloader.ParakeetModelOption) {
        showMessageDialog(
            title = host.text(R.string.model_mgmt_download_import_title, option.displayName),
            message = host.text(
                R.string.model_mgmt_asr_parakeet_download_message,
                option.description,
                downloadLocation(option.directoryName),
                option.sizeLabel
            ),
            confirmLabel = host.text(R.string.model_mgmt_download_and_import),
            dismissLabel = host.text(R.string.cancel),
            onConfirm = {
                runWithModelStorageAccess { startParakeetDownload(option) }
            }
        )
    }

    private fun showWhisperDownloadModelPicker() {
        val options = modelRepository.whisperModels
        showOptionsDialog(
            title = host.text(R.string.model_mgmt_asr_select_whisper),
            options = options.map { optionLabel(it.displayName, it.sizeLabel) }
        ) { confirmWhisperDownload(options[it]) }
    }

    private fun confirmWhisperDownload(option: ModelDownloader.WhisperModelOption) {
        showMessageDialog(
            title = host.text(R.string.model_mgmt_asr_whisper_download_title, option.displayName),
            message = host.text(
                R.string.model_mgmt_asr_download_message,
                downloadLocation(option.directoryName),
                option.sizeLabel,
                ""
            ),
            confirmLabel = host.text(R.string.model_mgmt_download_and_import),
            dismissLabel = host.text(R.string.cancel),
            onConfirm = {
                runWithModelStorageAccess { startWhisperDownload(option) }
            }
        )
    }

    private fun confirmQwen3AsrDownload(option: ModelDownloader.Qwen3AsrModelOption) {
        showMessageDialog(
            title = host.text(R.string.model_mgmt_asr_qwen_download_title, option.displayName),
            message = host.text(
                R.string.model_mgmt_asr_qwen_download_message,
                downloadLocation(option.directoryName),
                option.sizeLabel
            ),
            confirmLabel = host.text(R.string.model_mgmt_download_and_import),
            dismissLabel = host.text(R.string.cancel),
            onConfirm = {
                runWithModelStorageAccess { startQwen3AsrDownload(option) }
            }
        )
    }

    private fun confirmQwen3ForcedAlignerDownload() {
        if (modelDownloadJob?.isActive == true) return
        if (adoptAvailableQwenForcedAligner()) {
            host.showToast(R.string.model_mgmt_asr_aligner_found)
            return
        }
        if (hasConfiguredQwen3ForcedAligner()) {
            host.showToast(R.string.model_mgmt_asr_aligner_already_imported)
            return
        }
        showMessageDialog(
            title = host.text(R.string.model_mgmt_asr_aligner_download_title),
            message = host.text(
                R.string.model_mgmt_asr_aligner_download_message,
                Qwen3ForcedAlignerModelFiles.DIRECTORY_NAME
            ),
            confirmLabel = host.text(R.string.model_mgmt_download_and_import),
            dismissLabel = host.text(R.string.cancel),
            onConfirm = {
                runWithModelStorageAccess { startQwen3ForcedAlignerDownload() }
            }
        )
    }

    private fun startQwen3ForcedAlignerDownload() {
        if (adoptAvailableQwenForcedAligner()) return
        if (hasConfiguredQwen3ForcedAligner()) return
        startAsrModelDownload(ModelDownloadWorker.KIND_QWEN3_FORCED_ALIGNER)
    }

    private fun confirmResetQwen3ForcedAligner() {
        showMessageDialog(
            title = host.text(R.string.model_mgmt_asr_aligner_reset_title),
            message = host.text(R.string.model_mgmt_asr_aligner_reset_message),
            confirmLabel = host.text(R.string.model_mgmt_reset),
            dismissLabel = host.text(R.string.cancel),
            onConfirm = {
                settingsManager.clearQwen3ForcedAlignerPath()
                updateAsrModelUi()
            }
        )
    }

    private fun showQwen3AsrDownloadModelPicker() {
        val options = modelRepository.qwen3AsrModels
        showOptionsDialog(
            title = host.text(R.string.model_mgmt_asr_select_qwen),
            options = options.map { optionLabel(it.displayName, it.sizeLabel) }
        ) { confirmQwen3AsrDownload(options[it]) }
    }

    private fun startSenseVoiceDownload(option: ModelDownloader.SenseVoiceModelOption) {
        val isNpu = option.architecture == ModelDownloader.SenseVoiceArchitecture.QNN
        if (isNpu && !ensureQnnRuntimeAvailable()) return
        if (isNpu && "arm64-v8a" !in Build.SUPPORTED_ABIS) {
            host.showToast(R.string.model_mgmt_asr_npu_arm64_only, duration = Toast.LENGTH_LONG)
            return
        }
        startAsrModelDownload(DownloadAsrModelUseCase.KIND_SENSEVOICE, option.id)
    }

    private fun startWhisperDownload(option: ModelDownloader.WhisperModelOption) {
        startAsrModelDownload(DownloadAsrModelUseCase.KIND_WHISPER, option.id)
    }

    private fun startParakeetDownload(option: ModelDownloader.ParakeetModelOption) {
        startAsrModelDownload(DownloadAsrModelUseCase.KIND_PARAKEET, option.modelType)
    }

    private fun startQwen3AsrDownload(option: ModelDownloader.Qwen3AsrModelOption) {
        startAsrModelDownload(DownloadAsrModelUseCase.KIND_QWEN3_ASR, option.id)
    }

    private fun startAsrModelDownload(kind: String, optionId: String? = null) {
        if (modelDownloadJob?.isActive == true || pendingNotificationAction != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionPreferences.getBoolean("requested", false)
        ) {
            pendingNotificationAction = { observeAsrModelDownload(kind, optionId) }
            host.sendEvent(ModelManagementEvent.RequestNotificationPermission)
            return
        }
        observeAsrModelDownload(kind, optionId)
    }

    private fun observeAsrModelDownload(
        kind: String? = null,
        optionId: String? = null,
        retryWorkId: UUID? = null
    ) {
        if (modelDownloadJob?.isActive == true) return
        setAsrModelActionsEnabled(false)
        modelDownloadJob = host.scope.launch {
            var progressToken: Long? = null
            try {
                val scheduler = (host.app as SubtitleEditApplication).dependencies.taskWorkScheduler
                val workId = if (retryWorkId != null) {
                    scheduler.retryModelDownload(retryWorkId)
                } else if (kind == ModelDownloadWorker.KIND_QWEN3_FORCED_ALIGNER) {
                    scheduler.enqueueQwen3ForcedAlignerDownload()
                } else if (kind != null) {
                    scheduler.enqueueAsrModelDownload(kind, requireNotNull(optionId))
                } else {
                    scheduler.findActiveAsrModelDownload(modelDownloadWorkId) ?: return@launch
                }
                modelDownloadWorkId = workId
                progressToken = showProgressDialog(host.text(R.string.model_mgmt_asr_download_progress_title)) {
                    host.scope.launch {
                        try {
                            scheduler.cancel(workId)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            host.showToast(
                                R.string.model_mgmt_cancel_download_failed,
                                error.message.toString(),
                                duration = Toast.LENGTH_LONG
                            )
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
                            ModelDownloader.Progress(message, taskState.progress.current, taskState.progress.total)
                        )
                    }
                    when (taskState.status) {
                        TaskStatus.SUCCEEDED -> {
                            modelDownloadWorkId = null
                            loadSavedSettings()
                            onModelsChanged()
                            val successMessage = if (taskState.type == ModelDownloadWorker.KIND_QWEN3_FORCED_ALIGNER) {
                                R.string.model_mgmt_asr_aligner_downloaded
                            } else R.string.model_mgmt_asr_model_downloaded
                            host.showToast(successMessage, duration = Toast.LENGTH_LONG)
                            false
                        }
                        TaskStatus.FAILED -> {
                            showModelDownloadFailure(
                                workId,
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
                host.showToast(
                    R.string.model_mgmt_task_failed_with_reason,
                    error.message.toString(),
                    duration = Toast.LENGTH_LONG
                )
            } finally {
                progressToken?.let(::dismissProgressDialog)
                modelDownloadJob = null
                setAsrModelActionsEnabled(true)
                if (isActive && modelDownloadWorkId == null) {
                    migrateLegacySenseVoiceNpuSelectionIfNeeded()
                }
            }
        }
    }

    private fun showModelDownloadFailure(workId: UUID, error: String) {
        showMessageDialog(
            title = host.text(R.string.model_mgmt_asr_download_failed_title),
            message = host.text(R.string.model_mgmt_download_retry_hint, error),
            confirmLabel = host.text(R.string.model_mgmt_retry),
            dismissLabel = host.text(R.string.model_mgmt_close),
            onConfirm = {
                runWithModelStorageAccess { observeAsrModelDownload(retryWorkId = workId) }
            },
            onDismiss = { modelDownloadWorkId = null }
        )
    }

    private fun confirmResetCurrentAsrModel() {
        val modelName = currentModelDisplayName()
        showMessageDialog(
            title = host.text(R.string.model_mgmt_reset_selection_title),
            message = host.text(R.string.model_mgmt_asr_reset_message, modelName),
            confirmLabel = host.text(R.string.model_mgmt_reset),
            dismissLabel = host.text(R.string.cancel),
            onConfirm = ::resetCurrentAsrModel
        )
    }

    private fun resetCurrentAsrModel() {
        when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE -> settingsManager.clearSenseVoiceModelPaths()
            SettingsManager.ASR_MODEL_PARAKEET_TDT -> settingsManager.clearParakeetTdtModelPaths()
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> settingsManager.clearParakeetCtcModelPaths()
            SettingsManager.ASR_MODEL_QWEN3_ASR -> settingsManager.clearQwen3AsrModelPaths()
            else -> settingsManager.clearWhisperModelPaths()
        }
        loadModelPaths()
        updateAsrModelUi()
        host.showToast(R.string.model_mgmt_asr_reset_done)
    }

    private fun setAsrModelActionsEnabled(enabled: Boolean) {
        uiState = uiState.copy(actionsEnabled = enabled)
    }

    private fun runWithModelStorageAccess(action: () -> Unit) {
        if (hasModelStorageAccess()) {
            action()
            return
        }
        pendingStorageAction = action
        host.requestStorageAccess(StorageAccessRequester.ASR_IMPORT)
    }

    private fun hasModelStorageAccess(): Boolean = hasModelStorageAccess(context)

    private fun continuePendingModelDownload() {
        val action = pendingStorageAction ?: return
        pendingStorageAction = null
        if (hasModelStorageAccess()) {
            action()
        } else {
            host.showToast(R.string.model_mgmt_storage_required_for_download, duration = Toast.LENGTH_LONG)
        }
    }

    private fun loadSavedSettings() {
        // 加载模型路径
        modelType = settingsManager.getAsrModelType()
        restoreAvailableModelPathsIfMissing()
        loadModelPaths()
        updateAsrModelUi()
        vadModelPath = settingsManager.getVadModelPath()
        discardInaccessibleVadModel()
        updateVadModelUi()

        migrateLegacySenseVoiceNpuSelectionIfNeeded()
    }

    private fun migrateLegacySenseVoiceNpuSelectionIfNeeded() {
        if (!isSenseVoiceNpu() || encoderPath.isBlank()) return
        if (!QnnRuntimeAvailability.isAvailable(context)) return
        if (SenseVoiceNpuModelPathPolicy.isContextBinarySelection(encoderPath)) return
        startSenseVoiceNpuImport(
            Uri.parse(encoderPath),
            settingsManager.getSenseVoiceNpuDurationSeconds()
        )
    }

    private fun handleSelectedEncoder(uri: Uri) {
        try {
            if (isSenseVoiceNpu() && !ensureQnnRuntimeAvailable()) return
            val fileName = getFileNameFromUri(uri)
            val senseVoiceNpu = isSenseVoiceNpu()
            if (!senseVoiceNpu) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val isValid = when {
                senseVoiceNpu -> fileName.equals("libmodel.so", ignoreCase = true)
                modelType == SettingsManager.ASR_MODEL_SENSEVOICE ||
                    modelType == SettingsManager.ASR_MODEL_PARAKEET_CTC_JA ->
                    fileName.endsWith(".onnx", ignoreCase = true)
                else ->
                    fileName.contains("encoder", ignoreCase = true) &&
                        fileName.endsWith(".onnx", ignoreCase = true)
            }
            if (!isValid) {
                host.showToast(
                    when {
                        senseVoiceNpu -> host.text(R.string.model_mgmt_asr_pick_npu_libmodel)
                        isSingleFileModel() -> host.text(R.string.model_mgmt_asr_pick_onnx)
                        else -> host.text(R.string.model_mgmt_asr_pick_named_onnx, "encoder")
                    },
                    Toast.LENGTH_LONG
                )
                return
            }

            if (senseVoiceNpu) {
                val detectedDuration = detectSenseVoiceNpuDuration(uri, fileName)
                if (detectedDuration != null) {
                    startSenseVoiceNpuImport(uri, detectedDuration)
                } else {
                    showSenseVoiceNpuDurationPicker(uri)
                }
            } else {
                saveSelectedEncoder(uri, fileName)
            }

        } catch (e: Exception) {
            showSelectFileFailed(e)
        }
    }

    private fun showSelectFileFailed(e: Exception) =
        host.showToast(R.string.model_mgmt_select_file_failed, e.message.toString(), duration = Toast.LENGTH_LONG)

    private fun saveSelectedEncoder(uri: Uri, fileName: String) {
        encoderPath = uri.toString()
        when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE -> settingsManager.setSenseVoiceModelPath(encoderPath)
            SettingsManager.ASR_MODEL_PARAKEET_TDT -> settingsManager.setParakeetTdtEncoderPath(encoderPath)
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> settingsManager.setParakeetCtcModelPath(encoderPath)
            SettingsManager.ASR_MODEL_QWEN3_ASR -> settingsManager.setQwen3AsrEncoderPath(encoderPath)
            else -> settingsManager.setWhisperEncoderPath(encoderPath)
        }
        updateAsrModelUi()
    }

    private fun detectSenseVoiceNpuDuration(uri: Uri, fileName: String): Int? {
        val identity = "${uri} $fileName".lowercase(Locale.ROOT)
        return when {
            identity.contains("10-seconds") || identity.contains("10_seconds") ||
                identity.contains("10 seconds") || identity.contains("10%20seconds") -> 10
            identity.contains("5-seconds") || identity.contains("5_seconds") ||
                identity.contains("5 seconds") || identity.contains("5%20seconds") -> 5
            else -> null
        }
    }

    private fun showSenseVoiceNpuDurationPicker(uri: Uri) {
        val durations = intArrayOf(5, 10)
        showOptionsDialog(
            host.text(R.string.model_mgmt_asr_npu_duration_title),
            durations.map { host.text(R.string.model_mgmt_asr_npu_duration_option, it) }
        ) {
            startSenseVoiceNpuImport(uri, durations[it])
        }
    }

    private fun startSenseVoiceNpuImport(modelUri: Uri, durationSeconds: Int) {
        if (modelDownloadJob?.isActive == true) return
        if (!ensureQnnRuntimeAvailable()) return
        if ("arm64-v8a" !in Build.SUPPORTED_ABIS) {
            host.showToast(R.string.model_mgmt_asr_npu_arm64_only, duration = Toast.LENGTH_LONG)
            return
        }
        val selectedTokensPath = settingsManager.getSenseVoiceTokensPath(
            SettingsManager.SENSEVOICE_PROVIDER_NPU
        )
        if (selectedTokensPath.isBlank() || !canReadSavedUri(selectedTokensPath)) {
            host.showToast(R.string.model_mgmt_asr_npu_tokens_first, duration = Toast.LENGTH_LONG)
            return
        }

        val progressToken = showProgressDialog(host.text(R.string.model_mgmt_asr_npu_import_title)) {
            modelDownloadJob?.cancel()
        }
        setAsrModelActionsEnabled(false)

        modelDownloadJob = host.scope.launch {
            val importer = SenseVoiceNpuModelImporter(
                context,
                context.contentResolver
            )
            val previousModelPath = settingsManager.getSenseVoiceModelPath(
                SettingsManager.SENSEVOICE_PROVIDER_NPU
            )
            try {
                val imported = withContext(Dispatchers.IO) {
                    importer.importFromUris(
                        modelUri = modelUri,
                        tokensUri = Uri.parse(selectedTokensPath),
                        durationSeconds = durationSeconds
                    ) { message ->
                        updateProgressDialog(progressToken, ModelDownloader.Progress(message))
                    }
                }
                val importedUri = Uri.fromFile(imported.contextBinary).toString()
                modelType = SettingsManager.ASR_MODEL_SENSEVOICE
                settingsManager.setAsrModelType(modelType)
                settingsManager.setSenseVoiceProvider(SettingsManager.SENSEVOICE_PROVIDER_NPU)
                settingsManager.setSenseVoiceNpuDurationSeconds(durationSeconds)
                settingsManager.setSenseVoiceModelPath(importedUri)
                settingsManager.setSenseVoiceTokensPath(Uri.fromFile(imported.tokens).toString())
                withContext(Dispatchers.IO) {
                    importer.deleteManagedContextBinary(
                        previousModelPath,
                        except = imported.contextBinary
                    )
                    deleteManagedDownloadedNpuSource(modelUri)
                }
                releasePersistedReadPermission(previousModelPath)
                releasePersistedReadPermission(modelUri.toString())
                releasePersistedReadPermission(selectedTokensPath)
                loadModelPaths()
                updateAsrModelUi()
                host.showToast(R.string.model_mgmt_asr_npu_imported, duration = Toast.LENGTH_LONG)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.showToast(
                    R.string.model_mgmt_asr_npu_import_failed,
                    e.message.toString(),
                    duration = Toast.LENGTH_LONG
                )
            } finally {
                dismissProgressDialog(progressToken)
                setAsrModelActionsEnabled(true)
                modelDownloadJob = null
            }
        }
    }

    private fun deleteManagedDownloadedNpuSource(modelUri: Uri) {
        if (modelUri.scheme != "file") return
        val source = modelUri.path?.let(::File) ?: return
        if (!source.name.equals("libmodel.so", ignoreCase = true)) return
        val modelsRoot = runCatching { modelRepository.modelsDirectory().canonicalFile }.getOrNull()
            ?: return
        val candidate = runCatching { source.canonicalFile }.getOrNull() ?: return
        if (SenseVoiceNpuModelPathPolicy.isInside(modelsRoot, candidate)) candidate.delete()
    }

    private fun handleSelectedDecoder(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            if (!fileName.contains("decoder", ignoreCase = true) ||
                !fileName.endsWith(".onnx", ignoreCase = true)) {
                host.showToast(R.string.model_mgmt_asr_pick_named_onnx, "decoder", duration = Toast.LENGTH_LONG)
                return
            }

            decoderPath = uri.toString()
            when (modelType) {
                SettingsManager.ASR_MODEL_PARAKEET_TDT -> settingsManager.setParakeetTdtDecoderPath(decoderPath)
                SettingsManager.ASR_MODEL_QWEN3_ASR -> settingsManager.setQwen3AsrDecoderPath(decoderPath)
                else -> settingsManager.setWhisperDecoderPath(decoderPath)
            }
            updateAsrModelUi()

        } catch (e: Exception) {
            showSelectFileFailed(e)
        }
    }

    private fun handleSelectedJoiner(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val fileName = getFileNameFromUri(uri)
            val expectedName = if (isQwen3Asr()) "conv_frontend" else "joiner"
            if (!fileName.contains(expectedName, ignoreCase = true) ||
                !fileName.endsWith(".onnx", ignoreCase = true)
            ) {
                host.showToast(R.string.model_mgmt_asr_pick_named_onnx, expectedName, duration = Toast.LENGTH_LONG)
                return
            }
            joinerPath = uri.toString()
            if (isQwen3Asr()) settingsManager.setQwen3AsrConvFrontendPath(joinerPath)
            else settingsManager.setParakeetTdtJoinerPath(joinerPath)
            updateAsrModelUi()
        } catch (e: Exception) {
            showSelectFileFailed(e)
        }
    }

    private fun handleSelectedTokens(uri: Uri) {
        try {
            if (isSenseVoiceNpu() && !ensureQnnRuntimeAvailable()) return
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            if (!fileName.contains("token", ignoreCase = true) ||
                !fileName.endsWith(".txt", ignoreCase = true)) {
                host.showToast(R.string.model_mgmt_asr_pick_tokens, duration = Toast.LENGTH_LONG)
                return
            }

            tokensPath = uri.toString()
            when (modelType) {
                SettingsManager.ASR_MODEL_SENSEVOICE -> settingsManager.setSenseVoiceTokensPath(tokensPath)
                SettingsManager.ASR_MODEL_PARAKEET_TDT -> settingsManager.setParakeetTdtTokensPath(tokensPath)
                SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> settingsManager.setParakeetCtcTokensPath(tokensPath)
                else -> settingsManager.setWhisperTokensPath(tokensPath)
            }
            updateAsrModelUi()

        } catch (e: Exception) {
            showSelectFileFailed(e)
        }
    }

    private fun handleSelectedQwen3Tokenizer(uri: Uri) {
        val requiredFiles = listOf(
            "chat_template.json", "config.json", "merges.txt",
            "preprocessor_config.json", "tokenizer_config.json", "vocab.json"
        )
        val source = DocumentFile.fromTreeUri(context, uri)
        val sourceFiles = source?.listFiles()?.associateBy { it.name?.lowercase(Locale.ROOT) }
        val selectedFiles = requiredFiles.mapNotNull { name ->
            sourceFiles?.get(name.lowercase(Locale.ROOT))?.takeIf { it.isFile }
                ?.let { name to it }
        }
        if (selectedFiles.size != requiredFiles.size) {
            host.showToast(R.string.model_mgmt_asr_pick_tokenizer_folder, duration = Toast.LENGTH_LONG)
            return
        }

        val variant = settingsManager.getQwen3AsrModelVariant()
        val modelDirectory = File(context.filesDir, "models/qwen3-asr/$variant")
        val target = File(modelDirectory, "tokenizer")
        val staging = File(modelDirectory, ".tokenizer_importing")
        val backup = File(modelDirectory, ".tokenizer_backup")
        val progressToken = showProgressDialog(host.text(R.string.model_mgmt_asr_tokenizer_import_title)) {
            modelDownloadJob?.cancel(CancellationException("用户取消 Qwen3-ASR tokenizer 导入"))
        }
        setAsrModelActionsEnabled(false)
        modelDownloadJob = host.scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
                        throw IllegalStateException(host.text(R.string.model_mgmt_asr_tokenizer_mkdir_failed))
                    }
                    staging.deleteRecursively()
                    backup.deleteRecursively()
                    if (!staging.mkdirs()) throw IllegalStateException(host.text(R.string.model_mgmt_asr_tokenizer_staging_failed))
                    selectedFiles.forEach { (name, document) ->
                        currentCoroutineContext().ensureActive()
                        val output = File(staging, name)
                        context.contentResolver.openInputStream(document.uri)?.use { input ->
                            output.outputStream().use { destination ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    destination.write(buffer, 0, count)
                                }
                            }
                        } ?: throw IllegalStateException(host.text(R.string.model_mgmt_asr_tokenizer_read_failed, name))
                        if (output.length() == 0L) {
                            throw IllegalStateException(host.text(R.string.model_mgmt_asr_tokenizer_file_empty, name))
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (target.exists() && !target.renameTo(backup)) {
                        throw IllegalStateException(host.text(R.string.model_mgmt_asr_tokenizer_backup_failed))
                    }
                    try {
                        if (!staging.renameTo(target)) {
                            throw IllegalStateException(host.text(R.string.model_mgmt_asr_tokenizer_install_failed))
                        }
                    } catch (error: Exception) {
                        if (backup.exists() && !backup.renameTo(target)) {
                            error.addSuppressed(IllegalStateException(host.text(R.string.model_mgmt_asr_tokenizer_restore_failed)))
                        }
                        throw error
                    }
                    backup.deleteRecursively()
                    target
                }
                tokensPath = Uri.fromFile(imported).toString()
                settingsManager.setQwen3AsrTokenizerPath(tokensPath, variant)
                updateAsrModelUi()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.showToast(
                    R.string.model_mgmt_asr_tokenizer_import_failed,
                    e.message.toString(),
                    duration = Toast.LENGTH_LONG
                )
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    staging.deleteRecursively()
                    if (backup.exists() && !target.exists()) backup.renameTo(target)
                }
                dismissProgressDialog(progressToken)
                setAsrModelActionsEnabled(true)
                modelDownloadJob = null
            }
        }
    }

    private fun importQwen3ForcedAligner(uris: List<Uri>) {
        if (modelDownloadJob?.isActive == true) return
        if (uris.size != 2 || uris.distinct().size != 2) {
            host.showToast(R.string.qwen_aligner_select_pair, duration = Toast.LENGTH_LONG)
            return
        }
        setAsrModelActionsEnabled(false)
        modelDownloadJob = host.scope.launch {
            try {
                val graph = withContext(Dispatchers.IO) {
                    val files = uris.map { uri ->
                        val document = DocumentFile.fromSingleUri(context, uri)
                            ?: error(host.text(R.string.model_mgmt_asr_aligner_read_info_failed))
                        val name = document.name
                            ?: error(host.text(R.string.model_mgmt_asr_aligner_read_name_failed))
                        val file = Qwen3ForcedAlignerPathResolver.resolve(context, uri)
                            ?: error(host.text(R.string.model_mgmt_asr_aligner_local_path_failed, name))
                        check(file.name == name) { host.text(R.string.model_mgmt_asr_aligner_path_mismatch, name) }
                        name to file
                    }
                    val graphName = Qwen3ForcedAlignerModelFiles.graphName(files.map { it.first })
                    val selectedGraph = files.single { it.first == graphName }.second
                    check(files.all { it.second.canonicalFile.parentFile == selectedGraph.canonicalFile.parentFile }) {
                        host.text(R.string.model_mgmt_asr_aligner_same_folder)
                    }
                    check(Qwen3ForcedAlignerModelFiles.isConfigured(selectedGraph, context.filesDir)) {
                        host.text(R.string.model_mgmt_asr_aligner_files_missing)
                    }
                    selectedGraph
                }
                settingsManager.setQwen3ForcedAlignerPath(Uri.fromFile(graph).toString())
                updateAsrModelUi()
                onModelsChanged()
                host.showToast(R.string.model_mgmt_asr_aligner_selected)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                host.showToast(
                    R.string.model_mgmt_asr_aligner_import_failed,
                    error.message.toString(),
                    duration = Toast.LENGTH_LONG
                )
            } finally {
                modelDownloadJob = null
                setAsrModelActionsEnabled(true)
                updateAsrModelUi()
            }
        }
    }

    private fun handleSelectedVad(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            if (!fileName.contains("vad", ignoreCase = true) ||
                !fileName.endsWith(".onnx", ignoreCase = true)) {
                host.showToast(R.string.model_mgmt_asr_pick_vad, duration = Toast.LENGTH_LONG)
                return
            }

            vadModelPath = uri.toString()
            settingsManager.setVadModelPath(vadModelPath)
            settingsManager.setVadUseBuiltInModel(false)
            updateVadModelUi()
            host.showToast(R.string.model_mgmt_asr_vad_selected)

        } catch (e: Exception) {
            showSelectFileFailed(e)
        }
    }

    private fun updateVadModelUi() {
        val useBuiltInModel = settingsManager.isVadUseBuiltInModel()
        uiState = uiState.copy(
            vadModelFileName = if (useBuiltInModel || vadModelPath.isBlank()) null
            else getFileNameFromUri(Uri.parse(vadModelPath)),
            useBuiltInVad = useBuiltInModel
        )
    }

    private fun getFileNameFromUri(uri: Uri): String =
        UriDisplayName.of(context, uri, uri.lastPathSegment ?: host.text(R.string.model_mgmt_unknown_file))

    private fun showModelGuide() {
        val message = when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE ->
                if (settingsManager.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU) {
                    host.text(R.string.model_mgmt_asr_guide_sensevoice_npu)
                } else {
                    host.text(R.string.model_mgmt_asr_guide_sensevoice_cpu)
                }
            SettingsManager.ASR_MODEL_PARAKEET_TDT -> host.text(R.string.model_mgmt_asr_guide_parakeet_tdt)
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> host.text(R.string.model_mgmt_asr_guide_parakeet_ctc)
            SettingsManager.ASR_MODEL_QWEN3_ASR -> host.text(R.string.model_mgmt_asr_guide_qwen3)
            else -> host.text(R.string.model_mgmt_asr_guide_whisper)
        }

        val isQwenGuide = modelType == SettingsManager.ASR_MODEL_QWEN3_ASR
        val guideMessage = if (isQwenGuide) {
            val url = Qwen3ForcedAlignerReleaseDownloader.PROJECT_URL
            host.text(R.string.model_mgmt_asr_guide_aligner_project, message, url)
        } else message
        showMessageDialog(
            title = host.text(R.string.model_mgmt_asr_guide_title),
            message = guideMessage,
            confirmLabel = host.text(R.string.model_mgmt_ok),
            auxiliaryLabel = host.text(
                if (isQwenGuide) R.string.model_mgmt_asr_guide_open_modelscope
                else R.string.model_mgmt_asr_guide_open_github
            ),
            onAuxiliary = {
                val url = if (isQwenGuide) {
                    ModelDownloader.QWEN3_ASR_MODELSCOPE_URL
                } else if (
                    modelType == SettingsManager.ASR_MODEL_SENSEVOICE &&
                    settingsManager.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU
                ) {
                    "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models-qnn"
                } else {
                    "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models"
                }
                host.sendEvent(ModelManagementEvent.OpenUrl(url))
            }
        )
    }

    private fun showAsrModelPicker() {
        val types = arrayOf(
            SettingsManager.ASR_MODEL_SENSEVOICE,
            SettingsManager.ASR_MODEL_QWEN3_ASR,
            SettingsManager.ASR_MODEL_PARAKEET_TDT,
            SettingsManager.ASR_MODEL_WHISPER
        )
        val labels = arrayOf(
            host.text(R.string.model_mgmt_asr_type_sensevoice_recommended),
            "Qwen3-ASR",
            "Parakeet",
            "Whisper"
        )
        val checked = when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE -> 0
            SettingsManager.ASR_MODEL_QWEN3_ASR -> 1
            SettingsManager.ASR_MODEL_PARAKEET_TDT,
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> 2
            else -> 3
        }
        showOptionsDialog(
            host.text(R.string.model_mgmt_asr_select_type_title),
            labels.toList(),
            selectedIndex = checked
        ) { which ->
                val selectedType = types[which]
                if (selectedType != modelType) {
                    modelType = selectedType
                    settingsManager.setAsrModelType(selectedType)
                    restoreAvailableModelPathsIfMissing()
                    loadModelPaths()
                    updateAsrModelUi()
                }
            }
    }

    private fun selectParakeetVariant(selectedType: String) {
        if (selectedType == modelType) return
        modelType = selectedType
        settingsManager.setAsrModelType(selectedType)
        loadModelPaths()
        updateAsrModelUi()
    }

    private fun selectSenseVoiceProvider(provider: String) {
        if (provider == SettingsManager.SENSEVOICE_PROVIDER_NPU &&
            !ensureQnnRuntimeAvailable()
        ) {
            return
        }
        if (provider == settingsManager.getSenseVoiceProvider()) return
        settingsManager.setSenseVoiceProvider(provider)
        restoreAvailableModelPathsIfMissing()
        loadModelPaths()
        updateAsrModelUi()
    }

    private fun loadModelPaths() {
        when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE -> {
                encoderPath = settingsManager.getSenseVoiceModelPath()
                decoderPath = ""
                joinerPath = ""
                tokensPath = settingsManager.getSenseVoiceTokensPath()
            }
            SettingsManager.ASR_MODEL_PARAKEET_TDT -> {
                encoderPath = settingsManager.getParakeetTdtEncoderPath()
                decoderPath = settingsManager.getParakeetTdtDecoderPath()
                joinerPath = settingsManager.getParakeetTdtJoinerPath()
                tokensPath = settingsManager.getParakeetTdtTokensPath()
            }
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> {
                encoderPath = settingsManager.getParakeetCtcModelPath()
                decoderPath = ""
                joinerPath = ""
                tokensPath = settingsManager.getParakeetCtcTokensPath()
            }
            SettingsManager.ASR_MODEL_QWEN3_ASR -> {
                val variant = settingsManager.getQwen3AsrModelVariant()
                encoderPath = settingsManager.getQwen3AsrEncoderPath(variant)
                decoderPath = settingsManager.getQwen3AsrDecoderPath(variant)
                joinerPath = settingsManager.getQwen3AsrConvFrontendPath(variant)
                tokensPath = settingsManager.getQwen3AsrTokenizerPath(variant)
            }
            else -> {
                encoderPath = settingsManager.getWhisperEncoderPath()
                decoderPath = settingsManager.getWhisperDecoderPath()
                joinerPath = ""
                tokensPath = settingsManager.getWhisperTokensPath()
            }
        }
        discardInaccessibleAsrModels()
    }

    private fun discardInaccessibleAsrModels() {
        var discarded = false
        if (encoderPath.isNotBlank() && !canReadSavedUri(encoderPath)) {
            encoderPath = ""
            when (modelType) {
                SettingsManager.ASR_MODEL_SENSEVOICE -> settingsManager.setSenseVoiceModelPath("")
                SettingsManager.ASR_MODEL_PARAKEET_TDT -> settingsManager.setParakeetTdtEncoderPath("")
                SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> settingsManager.setParakeetCtcModelPath("")
                SettingsManager.ASR_MODEL_QWEN3_ASR -> settingsManager.setQwen3AsrEncoderPath("")
                else -> settingsManager.setWhisperEncoderPath("")
            }
            discarded = true
        }
        if (decoderPath.isNotBlank() && !canReadSavedUri(decoderPath)) {
            decoderPath = ""
            when (modelType) {
                SettingsManager.ASR_MODEL_PARAKEET_TDT -> settingsManager.setParakeetTdtDecoderPath("")
                SettingsManager.ASR_MODEL_QWEN3_ASR -> settingsManager.setQwen3AsrDecoderPath("")
                else -> settingsManager.setWhisperDecoderPath("")
            }
            discarded = true
        }
        if (joinerPath.isNotBlank() && !canReadSavedUri(joinerPath)) {
            joinerPath = ""
            if (isQwen3Asr()) settingsManager.setQwen3AsrConvFrontendPath("")
            else settingsManager.setParakeetTdtJoinerPath("")
            discarded = true
        }
        if (tokensPath.isNotBlank() && !canReadSavedUri(tokensPath)) {
            tokensPath = ""
            when (modelType) {
                SettingsManager.ASR_MODEL_SENSEVOICE -> settingsManager.setSenseVoiceTokensPath("")
                SettingsManager.ASR_MODEL_PARAKEET_TDT -> settingsManager.setParakeetTdtTokensPath("")
                SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> settingsManager.setParakeetCtcTokensPath("")
                SettingsManager.ASR_MODEL_QWEN3_ASR -> settingsManager.setQwen3AsrTokenizerPath("")
                else -> settingsManager.setWhisperTokensPath("")
            }
            discarded = true
        }
        if (discarded) showAccessExpiredMessage()
    }

    private fun discardInaccessibleVadModel() {
        if (vadModelPath.isBlank() || canReadSavedUri(vadModelPath)) return
        vadModelPath = ""
        settingsManager.setVadModelPath("")
        settingsManager.setVadUseBuiltInModel(true)
        showAccessExpiredMessage()
    }

    private fun canReadSavedUri(uriString: String): Boolean = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File) ?: return false
            file.isFile || (isQwen3Asr() && uriString == tokensPath && file.isDirectory)
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }
    }.getOrDefault(false)

    private fun releasePersistedReadPermission(uriString: String) {
        if (uriString.isBlank()) return
        val uri = Uri.parse(uriString)
        if (uri.scheme != "content") return
        val hasPersistedReadPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!hasPersistedReadPermission) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun showAccessExpiredMessage() {
        if (accessWarningShown) return
        accessWarningShown = true
        host.showToast(R.string.model_mgmt_access_expired, duration = Toast.LENGTH_LONG)
    }

    private fun updateAsrModelUi() {
        val senseVoice = modelType == SettingsManager.ASR_MODEL_SENSEVOICE
        val senseVoiceNpu = senseVoice &&
            settingsManager.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU
        val parakeetTdt = modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT
        val parakeetCtc = modelType == SettingsManager.ASR_MODEL_PARAKEET_CTC_JA
        val parakeet = parakeetTdt || parakeetCtc
        val qwen3Asr = isQwen3Asr()
        val hasSelectedModel = encoderPath.isNotBlank() || decoderPath.isNotBlank() ||
            joinerPath.isNotBlank() || tokensPath.isNotBlank()
        val alignerUi = if (qwen3Asr) {
            val alignerPath = settingsManager.getQwen3ForcedAlignerPath()
            val alignerFile = localFile(alignerPath)
            val complete = Qwen3ForcedAlignerModelFiles.isConfigured(alignerFile, context.filesDir)
            val downloadedGraph = if (hasModelStorageAccess()) {
                Qwen3ForcedAlignerModelFiles.findCompleteGraph(qwen3ForcedAlignerDirectory())
            } else null
            when {
                complete -> ForcedAlignerUi(
                    complete = true,
                    status = ForcedAlignerImportStatus.CONFIGURED,
                    graphName = alignerFile!!.name,
                    dataName = Qwen3ForcedAlignerModelFiles.dataFile(alignerFile).name
                )
                downloadedGraph != null -> ForcedAlignerUi(
                    complete = false,
                    status = ForcedAlignerImportStatus.LOCAL_MODEL_AVAILABLE
                )
                alignerPath.isNotBlank() -> ForcedAlignerUi(
                    complete = false,
                    status = ForcedAlignerImportStatus.INCOMPLETE
                )
                else -> ForcedAlignerUi(
                    complete = false,
                    status = ForcedAlignerImportStatus.NOT_CONFIGURED
                )
            }
        } else {
            ForcedAlignerUi(
                complete = uiState.forcedAlignerComplete,
                status = uiState.forcedAlignerStatus,
                graphName = uiState.forcedAlignerGraphName,
                dataName = uiState.forcedAlignerDataName
            )
        }
        val modelTitleRes = when {
            parakeet -> R.string.model_import_parakeet_model
            qwen3Asr -> R.string.model_import_qwen3_model
            senseVoice -> R.string.model_import_sensevoice_model
            else -> R.string.model_import_whisper_model
        }
        val encoderLabelRes = when {
            senseVoiceNpu -> R.string.model_import_sensevoice_npu_model
            senseVoice -> R.string.model_import_sensevoice_cpu_model
            parakeetCtc -> R.string.model_import_ctc_model
            else -> R.string.model_import_encoder_model
        }
        val encoderButtonLabelRes = if (senseVoice || parakeetCtc) {
            R.string.model_import_select_model
        } else {
            R.string.activity_model_settings_text_06
        }
        val joinerLabelRes = if (qwen3Asr) {
            R.string.model_import_conv_frontend_model
        } else {
            R.string.model_import_joiner_model
        }
        val joinerButtonLabelRes = if (qwen3Asr) {
            R.string.model_import_select_conv_frontend
        } else {
            R.string.model_import_select_joiner
        }
        val tokensLabelRes = if (qwen3Asr) {
            R.string.model_import_tokenizer_folder
        } else {
            R.string.model_import_tokens_file
        }
        val qnnRuntimeAvailable = QnnRuntimeAvailability.isAvailable(context)
        uiState = uiState.copy(
            modelTitleRes = modelTitleRes,
            encoderLabelRes = encoderLabelRes,
            encoderValue = encoderPath.takeIf(String::isNotEmpty)?.let {
                getFileNameFromUri(Uri.parse(it))
            } ?: "",
            encoderDurationSeconds = if (isSenseVoiceNpu() && encoderPath.isNotBlank()) {
                settingsManager.getSenseVoiceNpuDurationSeconds()
            } else null,
            encoderButtonLabelRes = encoderButtonLabelRes,
            decoderValue = decoderPath.takeIf(String::isNotEmpty)?.let {
                getFileNameFromUri(Uri.parse(it))
            } ?: "",
            joinerLabelRes = joinerLabelRes,
            joinerValue = joinerPath.takeIf(String::isNotEmpty)?.let {
                getFileNameFromUri(Uri.parse(it))
            } ?: "",
            joinerButtonLabelRes = joinerButtonLabelRes,
            tokensLabelRes = tokensLabelRes,
            tokensIsDirectory = qwen3Asr,
            tokensValue = when {
                tokensPath.isEmpty() -> ""
                qwen3Asr -> "tokenizer/"
                else -> getFileNameFromUri(Uri.parse(tokensPath))
            },
            showDecoder = !senseVoice && !parakeetCtc,
            showJoiner = parakeetTdt || qwen3Asr,
            showForcedAligner = qwen3Asr,
            forcedAlignerComplete = alignerUi.complete,
            forcedAlignerStatus = alignerUi.status,
            forcedAlignerGraphName = alignerUi.graphName,
            forcedAlignerDataName = alignerUi.dataName,
            showModelDownload = !hasSelectedModel,
            showModelReset = hasSelectedModel,
            showSenseVoiceProvider = senseVoice,
            useSenseVoiceNpu = senseVoiceNpu,
            npuAvailable = qnnRuntimeAvailable,
            showParakeetVariant = parakeet,
            parakeetCtcSelected = parakeetCtc
        )
    }

    private fun localFile(path: String): File? {
        if (path.isBlank()) return null
        val uri = Uri.parse(path)
        return if (uri.scheme.isNullOrEmpty() || uri.scheme == "file") {
            File(uri.path ?: path)
        } else {
            null
        }
    }

    private fun restoreAvailableModelPathsIfMissing(
        includeQwen: Boolean = true,
        includeNpu: Boolean = true
    ): Boolean {
        val modelsRoot = modelRepository.modelsDirectory()
        var restored = false
        if (includeQwen) {
            refreshForcedAlignerStatus()
        }
        if (includeNpu && modelType == SettingsManager.ASR_MODEL_SENSEVOICE &&
            settingsManager.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU
        ) {
            val currentBinary = localFile(settingsManager.getSenseVoiceModelPath())
            val currentTokens = localFile(settingsManager.getSenseVoiceTokensPath())
            if (currentBinary?.isFile != true || currentBinary.length() == 0L ||
                currentTokens?.isFile != true || currentTokens.length() == 0L
            ) {
                val selectedDuration = settingsManager.getSenseVoiceNpuDurationSeconds()
                val durations = listOf(selectedDuration, if (selectedDuration == 5) 10 else 5)
                val importer = SenseVoiceNpuModelImporter(context, context.contentResolver)
                val internalModel = durations.firstNotNullOfOrNull { seconds ->
                    importer.findInstalledModel(seconds)?.let { seconds to (it.contextBinary to it.tokens) }
                }
                val exportedModel = if (internalModel == null) durations.firstNotNullOfOrNull { seconds ->
                    val kind = if (seconds == 5) InternalModelExport.Kind.SENSEVOICE_NPU_5
                    else InternalModelExport.Kind.SENSEVOICE_NPU_10
                    InternalModelExport.completeSenseVoiceFiles(InternalModelExport.directory(modelsRoot, kind))
                        ?.let { seconds to it }
                } else null
                (internalModel ?: exportedModel)?.let { (seconds, files) ->
                    settingsManager.setSenseVoiceNpuDurationSeconds(seconds)
                    settingsManager.setSenseVoiceModelPath(Uri.fromFile(files.first).toString())
                    settingsManager.setSenseVoiceTokensPath(Uri.fromFile(files.second).toString())
                    restored = true
                }
            }
        }
        return restored
    }

    private fun adoptAvailableQwenForcedAligner(): Boolean {
        val graph = (if (hasModelStorageAccess()) {
            Qwen3ForcedAlignerModelFiles.findCompleteGraph(qwen3ForcedAlignerDirectory())
        } else null) ?: return false
        val path = Uri.fromFile(graph).toString()
        if (settingsManager.getQwen3ForcedAlignerPath() != path) {
            settingsManager.setQwen3ForcedAlignerPath(path)
        }
        updateAsrModelUi()
        return true
    }

    private fun qwen3ForcedAlignerDirectory(): File =
        File(modelRepository.modelsDirectory(), Qwen3ForcedAlignerModelFiles.DIRECTORY_NAME)

    fun refreshForcedAlignerStatus() {
        val selected = Qwen3ForcedAlignerModelFiles.configuredGraph(
            localFile(settingsManager.getQwen3ForcedAlignerPath()), context.filesDir
        )
        val detectedPath = selected?.let { Uri.fromFile(it).toString() }.orEmpty()
        if (settingsManager.getQwen3ForcedAlignerPath() != detectedPath) {
            settingsManager.setQwen3ForcedAlignerPath(detectedPath)
        }
        if (isQwen3Asr()) updateAsrModelUi()
    }

    private fun hasConfiguredQwen3ForcedAligner(): Boolean =
        Qwen3ForcedAlignerModelFiles.isConfigured(
            localFile(settingsManager.getQwen3ForcedAlignerPath()), context.filesDir
        )

    private fun isSingleFileModel(): Boolean =
        modelType == SettingsManager.ASR_MODEL_SENSEVOICE ||
            modelType == SettingsManager.ASR_MODEL_PARAKEET_CTC_JA

    private fun isSenseVoiceNpu(): Boolean =
        modelType == SettingsManager.ASR_MODEL_SENSEVOICE &&
            settingsManager.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU

    private fun isQwen3Asr(): Boolean = modelType == SettingsManager.ASR_MODEL_QWEN3_ASR

    private fun ensureQnnRuntimeAvailable(): Boolean {
        if (QnnRuntimeAvailability.isAvailable(context)) return true
        showMessageDialog(
            title = host.text(R.string.model_mgmt_asr_qnn_required_title),
            message = host.text(R.string.model_mgmt_asr_qnn_required_message),
            confirmLabel = host.text(R.string.model_mgmt_asr_open_download_page),
            dismissLabel = host.text(R.string.cancel),
            onConfirm = ::openQnnEditionReleases
        )
        return false
    }

    private fun openQnnEditionReleases() {
        host.sendEvent(
            ModelManagementEvent.OpenUrl(
                QnnRuntimeAvailability.QNN_EDITION_RELEASES_URL,
                failureMessage = host.text(R.string.model_mgmt_open_download_page_failed)
            )
        )
    }

    private fun currentModelDisplayName(): String = when (modelType) {
        SettingsManager.ASR_MODEL_SENSEVOICE -> "SenseVoice"
        SettingsManager.ASR_MODEL_PARAKEET_TDT -> "Parakeet TDT 0.6B v3"
        SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> host.text(R.string.model_mgmt_asr_name_parakeet_ctc_ja)
        SettingsManager.ASR_MODEL_QWEN3_ASR -> "Qwen3-ASR"
        else -> "Whisper"
    }

    fun refresh() {
        loadSavedSettings()
    }

    /** Called from ViewModel.onCleared(): the page is finishing for real. */
    fun dispose() {
        modelDownloadJob?.cancel()
        setDialog(null)
        pendingStorageAction = null
        pendingNotificationAction = null
    }

    private companion object {
        const val KEY_WORK_ID = "asr-model-download-work-id"
    }
}
