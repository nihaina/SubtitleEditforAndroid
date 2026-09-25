package com.subtitleedit

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.graphics.Typeface
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.databinding.ViewAsrModelImportBinding
import com.subtitleedit.repository.ModelRepository
import com.subtitleedit.task.TaskStatus
import com.subtitleedit.usecase.DownloadAsrModelUseCase
import com.subtitleedit.util.ModelDownloadProgressDialog
import com.subtitleedit.util.ModelDownloader
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.QnnRuntimeAvailability
import com.subtitleedit.util.Qwen3ForcedAlignerOnnx
import com.subtitleedit.util.Qwen3ForcedAlignerImporter
import com.subtitleedit.util.Qwen3ForcedAlignerModelFiles
import com.subtitleedit.util.SenseVoiceNpuModelImporter
import com.subtitleedit.util.SenseVoiceNpuModelPathPolicy
import com.subtitleedit.util.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * 模型设置页面
 */
class AsrModelImportController(private val host: AppCompatActivity, private val binding: ViewAsrModelImportBinding) {

    private lateinit var settingsManager: SettingsManager
    private val modelRepository: ModelRepository
        get() = (host.application as SubtitleEditApplication).dependencies.modelRepository

    private var encoderPath: String = ""
    private var decoderPath: String = ""
    private var joinerPath: String = ""
    private var tokensPath: String = ""
    private var vadModelPath: String = ""
    private var modelType: String = SettingsManager.ASR_MODEL_SENSEVOICE
    private var accessWarningShown = false
    private var modelDownloadJob: Job? = null
    private var modelDownloadWorkId: UUID? = null
    private var pendingNotificationAction: (() -> Unit)? = null
    private val notificationPermissionPreferences by lazy {
        host.getSharedPreferences("task_notifications", Context.MODE_PRIVATE)
    }
    private var modelDownloadDialog: ModelDownloadProgressDialog? = null
    private var modelDownloadErrorDialog: AlertDialog? = null
    private var pendingStorageAction: (() -> Unit)? = null

