package com.subtitleedit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Typeface
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.databinding.ActivityModelSettingsBinding
import com.subtitleedit.util.ModelDownloadProgressDialog
import com.subtitleedit.util.ModelDownloader
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.QnnRuntimeAvailability
import com.subtitleedit.util.SenseVoiceNpuModelImporter
import com.subtitleedit.util.SenseVoiceNpuModelPathPolicy
import com.subtitleedit.util.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 模型设置页面
 */
class ModelSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelSettingsBinding
    private lateinit var settingsManager: SettingsManager

    private var encoderPath: String = ""
    private var decoderPath: String = ""
    private var joinerPath: String = ""
    private var tokensPath: String = ""
    private var vadModelPath: String = ""
    private var modelType: String = SettingsManager.ASR_MODEL_SENSEVOICE
    private var updatingVadThreshold = false
    private var accessWarningShown = false
    private var modelDownloadJob: Job? = null
    private var modelDownloadDialog: ModelDownloadProgressDialog? = null
    private var pendingStorageAction: (() -> Unit)? = null

    private companion object {
        private const val VAD_THRESHOLD_MIN = 0.01f
        private const val VAD_THRESHOLD_MAX = 0.9f
        private const val VAD_THRESHOLD_STEP = 0.01f
        private const val VAD_MIN_SILENCE_MIN = 0.01f
        private const val VAD_MIN_SILENCE_MAX = 2.0f
        private const val VAD_MIN_SILENCE_STEP = 0.01f
        private const val VAD_MIN_SPEECH_MIN = 0.01f
        private const val VAD_MIN_SPEECH_MAX = 1.0f
        private const val VAD_MIN_SPEECH_STEP = 0.01f
        private const val VAD_MAX_SPEECH_MIN = 1.0f
        private const val VAD_MAX_SPEECH_MAX = 60.0f
        private const val VAD_MAX_SPEECH_STEP = 1.0f
    }

    // Encoder 文件选择器
    private val encoderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedEncoder(it) }
    }

    // Decoder 文件选择器
    private val decoderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedDecoder(it) }
    }

    private val joinerPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedJoiner(it) }
    }

    // Tokens 文件选择器
    private val tokensPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedTokens(it) }
    }

    // VAD 模型文件选择器
    private val vadPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedVad(it) }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { continuePendingModelDownload() }

    private val writeStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { continuePendingModelDownload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ToolCardShadow.removeFrom(binding.root)

        settingsManager = SettingsManager.getInstance(this)

        setupToolbar()
        setupButtons()
        setupVadSettings()
        loadSavedSettings()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "语音转录设置"

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
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
            tokensPickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnSelectVad.setOnClickListener {
            vadPickerLauncher.launch(arrayOf("*/*"))
        }

        binding.cbUseBuiltInVad.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setVadUseBuiltInModel(isChecked)
            updateVadModelUi()
        }

        binding.btnSpeechAdvancedSettings.setOnClickListener {
            startActivity(Intent(this, SpeechToSubtitleSettingsActivity::class.java))
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
            startActivity(Intent(this, WhisperSettingsActivity::class.java))
        }

        binding.tvModelGuide.setOnClickListener {
            showModelGuide()
        }

    }

    private fun showAsrDownloadOptions() {
        if (modelDownloadJob?.isActive == true) {
            OverwritingToast.makeText(this, "模型正在下载", Toast.LENGTH_SHORT).show()
            return
        }
        if (isSenseVoiceNpu() && !ensureQnnRuntimeAvailable()) return
        when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE -> showSenseVoiceDownloadOptions()
            SettingsManager.ASR_MODEL_PARAKEET_TDT ->
                confirmParakeetDownload(ModelDownloader.PARAKEET_TDT_MODEL)
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA ->
                confirmParakeetDownload(ModelDownloader.PARAKEET_CTC_JA_MODEL)
            else -> showWhisperDownloadModelPicker()
        }
    }

    private fun showSenseVoiceDownloadOptions() {
        if (settingsManager.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU) {
            if (!ensureQnnRuntimeAvailable()) return
            val options = ModelDownloader.SENSEVOICE_NPU_MODELS
            val labels = options.map { "${it.displayName}（${it.sizeLabel}）" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("选择 SenseVoice NPU 模型")
                .setItems(labels) { _, which -> confirmSenseVoiceDownload(options[which]) }
                .setNegativeButton("取消", null)
                .show()
        } else {
            confirmSenseVoiceDownload(ModelDownloader.SENSEVOICE_CPU_MODEL)
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
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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
        val options = ModelDownloader.WHISPER_MODELS
        val labels = options.map { "${it.displayName}（${it.sizeLabel}）" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择 Whisper 模型")
            .setItems(labels) { _, which -> confirmWhisperDownload(options[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmWhisperDownload(option: ModelDownloader.WhisperModelOption) {
        AlertDialog.Builder(this)
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

    private fun startSenseVoiceDownload(option: ModelDownloader.SenseVoiceModelOption) {
        if (modelDownloadJob?.isActive == true) return
        val isNpu = option.architecture == ModelDownloader.SenseVoiceArchitecture.QNN
        if (isNpu && !ensureQnnRuntimeAvailable()) return
        if (isNpu &&
            "arm64-v8a" !in Build.SUPPORTED_ABIS
        ) {
            OverwritingToast.makeText(
                this,
                "SenseVoice NPU 模型仅支持 arm64-v8a 骁龙设备",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val progressDialog = ModelDownloadProgressDialog(
            this,
            "下载 SenseVoice ${option.displayName} 模型"
        ) { modelDownloadJob?.cancel() }
        modelDownloadDialog = progressDialog
        progressDialog.show()
        setAsrModelActionsEnabled(false)

        modelDownloadJob = lifecycleScope.launch {
            try {
                val isNpu = option.architecture == ModelDownloader.SenseVoiceArchitecture.QNN
                val importer = SenseVoiceNpuModelImporter(
                    this@ModelSettingsActivity,
                    contentResolver
                )
                val previousNpuModelPath = if (isNpu) {
                    settingsManager.getSenseVoiceModelPath(SettingsManager.SENSEVOICE_PROVIDER_NPU)
                } else {
                    ""
                }
                val previousNpuTokensPath = if (isNpu) {
                    settingsManager.getSenseVoiceTokensPath(SettingsManager.SENSEVOICE_PROVIDER_NPU)
                } else {
                    ""
                }
                var downloadedFiles: ModelDownloader.SenseVoiceFiles? = null
                var reusedGeneratedModel = false
                val selectedFiles = if (isNpu) {
                    val durationSeconds = option.durationSeconds
                        ?: throw IllegalStateException("SenseVoice NPU 模型缺少时长信息")
                    modelDownloadDialog?.update(
                        ModelDownloader.Progress("正在检查已生成的 SenseVoice NPU BIN 模型")
                    )
                    val installed = withContext(Dispatchers.IO) {
                        importer.findInstalledModel(durationSeconds)
                    }
                    if (installed != null) {
                        reusedGeneratedModel = true
                        installed.contextBinary to installed.tokens
                    } else {
                        val files = ModelDownloader.downloadSenseVoice(option) { progress ->
                            runOnUiThread { modelDownloadDialog?.update(progress) }
                        }
                        downloadedFiles = files
                        val imported = withContext(Dispatchers.IO) {
                            importer.importFromFiles(
                                modelFile = files.model,
                                tokensFile = files.tokens,
                                durationSeconds = durationSeconds
                            ) { message ->
                                runOnUiThread {
                                    modelDownloadDialog?.update(ModelDownloader.Progress(message))
                                }
                            }
                        }
                        imported.contextBinary to imported.tokens
                    }
                } else {
                    val files = ModelDownloader.downloadSenseVoice(option) { progress ->
                        runOnUiThread { modelDownloadDialog?.update(progress) }
                    }
                    downloadedFiles = files
                    files.model to files.tokens
                }
                val selectedModel = Uri.fromFile(selectedFiles.first).toString()
                val selectedTokens = Uri.fromFile(selectedFiles.second).toString()
                modelType = SettingsManager.ASR_MODEL_SENSEVOICE
                settingsManager.setAsrModelType(modelType)
                settingsManager.setSenseVoiceProvider(
                    if (isNpu) {
                        SettingsManager.SENSEVOICE_PROVIDER_NPU
                    } else {
                        SettingsManager.SENSEVOICE_PROVIDER_CPU
                    }
                )
                option.durationSeconds?.let(settingsManager::setSenseVoiceNpuDurationSeconds)
                settingsManager.setSenseVoiceModelPath(selectedModel)
                settingsManager.setSenseVoiceTokensPath(selectedTokens)
                if (isNpu) {
                    withContext(Dispatchers.IO) {
                        importer.deleteManagedContextBinary(
                            previousNpuModelPath,
                            except = selectedFiles.first
                        )
                        downloadedFiles?.model?.delete()
                    }
                    releasePersistedReadPermission(previousNpuModelPath)
                    releasePersistedReadPermission(previousNpuTokensPath)
                }
                loadModelPaths()
                updateAsrModelUi()
                progressDialog.dismiss()
                OverwritingToast.makeText(
                    this@ModelSettingsActivity,
                    if (reusedGeneratedModel) {
                        "检测到已生成的 SenseVoice ${option.displayName} BIN 模型，已直接导入"
                    } else if (isNpu) {
                        "SenseVoice ${option.displayName} 已生成 BIN 模型并自动选择"
                    } else {
                        "SenseVoice ${option.displayName} 模型已下载、解压并自动选择\n" +
                            "${downloadedFiles?.model?.parentFile?.absolutePath}"
                    },
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: CancellationException) {
                progressDialog.dismiss()
                throw e
            } catch (e: Exception) {
                progressDialog.dismiss()
                OverwritingToast.makeText(
                    this@ModelSettingsActivity,
                    "SenseVoice ${option.displayName} 模型下载或导入失败：${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setAsrModelActionsEnabled(true)
                if (modelDownloadDialog === progressDialog) modelDownloadDialog = null
                modelDownloadJob = null
            }
        }
    }

    private fun startWhisperDownload(option: ModelDownloader.WhisperModelOption) {
        if (modelDownloadJob?.isActive == true) return
        val progressDialog = ModelDownloadProgressDialog(
            this,
            "下载 Whisper ${option.displayName} 模型"
        ) { modelDownloadJob?.cancel() }
        modelDownloadDialog = progressDialog
        progressDialog.show()
        setAsrModelActionsEnabled(false)

        modelDownloadJob = lifecycleScope.launch {
            try {
                val files = ModelDownloader.downloadWhisper(option) { progress ->
                    runOnUiThread { modelDownloadDialog?.update(progress) }
                }
                modelType = SettingsManager.ASR_MODEL_WHISPER
                settingsManager.setAsrModelType(modelType)
                settingsManager.setWhisperEncoderPath(Uri.fromFile(files.encoder).toString())
                settingsManager.setWhisperDecoderPath(Uri.fromFile(files.decoder).toString())
                settingsManager.setWhisperTokensPath(Uri.fromFile(files.tokens).toString())
                loadModelPaths()
                updateAsrModelUi()
                progressDialog.dismiss()
                OverwritingToast.makeText(
                    this@ModelSettingsActivity,
                    "Whisper ${option.displayName} 模型已下载、解压并自动选择\n${files.encoder.parentFile?.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: CancellationException) {
                progressDialog.dismiss()
                throw e
            } catch (e: Exception) {
                progressDialog.dismiss()
                OverwritingToast.makeText(
                    this@ModelSettingsActivity,
                    "Whisper ${option.displayName} 模型下载失败：${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setAsrModelActionsEnabled(true)
                if (modelDownloadDialog === progressDialog) modelDownloadDialog = null
                modelDownloadJob = null
            }
        }
    }

    private fun startParakeetDownload(option: ModelDownloader.ParakeetModelOption) {
        if (modelDownloadJob?.isActive == true) return
        val progressDialog = ModelDownloadProgressDialog(
            this,
            "下载 ${option.displayName} 模型"
        ) { modelDownloadJob?.cancel() }
        modelDownloadDialog = progressDialog
        progressDialog.show()
        setAsrModelActionsEnabled(false)

        modelDownloadJob = lifecycleScope.launch {
            try {
                val files = ModelDownloader.downloadParakeet(option) { progress ->
                    runOnUiThread { modelDownloadDialog?.update(progress) }
                }
                modelType = option.modelType
                settingsManager.setAsrModelType(modelType)
                when (option.architecture) {
                    ModelDownloader.ParakeetArchitecture.TDT -> {
                        settingsManager.setParakeetTdtEncoderPath(Uri.fromFile(requireNotNull(files.encoder)).toString())
                        settingsManager.setParakeetTdtDecoderPath(Uri.fromFile(requireNotNull(files.decoder)).toString())
                        settingsManager.setParakeetTdtJoinerPath(Uri.fromFile(requireNotNull(files.joiner)).toString())
                        settingsManager.setParakeetTdtTokensPath(Uri.fromFile(files.tokens).toString())
                    }
                    ModelDownloader.ParakeetArchitecture.CTC -> {
                        settingsManager.setParakeetCtcModelPath(Uri.fromFile(requireNotNull(files.model)).toString())
                        settingsManager.setParakeetCtcTokensPath(Uri.fromFile(files.tokens).toString())
                    }
                }
                loadModelPaths()
                updateAsrModelUi()
                progressDialog.dismiss()
                OverwritingToast.makeText(
                    this@ModelSettingsActivity,
                    "${option.displayName} 模型已下载、解压并自动选择\n${files.tokens.parentFile?.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: CancellationException) {
                progressDialog.dismiss()
                throw e
            } catch (e: Exception) {
                progressDialog.dismiss()
                OverwritingToast.makeText(
                    this@ModelSettingsActivity,
                    "${option.displayName} 模型下载失败：${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setAsrModelActionsEnabled(true)
                if (modelDownloadDialog === progressDialog) modelDownloadDialog = null
                modelDownloadJob = null
            }
        }
    }

    private fun confirmResetCurrentAsrModel() {
        val modelName = currentModelDisplayName()
        AlertDialog.Builder(this)
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
            else -> settingsManager.clearWhisperModelPaths()
        }
        loadModelPaths()
        updateAsrModelUi()
        OverwritingToast.makeText(this, "已清除当前模型选择，请重新选择", Toast.LENGTH_SHORT).show()
    }

    private fun setAsrModelActionsEnabled(enabled: Boolean) {
        binding.btnDownloadAsrModel.isEnabled = enabled
        binding.btnResetAsrModel.isEnabled = enabled
        binding.btnSwitchAsrModel.isEnabled = enabled
        binding.btnSelectEncoder.isEnabled = enabled
        binding.btnSelectTokens.isEnabled = enabled
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
                Uri.parse("package:$packageName")
            )
            val opened = runCatching { manageStorageLauncher.launch(appIntent) }.isSuccess ||
                runCatching {
                    manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }.isSuccess
            if (!opened) {
                pendingStorageAction = null
                OverwritingToast.makeText(this, "无法打开存储权限设置", Toast.LENGTH_LONG).show()
            }
        } else {
            writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun hasModelStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun continuePendingModelDownload() {
        val action = pendingStorageAction ?: return
        pendingStorageAction = null
        if (hasModelStorageAccess()) {
            action()
        } else {
            OverwritingToast.makeText(this, "需要存储权限才能保存下载的模型", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupVadSettings() {
        binding.sliderVadThreshold.valueFrom = VAD_THRESHOLD_MIN
        binding.sliderVadThreshold.valueTo = VAD_THRESHOLD_MAX
        binding.sliderVadThreshold.stepSize = VAD_THRESHOLD_STEP
        binding.sliderVadThreshold.setLabelFormatter { value ->
            String.format(Locale.US, "%.2f", normalizeVadThreshold(value))
        }

        // VAD 阈值
        binding.sliderVadThreshold.addOnChangeListener { _, value, fromUser ->
            if (updatingVadThreshold) return@addOnChangeListener
            val snapped = normalizeVadThreshold(value)
            if (fromUser) {
                updatingVadThreshold = true
                if (!floatEquals(binding.sliderVadThreshold.value, snapped)) {
                    binding.sliderVadThreshold.value = snapped
                }
                binding.etVadThreshold.setText(String.format(Locale.US, "%.2f", snapped))
                binding.etVadThreshold.setSelection(binding.etVadThreshold.text?.length ?: 0)
                updatingVadThreshold = false
            }
            settingsManager.setVadThreshold(snapped)
        }
        binding.etVadThreshold.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingVadThreshold) return
                val text = s.toString()
                if (text.isBlank() || text.endsWith(".")) return
                val v = text.toFloatOrNull() ?: return
                val clamped = v.coerceIn(VAD_THRESHOLD_MIN, VAD_THRESHOLD_MAX)
                val snapped = normalizeVadThreshold(clamped)
                val normalized = String.format(Locale.US, "%.2f", snapped)
                val decimalLength = text.substringAfter('.', "").takeIf { text.contains('.') }?.length ?: 0
                val shouldNormalizeText = decimalLength >= 2 || text.toFloatOrNull() != clamped
                updatingVadThreshold = true
                if (!floatEquals(binding.sliderVadThreshold.value, snapped)) {
                    binding.sliderVadThreshold.value = snapped
                }
                if (shouldNormalizeText && text != normalized) {
                    binding.etVadThreshold.setText(normalized)
                    binding.etVadThreshold.setSelection(binding.etVadThreshold.text?.length ?: 0)
                }
                updatingVadThreshold = false
                settingsManager.setVadThreshold(snapped)
            }
        })

        // 最小静音时长
        binding.sliderMinSilence.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.etMinSilence.setText(String.format(Locale.US, "%.2f", value))
            settingsManager.setVadMinSilenceDuration(value)
        }
        binding.etMinSilence.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s.toString().toFloatOrNull() ?: return
                val snapped = snapVadValue(
                    v,
                    VAD_MIN_SILENCE_STEP,
                    VAD_MIN_SILENCE_MIN,
                    VAD_MIN_SILENCE_MAX
                )
                if (binding.sliderMinSilence.value != snapped) binding.sliderMinSilence.value = snapped
                settingsManager.setVadMinSilenceDuration(snapped)
            }
        })

        // 最小语音时长
        binding.sliderMinSpeech.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.etMinSpeech.setText(String.format(Locale.US, "%.2f", value))
            settingsManager.setVadMinSpeechDuration(value)
        }
        binding.etMinSpeech.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s.toString().toFloatOrNull() ?: return
                val snapped = snapVadValue(
                    v,
                    VAD_MIN_SPEECH_STEP,
                    VAD_MIN_SPEECH_MIN,
                    VAD_MIN_SPEECH_MAX
                )
                if (binding.sliderMinSpeech.value != snapped) binding.sliderMinSpeech.value = snapped
                settingsManager.setVadMinSpeechDuration(snapped)
            }
        })

        // 最大语音时长
        binding.sliderMaxSpeech.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.etMaxSpeech.setText(String.format(Locale.US, "%.1f", value))
            settingsManager.setVadMaxSpeechDuration(value)
        }
        binding.etMaxSpeech.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s.toString().toFloatOrNull() ?: return
                val snapped = snapVadValue(
                    v,
                    VAD_MAX_SPEECH_STEP,
                    VAD_MAX_SPEECH_MIN,
                    VAD_MAX_SPEECH_MAX
                )
                if (binding.sliderMaxSpeech.value != snapped) binding.sliderMaxSpeech.value = snapped
                settingsManager.setVadMaxSpeechDuration(snapped)
            }
        })
    }

    private fun loadSavedSettings() {
        // 加载模型路径
        modelType = settingsManager.getAsrModelType()
        loadModelPaths()
        vadModelPath = settingsManager.getVadModelPath()
        discardInaccessibleVadModel()
        binding.cbUseBuiltInVad.isChecked = settingsManager.isVadUseBuiltInModel()
        updateVadModelUi()
        updateAsrModelUi()

        // 加载 VAD 参数
        val threshold = settingsManager.getVadThreshold()
        val minSilence = settingsManager.getVadMinSilenceDuration()
        val minSpeech = settingsManager.getVadMinSpeechDuration()
        val maxSpeech = settingsManager.getVadMaxSpeechDuration()

        settingsManager.setVadThreshold(threshold)
        binding.sliderVadThreshold.value = threshold
        binding.etVadThreshold.setText(String.format(Locale.US, "%.2f", threshold))

        binding.sliderMinSilence.value = minSilence
        binding.etMinSilence.setText(String.format(Locale.US, "%.2f", minSilence))

        binding.sliderMinSpeech.value = minSpeech
        binding.etMinSpeech.setText(String.format(Locale.US, "%.2f", minSpeech))

        binding.sliderMaxSpeech.value = maxSpeech
        binding.etMaxSpeech.setText(String.format(Locale.US, "%.1f", maxSpeech))

        migrateLegacySenseVoiceNpuSelectionIfNeeded()
    }

    private fun migrateLegacySenseVoiceNpuSelectionIfNeeded() {
        if (!isSenseVoiceNpu() || encoderPath.isBlank()) return
        if (!QnnRuntimeAvailability.isAvailable(this)) return
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
                contentResolver.takePersistableUriPermission(
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
                    this,
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
            OverwritingToast.makeText(this, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveSelectedEncoder(uri: Uri, fileName: String) {
        encoderPath = uri.toString()
        when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE -> settingsManager.setSenseVoiceModelPath(encoderPath)
            SettingsManager.ASR_MODEL_PARAKEET_TDT -> settingsManager.setParakeetTdtEncoderPath(encoderPath)
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> settingsManager.setParakeetCtcModelPath(encoderPath)
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
        AlertDialog.Builder(this)
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
                this,
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
                this,
                "请先选择 SenseVoice NPU 模型对应的 tokens.txt，再导入 libmodel.so",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val progressDialog = ModelDownloadProgressDialog(
            this,
            "导入 SenseVoice NPU 模型"
        ) { modelDownloadJob?.cancel() }
        modelDownloadDialog = progressDialog
        progressDialog.show()
        setAsrModelActionsEnabled(false)

        modelDownloadJob = lifecycleScope.launch {
            val importer = SenseVoiceNpuModelImporter(
                this@ModelSettingsActivity,
                contentResolver
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
                        runOnUiThread {
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
                    this@ModelSettingsActivity,
                    "SenseVoice NPU BIN 模型已生成并自动选择",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: CancellationException) {
                progressDialog.dismiss()
                throw e
            } catch (e: Exception) {
                progressDialog.dismiss()
                OverwritingToast.makeText(
                    this@ModelSettingsActivity,
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
        val modelsRoot = runCatching { ModelDownloader.modelsDirectory().canonicalFile }.getOrNull()
            ?: return
        val candidate = runCatching { source.canonicalFile }.getOrNull() ?: return
        if (SenseVoiceNpuModelPathPolicy.isInside(modelsRoot, candidate)) candidate.delete()
    }

    private fun handleSelectedDecoder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            if (!fileName.contains("decoder", ignoreCase = true) ||
                !fileName.endsWith(".onnx", ignoreCase = true)) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    this,
                    "请选择 decoder 模型文件（文件名应包含 'decoder' 且以 .onnx 结尾）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            decoderPath = uri.toString()
            if (modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT) {
                settingsManager.setParakeetTdtDecoderPath(decoderPath)
            } else {
                settingsManager.setWhisperDecoderPath(decoderPath)
            }
            binding.tvDecoderFile.text = fileName
            updateAsrModelUi()

        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSelectedJoiner(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val fileName = getFileNameFromUri(uri)
            if (!fileName.contains("joiner", ignoreCase = true) ||
                !fileName.endsWith(".onnx", ignoreCase = true)
            ) {
                OverwritingToast.makeText(
                    this,
                    "请选择 joiner 模型文件（文件名应包含 'joiner' 且以 .onnx 结尾）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            joinerPath = uri.toString()
            settingsManager.setParakeetTdtJoinerPath(joinerPath)
            binding.tvJoinerFile.text = fileName
            updateAsrModelUi()
        } catch (e: Exception) {
            OverwritingToast.makeText(this, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSelectedTokens(uri: Uri) {
        try {
            if (isSenseVoiceNpu() && !ensureQnnRuntimeAvailable()) return
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            if (!fileName.contains("token", ignoreCase = true) ||
                !fileName.endsWith(".txt", ignoreCase = true)) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    this,
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
            com.subtitleedit.util.OverwritingToast.makeText(this, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSelectedVad(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            if (!fileName.contains("vad", ignoreCase = true) ||
                !fileName.endsWith(".onnx", ignoreCase = true)) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    this,
                    "请选择 VAD 模型文件（文件名应包含 'vad' 且以 .onnx 结尾）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            vadModelPath = uri.toString()
            settingsManager.setVadModelPath(vadModelPath)
            settingsManager.setVadUseBuiltInModel(false)
            binding.cbUseBuiltInVad.isChecked = false
            updateVadModelUi()
            com.subtitleedit.util.OverwritingToast.makeText(this, "外部 VAD 模型已选择", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateVadModelUi() {
        val useBuiltIn = settingsManager.isVadUseBuiltInModel()
        binding.btnSelectVad.isEnabled = !useBuiltIn
        binding.btnSelectVad.alpha = if (useBuiltIn) 0.6f else 1f
        binding.tvVadFile.text = when {
            useBuiltIn -> "当前使用：内置 silero_vad.onnx"
            vadModelPath.isNotBlank() -> "当前使用：外部模型 ${getFileNameFromUri(Uri.parse(vadModelPath))}"
            else -> "当前使用：外部模型（未选择）"
        }
    }

    private fun normalizeVadThreshold(threshold: Float): Float {
        return snapVadValue(
            threshold,
            VAD_THRESHOLD_STEP,
            VAD_THRESHOLD_MIN,
            VAD_THRESHOLD_MAX
        )
    }

    private fun snapVadValue(value: Float, step: Float, min: Float, max: Float): Float {
        val clamped = value.coerceIn(min, max)
        return (Math.round((clamped - min) / step) * step + min).coerceIn(min, max)
    }

    private fun floatEquals(a: Float, b: Float): Boolean {
        return kotlin.math.abs(a - b) < 0.0001f
    }

    private fun getFileNameFromUri(uri: Uri): String {
        return runCatching {
            var fileName = uri.lastPathSegment ?: "未知文件"
            contentResolver.query(uri, null, null, null, null)?.use {
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
            else -> """
                Whisper 模型下载指引：

                Whisper 是通用多语言语音识别模型，可在源语言中选择指定语言或使用自动检测。

                1. 推荐点击蓝色下载按钮选择 Tiny、Small、Large v3 或 Turbo，应用会自动下载、解压并选择模型。

                2. 模型越大通常识别效果越好，但需要更多存储、内存和处理时间。

                3. 手动导入需要选择 encoder.onnx、decoder.onnx 和 tokens.txt。
            """.trimIndent()
        }

        AlertDialog.Builder(this)
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
                startActivity(intent)
            }
            .show()
    }

    private fun showAsrModelPicker() {
        val types = arrayOf(
            SettingsManager.ASR_MODEL_SENSEVOICE,
            SettingsManager.ASR_MODEL_PARAKEET_TDT,
            SettingsManager.ASR_MODEL_WHISPER
        )
        val labels = arrayOf(
            "SenseVoice",
            "Parakeet",
            "Whisper"
        )
        val checked = when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE -> 0
            SettingsManager.ASR_MODEL_PARAKEET_TDT,
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> 1
            else -> 2
        }
        AlertDialog.Builder(this)
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
        binding.tvTokensFile.text = tokensPath.takeIf { it.isNotEmpty() }?.let { getFileNameFromUri(Uri.parse(it)) } ?: "未选择"
    }

    private fun discardInaccessibleAsrModels() {
        var discarded = false
        if (encoderPath.isNotBlank() && !canReadSavedUri(encoderPath)) {
            encoderPath = ""
            when (modelType) {
                SettingsManager.ASR_MODEL_SENSEVOICE -> settingsManager.setSenseVoiceModelPath("")
                SettingsManager.ASR_MODEL_PARAKEET_TDT -> settingsManager.setParakeetTdtEncoderPath("")
                SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> settingsManager.setParakeetCtcModelPath("")
                else -> settingsManager.setWhisperEncoderPath("")
            }
            discarded = true
        }
        if (decoderPath.isNotBlank() && !canReadSavedUri(decoderPath)) {
            decoderPath = ""
            if (modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT) {
                settingsManager.setParakeetTdtDecoderPath("")
            } else {
                settingsManager.setWhisperDecoderPath("")
            }
            discarded = true
        }
        if (joinerPath.isNotBlank() && !canReadSavedUri(joinerPath)) {
            joinerPath = ""
            settingsManager.setParakeetTdtJoinerPath("")
            discarded = true
        }
        if (tokensPath.isNotBlank() && !canReadSavedUri(tokensPath)) {
            tokensPath = ""
            when (modelType) {
                SettingsManager.ASR_MODEL_SENSEVOICE -> settingsManager.setSenseVoiceTokensPath("")
                SettingsManager.ASR_MODEL_PARAKEET_TDT -> settingsManager.setParakeetTdtTokensPath("")
                SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> settingsManager.setParakeetCtcTokensPath("")
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
            uri.path?.let(::File)?.isFile == true
        } else {
            contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }
    }.getOrDefault(false)

    private fun releasePersistedReadPermission(uriString: String) {
        if (uriString.isBlank()) return
        val uri = Uri.parse(uriString)
        if (uri.scheme != "content") return
        val hasPersistedReadPermission = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!hasPersistedReadPermission) return
        runCatching {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun showAccessExpiredMessage() {
        if (accessWarningShown) return
        accessWarningShown = true
        OverwritingToast.makeText(this, "模型访问权限已失效，请重新选择模型文件", Toast.LENGTH_LONG).show()
    }

    private fun updateAsrModelUi() {
        val senseVoice = modelType == SettingsManager.ASR_MODEL_SENSEVOICE
        val senseVoiceNpu = senseVoice &&
            settingsManager.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU
        val parakeetTdt = modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT
        val parakeetCtc = modelType == SettingsManager.ASR_MODEL_PARAKEET_CTC_JA
        val parakeet = parakeetTdt || parakeetCtc
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
            else -> "Encoder 模型"
        }
        binding.btnSelectEncoder.text = if (senseVoice || parakeetCtc) "选择模型" else "选择 Encoder"
        binding.btnSelectEncoder.visibility = View.VISIBLE
        binding.btnSelectTokens.visibility = View.VISIBLE
        binding.layoutDecoder.visibility = if (senseVoice || parakeetCtc) View.GONE else View.VISIBLE
        binding.layoutJoiner.visibility = if (parakeetTdt) View.VISIBLE else View.GONE
        binding.btnWhisperConfig.visibility = if (modelType == SettingsManager.ASR_MODEL_WHISPER) View.VISIBLE else View.GONE
        binding.layoutSenseVoiceProviderOptions.visibility = if (senseVoice) View.VISIBLE else View.GONE
        binding.layoutParakeetVariantOptions.visibility = if (parakeet) View.VISIBLE else View.GONE
        binding.tvSenseVoiceCpuOption.setTextColor(
            ContextCompat.getColor(this, if (!senseVoiceNpu) R.color.primary else R.color.on_surface_variant)
        )
        binding.tvSenseVoiceNpuOption.setTextColor(
            ContextCompat.getColor(this, if (senseVoiceNpu) R.color.primary else R.color.on_surface_variant)
        )
        binding.tvSenseVoiceCpuOption.setTypeface(
            null,
            if (!senseVoiceNpu) Typeface.BOLD else Typeface.NORMAL
        )
        binding.tvSenseVoiceNpuOption.setTypeface(
            null,
            if (senseVoiceNpu) Typeface.BOLD else Typeface.NORMAL
        )
        val qnnRuntimeAvailable = QnnRuntimeAvailability.isAvailable(this)
        binding.tvSenseVoiceNpuOption.alpha = if (qnnRuntimeAvailable) 1f else 0.55f
        binding.tvSenseVoiceNpuOption.contentDescription = if (qnnRuntimeAvailable) {
            "选择 SenseVoice NPU"
        } else {
            "SenseVoice NPU，需要安装 QNN 版"
        }
        binding.tvParakeetTdtOption.setTextColor(
            ContextCompat.getColor(this, if (parakeetTdt) R.color.primary else R.color.on_surface_variant)
        )
        binding.tvParakeetCtcOption.setTextColor(
            ContextCompat.getColor(this, if (parakeetCtc) R.color.primary else R.color.on_surface_variant)
        )
        binding.tvParakeetTdtOption.setTypeface(null, if (parakeetTdt) Typeface.BOLD else Typeface.NORMAL)
        binding.tvParakeetCtcOption.setTypeface(null, if (parakeetCtc) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun isSingleFileModel(): Boolean =
        modelType == SettingsManager.ASR_MODEL_SENSEVOICE ||
            modelType == SettingsManager.ASR_MODEL_PARAKEET_CTC_JA

    private fun isSenseVoiceNpu(): Boolean =
        modelType == SettingsManager.ASR_MODEL_SENSEVOICE &&
            settingsManager.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU

    private fun ensureQnnRuntimeAvailable(): Boolean {
        if (QnnRuntimeAvailability.isAvailable(this)) return true
        AlertDialog.Builder(this)
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
        runCatching { startActivity(intent) }.onFailure {
            OverwritingToast.makeText(this, "无法打开下载页", Toast.LENGTH_LONG).show()
        }
    }

    private fun currentModelDisplayName(): String = when (modelType) {
        SettingsManager.ASR_MODEL_SENSEVOICE -> "SenseVoice"
        SettingsManager.ASR_MODEL_PARAKEET_TDT -> "Parakeet TDT 0.6B v3"
        SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> "Parakeet CTC 0.6B 日语"
        else -> "Whisper"
    }

    override fun onDestroy() {
        modelDownloadJob?.cancel()
        pendingStorageAction = null
        modelDownloadDialog?.dismiss()
        modelDownloadDialog = null
        super.onDestroy()
    }
}
