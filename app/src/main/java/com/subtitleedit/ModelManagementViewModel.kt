package com.subtitleedit

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.subtitleedit.feature.ui.ModelImportDialogUi
import com.subtitleedit.feature.ui.ModelManagementDialogUi
import com.subtitleedit.feature.ui.ModelManagementItemUi
import com.subtitleedit.repository.ModelRepository
import com.subtitleedit.util.InternalModelExport
import com.subtitleedit.util.ModelDownloader
import com.subtitleedit.util.Qwen3ForcedAlignerModelFiles
import com.subtitleedit.util.SenseVoiceNpuModelImporter
import com.subtitleedit.util.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class ModelManagementUiState(
    val selectedPage: Int = 0,
    val models: List<ModelManagementItemUi> = emptyList(),
    val modelsDirectoryLabel: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val emptyMessage: String = "",
    val isExporting: Boolean = false,
    val deletingModelKey: String? = null,
    val dialog: ModelManagementDialogUi? = null
)

/**
 * Model management page: installed model list/export/delete plus the ASR and
 * vocal separation import controllers. Everything here survives Activity
 * recreation; the Activity only launches pickers/permission flows/navigation.
 */
internal class ModelManagementViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AppViewModel<ModelManagementUiState, ModelManagementEvent>(
    application,
    ModelManagementUiState(selectedPage = savedState.get<Int>(KEY_SELECTED_PAGE)?.coerceIn(0, 1) ?: 0)
) {
    private data class ModelItem(
        val category: String,
        val displayName: String,
        val file: File,
        val size: Long,
        val exportKind: InternalModelExport.Kind? = null,
        val canDelete: Boolean = true
    )

    private val settingsManager = SettingsManager.getInstance(application)
    private val modelRepository: ModelRepository get() = dependencies.modelRepository
    private var requestedStorageAccess = false
    private val pendingStorageRequesters = linkedSetOf<StorageAccessRequester>()
    private var modelScanVersion = 0
    private var pendingExportItem: ModelItem? = null
    private var exportJob: Job? = null
    private var scannedModels = emptyList<ModelItem>()

    private val importHost = object : ModelImportHost {
        override val app: Application get() = this@ModelManagementViewModel.app
        override val scope: CoroutineScope get() = viewModelScope
        override fun text(id: Int, vararg args: Any): String = string(id, *args)
        override fun showToast(text: CharSequence, duration: Int) = toast(text, duration)
        override fun sendEvent(event: ModelManagementEvent) = this@ModelManagementViewModel.sendEvent(event)
        override fun requestStorageAccess(requester: StorageAccessRequester) =
            this@ModelManagementViewModel.requestStorageAccess(requester)
    }

    val asrImport = AsrModelImportController(importHost, savedState) { loadModels() }
    val llmImport = LlmModelImportController(importHost, savedState) { loadModels() }
    val demucsImport = DemucsModelImportController(importHost, savedState)
    private val exportDialogs = ModelImportDialogState(importHost)
    val exportDialog: StateFlow<ModelImportDialogUi?> get() = exportDialogs.dialog

    init {
        setState {
            copy(
                modelsDirectoryLabel = string(
                    R.string.model_mgmt_directory_label,
                    modelRepository.modelsDirectory().absolutePath
                ),
                emptyMessage = string(R.string.model_mgmt_empty)
            )
        }
        loadModels()
    }

    /** Called from Activity.onResume, matching the previous onResume reloads. */
    fun refresh() {
        asrImport.refresh()
        llmImport.refresh()
        demucsImport.refresh()
        if (currentState.selectedPage == 1) loadModels()
    }

    fun onPageSelected(position: Int) {
        if (currentState.selectedPage == position) return
        setState { copy(selectedPage = position) }
        savedState[KEY_SELECTED_PAGE] = position
        if (position == 0) {
            asrImport.refresh()
            llmImport.refresh()
        } else {
            loadModels()
            if (!hasStorageAccess() && !requestedStorageAccess) requestModelListStorageAccess()
        }
    }

    // region Activity-only flows (storage / notification permission, pickers)

    private fun requestStorageAccess(requester: StorageAccessRequester) {
        pendingStorageRequesters += requester
        sendEvent(ModelManagementEvent.RequestStorageAccess)
    }

    private fun requestModelListStorageAccess() {
        requestedStorageAccess = true
        requestStorageAccess(StorageAccessRequester.MODEL_LIST)
    }

    fun onStorageAccessResult() = drainStorageRequesters { requester ->
        when (requester) {
            StorageAccessRequester.MODEL_LIST -> handleStorageAccessResult()
            StorageAccessRequester.ASR_IMPORT -> asrImport.onStorageAccessResult()
            StorageAccessRequester.LLM_IMPORT -> llmImport.onStorageAccessResult()
            StorageAccessRequester.DEMUCS_IMPORT -> demucsImport.onStorageAccessResult()
        }
    }

    /** No storage permission settings screen could be opened. */
    fun onStorageAccessUnavailable() = drainStorageRequesters { requester ->
        when (requester) {
            StorageAccessRequester.MODEL_LIST -> {
                requestedStorageAccess = false
                loadModels()
            }
            StorageAccessRequester.ASR_IMPORT -> asrImport.onStorageAccessUnavailable()
            StorageAccessRequester.LLM_IMPORT -> llmImport.onStorageAccessUnavailable()
            StorageAccessRequester.DEMUCS_IMPORT -> demucsImport.onStorageAccessUnavailable()
        }
    }

    private inline fun drainStorageRequesters(block: (StorageAccessRequester) -> Unit) {
        val requesters = pendingStorageRequesters.toList()
        pendingStorageRequesters.clear()
        requesters.forEach(block)
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        app.getSharedPreferences("task_notifications", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("requested", true).apply()
        asrImport.onNotificationPermissionResult(granted)
        llmImport.onNotificationPermissionResult(granted)
        demucsImport.onNotificationPermissionResult(granted)
    }

    fun onDocumentPicked(target: ModelPickTarget, uri: Uri) {
        asrImport.onDocumentPicked(target, uri)
        llmImport.onDocumentPicked(target, uri)
        demucsImport.onDocumentPicked(target, uri)
    }

    fun onTokenizerFolderPicked(uri: Uri) = asrImport.onTokenizerFolderPicked(uri)

    fun onForcedAlignerPicked(uris: List<Uri>) = asrImport.onForcedAlignerPicked(uris)

    // endregion

    private fun handleStorageAccessResult() {
        requestedStorageAccess = false
        loadModels()
        val pending = pendingExportItem
        pendingExportItem = null
        if (pending != null) {
            if (hasStorageAccess()) confirmExportModel(pending)
            else toast(R.string.model_mgmt_export_requires_storage, duration = Toast.LENGTH_LONG)
        }
    }

    private fun hasStorageAccess(): Boolean = hasModelStorageAccess(app)

    private fun loadModels() {
        val scanVersion = ++modelScanVersion
        setState { copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { scanModels() } }
            if (scanVersion != modelScanVersion) return@launch
            setState { copy(isLoading = false) }
            result.onSuccess { items ->
                asrImport.refreshForcedAlignerStatus()
                scannedModels = items
                val empty = string(
                    if (hasStorageAccess()) R.string.model_mgmt_empty
                    else R.string.model_mgmt_storage_required_for_scan
                )
                setState { copy(models = items.map(::toUiModel), emptyMessage = empty) }
            }.onFailure { error ->
                scannedModels = emptyList()
                val message = string(R.string.model_mgmt_read_directory_failed, error.message.toString())
                setState { copy(models = emptyList(), errorMessage = message) }
            }
        }
    }

    private fun modelKey(item: ModelItem): String = "${item.category}:${item.file.absolutePath}"

    private fun toUiModel(item: ModelItem) = ModelManagementItemUi(
        key = modelKey(item),
        category = item.category,
        displayName = item.displayName,
        path = item.file.absolutePath,
        formattedSize = formatModelSize(item.size),
        canExport = item.exportKind != null,
        canDelete = item.canDelete
    )

    private fun findModel(item: ModelManagementItemUi): ModelItem? =
        scannedModels.firstOrNull { modelKey(it) == item.key }

    fun onExportRequested(uiItem: ModelManagementItemUi) {
        findModel(uiItem)?.let(::confirmExportModel)
    }

    fun onDeleteRequested(uiItem: ModelManagementItemUi) {
        if (exportJob?.isActive != true) setState { copy(dialog = ModelManagementDialogUi.ConfirmDelete(uiItem)) }
    }

    fun dismissDialog() = setState { copy(dialog = null) }

    fun onDeleteConfirmed(uiItem: ModelManagementItemUi) {
        dismissDialog()
        val item = findModel(uiItem) ?: return
        deleteModel(item)
    }

    fun onOverwriteConfirmed(uiItem: ModelManagementItemUi) {
        dismissDialog()
        val item = findModel(uiItem) ?: return
        val kind = item.exportKind ?: return
        startExportModel(item, kind)
    }

    private fun scanModels(): List<ModelItem> {
        val senseVoice = string(R.string.model_mgmt_category_sensevoice)
        val whisper = string(R.string.model_mgmt_category_whisper)
        val parakeet = string(R.string.model_mgmt_category_parakeet)
        val qwen3Asr = string(R.string.model_mgmt_category_qwen3_asr)
        val qwen3Aligner = string(R.string.model_mgmt_category_qwen3_aligner)
        val llm = string(R.string.model_mgmt_category_llm)
        val separation = string(R.string.model_mgmt_category_separation)
        val other = string(R.string.model_mgmt_category_other)
        val items = mutableListOf<ModelItem>()
        val root = modelRepository.modelsDirectory()
        val canScanDownloads = hasStorageAccess()
        if (canScanDownloads && root.isDirectory) {
            root.listFiles().orEmpty()
                .filterNot { it.name.startsWith(".") || it.name.contains(".part.") || it.name.endsWith(".backup") }
                .forEach { file ->
                    when {
                        file.isDirectory && file.name.startsWith("sherpa-onnx-sense-voice-") -> {
                            items += ModelItem(senseVoice, "SenseVoice", file, calculateSize(file))
                        }
                        file.isDirectory && file.name.startsWith("sherpa-onnx-qnn-") &&
                            file.name.contains("sense-voice") -> {
                            val option = modelRepository.senseVoiceNpuModels
                                .firstOrNull { it.directoryName == file.name }
                            items += ModelItem(
                                senseVoice,
                                "SenseVoice NPU ${option?.displayName ?: file.name}",
                                file,
                                calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name.startsWith("sherpa-onnx-whisper-") -> {
                            val variant = file.name.removePrefix("sherpa-onnx-whisper-")
                            items += ModelItem(
                                whisper,
                                "Whisper ${formatVariantName(variant)}",
                                file,
                                calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name == modelRepository.parakeetTdtModel.directoryName -> {
                            items += ModelItem(
                                parakeet,
                                modelRepository.parakeetTdtModel.displayName,
                                file,
                                calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name == modelRepository.parakeetCtcJaModel.directoryName -> {
                            items += ModelItem(
                                parakeet,
                                modelRepository.parakeetCtcJaModel.displayName,
                                file,
                                calculateSize(file)
                            )
                        }
                        file.isDirectory && modelRepository.qwen3AsrModels.any { it.directoryName == file.name } -> {
                            val option = modelRepository.qwen3AsrModels.first { it.directoryName == file.name }
                            items += ModelItem(
                                qwen3Asr,
                                "Qwen3-ASR ${option.displayName}",
                                file,
                                calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name == Qwen3ForcedAlignerModelFiles.DIRECTORY_NAME -> {
                            val complete = Qwen3ForcedAlignerModelFiles.findCompleteGraph(file) != null
                            items += ModelItem(
                                qwen3Aligner,
                                if (complete) "Qwen3 ForcedAligner"
                                else string(R.string.model_mgmt_item_aligner_incomplete),
                                file, calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name in listOf(
                            InternalModelExport.Kind.SENSEVOICE_NPU_5.directoryName,
                            InternalModelExport.Kind.SENSEVOICE_NPU_10.directoryName
                        ) -> {
                            val seconds = if (file.name == InternalModelExport.Kind.SENSEVOICE_NPU_5.directoryName) 5 else 10
                            items += ModelItem(
                                senseVoice, string(R.string.model_mgmt_item_npu_exported, seconds),
                                file, calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name == modelRepository.separationDirectoryName -> {
                            file.listFiles().orEmpty().filterNot { it.name.startsWith(".") }.forEach { model ->
                                items += ModelItem(separation, model.name, model, calculateSize(model))
                            }
                        }
                        file.isDirectory && file.name == ModelDownloader.LLM_DIRECTORY_NAME -> {
                            file.listFiles().orEmpty().filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
                                .forEach { model ->
                                    items += ModelItem(llm, model.name, model, calculateSize(model))
                                }
                        }
                        else -> {
                            items += ModelItem(other, file.name, file, calculateSize(file))
                        }
                    }
                }
        }
        val selectedPath = settingsManager.getQwen3ForcedAlignerPath()
        val selectedUri = runCatching { Uri.parse(selectedPath) }.getOrNull()
        val selectedGraph = if (selectedUri?.scheme.isNullOrEmpty() || selectedUri?.scheme == "file") {
            selectedUri?.path?.let(::File)
        } else null
        val downloadedDirectory = File(root, Qwen3ForcedAlignerModelFiles.DIRECTORY_NAME)
        if (Qwen3ForcedAlignerModelFiles.isConfigured(selectedGraph, app.filesDir) &&
            runCatching { selectedGraph!!.parentFile?.canonicalFile != downloadedDirectory.canonicalFile }
                .getOrDefault(false)
        ) {
            val graph = requireNotNull(selectedGraph)
            items += ModelItem(
                qwen3Aligner, string(R.string.model_mgmt_item_aligner_selected),
                graph,
                graph.length() + Qwen3ForcedAlignerModelFiles.dataFile(graph).length(),
                canDelete = false
            )
        }
        val npuImporter = SenseVoiceNpuModelImporter(app, app.contentResolver)
        listOf(5, 10).mapNotNull(npuImporter::findInstalledModel).forEach { model ->
            val directory = requireNotNull(model.contextBinary.parentFile)
            items += ModelItem(
                senseVoice,
                string(R.string.model_mgmt_item_npu_internal, model.durationSeconds),
                directory,
                calculateSize(directory),
                if (model.durationSeconds == 5) InternalModelExport.Kind.SENSEVOICE_NPU_5
                else InternalModelExport.Kind.SENSEVOICE_NPU_10
            )
        }
        val categoryOrder = mapOf(
            senseVoice to 0,
            whisper to 1,
            parakeet to 2,
            qwen3Asr to 3,
            qwen3Aligner to 4,
            llm to 5,
            separation to 6,
            other to 7
        )
        return items.sortedWith(
            compareBy<ModelItem> { categoryOrder[it.category] ?: Int.MAX_VALUE }
                .thenBy { it.displayName.lowercase() }
        )
    }

    private fun confirmExportModel(item: ModelItem) {
        val kind = item.exportKind ?: return
        if (exportJob?.isActive == true) return
        if (!hasStorageAccess()) {
            pendingExportItem = item
            if (!requestedStorageAccess) requestModelListStorageAccess()
            return
        }
        val destination = InternalModelExport.directory(modelRepository.modelsDirectory(), kind)
        if (destination.exists()) {
            setState { copy(dialog = ModelManagementDialogUi.ConfirmOverwrite(toUiModel(item))) }
        } else {
            startExportModel(item, kind)
        }
    }

    private fun startExportModel(item: ModelItem, kind: InternalModelExport.Kind) {
        if (exportJob?.isActive == true) return
        val progressToken = exportDialogs.showProgressDialog(string(R.string.model_mgmt_export_title, item.displayName)) {
            exportJob?.cancel()
        }
        setState { copy(isExporting = true) }
        exportJob = viewModelScope.launch {
            try {
                val destination = withContext(Dispatchers.IO) {
                    InternalModelExport.export(item.file, modelRepository.modelsDirectory(), kind) { copied, total ->
                        exportDialogs.updateProgressDialog(
                            progressToken,
                            ModelDownloader.Progress(string(R.string.model_mgmt_exporting), copied, total)
                        )
                    }
                }
                toast(R.string.model_mgmt_exported_to, destination.absolutePath, duration = Toast.LENGTH_LONG)
                loadModels()
            } catch (error: CancellationException) {
                toast(R.string.model_mgmt_export_cancelled)
            } catch (error: Exception) {
                toast(R.string.model_mgmt_export_failed, error.message.toString(), duration = Toast.LENGTH_LONG)
            } finally {
                exportDialogs.dismissProgressDialog(progressToken)
                exportJob = null
                setState { copy(isExporting = false) }
            }
        }
    }

    private fun deleteModel(item: ModelItem) {
        setState { copy(deletingModelKey = modelKey(item)) }
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching {
                    val removed = if (item.file.isDirectory) item.file.deleteRecursively() else item.file.delete()
                    if (removed || !item.file.exists()) {
                        clearSettingsReferencing(item.file)
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
            }
            setState { copy(deletingModelKey = null) }
            if (deleted) {
                toast(R.string.model_mgmt_deleted, item.displayName)
                loadModels()
            } else {
                toast(R.string.model_mgmt_delete_failed, duration = Toast.LENGTH_LONG)
            }
        }
    }

    private fun clearSettingsReferencing(target: File) {
        SettingsManager.LLM_MODEL_FAMILIES.forEach { family ->
            val llmPath = settingsManager.getLlmModelPath(family)
            if (pointsInsideTarget(llmPath, target)) settingsManager.clearLlmModelPath(family)
        }
        val whisperPaths = listOf(
            settingsManager.getWhisperEncoderPath(),
            settingsManager.getWhisperDecoderPath(),
            settingsManager.getWhisperTokensPath()
        )
        if (whisperPaths.any { pointsInsideTarget(it, target) }) {
            settingsManager.clearWhisperModelPaths()
        }
        listOf(
            SettingsManager.SENSEVOICE_PROVIDER_CPU,
            SettingsManager.SENSEVOICE_PROVIDER_NPU
        ).forEach { provider ->
            val senseVoicePaths = listOf(
                settingsManager.getSenseVoiceModelPath(provider),
                settingsManager.getSenseVoiceTokensPath(provider)
            )
            if (senseVoicePaths.any { pointsInsideTarget(it, target) }) {
                settingsManager.clearSenseVoiceModelPaths(provider)
            }
        }
        val parakeetTdtPaths = listOf(
            settingsManager.getParakeetTdtEncoderPath(),
            settingsManager.getParakeetTdtDecoderPath(),
            settingsManager.getParakeetTdtJoinerPath(),
            settingsManager.getParakeetTdtTokensPath()
        )
        if (parakeetTdtPaths.any { pointsInsideTarget(it, target) }) {
            settingsManager.clearParakeetTdtModelPaths()
        }
        val parakeetCtcPaths = listOf(
            settingsManager.getParakeetCtcModelPath(),
            settingsManager.getParakeetCtcTokensPath()
        )
        if (parakeetCtcPaths.any { pointsInsideTarget(it, target) }) {
            settingsManager.clearParakeetCtcModelPaths()
        }
        ModelDownloader.QWEN3_ASR_MODELS.forEach { option ->
            val qwen3Paths = listOf(
                settingsManager.getQwen3AsrEncoderPath(option.id),
                settingsManager.getQwen3AsrDecoderPath(option.id),
                settingsManager.getQwen3AsrConvFrontendPath(option.id),
                settingsManager.getQwen3AsrTokenizerPath(option.id)
            )
            if (qwen3Paths.any { pointsInsideTarget(it, target) }) {
                settingsManager.clearQwen3AsrModelPaths(option.id)
            }
        }
        if (pointsInsideTarget(settingsManager.getQwen3ForcedAlignerPath(), target)) {
            settingsManager.clearQwen3ForcedAlignerPath()
        }
        if (pointsInsideTarget(settingsManager.getVadModelPath(), target)) {
            settingsManager.setVadModelPath("")
            settingsManager.setVadUseBuiltInModel(true)
        }
        listOf("general", "vocals", "drums", "bass", "other").forEach { key ->
            if (pointsInsideTarget(settingsManager.getDemixModelUri(key), target)) {
                settingsManager.setDemixModelUri(key, "")
            }
        }
    }

    private fun pointsInsideTarget(uriString: String, target: File): Boolean {
        if (uriString.isBlank()) return false
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        if (!uri.scheme.isNullOrBlank() && uri.scheme != "file") return false
        val path = uri.path ?: uriString
        return runCatching {
            File(path).canonicalFile.toPath().startsWith(target.canonicalFile.toPath())
        }.getOrDefault(false)
    }

    private fun calculateSize(file: File): Long = runCatching {
        when {
            file.isFile -> file.length()
            file.isDirectory -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            else -> 0L
        }
    }.getOrDefault(0L)

    private fun formatVariantName(value: String): String = when (value.lowercase()) {
        "tiny" -> "Tiny"
        "small" -> "Small"
        "large-v3" -> "Large v3"
        "turbo" -> "Turbo"
        else -> value
    }

    override fun onCleared() {
        // Page is finishing for real (not a configuration change).
        asrImport.dispose()
        llmImport.dispose()
        demucsImport.dispose()
        exportJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val KEY_SELECTED_PAGE = "state_selected_page"
    }
}
