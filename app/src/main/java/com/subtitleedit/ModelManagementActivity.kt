package com.subtitleedit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.feature.ui.ModelManagementDialogUi
import com.subtitleedit.feature.ui.ModelManagementItemUi
import com.subtitleedit.feature.ui.ModelManagementScreen
import com.subtitleedit.feature.ui.ModelImportDialogUi
import com.subtitleedit.repository.ModelRepository
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.InternalModelExport
import com.subtitleedit.util.ModelDownloader
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.Qwen3ForcedAlignerModelFiles
import com.subtitleedit.util.SenseVoiceNpuModelImporter
import com.subtitleedit.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class ModelManagementActivity : AppComposeActivity() {
    private lateinit var settingsManager: SettingsManager
    private val modelRepository: ModelRepository
        get() = (application as SubtitleEditApplication).dependencies.modelRepository
    private var requestedStorageAccess = false
    private var modelScanVersion = 0
    private var pendingExportItem: ModelItem? = null
    private var exportJob: Job? = null
    private var modelItems by mutableStateOf(emptyList<ModelManagementItemUi>())
    private var isLoadingModels by mutableStateOf(false)
    private var modelErrorMessage by mutableStateOf<String?>(null)
    private var emptyMessage by mutableStateOf("未发现模型")
    private var selectedPage by mutableIntStateOf(0)
    private var deletingModelKey by mutableStateOf<String?>(null)
    private var isExportingModels by mutableStateOf(false)
    private var dialog by mutableStateOf<ModelManagementDialogUi?>(null)
    private var exportProgressDialog by mutableStateOf<ModelImportDialogUi?>(null)
    private var nextExportProgressToken = 0L
    private var scannedModels = emptyList<ModelItem>()

    private lateinit var asrImportController: AsrModelImportController
    private lateinit var demucsImportController: DemucsModelImportController

    private data class ModelItem(
        val category: String,
        val displayName: String,
        val file: File,
        val size: Long,
        val exportKind: InternalModelExport.Kind? = null,
        val canDelete: Boolean = true
    )

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { handleStorageAccessResult() }

    private val writeStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { handleStorageAccessResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedPage = savedInstanceState?.getInt(STATE_SELECTED_PAGE)?.coerceIn(0, 1) ?: 0
        settingsManager = SettingsManager.getInstance(this)

        asrImportController = AsrModelImportController(this) {
            loadModels()
        }
        demucsImportController = DemucsModelImportController(this)

        setContent {
            SubtitleEditComposeTheme {
                ModelManagementScreen(
                    asrImport = asrImportController.uiState,
                    demucsImport = demucsImportController.uiState,
                    selectedPage = selectedPage,
                    models = modelItems,
                    modelsDirectoryLabel = modelsDirectoryLabel(),
                    isLoading = isLoadingModels,
                    errorMessage = modelErrorMessage,
                    emptyMessage = emptyMessage,
                    isExporting = isExportingModels,
                    deletingModelKey = deletingModelKey,
                    asrDialog = asrImportController.dialog,
                    demucsDialog = demucsImportController.dialog,
                    exportDialog = exportProgressDialog,
                    dialog = dialog,
                    onPageSelected = ::onPageSelected,
                    onAsrImportAction = asrImportController::onAction,
                    onBuiltInVadChanged = asrImportController::onBuiltInVadChanged,
                    onDemucsImportAction = demucsImportController::onAction,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() },
                    onExport = ::onExportRequested,
                    onDelete = ::onDeleteRequested,
                    onDismissDialog = { dialog = null },
                    onConfirmDelete = ::onDeleteConfirmed,
                    onConfirmOverwrite = ::onOverwriteConfirmed
                )
            }
        }

        loadModels()
    }

    override fun onResume() {
        super.onResume()
        if (::asrImportController.isInitialized) {
            asrImportController.refresh()
            demucsImportController.refresh()
        }
        if (selectedPage == 1) loadModels()
    }

    private fun onPageSelected(position: Int) {
        if (selectedPage == position) return
        selectedPage = position
        if (position == 0) {
            asrImportController.refresh()
        } else {
            loadModels()
            if (!hasStorageAccess() && !requestedStorageAccess) requestStorageAccess()
        }
    }

    private fun requestStorageAccess() {
        requestedStorageAccess = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            val opened = runCatching { manageStorageLauncher.launch(appIntent) }.isSuccess ||
                runCatching {
                    manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }.isSuccess
            if (!opened) {
                requestedStorageAccess = false
                loadModels()
            }
        } else {
            writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun handleStorageAccessResult() {
        requestedStorageAccess = false
        loadModels()
        val pending = pendingExportItem
        pendingExportItem = null
        if (pending != null) {
            if (hasStorageAccess()) confirmExportModel(pending)
            else OverwritingToast.makeText(this, "导出模型需要下载目录存储权限", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasStorageAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun loadModels() {
        val scanVersion = ++modelScanVersion
        isLoadingModels = true
        modelErrorMessage = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { scanModels() } }
            if (scanVersion != modelScanVersion) return@launch
            isLoadingModels = false
            result.onSuccess { items ->
                asrImportController.refreshForcedAlignerStatus()
                scannedModels = items
                modelItems = items.map(::toUiModel)
                emptyMessage = if (hasStorageAccess()) "未发现模型" else "需要存储权限才能扫描下载模型"
            }.onFailure { error ->
                scannedModels = emptyList()
                modelItems = emptyList()
                modelErrorMessage = "模型目录读取失败：${error.message}"
            }
        }
    }

    private fun modelsDirectoryLabel(): String =
        "下载模型目录：${modelRepository.modelsDirectory().absolutePath}\n" +
            "SenseVoice NPU BIN 保存在应用内部目录"

    private fun modelKey(item: ModelItem): String = "${item.category}:${item.file.absolutePath}"

    private fun toUiModel(item: ModelItem) = ModelManagementItemUi(
        key = modelKey(item),
        category = item.category,
        displayName = item.displayName,
        path = item.file.absolutePath,
        formattedSize = formatSize(item.size),
        canExport = item.exportKind != null,
        canDelete = item.canDelete
    )

    private fun findModel(item: ModelManagementItemUi): ModelItem? =
        scannedModels.firstOrNull { modelKey(it) == item.key }

    private fun onExportRequested(uiItem: ModelManagementItemUi) {
        findModel(uiItem)?.let(::confirmExportModel)
    }

    private fun onDeleteRequested(uiItem: ModelManagementItemUi) {
        if (exportJob?.isActive != true) dialog = ModelManagementDialogUi.ConfirmDelete(uiItem)
    }

    private fun onDeleteConfirmed(uiItem: ModelManagementItemUi) {
        dialog = null
        val item = findModel(uiItem) ?: return
        deleteModel(item)
    }

    private fun onOverwriteConfirmed(uiItem: ModelManagementItemUi) {
        dialog = null
        val item = findModel(uiItem) ?: return
        val kind = item.exportKind ?: return
        startExportModel(item, kind)
    }

    private fun scanModels(): List<ModelItem> {
        val items = mutableListOf<ModelItem>()
        val root = modelRepository.modelsDirectory()
        val canScanDownloads = hasStorageAccess()
        if (canScanDownloads && root.isDirectory) {
            root.listFiles().orEmpty()
                .filterNot { it.name.startsWith(".") || it.name.contains(".part.") || it.name.endsWith(".backup") }
                .forEach { file ->
                    when {
                        file.isDirectory && file.name.startsWith("sherpa-onnx-sense-voice-") -> {
                            items += ModelItem("SenseVoice 模型", "SenseVoice", file, calculateSize(file))
                        }
                        file.isDirectory && file.name.startsWith("sherpa-onnx-qnn-") &&
                            file.name.contains("sense-voice") -> {
                            val option = modelRepository.senseVoiceNpuModels
                                .firstOrNull { it.directoryName == file.name }
                            items += ModelItem(
                                "SenseVoice 模型",
                                "SenseVoice NPU ${option?.displayName ?: file.name}",
                                file,
                                calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name.startsWith("sherpa-onnx-whisper-") -> {
                            val variant = file.name.removePrefix("sherpa-onnx-whisper-")
                            items += ModelItem(
                                "Whisper 模型",
                                "Whisper ${formatVariantName(variant)}",
                                file,
                                calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name == modelRepository.parakeetTdtModel.directoryName -> {
                            items += ModelItem(
                                "Parakeet 模型",
                                modelRepository.parakeetTdtModel.displayName,
                                file,
                                calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name == modelRepository.parakeetCtcJaModel.directoryName -> {
                            items += ModelItem(
                                "Parakeet 模型",
                                modelRepository.parakeetCtcJaModel.displayName,
                                file,
                                calculateSize(file)
                            )
                        }
                        file.isDirectory && modelRepository.qwen3AsrModels.any { it.directoryName == file.name } -> {
                            val option = modelRepository.qwen3AsrModels.first { it.directoryName == file.name }
                            items += ModelItem(
                                "Qwen3-ASR 模型",
                                "Qwen3-ASR ${option.displayName}",
                                file,
                                calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name == Qwen3ForcedAlignerModelFiles.DIRECTORY_NAME -> {
                            val complete = Qwen3ForcedAlignerModelFiles.findCompleteGraph(file) != null
                            items += ModelItem(
                                "Qwen3 强制对齐模型",
                                if (complete) "Qwen3 ForcedAligner"
                                else "Qwen3 ForcedAligner（文件不完整）",
                                file, calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name in listOf(
                            InternalModelExport.Kind.SENSEVOICE_NPU_5.directoryName,
                            InternalModelExport.Kind.SENSEVOICE_NPU_10.directoryName
                        ) -> {
                            val seconds = if (file.name == InternalModelExport.Kind.SENSEVOICE_NPU_5.directoryName) 5 else 10
                            items += ModelItem(
                                "SenseVoice 模型", "SenseVoice NPU $seconds 秒 BIN（已导出）",
                                file, calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name == modelRepository.separationDirectoryName -> {
                            file.listFiles().orEmpty().filterNot { it.name.startsWith(".") }.forEach { model ->
                                items += ModelItem("人声分离模型", model.name, model, calculateSize(model))
                            }
                        }
                        else -> {
                            items += ModelItem("其他模型文件", file.name, file, calculateSize(file))
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
        if (Qwen3ForcedAlignerModelFiles.isConfigured(selectedGraph, filesDir) &&
            runCatching { selectedGraph!!.parentFile?.canonicalFile != downloadedDirectory.canonicalFile }
                .getOrDefault(false)
        ) {
            val graph = requireNotNull(selectedGraph)
            items += ModelItem(
                "Qwen3 强制对齐模型", "Qwen3 ForcedAligner（已选择）",
                graph,
                graph.length() + Qwen3ForcedAlignerModelFiles.dataFile(graph).length(),
                canDelete = false
            )
        }
        val npuImporter = SenseVoiceNpuModelImporter(this, contentResolver)
        listOf(5, 10).mapNotNull(npuImporter::findInstalledModel).forEach { model ->
            val directory = requireNotNull(model.contextBinary.parentFile)
            items += ModelItem(
                "SenseVoice 模型",
                "SenseVoice NPU ${model.durationSeconds} 秒 BIN",
                directory,
                calculateSize(directory),
                if (model.durationSeconds == 5) InternalModelExport.Kind.SENSEVOICE_NPU_5
                else InternalModelExport.Kind.SENSEVOICE_NPU_10
            )
        }
        val categoryOrder = mapOf(
            "SenseVoice 模型" to 0,
            "Whisper 模型" to 1,
            "Parakeet 模型" to 2,
            "Qwen3-ASR 模型" to 3,
            "Qwen3 强制对齐模型" to 4,
            "人声分离模型" to 5,
            "其他模型文件" to 6
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
            if (!requestedStorageAccess) requestStorageAccess()
            return
        }
        val destination = InternalModelExport.directory(modelRepository.modelsDirectory(), kind)
        if (destination.exists()) {
            dialog = ModelManagementDialogUi.ConfirmOverwrite(toUiModel(item))
        } else {
            startExportModel(item, kind)
        }
    }

    private fun startExportModel(item: ModelItem, kind: InternalModelExport.Kind) {
        if (exportJob?.isActive == true) return
        val progressToken = ++nextExportProgressToken
        exportProgressDialog = ModelImportDialogUi.Progress(
            token = progressToken,
            title = "导出 ${item.displayName}",
            message = "正在准备下载"
        ) {
            if ((exportProgressDialog as? ModelImportDialogUi.Progress)?.token == progressToken) {
                exportProgressDialog = null
            }
            exportJob?.cancel()
        }
        isExportingModels = true
        exportJob = lifecycleScope.launch {
            try {
                val destination = withContext(Dispatchers.IO) {
                    InternalModelExport.export(item.file, modelRepository.modelsDirectory(), kind) { copied, total ->
                        withContext(Dispatchers.Main) {
                            updateExportProgress(
                                progressToken,
                                ModelDownloader.Progress("正在导出模型", copied, total)
                            )
                        }
                    }
                }
                OverwritingToast.makeText(
                    this@ModelManagementActivity,
                    "已导出至 ${destination.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
                loadModels()
            } catch (error: CancellationException) {
                OverwritingToast.makeText(this@ModelManagementActivity, "已取消模型导出", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                OverwritingToast.makeText(
                    this@ModelManagementActivity,
                    "导出失败：${error.message}", Toast.LENGTH_LONG
                ).show()
            } finally {
                if ((exportProgressDialog as? ModelImportDialogUi.Progress)?.token == progressToken) {
                    exportProgressDialog = null
                }
                exportJob = null
                isExportingModels = false
            }
        }
    }

    private fun updateExportProgress(token: Long, progress: ModelDownloader.Progress) {
        val current = exportProgressDialog as? ModelImportDialogUi.Progress ?: return
        if (current.token != token) return
        val total = progress.totalBytes
        val message = if (total > 0L) {
            val percent = ((progress.downloadedBytes * 100L) / total).coerceIn(0L, 100L)
            "${progress.message}：$percent%（${formatSize(progress.downloadedBytes)} / ${formatSize(total)}）"
        } else {
            "${progress.message}：已处理 ${formatSize(progress.downloadedBytes.coerceAtLeast(0L))}"
        }
        exportProgressDialog = current.copy(
            message = message,
            progress = if (total > 0L) progress.downloadedBytes.toFloat() / total else null
        )
    }

    private fun deleteModel(item: ModelItem) {
        deletingModelKey = modelKey(item)
        lifecycleScope.launch {
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
            if (deleted) {
                deletingModelKey = null
                OverwritingToast.makeText(
                    this@ModelManagementActivity,
                    "已删除 ${item.displayName}",
                    Toast.LENGTH_SHORT
                ).show()
                loadModels()
            } else {
                deletingModelKey = null
                OverwritingToast.makeText(
                    this@ModelManagementActivity,
                    "删除失败，请检查存储权限",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun clearSettingsReferencing(target: File) {
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

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    override fun onDestroy() {
        if (::asrImportController.isInitialized) asrImportController.dispose()
        if (::demucsImportController.isInitialized) demucsImportController.dispose()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_PAGE, selectedPage)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val STATE_SELECTED_PAGE = "state_selected_page"
    }
}