    // Encoder 文件选择器
    private val encoderPickerLauncher = host.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedEncoder(it) }
    }

    // Decoder 文件选择器
    private val decoderPickerLauncher = host.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedDecoder(it) }
    }

    private val joinerPickerLauncher = host.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedJoiner(it) }
    }

    // Tokens 文件选择器
    private val tokensPickerLauncher = host.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedTokens(it) }
    }

    private val qwen3TokenizerPickerLauncher = host.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { handleSelectedQwen3Tokenizer(it) } }

    private val qwen3ForcedAlignerPickerLauncher = host.registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) importQwen3ForcedAligner(uris) }

    // VAD 模型文件选择器
    private val vadPickerLauncher = host.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedVad(it) }
    }

    private val manageStorageLauncher = host.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { continuePendingModelDownload() }

    private val writeStoragePermissionLauncher = host.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { continuePendingModelDownload() }

    private val notificationPermissionLauncher = host.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionPreferences.edit().putBoolean("requested", true).apply()
        val action = pendingNotificationAction
        pendingNotificationAction = null
        if (action != null) {
            if (!granted) {
                OverwritingToast.makeText(
                    host, "未开启通知，下载仍会继续，可在此页面查看进度", Toast.LENGTH_LONG
                ).show()
            }
            action()
        }
    }

    init {
        settingsManager = SettingsManager.getInstance(host)
        setupButtons()
        restoreModelDownloadState()
        observeAsrModelDownload()
        loadSavedSettings()
    }

    private fun setupButtons() {
        binding.btnSelectEncoder.setOnClickListener {
            if (isSenseVoiceNpu() && !ensureQnnRuntimeAvailable()) return@setOnClickListener
            encoderPickerLauncher.launch(arrayOf("*/*"))
        }
        binding.btnDownloadAsrModel.setOnClickListener { showAsrDownloadOptions() }
        binding.btnResetAsrModel.setOnClickListener { confirmResetCurrentAsrModel() }

        binding.btnSelectDecoder.setOnClickListener {
            decoderPickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnSelectJoiner.setOnClickListener {
            joinerPickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnSelectTokens.setOnClickListener {
            if (isSenseVoiceNpu() && !ensureQnnRuntimeAvailable()) return@setOnClickListener
            if (isQwen3Asr()) qwen3TokenizerPickerLauncher.launch(null)
            else tokensPickerLauncher.launch(arrayOf("*/*"))
        }
        binding.btnSelectQwen3ForcedAligner.setOnClickListener {
            if (modelDownloadJob?.isActive == true) return@setOnClickListener
            qwen3ForcedAlignerPickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnVadConfig.setOnClickListener {
            host.startActivity(Intent(host, VadModelSettingsActivity::class.java))
        }
        binding.btnSelectVad.setOnClickListener { vadPickerLauncher.launch(arrayOf("*/*")) }
        binding.cbUseBuiltInVad.setOnCheckedChangeListener { _, checked ->
            settingsManager.setVadUseBuiltInModel(checked)
            updateVadModelUi()
        }

        binding.btnSwitchAsrModel.setOnClickListener { showAsrModelPicker() }
        binding.tvSenseVoiceCpuOption.setOnClickListener {
            selectSenseVoiceProvider(SettingsManager.SENSEVOICE_PROVIDER_CPU)
        }
        binding.tvSenseVoiceNpuOption.setOnClickListener {
            selectSenseVoiceProvider(SettingsManager.SENSEVOICE_PROVIDER_NPU)
        }
        binding.tvParakeetTdtOption.setOnClickListener {
            selectParakeetVariant(SettingsManager.ASR_MODEL_PARAKEET_TDT)
        }
        binding.tvParakeetCtcOption.setOnClickListener {
            selectParakeetVariant(SettingsManager.ASR_MODEL_PARAKEET_CTC_JA)
        }
        binding.btnWhisperConfig.setOnClickListener {
            host.startActivity(Intent(host, WhisperSettingsActivity::class.java))
        }

        binding.tvModelGuide.setOnClickListener {
            showModelGuide()
        }

    }

    private fun showAsrDownloadOptions() {
        if (modelDownloadJob?.isActive == true) {
            OverwritingToast.makeText(host, "模型正在下载", Toast.LENGTH_SHORT).show()
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
            val labels = options.map { "${it.displayName}（${it.sizeLabel}）" }.toTypedArray()
            AlertDialog.Builder(host)
                .setTitle("选择 SenseVoice NPU 模型")
                .setItems(labels) { _, which -> confirmSenseVoiceDownload(options[which]) }
                .setNegativeButton("取消", null)
                .show()
        } else {
            confirmSenseVoiceDownload(modelRepository.senseVoiceCpuModel)
        }
    }

    private fun confirmSenseVoiceDownload(option: ModelDownloader.SenseVoiceModelOption) {
        val isNpu = option.architecture == ModelDownloader.SenseVoiceArchitecture.QNN
        val location = "/Download/SubtitleEdit/models/${option.directoryName}"
        val compatibility = if (isNpu) {
            "\n\n下载完成后会立即生成内部 model.bin，并清理已导入的 libmodel.so。" +
                "\n适用于支持 Qualcomm HTP 的 arm64 骁龙设备。"
        } else {
            ""
        }
        AlertDialog.Builder(host)
            .setTitle("一键下载导入 SenseVoice ${option.displayName}")
            .setMessage(
                "是否一键下载导入该模型？\n\n" +
                    "文件存放至：\n$location\n\n" +
                    "${option.sizeLabel} 存储空间。$compatibility"
            )
            .setPositiveButton("下载并导入") { _, _ ->
                runWithModelStorageAccess { startSenseVoiceDownload(option) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmParakeetDownload(option: ModelDownloader.ParakeetModelOption) {
        AlertDialog.Builder(host)
            .setTitle("一键下载导入 ${option.displayName}")
            .setMessage(
                "${option.description}\n\n" +
                    "文件存放至：\n/Download/SubtitleEdit/models/${option.directoryName}\n\n" +
                    "${option.sizeLabel} 存储空间。"
            )
            .setPositiveButton("下载并导入") { _, _ ->
                runWithModelStorageAccess { startParakeetDownload(option) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showWhisperDownloadModelPicker() {
        val options = modelRepository.whisperModels
        val labels = options.map { "${it.displayName}（${it.sizeLabel}）" }.toTypedArray()
        AlertDialog.Builder(host)
            .setTitle("选择 Whisper 模型")
            .setItems(labels) { _, which -> confirmWhisperDownload(options[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmWhisperDownload(option: ModelDownloader.WhisperModelOption) {
        AlertDialog.Builder(host)
            .setTitle("一键下载导入 Whisper ${option.displayName}")
            .setMessage(
                "是否一键下载导入该模型？\n\n" +
                    "文件存放至：\n/Download/SubtitleEdit/models/${option.directoryName}\n\n" +
                    "${option.sizeLabel} 存储空间。"
            )
            .setPositiveButton("下载并导入") { _, _ ->
                runWithModelStorageAccess { startWhisperDownload(option) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmQwen3AsrDownload(option: ModelDownloader.Qwen3AsrModelOption) {
        AlertDialog.Builder(host)
            .setTitle("一键下载导入 Qwen3-ASR ${option.displayName}")
            .setMessage(
                "从 ModelScope 下载 int8 模型文件与 tokenizer。\n\n" +
                    "文件存放至：\n/Download/SubtitleEdit/models/${option.directoryName}\n\n" +
                    "模型文件约 ${option.sizeLabel}。"
            )
            .setPositiveButton("下载并导入") { _, _ ->
                runWithModelStorageAccess { startQwen3AsrDownload(option) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showQwen3AsrDownloadModelPicker() {
        val options = modelRepository.qwen3AsrModels
        val labels = options.map { "${it.displayName}（${it.sizeLabel}）" }.toTypedArray()
        AlertDialog.Builder(host)
            .setTitle("选择 Qwen3-ASR 模型")
            .setItems(labels) { _, which ->
                val option = options[which]
                confirmQwen3AsrDownload(option)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startSenseVoiceDownload(option: ModelDownloader.SenseVoiceModelOption) {
        val isNpu = option.architecture == ModelDownloader.SenseVoiceArchitecture.QNN
        if (isNpu && !ensureQnnRuntimeAvailable()) return
        if (isNpu && "arm64-v8a" !in Build.SUPPORTED_ABIS) {
            OverwritingToast.makeText(
                host, "SenseVoice NPU 模型仅支持 arm64-v8a 骁龙设备", Toast.LENGTH_LONG
            ).show()
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

    private fun startAsrModelDownload(kind: String, optionId: String) {
        if (modelDownloadJob?.isActive == true || pendingNotificationAction != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(host, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionPreferences.getBoolean("requested", false)
        ) {
            pendingNotificationAction = { observeAsrModelDownload(kind, optionId) }
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        observeAsrModelDownload(kind, optionId)
    }

    private fun restoreModelDownloadState() {
        val key = "asr-model-download"
        val savedState = host.savedStateRegistry.consumeRestoredStateForKey(key)
        modelDownloadWorkId = savedState?.getString("work-id")?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        host.savedStateRegistry.registerSavedStateProvider(key) {
            Bundle().apply { putString("work-id", modelDownloadWorkId?.toString()) }
        }
    }

    private fun observeAsrModelDownload(
        kind: String? = null,
        optionId: String? = null,
        retryWorkId: UUID? = null
    ) {
        if (modelDownloadJob?.isActive == true) return
        setAsrModelActionsEnabled(false)
        modelDownloadJob = host.lifecycleScope.launch {
            var progressDialog: ModelDownloadProgressDialog? = null
            try {
                val scheduler = (host.application as SubtitleEditApplication).dependencies.taskWorkScheduler
                val workId = if (retryWorkId != null) {
                    scheduler.retryModelDownload(retryWorkId)
                } else if (kind != null) {
                    scheduler.enqueueAsrModelDownload(kind, requireNotNull(optionId))
                } else {
                    scheduler.findActiveAsrModelDownload(modelDownloadWorkId) ?: return@launch
                }
                modelDownloadWorkId = workId
                progressDialog = ModelDownloadProgressDialog(host, "下载语音识别模型") {
                    host.lifecycleScope.launch {
                        try {
                            scheduler.cancel(workId)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            OverwritingToast.makeText(host, "取消下载失败：${error.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                modelDownloadDialog = progressDialog
                progressDialog.show()
                scheduler.observeTask(workId).takeWhile { taskState ->
                    if (taskState == null) {
                        modelDownloadWorkId = null
                        throw IllegalStateException("下载任务不存在")
                    }
                    taskState.progress.message.takeIf(String::isNotBlank)?.let { message ->
                        progressDialog.update(ModelDownloader.Progress(
                            message, taskState.progress.current, taskState.progress.total
                        ))
                    }
                    when (taskState.status) {
                        TaskStatus.SUCCEEDED -> {
                            modelDownloadWorkId = null
                            loadSavedSettings()
                            OverwritingToast.makeText(
                                host, "语音识别模型已下载、导入并自动选择", Toast.LENGTH_LONG
                            ).show()
                            false
                        }
                        TaskStatus.FAILED -> {
                            showModelDownloadFailure(workId, taskState.errorMessage ?: "模型任务失败")
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
                OverwritingToast.makeText(host, "模型任务失败：${error.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressDialog?.dismiss()
                if (modelDownloadDialog === progressDialog) modelDownloadDialog = null
                modelDownloadJob = null
                setAsrModelActionsEnabled(true)
                if (isActive && !host.isDestroyed && modelDownloadWorkId == null) {
                    migrateLegacySenseVoiceNpuSelectionIfNeeded()
                }
            }
        }
    }

    private fun showModelDownloadFailure(workId: UUID, error: String) {
        modelDownloadErrorDialog?.dismiss()
        modelDownloadErrorDialog = AlertDialog.Builder(host)
            .setTitle("模型下载或导入失败")
            .setMessage("$error\n\n重试时会尝试从已保存的下载进度继续。")
            .setPositiveButton("重试") { _, _ ->
                runWithModelStorageAccess { observeAsrModelDownload(retryWorkId = workId) }
            }
            .setNegativeButton("关闭") { _, _ -> modelDownloadWorkId = null }
            .setOnCancelListener { modelDownloadWorkId = null }
            .show()
    }

    private fun confirmResetCurrentAsrModel() {
        val modelName = currentModelDisplayName()
        AlertDialog.Builder(host)
            .setTitle("重置模型选择")
            .setMessage(
                "确定清除当前 $modelName 模型选择吗？\n\n" +
                    "模型文件不会被删除，重置后需要重新选择或导入。"
            )
            .setPositiveButton("重置") { _, _ -> resetCurrentAsrModel() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun resetCurrentAsrModel() {
        when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE -> settingsManager.clearSenseVoiceModelPaths()
            SettingsManager.ASR_MODEL_PARAKEET_TDT -> settingsManager.clearParakeetTdtModelPaths()
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> settingsManager.clearParakeetCtcModelPaths()
            SettingsManager.ASR_MODEL_QWEN3_ASR -> settingsManager.clearQwen3AsrModelPaths()
            else -> settingsManager.clearWhisperModelPaths()
        }
        if (modelType == SettingsManager.ASR_MODEL_QWEN3_ASR) {
            settingsManager.clearQwen3ForcedAlignerPath()
        }
        loadModelPaths()
        updateAsrModelUi()
        OverwritingToast.makeText(host, "已清除当前模型选择，请重新选择", Toast.LENGTH_SHORT).show()
    }

    private fun setAsrModelActionsEnabled(enabled: Boolean) {
        binding.btnDownloadAsrModel.isEnabled = enabled
        binding.btnResetAsrModel.isEnabled = enabled
        binding.btnSwitchAsrModel.isEnabled = enabled
        binding.btnSelectEncoder.isEnabled = enabled
        binding.btnSelectTokens.isEnabled = enabled
        binding.btnSelectDecoder.isEnabled = enabled
        binding.btnSelectJoiner.isEnabled = enabled
        binding.btnSelectQwen3ForcedAligner.isEnabled = enabled
        binding.tvSenseVoiceCpuOption.isEnabled = enabled
        binding.tvSenseVoiceNpuOption.isEnabled = enabled
        binding.tvParakeetTdtOption.isEnabled = enabled
        binding.tvParakeetCtcOption.isEnabled = enabled
    }

    private fun runWithModelStorageAccess(action: () -> Unit) {
        if (hasModelStorageAccess()) {
            action()
            return
        }
        pendingStorageAction = action
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$host.packageName")
            )
            val opened = runCatching { manageStorageLauncher.launch(appIntent) }.isSuccess ||
                runCatching {
                    manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }.isSuccess
            if (!opened) {
                pendingStorageAction = null
                OverwritingToast.makeText(host, "无法打开存储权限设置", Toast.LENGTH_LONG).show()
            }
        } else {
            writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun hasModelStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(host, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun continuePendingModelDownload() {
        val action = pendingStorageAction ?: return
        pendingStorageAction = null
        if (hasModelStorageAccess()) {
            action()
        } else {
            OverwritingToast.makeText(host, "需要存储权限才能保存下载的模型", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSavedSettings() {
        // 加载模型路径
        modelType = settingsManager.getAsrModelType()
        loadModelPaths()
        updateAsrModelUi()
        vadModelPath = settingsManager.getVadModelPath()
        discardInaccessibleVadModel()
        binding.cbUseBuiltInVad.isChecked = settingsManager.isVadUseBuiltInModel()
        updateVadModelUi()

        migrateLegacySenseVoiceNpuSelectionIfNeeded()
    }

    private fun migrateLegacySenseVoiceNpuSelectionIfNeeded() {
        if (!isSenseVoiceNpu() || encoderPath.isBlank()) return
        if (!QnnRuntimeAvailability.isAvailable(host)) return
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
                host.contentResolver.takePersistableUriPermission(
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
                OverwritingToast.makeText(
                    host,
                    when {
                        senseVoiceNpu -> "请选择 SenseVoice NPU 模型文件 libmodel.so"
                        isSingleFileModel() -> "请选择 ONNX 模型文件（以 .onnx 结尾）"
                        else -> "请选择 encoder 模型文件（文件名应包含 'encoder' 且以 .onnx 结尾）"
                    },
                    Toast.LENGTH_LONG
                ).show()
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
            OverwritingToast.makeText(host, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveSelectedEncoder(uri: Uri, fileName: String) {
        encoderPath = uri.toString()
        when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE -> settingsManager.setSenseVoiceModelPath(encoderPath)
            SettingsManager.ASR_MODEL_PARAKEET_TDT -> settingsManager.setParakeetTdtEncoderPath(encoderPath)
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> settingsManager.setParakeetCtcModelPath(encoderPath)
            SettingsManager.ASR_MODEL_QWEN3_ASR -> settingsManager.setQwen3AsrEncoderPath(encoderPath)
            else -> settingsManager.setWhisperEncoderPath(encoderPath)
        }
        binding.tvEncoderFile.text = fileName
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
        val labels = durations.map { "$it 秒模型" }.toTypedArray()
        AlertDialog.Builder(host)
            .setTitle("选择 SenseVoice NPU 模型时长")
            .setItems(labels) { _, which ->
                startSenseVoiceNpuImport(uri, durations[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startSenseVoiceNpuImport(modelUri: Uri, durationSeconds: Int) {
        if (modelDownloadJob?.isActive == true) return
        if (!ensureQnnRuntimeAvailable()) return
        if ("arm64-v8a" !in Build.SUPPORTED_ABIS) {
            OverwritingToast.makeText(
                host,
                "SenseVoice NPU 模型仅支持 arm64-v8a 骁龙设备",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val selectedTokensPath = settingsManager.getSenseVoiceTokensPath(
            SettingsManager.SENSEVOICE_PROVIDER_NPU
        )
        if (selectedTokensPath.isBlank() || !canReadSavedUri(selectedTokensPath)) {
            OverwritingToast.makeText(
                host,
                "请先选择 SenseVoice NPU 模型对应的 tokens.txt，再导入 libmodel.so",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val progressDialog = ModelDownloadProgressDialog(
            host,
            "导入 SenseVoice NPU 模型"
        ) { modelDownloadJob?.cancel() }
        modelDownloadDialog = progressDialog
        progressDialog.show()
        setAsrModelActionsEnabled(false)

        modelDownloadJob = host.lifecycleScope.launch {
            val importer = SenseVoiceNpuModelImporter(
                host,
                host.contentResolver
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
                        host.runOnUiThread {
                            modelDownloadDialog?.update(ModelDownloader.Progress(message))
                        }
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
                progressDialog.dismiss()
                OverwritingToast.makeText(
                    host,
                    "SenseVoice NPU BIN 模型已生成并自动选择",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: CancellationException) {
                progressDialog.dismiss()
                throw e
            } catch (e: Exception) {
                progressDialog.dismiss()
                OverwritingToast.makeText(
                    host,
                    "SenseVoice NPU 模型导入失败：${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setAsrModelActionsEnabled(true)
                if (modelDownloadDialog === progressDialog) modelDownloadDialog = null
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
            host.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            if (!fileName.contains("decoder", ignoreCase = true) ||
                !fileName.endsWith(".onnx", ignoreCase = true)) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    host,
                    "请选择 decoder 模型文件（文件名应包含 'decoder' 且以 .onnx 结尾）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            decoderPath = uri.toString()
            when (modelType) {
                SettingsManager.ASR_MODEL_PARAKEET_TDT -> settingsManager.setParakeetTdtDecoderPath(decoderPath)
                SettingsManager.ASR_MODEL_QWEN3_ASR -> settingsManager.setQwen3AsrDecoderPath(decoderPath)
                else -> settingsManager.setWhisperDecoderPath(decoderPath)
            }
            binding.tvDecoderFile.text = fileName
            updateAsrModelUi()

        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(host, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSelectedJoiner(uri: Uri) {
        try {
            host.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val fileName = getFileNameFromUri(uri)
            val expectedName = if (isQwen3Asr()) "conv_frontend" else "joiner"
            if (!fileName.contains(expectedName, ignoreCase = true) ||
                !fileName.endsWith(".onnx", ignoreCase = true)
            ) {
                OverwritingToast.makeText(
                    host,
                    "请选择 ${if (isQwen3Asr()) "conv_frontend" else "joiner"} 模型文件（文件名应包含 '$expectedName' 且以 .onnx 结尾）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            joinerPath = uri.toString()
            if (isQwen3Asr()) settingsManager.setQwen3AsrConvFrontendPath(joinerPath)
            else settingsManager.setParakeetTdtJoinerPath(joinerPath)
            binding.tvJoinerFile.text = fileName
            updateAsrModelUi()
        } catch (e: Exception) {
            OverwritingToast.makeText(host, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSelectedTokens(uri: Uri) {
        try {
            if (isSenseVoiceNpu() && !ensureQnnRuntimeAvailable()) return
            host.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            if (!fileName.contains("token", ignoreCase = true) ||
                !fileName.endsWith(".txt", ignoreCase = true)) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    host,
                    "请选择 tokens 文件（文件名应包含 'token' 且以 .txt 结尾）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            tokensPath = uri.toString()
            when (modelType) {
                SettingsManager.ASR_MODEL_SENSEVOICE -> settingsManager.setSenseVoiceTokensPath(tokensPath)
                SettingsManager.ASR_MODEL_PARAKEET_TDT -> settingsManager.setParakeetTdtTokensPath(tokensPath)
                SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> settingsManager.setParakeetCtcTokensPath(tokensPath)
                else -> settingsManager.setWhisperTokensPath(tokensPath)
            }
            binding.tvTokensFile.text = fileName
            updateAsrModelUi()

        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(host, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSelectedQwen3Tokenizer(uri: Uri) {
        val requiredFiles = listOf(
            "chat_template.json", "config.json", "merges.txt",
            "preprocessor_config.json", "tokenizer_config.json", "vocab.json"
        )
        val source = DocumentFile.fromTreeUri(host, uri)
        val sourceFiles = source?.listFiles()?.associateBy { it.name?.lowercase(Locale.ROOT) }
        val selectedFiles = requiredFiles.mapNotNull { name ->
            sourceFiles?.get(name.lowercase(Locale.ROOT))?.takeIf { it.isFile }
                ?.let { name to it }
        }
        if (selectedFiles.size != requiredFiles.size) {
            OverwritingToast.makeText(
                host,
                "请选择包含六个 Qwen3-ASR tokenizer 配置文件的文件夹",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val variant = settingsManager.getQwen3AsrModelVariant()
        val modelDirectory = File(host.filesDir, "models/qwen3-asr/$variant")
        val target = File(modelDirectory, "tokenizer")
        val staging = File(modelDirectory, ".tokenizer_importing")
        val backup = File(modelDirectory, ".tokenizer_backup")
        val progressDialog = ModelDownloadProgressDialog(host, "导入 Qwen3-ASR tokenizer") {
            modelDownloadJob?.cancel(CancellationException("用户取消 Qwen3-ASR tokenizer 导入"))
        }
        progressDialog.show()
        setAsrModelActionsEnabled(false)
        modelDownloadJob = host.lifecycleScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
                        throw IllegalStateException("无法创建 Qwen3-ASR 模型目录")
                    }
                    staging.deleteRecursively()
                    backup.deleteRecursively()
                    if (!staging.mkdirs()) throw IllegalStateException("无法创建 tokenizer 暂存目录")
                    selectedFiles.forEach { (name, document) ->
                        currentCoroutineContext().ensureActive()
                        val output = File(staging, name)
                        host.contentResolver.openInputStream(document.uri)?.use { input ->
                            output.outputStream().use { destination ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    destination.write(buffer, 0, count)
                                }
                            }
                        } ?: throw IllegalStateException("无法读取 $name")
                        if (output.length() == 0L) throw IllegalStateException("$name 文件为空")
                    }
                    currentCoroutineContext().ensureActive()
                    if (target.exists() && !target.renameTo(backup)) {
                        throw IllegalStateException("无法备份现有 tokenizer 文件夹")
                    }
                    try {
                        if (!staging.renameTo(target)) {
                            throw IllegalStateException("无法安装 tokenizer 文件夹")
                        }
                    } catch (error: Exception) {
                        if (backup.exists() && !backup.renameTo(target)) {
                            error.addSuppressed(IllegalStateException("无法恢复原 tokenizer 文件夹"))
                        }
                        throw error
                    }
                    backup.deleteRecursively()
                    target
                }
                tokensPath = Uri.fromFile(imported).toString()
                settingsManager.setQwen3AsrTokenizerPath(tokensPath, variant)
                binding.tvTokensFile.text = "tokenizer/"
                updateAsrModelUi()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OverwritingToast.makeText(host, "Tokenizer 导入失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    staging.deleteRecursively()
                    if (backup.exists() && !target.exists()) backup.renameTo(target)
                }
                progressDialog.dismiss()
                setAsrModelActionsEnabled(true)
                modelDownloadJob = null
            }
        }
    }

    private fun importQwen3ForcedAligner(uris: List<Uri>) {
        if (modelDownloadJob?.isActive == true) return
        if (uris.size != 2 || uris.distinct().size != 2) {
            OverwritingToast.makeText(host, host.getString(R.string.qwen_aligner_select_pair), Toast.LENGTH_LONG).show()
            return
        }
        val progressDialog = ModelDownloadProgressDialog(host, "导入 Qwen3 ForcedAligner") {
            modelDownloadJob?.cancel(CancellationException("用户取消 ForcedAligner 导入"))
        }
        modelDownloadDialog = progressDialog
        progressDialog.show()
        progressDialog.update(ModelDownloader.Progress("正在读取两个模型文件"))
        setAsrModelActionsEnabled(false)
        modelDownloadJob = host.lifecycleScope.launch {
            try {
                val sources = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val document = DocumentFile.fromSingleUri(host, uri)
                            ?: error("无法读取所选文件信息")
                        Qwen3ForcedAlignerImporter.Source(
                            name = document.name ?: error("无法读取模型文件名，请保留导出时的原始文件名"),
                            size = document.length().takeIf { it > 0L },
                        ) {
                            host.contentResolver.openInputStream(uri) ?: error("无法读取 ${document.name}")
                        }
                    }
                }
                Qwen3ForcedAlignerImporter(File(host.filesDir, "models/qwen3-asr/forced-aligner")).install(
                    sources = sources,
                    validate = { graph -> Qwen3ForcedAlignerOnnx(graph).use { } },
                    publish = { graph -> settingsManager.setQwen3ForcedAlignerPath(Uri.fromFile(graph).toString()) },
                    onProgress = { progress ->
                        withContext(Dispatchers.Main) {
                            progressDialog.update(ModelDownloader.Progress(progress.message, progress.copied, progress.total))
                        }
                    }
                )
                updateAsrModelUi()
                OverwritingToast.makeText(host, "Qwen3 ForcedAligner 模型及权重已导入", Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                OverwritingToast.makeText(
                    host,
                    "ForcedAligner 导入失败：${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progressDialog.dismiss()
                if (modelDownloadDialog === progressDialog) modelDownloadDialog = null
                modelDownloadJob = null
                setAsrModelActionsEnabled(true)
                if (!host.isDestroyed) updateAsrModelUi()
            }
        }
    }

    private fun handleSelectedVad(uri: Uri) {
        try {
            host.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            if (!fileName.contains("vad", ignoreCase = true) ||
                !fileName.endsWith(".onnx", ignoreCase = true)) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    host,
                    "请选择 VAD 模型文件（文件名应包含 'vad' 且以 .onnx 结尾）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            vadModelPath = uri.toString()
            settingsManager.setVadModelPath(vadModelPath)
            settingsManager.setVadUseBuiltInModel(false)
            updateVadModelUi()
            com.subtitleedit.util.OverwritingToast.makeText(host, "外部 VAD 模型已选择", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(host, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateVadModelUi() {
    }

    private fun getFileNameFromUri(uri: Uri): String {
        return runCatching {
            var fileName = uri.lastPathSegment ?: "未知文件"
            host.contentResolver.query(uri, null, null, null, null)?.use {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (it.moveToFirst() && nameIndex >= 0) {
                    fileName = it.getString(nameIndex)
                }
            }
            fileName
        }.getOrElse { uri.lastPathSegment ?: "未知文件" }
    }

    private fun showModelGuide() {
        val message = when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE ->
                if (settingsManager.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU) {
                    """
                        SenseVoice NPU 模型下载指引：

                        1. 点击蓝色下载按钮选择 5 秒或 10 秒模型，应用会自动下载、解压、生成 model.bin 并选择。5秒的模型一次最长只能识别5秒钟,也就是说单句话超过时间会被强制分段,请根据需要自行选择合适的模型。

                        2. 手动导入时请先选择 tokens.txt，再选择 libmodel.so；应用会立即生成并索引 model.bin，不会保留对 libmodel.so 的授权。

                        3. NPU 模型使用 Qualcomm QNN HTP，仅支持兼容的 arm64 骁龙设备,首次使用需要一段时间进行初始化。

                        4. 请注意,NPU模型的识别的速度不一定比CPU模型快,甚至可能会更慢,但是一定程度上可以减少转录时的设备负载。

                        5. SenseVoice 支持中文、英语、日语、韩语和粤语，并能识别部分声音事件与情绪。
                    """.trimIndent()
                } else {
                    """
                        SenseVoice CPU 模型下载指引：

                        1. 推荐点击“选择模型”右侧的蓝色下载按钮，应用会自动下载、解压并选择模型。

                        2. SenseVoice 支持中文、英语、日语、韩语和粤语，并能识别部分声音事件与情绪。

                        3. 手动导入需要选择 model.int8.onnx（或 model.onnx）和 tokens.txt。
                    """.trimIndent()
                }
            SettingsManager.ASR_MODEL_PARAKEET_TDT -> """
                Parakeet TDT 0.6B v3 模型说明：

                1. 推荐点击蓝色下载按钮一键下载、解压并选择模型，约占用 640 MB。

                2. 这是 NVIDIA NeMo FastConformer-TDT 模型，支持英语、法语、德语、西班牙语、意大利语、俄语、乌克兰语等 25 种欧洲语言，可自动识别语言，并输出标点、大小写和时间信息。

                3. 当前应用仍按 VAD 或固定时长分段进行离线识别；每个分段内部可利用 TDT 上下文，但不会在分段之间传递解码状态。

                4. 手动导入需要选择 encoder.int8.onnx、decoder.int8.onnx、joiner.int8.onnx 和 tokens.txt。

                5. 该模型不支持中文和日语。
            """.trimIndent()
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> """
                Parakeet CTC 0.6B 日语模型说明：

                1. 推荐点击蓝色下载按钮一键下载、解压并选择模型，约占用 628 MB。

                2. 这是 NVIDIA NeMo Parakeet 日语模型导出的 CTC 分支，适合日语音频转写和日语字幕生成。

                3. CTC 结构使用单个模型文件，解码和部署比 TDT 简单；该模型只用于日语，不支持中文，也不用于多语自动检测。

                4. 手动导入需要选择 model.int8.onnx 和 tokens.txt。
            """.trimIndent()
            SettingsManager.ASR_MODEL_QWEN3_ASR -> """
                Qwen3-ASR 模型说明：

                一键下载会自动获取 sherpa-onnx 所需的 conv_frontend、encoder、decoder 和 tokenizer 文件。

                手动导入需要选择 conv_frontend.onnx、encoder.int8.onnx、decoder.int8.onnx 和包含六个 tokenizer 配置文件的文件夹。

                当前按语音段生成字幕时间，暂不使用 Qwen3-ASR 的 token 时间戳。
            """.trimIndent()
            else -> """
                Whisper 模型下载指引：

                Whisper 是通用多语言语音识别模型，可在源语言中选择指定语言或使用自动检测。

                1. 推荐点击蓝色下载按钮选择 Tiny、Small、Large v3 或 Turbo，应用会自动下载、解压并选择模型。

                2. 模型越大通常识别效果越好，但需要更多存储、内存和处理时间。

                3. 手动导入需要选择 encoder.onnx、decoder.onnx 和 tokens.txt。
            """.trimIndent()
        }

        AlertDialog.Builder(host)
            .setTitle("模型下载指引")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .setNeutralButton("打开 GitHub") { _, _ ->
                val releaseTag = if (
                    modelType == SettingsManager.ASR_MODEL_SENSEVOICE &&
                    settingsManager.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU
                ) {
                    "asr-models-qnn"
                } else {
                    "asr-models"
                }
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/k2-fsa/sherpa-onnx/releases/tag/$releaseTag")
                )
                host.startActivity(intent)
            }
            .show()
    }

    private fun showAsrModelPicker() {
        val types = arrayOf(
            SettingsManager.ASR_MODEL_SENSEVOICE,
            SettingsManager.ASR_MODEL_QWEN3_ASR,
            SettingsManager.ASR_MODEL_PARAKEET_TDT,
            SettingsManager.ASR_MODEL_WHISPER
        )
        val labels = arrayOf(
            "SenseVoice",
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
        AlertDialog.Builder(host)
            .setTitle("选择识别模型")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selectedType = types[which]
                if (selectedType != modelType) {
                    modelType = selectedType
                    settingsManager.setAsrModelType(selectedType)
                    loadModelPaths()
                    updateAsrModelUi()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
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
        binding.tvEncoderFile.text = encoderPath.takeIf { it.isNotEmpty() }?.let {
            val fileName = getFileNameFromUri(Uri.parse(it))
            if (isSenseVoiceNpu()) {
                "$fileName（${settingsManager.getSenseVoiceNpuDurationSeconds()} 秒）"
            } else {
                fileName
            }
        } ?: "未选择"
        binding.tvDecoderFile.text = decoderPath.takeIf { it.isNotEmpty() }?.let { getFileNameFromUri(Uri.parse(it)) } ?: "未选择"
        binding.tvJoinerFile.text = joinerPath.takeIf { it.isNotEmpty() }?.let { getFileNameFromUri(Uri.parse(it)) } ?: "未选择"
        binding.tvTokensFile.text = when {
            tokensPath.isEmpty() -> "未选择"
            isQwen3Asr() -> "tokenizer/"
            else -> getFileNameFromUri(Uri.parse(tokensPath))
        }
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
            host.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }
    }.getOrDefault(false)

    private fun releasePersistedReadPermission(uriString: String) {
        if (uriString.isBlank()) return
        val uri = Uri.parse(uriString)
        if (uri.scheme != "content") return
        val hasPersistedReadPermission = host.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!hasPersistedReadPermission) return
        runCatching {
            host.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun showAccessExpiredMessage() {
        if (accessWarningShown) return
        accessWarningShown = true
        OverwritingToast.makeText(host, "模型访问权限已失效，请重新选择模型文件", Toast.LENGTH_LONG).show()
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
        binding.tvAsrModelTitle.text = if (parakeet) "Parakeet 模型" else "${currentModelDisplayName()} 模型"
        binding.btnDownloadAsrModel.contentDescription = "一键下载并导入 ${currentModelDisplayName()} 模型"
        binding.btnDownloadAsrModel.visibility = if (hasSelectedModel) View.GONE else View.VISIBLE
        binding.btnResetAsrModel.visibility = if (hasSelectedModel) View.VISIBLE else View.GONE
        binding.tvEncoderLabel.text = when {
            senseVoiceNpu -> "SenseVoice NPU 模型"
            senseVoice -> "SenseVoice CPU 模型"
            parakeetCtc -> "CTC 模型"
            qwen3Asr -> "Encoder 模型"
            else -> "Encoder 模型"
        }
        binding.btnSelectEncoder.text = if (senseVoice || parakeetCtc) "选择模型" else "选择 Encoder"
        binding.btnSelectEncoder.visibility = View.VISIBLE
        binding.btnSelectTokens.visibility = View.VISIBLE
        binding.layoutDecoder.visibility = if (senseVoice || parakeetCtc) View.GONE else View.VISIBLE
        binding.layoutJoiner.visibility = if (parakeetTdt || qwen3Asr) View.VISIBLE else View.GONE
        binding.tvJoinerLabel.text = if (qwen3Asr) "Conv Frontend 模型" else "Joiner 模型"
        binding.btnSelectJoiner.text = if (qwen3Asr) "选择 Conv Frontend" else "选择 Joiner"
        binding.tvTokensLabel.text = if (qwen3Asr) "Tokenizer 文件夹" else "Tokens 文件"
        binding.btnSelectTokens.text = if (qwen3Asr) "选择 Tokenizer 文件夹" else "选择 Tokens"
        binding.layoutQwen3ForcedAligner.visibility = if (qwen3Asr) View.VISIBLE else View.GONE
        if (qwen3Asr) {
            val alignerPath = settingsManager.getQwen3ForcedAlignerPath()
            val alignerFile = localFile(alignerPath)
            val complete = Qwen3ForcedAlignerModelFiles.isComplete(alignerFile)
            binding.tvQwen3ForcedAlignerPath.text = when {
                complete -> "已配置：${alignerFile!!.name} + ${Qwen3ForcedAlignerModelFiles.dataFile(alignerFile).name}"
                alignerPath.isNotBlank() -> "模型或权重文件缺失/不可读，请重新导入两个文件"
                else -> "尚未配置 ForcedAligner 模型及权重"
            }
            binding.tvQwen3ForcedAlignerPath.setTextColor(
                ContextCompat.getColor(
                    host,
                    if (complete) R.color.on_surface_variant else R.color.error
                )
            )
        }
        binding.btnWhisperConfig.visibility = if (modelType == SettingsManager.ASR_MODEL_WHISPER) View.VISIBLE else View.GONE
        binding.layoutSenseVoiceProviderOptions.visibility = if (senseVoice) View.VISIBLE else View.GONE
        binding.layoutParakeetVariantOptions.visibility = if (parakeet) View.VISIBLE else View.GONE
        binding.tvSenseVoiceCpuOption.setTextColor(
            ContextCompat.getColor(host, if (!senseVoiceNpu) R.color.primary else R.color.on_surface_variant)
        )
        binding.tvSenseVoiceNpuOption.setTextColor(
            ContextCompat.getColor(host, if (senseVoiceNpu) R.color.primary else R.color.on_surface_variant)
        )
        binding.tvSenseVoiceCpuOption.setTypeface(
            null,
            if (!senseVoiceNpu) Typeface.BOLD else Typeface.NORMAL
        )
        binding.tvSenseVoiceNpuOption.setTypeface(
            null,
            if (senseVoiceNpu) Typeface.BOLD else Typeface.NORMAL
        )
        val qnnRuntimeAvailable = QnnRuntimeAvailability.isAvailable(host)
        binding.tvSenseVoiceNpuOption.alpha = if (qnnRuntimeAvailable) 1f else 0.55f
        binding.tvSenseVoiceNpuOption.contentDescription = if (qnnRuntimeAvailable) {
            "选择 SenseVoice NPU"
        } else {
            "SenseVoice NPU，需要安装 QNN 版"
        }
        binding.tvParakeetTdtOption.setTextColor(
            ContextCompat.getColor(host, if (parakeetTdt) R.color.primary else R.color.on_surface_variant)
        )
        binding.tvParakeetCtcOption.setTextColor(
            ContextCompat.getColor(host, if (parakeetCtc) R.color.primary else R.color.on_surface_variant)
        )
        binding.tvParakeetTdtOption.setTypeface(null, if (parakeetTdt) Typeface.BOLD else Typeface.NORMAL)
        binding.tvParakeetCtcOption.setTypeface(null, if (parakeetCtc) Typeface.BOLD else Typeface.NORMAL)
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

    private fun isSingleFileModel(): Boolean =
        modelType == SettingsManager.ASR_MODEL_SENSEVOICE ||
            modelType == SettingsManager.ASR_MODEL_PARAKEET_CTC_JA

    private fun isSenseVoiceNpu(): Boolean =
        modelType == SettingsManager.ASR_MODEL_SENSEVOICE &&
            settingsManager.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU

    private fun isQwen3Asr(): Boolean = modelType == SettingsManager.ASR_MODEL_QWEN3_ASR

    private fun ensureQnnRuntimeAvailable(): Boolean {
        if (QnnRuntimeAvailability.isAvailable(host)) return true
        AlertDialog.Builder(host)
            .setTitle("需要安装 QNN 版")
            .setMessage(
                "当前安装包不包含 Qualcomm QNN 运行库，无法使用 SenseVoice NPU 模型。\n\n" +
                    "请前往项目发布页下载相同版本或更新版本的 arm64 QNN 安装包，并直接覆盖安装。已有模型和软件数据不会被清除。"
            )
            .setPositiveButton("打开下载页") { _, _ -> openQnnEditionReleases() }
            .setNegativeButton("取消", null)
            .show()
        return false
    }

    private fun openQnnEditionReleases() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(QnnRuntimeAvailability.QNN_EDITION_RELEASES_URL)
        )
        runCatching { host.startActivity(intent) }.onFailure {
            OverwritingToast.makeText(host, "无法打开下载页", Toast.LENGTH_LONG).show()
        }
    }

    private fun currentModelDisplayName(): String = when (modelType) {
        SettingsManager.ASR_MODEL_SENSEVOICE -> "SenseVoice"
        SettingsManager.ASR_MODEL_PARAKEET_TDT -> "Parakeet TDT 0.6B v3"
        SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> "Parakeet CTC 0.6B 日语"
        SettingsManager.ASR_MODEL_QWEN3_ASR -> "Qwen3-ASR"
        else -> "Whisper"
    }

    fun refresh() {
        loadSavedSettings()
    }

    fun dispose() {
        modelDownloadJob?.cancel()
        modelDownloadErrorDialog?.dismiss()
        modelDownloadErrorDialog = null
        pendingStorageAction = null
        pendingNotificationAction = null
        modelDownloadDialog?.dismiss()
        modelDownloadDialog = null
    }
}
