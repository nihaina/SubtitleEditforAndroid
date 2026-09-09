package com.subtitleedit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.databinding.ActivityVocalSeparationSettingsBinding
import com.subtitleedit.demix.VocalSeparationEngine
import com.subtitleedit.util.ModelDownloadProgressDialog
import com.subtitleedit.util.ModelDownloader
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.task.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class VocalSeparationSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVocalSeparationSettingsBinding
    private lateinit var settings: SettingsManager
    private var loading = false
    private var accessWarningShown = false
    private var modelType = SettingsManager.DEMIX_MODEL_GENERAL
    private var modelDownloadJob: Job? = null
    private var modelDownloadWorkId: UUID? = null
    private var modelDownloadDialog: ModelDownloadProgressDialog? = null
    private var pendingGeneralModelDownload = false

    private val vocalsModelPicker = modelPicker(VocalSeparationEngine.Stem.VOCALS)
    private val drumsModelPicker = modelPicker(VocalSeparationEngine.Stem.DRUMS)
    private val bassModelPicker = modelPicker(VocalSeparationEngine.Stem.BASS)
    private val otherModelPicker = modelPicker(VocalSeparationEngine.Stem.OTHER)
    private val generalModelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(::handleSelectedGeneralModel) }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { continuePendingGeneralModelDownload() }

    private val writeStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { continuePendingGeneralModelDownload() }

    private fun modelPicker(stem: VocalSeparationEngine.Stem) = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handleSelectedModel(stem, it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVocalSeparationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsManager.getInstance(this)
        modelDownloadWorkId = savedInstanceState?.getString("model_download_work_id")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        setupToolbar()
        setupListeners()
        loadSettings()
        restoreActiveGeneralModelDownload()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "人声分离设置"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupListeners() {
        val modelMimeTypes = arrayOf("application/octet-stream", "application/onnx", "*/*")
        binding.btnModelHelp.setOnClickListener { showModelHelp() }
        binding.btnSwitchDemixModel.setOnClickListener { showDemixModelPicker() }
        binding.btnSelectGeneralModel.setOnClickListener { generalModelPicker.launch(modelMimeTypes) }
        binding.btnDownloadGeneralModel.setOnClickListener { confirmGeneralModelDownload() }
        binding.btnResetGeneralModel.setOnClickListener { confirmResetGeneralModelSelection() }
        binding.btnSelectVocalsModel.setOnClickListener { vocalsModelPicker.launch(modelMimeTypes) }
        binding.btnSelectDrumsModel.setOnClickListener { drumsModelPicker.launch(modelMimeTypes) }
        binding.btnSelectBassModel.setOnClickListener { bassModelPicker.launch(modelMimeTypes) }
        binding.btnSelectOtherModel.setOnClickListener { otherModelPicker.launch(modelMimeTypes) }
        binding.switchGraphOptimization.setOnCheckedChangeListener { _, checked ->
            if (!loading) settings.setDemixOrtGraphOptimizationEnabled(checked)
        }
        binding.switchCpuArena.setOnCheckedChangeListener { _, checked ->
            if (!loading) settings.setDemixOrtCpuArenaEnabled(checked)
        }
    }

    private fun confirmGeneralModelDownload() {
        AlertDialog.Builder(this)
            .setTitle("一键下载导入")
            .setMessage(
                "是否一键下载导入 HTDemucs 通用模型？\n\n" +
                    "文件存放至：\n/Download/SubtitleEdit/models/separation\n\n" +
                    "约占用 158 MB 存储空间。"
            )
            .setPositiveButton("下载并导入") { _, _ -> startGeneralModelDownload() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmResetGeneralModelSelection() {
        AlertDialog.Builder(this)
            .setTitle("重置模型选择")
            .setMessage(
                "确定清除当前人声分离通用模型选择吗？\n\n" +
                    "模型文件不会被删除，重置后需要重新选择或导入。"
            )
            .setPositiveButton("重置") { _, _ -> resetGeneralModelSelection() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun resetGeneralModelSelection() {
        settings.setDemixModelUri("general", "")
        updateGeneralModelUi()
        OverwritingToast.makeText(this, "已清除通用模型选择，请重新选择", Toast.LENGTH_SHORT).show()
    }

    private fun startGeneralModelDownload() {
        if (!ensureModelStorageAccess()) return
        if (modelDownloadJob?.isActive == true) return
        observeGeneralModelDownload(enqueue = true)
    }

    private fun restoreActiveGeneralModelDownload() {
        observeGeneralModelDownload(enqueue = false)
    }

    private fun observeGeneralModelDownload(enqueue: Boolean) {
        if (modelDownloadJob?.isActive == true) return
        setModelDownloadActionsEnabled(false)
        modelDownloadJob = lifecycleScope.launch {
            var progressDialog: ModelDownloadProgressDialog? = null
            try {
                val scheduler = (application as SubtitleEditApplication).dependencies.taskWorkScheduler
                val workId = if (enqueue) {
                    scheduler.enqueueGeneralModelDownload()
                } else {
                    scheduler.findActiveModelDownload(modelDownloadWorkId) ?: return@launch
                }
                modelDownloadWorkId = workId
                progressDialog = ModelDownloadProgressDialog(
                    this@VocalSeparationSettingsActivity,
                    "下载人声分离模型"
                ) {
                    lifecycleScope.launch {
                        try {
                            scheduler.cancel(workId)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            showError("取消下载失败：" + error.message)
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
                        modelDownloadDialog?.update(
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
                            OverwritingToast.makeText(
                                this@VocalSeparationSettingsActivity,
                                "人声分离模型已下载并自动选择",
                                Toast.LENGTH_LONG
                            ).show()
                            false
                        }
                        TaskStatus.FAILED -> {
                            modelDownloadWorkId = null
                            showError("人声分离模型下载失败：" + (taskState.errorMessage ?: "模型任务失败"))
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
                showError("模型任务失败：" + error.message)
            } finally {
                progressDialog?.dismiss()
                setModelDownloadActionsEnabled(true)
                if (modelDownloadDialog === progressDialog) modelDownloadDialog = null
                modelDownloadJob = null
            }
        }
    }

    private fun setModelDownloadActionsEnabled(enabled: Boolean) {
        binding.btnDownloadGeneralModel.isEnabled = enabled
        binding.btnResetGeneralModel.isEnabled = enabled
        binding.btnSwitchDemixModel.isEnabled = enabled
        binding.btnSelectGeneralModel.isEnabled = enabled
    }

    override fun onSaveInstanceState(outState: Bundle) {
        modelDownloadWorkId?.let { outState.putString("model_download_work_id", it.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun ensureModelStorageAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return true
            pendingGeneralModelDownload = true
            val appIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            val opened = runCatching { manageStorageLauncher.launch(appIntent) }.isSuccess ||
                runCatching {
                    manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }.isSuccess
            if (!opened) {
                pendingGeneralModelDownload = false
                OverwritingToast.makeText(this, "无法打开存储权限设置", Toast.LENGTH_LONG).show()
            }
            return false
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED) {
            return true
        }
        pendingGeneralModelDownload = true
        writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return false
    }

    private fun continuePendingGeneralModelDownload() {
        if (!pendingGeneralModelDownload) return
        pendingGeneralModelDownload = false
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            startGeneralModelDownload()
        } else {
            OverwritingToast.makeText(this, "需要存储权限才能保存下载的模型", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSettings() {
        discardInaccessibleModelUris()
        modelType = settings.getDemixModelType()
        updateGeneralModelUi()
        updateModelUi(VocalSeparationEngine.Stem.VOCALS, binding.tvVocalsModel)
        updateModelUi(VocalSeparationEngine.Stem.DRUMS, binding.tvDrumsModel)
        updateModelUi(VocalSeparationEngine.Stem.BASS, binding.tvBassModel)
        updateModelUi(VocalSeparationEngine.Stem.OTHER, binding.tvOtherModel)
        updateDemixModelUi()

        loading = true
        binding.switchGraphOptimization.isChecked = settings.isDemixOrtGraphOptimizationEnabled()
        binding.switchCpuArena.isChecked = settings.isDemixOrtCpuArenaEnabled()
        loading = false
    }

    private fun handleSelectedModel(stem: VocalSeparationEngine.Stem, uri: Uri) {
        val fileName = getFileName(uri)
        if (!fileName.endsWith(".onnx", ignoreCase = true) ||
            !fileName.contains(stem.fileSuffix, ignoreCase = true)) {
            OverwritingToast.makeText(
                this,
                "请选择 ${stem.displayName} specialist ONNX 模型（文件名应包含 ${stem.fileSuffix}）",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            persistAndVerifyModel(uri)
            settings.setDemixModelUri(stem.fileSuffix, uri.toString())
            updateModelUi(stem, modelTextView(stem))
            OverwritingToast.makeText(this, "已选择 ${stem.displayName} 模型", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            showError("选择 ${stem.displayName} 模型失败：${e.message}")
        }
    }

    private fun handleSelectedGeneralModel(uri: Uri) {
        val fileName = getFileName(uri)
        if (!fileName.endsWith(".onnx", ignoreCase = true) || fileName.contains("_ft_", ignoreCase = true)) {
            OverwritingToast.makeText(
                this,
                "请选择通用四轨 HTDemucs ONNX 模型（例如 htdemucs_fp16weights.onnx）",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            persistAndVerifyModel(uri)
            settings.setDemixModelUri("general", uri.toString())
            updateGeneralModelUi()
            OverwritingToast.makeText(this, "已选择通用四轨模型", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            showError("选择通用模型失败：${e.message}")
        }
    }

    private fun persistAndVerifyModel(uri: Uri) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.statSize == 0L) throw IllegalStateException("模型文件为空")
        } ?: throw IllegalStateException("无法读取模型文件")
    }

    private fun updateGeneralModelUi() {
        val uriString = settings.getDemixModelUri("general")
        val hasSelectedModel = uriString.isNotBlank()
        binding.tvGeneralModel.text = if (uriString.isBlank()) {
            "未选择通用四轨模型"
        } else {
            "${getFileName(Uri.parse(uriString))}\n支持一次推理输出多个音轨"
        }
        binding.btnDownloadGeneralModel.visibility = if (hasSelectedModel) View.GONE else View.VISIBLE
        binding.btnResetGeneralModel.visibility = if (hasSelectedModel) View.VISIBLE else View.GONE
    }

    private fun updateModelUi(stem: VocalSeparationEngine.Stem, textView: TextView) {
        val uriString = settings.getDemixModelUri(stem.fileSuffix)
        textView.text = if (uriString.isBlank()) {
            "未选择 ${stem.displayName} specialist 模型"
        } else {
            "${getFileName(Uri.parse(uriString))}\n已保存读取权限，将直接从原位置调用"
        }
    }

    private fun modelTextView(stem: VocalSeparationEngine.Stem): TextView = when (stem) {
        VocalSeparationEngine.Stem.VOCALS -> binding.tvVocalsModel
        VocalSeparationEngine.Stem.DRUMS -> binding.tvDrumsModel
        VocalSeparationEngine.Stem.BASS -> binding.tvBassModel
        VocalSeparationEngine.Stem.OTHER -> binding.tvOtherModel
    }

    private fun showDemixModelPicker() {
        val labels = arrayOf("通用四轨模型", "FT 单音轨模型")
        val checked = if (modelType == SettingsManager.DEMIX_MODEL_FT) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("选择人声分离模型")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
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
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateDemixModelUi() {
        val showFtModels = modelType == SettingsManager.DEMIX_MODEL_FT
        binding.tvDemixModelTitle.text = if (showFtModels) "FT 单音轨模型" else "通用四轨模型"
        binding.layoutGeneralModel.visibility = if (showFtModels) View.GONE else View.VISIBLE
        binding.layoutFtModels.visibility = if (showFtModels) View.VISIBLE else View.GONE
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
            OverwritingToast.makeText(this, "模型访问权限已失效，请重新选择模型文件", Toast.LENGTH_LONG).show()
        }
    }

    private fun isSavedUriReadable(uriString: String): Boolean = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            uri.path?.let(::File)?.isFile == true
        } else {
            contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }
    }.getOrDefault(false)

    private fun getFileName(uri: Uri): String = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) return@runCatching cursor.getString(index)
        }
        uri.lastPathSegment ?: "unknown"
    }.getOrElse { uri.lastPathSegment ?: "unknown" }

    private fun showModelHelp() {
        val message = """
            模型分为两类：

            1. 通用模型
            htdemucs_fp16weights.onnx，一次推理可以输出 Vocals、Drums、Bass、Other 多个音轨。选择通用四轨模型后，单音轨和多音轨任务都会使用该模型。

            可直接点击“选择通用四轨模型”右侧的蓝色下载按钮，应用会自动下载并选择模型。

            下载地址：
            https://huggingface.co/StemSplitio/htdemucs-onnx/tree/main

            2. FT 单音轨模型
            Vocals、Drums、Bass、Other 各有一个 specialist 模型。切换到 FT 单音轨模型后，单音轨任务优先使用对应 FT 模型；未配置对应 FT 时回退通用模型。多音轨任务仍必须使用通用模型。

            下载地址：
            https://huggingface.co/StemSplitio/htdemucs-ft-onnx/tree/main

            手动选择的模型会直接从原文件位置读取；一键下载的通用模型保存在 Download/SubtitleEdit/models/separation。
        """.trimIndent()
        val dialog = AlertDialog.Builder(this)
            .setTitle("人声分离模型帮助")
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            Linkify.addLinks(this, Linkify.WEB_URLS)
            linksClickable = true
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("人声分离设置失败")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    override fun onDestroy() {
        modelDownloadJob?.cancel()
        modelDownloadDialog?.dismiss()
        modelDownloadDialog = null
        super.onDestroy()
    }
}
