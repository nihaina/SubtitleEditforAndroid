package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.feature.ui.VocalSeparationDialog
import com.subtitleedit.feature.ui.VocalSeparationScreen
import com.subtitleedit.feature.ui.VocalSeparationUiState
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.demix.DemixOutputWriter
import com.subtitleedit.demix.VocalSeparationEngine
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.RuntimeLogManager
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.nativebridge.NativeMediaOperation
import com.subtitleedit.nativebridge.PcmFormat
import com.subtitleedit.task.LongTaskController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class VocalSeparationActivity : AppComposeActivity() {
    private companion object {
        const val OUTPUT_DIRECTORY_KEY = "vocal_separation"
    }

    private var uiState by mutableStateOf(VocalSeparationUiState())
    private lateinit var settings: SettingsManager
    private val nativeMediaEngine: com.subtitleedit.nativebridge.NativeMediaEngine
        get() = (application as SubtitleEditApplication).dependencies.nativeMediaEngine
    private var mediaOperation: NativeMediaOperation? = null
    private val selectedFiles = mutableListOf<SelectedMediaFile>()
    private var outputDirUri: Uri? = null
    private var separationJob: Job? = null
    private var isRunning = false
    private val taskController by lazy {
        LongTaskController(
            (application as SubtitleEditApplication).dependencies.taskStateStore,
            "vocal-separation"
        )
    }
    private val isCancelled: Boolean get() = taskController.isCancellationRequested
    private var accessWarningShown = false
    private val runtimeText = StringBuilder()

    private data class SelectedMediaFile(val uri: Uri, val fileName: String)

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) handleSelectedFiles(uris) }

    private val outputDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { handleOutputDirectory(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsManager.getInstance(this)
        loadState()

        setContent {
            SubtitleEditComposeTheme {
                VocalSeparationScreen(
                    state = uiState,
                    onBack = ::requestBack,
                    onConfirmBack = ::confirmBack,
                    onSettings = { startActivity(Intent(this, VocalSeparationSettingsActivity::class.java)) },
                    onSelectFiles = { filePickerLauncher.launch(arrayOf("audio/*", "video/*")) },
                    onSelectOutputDirectory = { outputDirLauncher.launch(outputDirUri) },
                    onStemChange = ::updateSelectedStem,
                    onStart = ::startSeparation,
                    onCancel = {
                        if (uiState.dialog == VocalSeparationDialog.CANCEL) {
                            uiState = uiState.copy(dialog = VocalSeparationDialog.NONE)
                            cancelSeparation()
                        } else {
                            confirmCancel()
                        }
                    },
                    onOverwrite = {
                        uiState = uiState.copy(dialog = VocalSeparationDialog.NONE)
                        outputDirUri?.let { runSeparation(it, selectedStems(), true) }
                    },
                    onAutoRename = {
                        uiState = uiState.copy(dialog = VocalSeparationDialog.NONE)
                        outputDirUri?.let { runSeparation(it, selectedStems(), false) }
                    },
                    onDismissDialog = { uiState = uiState.copy(dialog = VocalSeparationDialog.NONE) }
                )
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                requestBack()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!isRunning) loadState()
    }

    private fun requestBack() {
        if (isRunning) {
            uiState = uiState.copy(dialog = VocalSeparationDialog.BACK)
        } else {
            finish()
        }
    }

    private fun confirmBack() {
        uiState = uiState.copy(dialog = VocalSeparationDialog.NONE)
        cancelSeparation()
        finish()
    }

    private fun loadState() {
        discardInaccessibleModelUris()
        val hasGeneral = isModelConfigured("general")
        val useFtModels = settings.getDemixModelType() == SettingsManager.DEMIX_MODEL_FT
        val enabled = VocalSeparationEngine.Stem.entries.filterTo(linkedSetOf()) { stem ->
            hasGeneral || useFtModels && isModelConfigured(stem)
        }
        var selected = uiState.selectedStems.intersect(enabled)
        if (!hasGeneral && selected.size > 1) selected = setOf(selected.first())
        uiState = uiState.copy(enabledStems = enabled, selectedStems = selected)
        if (outputDirUri == null) setupDefaultOutputDir()
        updateStartButton()
    }

    private fun handleSelectedFiles(uris: List<Uri>) {
        selectedFiles.clear()
        selectedFiles += uris.map { SelectedMediaFile(it, getFileName(it)) }
        val text = buildString {
            append("已选择 ${selectedFiles.size} 个文件：")
            selectedFiles.forEachIndexed { index, item -> append("\n${index + 1}. ${item.fileName}") }
        }
        uiState = uiState.copy(selectedFilesText = text, hasSelectedFiles = selectedFiles.isNotEmpty())
        updateStartButton()
    }

    private fun handleOutputDirectory(uri: Uri) {
        val permissionSaved = runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure { OverwritingToast.makeText(this, "目录权限保存失败：${it.message}", Toast.LENGTH_LONG).show() }
        outputDirUri = uri
        uiState = uiState.copy(outputDirectory = DirectoryDisplayPath.fromUri(this, uri))
        if (permissionSaved.isSuccess) {
            settings.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
        }
    }

    private fun setupDefaultOutputDir() {
        val savedUri = settings.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)
            ?.let(Uri::parse)
        if (savedUri != null) {
            outputDirUri = savedUri
            uiState = uiState.copy(outputDirectory = DirectoryDisplayPath.fromUri(this, savedUri))
            return
        }

        val path = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SubtitleEdit/Output"
        )
        path.mkdirs()
        outputDirUri = Uri.fromFile(path)
        uiState = uiState.copy(outputDirectory = path.absolutePath)
    }

    private fun updateStartButton() {
        uiState = uiState.copy(hasSelectedFiles = selectedFiles.isNotEmpty(), isRunning = isRunning)
    }

    private fun updateSelectedStem(stem: VocalSeparationEngine.Stem, checked: Boolean) {
        val updated = uiState.selectedStems.toMutableSet().apply {
            if (checked) add(stem) else remove(stem)
        }
        if (checked && updated.size > 1 && !isModelConfigured("general")) {
            OverwritingToast.makeText(this, "选择两个及以上音轨必须先选择通用四轨模型", Toast.LENGTH_LONG).show()
            return
        }
        uiState = uiState.copy(selectedStems = updated)
    }

    private fun startSeparation() {
        val output = outputDirUri
        val stems = selectedStems()
        val hasGeneral = isModelConfigured("general")
        val useFtModels = settings.getDemixModelType() == SettingsManager.DEMIX_MODEL_FT
        val modelsAvailable = when {
            stems.size > 1 -> hasGeneral
            stems.size == 1 -> hasGeneral || useFtModels && isModelConfigured(stems.first())
            else -> false
        }
        if (selectedFiles.isEmpty() || output == null || !modelsAvailable) {
            OverwritingToast.makeText(this, "请先选择音视频、模型和输出音轨", Toast.LENGTH_SHORT).show()
            return
        }
        val expected = selectedFiles.flatMap { file ->
            val base = file.fileName.substringBeforeLast(".")
            stems.map { "${base}_${it.fileSuffix}.wav" }
        }
        val conflicts = expected.filter { DemixOutputWriter.exists(this, output, it) }
        if (conflicts.isNotEmpty()) {
            uiState = uiState.copy(dialog = VocalSeparationDialog.OUTPUT_CONFLICT)
        } else {
            runSeparation(output, stems, false)
        }
    }

    private fun runSeparation(
        output: Uri,
        stems: Set<VocalSeparationEngine.Stem>,
        overwrite: Boolean
    ) {
        if (isRunning) return
        if (taskController.isRunning) return
        mediaOperation?.cancel()
        mediaOperation = nativeMediaEngine.openOperation()
        isRunning = true
        runtimeText.clear()
        uiState = uiState.copy(
            isRunning = true,
            progressVisible = true,
            progressStatus = null,
            progress = 0,
            log = "",
            dialog = VocalSeparationDialog.NONE
        )

        separationJob = taskController.launch(lifecycleScope) { task ->
            task.onCancel { mediaOperation?.cancel() }
            var success = 0
            try {
                appendRuntimeLog("开始人声分离")
                appendRuntimeLog("待处理文件：${selectedFiles.size} 个")
                appendRuntimeLog("输出音轨：${stems.joinToString { it.displayName }}")
                appendRuntimeLog("输出目录：${uiState.outputDirectory}")
                if (stems.size > 1) {
                    appendRuntimeLog("推理路线：通用四轨模型，一次推理输出 ${stems.size} 条音轨")
                    appendRuntimeLog("通用模型：${getFileName(Uri.parse(settings.getDemixModelUri("general")))}")
                } else {
                    val stem = stems.first()
                    val useFtModel = settings.getDemixModelType() == SettingsManager.DEMIX_MODEL_FT &&
                        isModelConfigured(stem)
                    if (useFtModel) {
                        appendRuntimeLog("推理路线：优先使用 ${stem.displayName} FT specialist")
                    } else if (settings.getDemixModelType() == SettingsManager.DEMIX_MODEL_FT) {
                        appendRuntimeLog("推理路线：未配置 ${stem.displayName} FT，回退通用模型")
                    } else {
                        appendRuntimeLog("推理路线：使用通用四轨模型")
                    }
                }
                val overwrittenBaseNames = mutableSetOf<String>()
                for ((index, selected) in selectedFiles.withIndex()) {
                    if (isCancelled) break
                    val baseName = selected.fileName.substringBeforeLast(".").lowercase(Locale.ROOT)
                    val shouldOverwrite = overwrite && overwrittenBaseNames.add(baseName)
                    val result = processOne(selected, index + 1, selectedFiles.size, output, stems, shouldOverwrite)
                    if (result) success++
                }
                if (isCancelled) {
                    appendRuntimeLog("任务已取消，已删除本次任务缓存和未完成输出")
                    OverwritingToast.makeText(this@VocalSeparationActivity, "已取消", Toast.LENGTH_SHORT).show()
                } else {
                    appendRuntimeLog("人声分离完成：成功 $success/${selectedFiles.size}")
                    showProgress("全部处理完成", 100)
                    OverwritingToast.makeText(this@VocalSeparationActivity, "分离完成：成功 $success/${selectedFiles.size}", Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                appendRuntimeLog("协程已取消")
            } catch (e: Exception) {
                if (!isCancelled) {
                    appendRuntimeLog("任务失败：${e.message}")
                    showError(e.message ?: "人声分离失败")
                }
            } finally {
                mediaOperation?.cancel()
                mediaOperation = null
                isRunning = false
                uiState = uiState.copy(isRunning = false, progressVisible = false)
                updateStartButton()
            }
        }
    }

    private suspend fun processOne(
        selected: SelectedMediaFile,
        index: Int,
        count: Int,
        output: Uri,
        stems: Set<VocalSeparationEngine.Stem>,
        overwrite: Boolean
    ): Boolean {
        val taskCache = File(cacheDir, "vocal_separation_${System.currentTimeMillis()}_${System.nanoTime()}").apply { mkdirs() }
        val prefix = "[$index/$count]"
        return try {
            showProgress("$prefix 正在准备", 0)
            appendRuntimeLog("$prefix 开始处理：${selected.fileName}")
            val input = withContext(Dispatchers.IO) { copyUriToCache(selected.uri, selected.fileName, taskCache) }
                ?: throw IllegalStateException("复制输入文件失败")
            appendRuntimeLog("$prefix 输入缓存：${input.name}（${formatBytes(input.length())}）")
            if (isCancelled) return false

            showProgress("$prefix 正在提取 44.1kHz 双声道音频", 5)
            appendRuntimeLog("$prefix 使用 FFmpeg 提取 44.1kHz、双声道、32-bit float PCM")
            val pcm = withContext(Dispatchers.IO) { convertToPcm(input, taskCache) }
                ?: throw IllegalStateException("FFmpeg 音频预处理失败")
            appendRuntimeLog("$prefix PCM 缓存：${pcm.name}（${formatBytes(pcm.length())}）")
            if (isCancelled) return false

            val tempOutput = File(taskCache, "outputs").apply { mkdirs() }
            showProgress("$prefix 正在运行 ONNX 分离", 10)
            val useGeneral = stems.size > 1
            val singleStem = stems.firstOrNull()
            val useFtModels = settings.getDemixModelType() == SettingsManager.DEMIX_MODEL_FT
            val modelKey = if (useGeneral) "general"
            else if (useFtModels && singleStem != null && isModelConfigured(singleStem)) singleStem.fileSuffix
            else "general"
            val modelUri = Uri.parse(settings.getDemixModelUri(modelKey))
            val modelLabel = when {
                useGeneral -> "通用四轨模型"
                modelKey != "general" -> "${singleStem?.displayName} specialist"
                useFtModels -> "通用模型回退"
                else -> "通用四轨模型"
            }
            appendRuntimeLog("$prefix 使用$modelLabel：${getFileName(modelUri)}")
            val result = withContext(Dispatchers.IO) {
                withDirectModelPath(modelUri) { directPath, size ->
                    val runner = (application as SubtitleEditApplication).dependencies
                        .vocalSeparationRunner(
                            modelPath = directPath,
                            modelDisplayName = getFileName(modelUri),
                            modelSize = size,
                            graphOptimizationEnabled = settings.isDemixOrtGraphOptimizationEnabled(),
                            cpuArenaEnabled = settings.isDemixOrtCpuArenaEnabled(),
                            log = { message -> appendRuntimeLog("$prefix $modelLabel: $message") }
                        )
                    runner.separate(
                        pcm,
                        tempOutput,
                        selected.fileName.substringBeforeLast("."),
                        stems,
                        { isCancelled }
                    ) { done, total ->
                        val progress = 10 + (done.toDouble() / total * 85).toInt()
                        runOnUiThread { showProgress("$prefix $modelLabel：$done/$total", progress) }
                    }
                }
            }
            for (stem in stems) {
                val temp = result.outputFiles.getValue(stem)
                val name = withContext(Dispatchers.IO) {
                    DemixOutputWriter.copy(this@VocalSeparationActivity, temp, output, temp.name, overwrite)
                }
                appendRuntimeLog("$prefix 已保存 ${stem.displayName}：$name")
            }
            appendRuntimeLog("$prefix 完成：${result.chunkCount} 个分块，耗时 ${formatDuration(result.elapsedMs)}")
            showProgress("$prefix 输出完成", 100)
            true
        } catch (e: InterruptedException) {
            appendRuntimeLog("$prefix 已取消：${e.message}")
            false
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { taskCache.deleteRecursively() }
        }
    }

    private suspend fun convertToPcm(input: File, cache: File): File? {
        val output = File(cache, "${input.nameWithoutExtension}_44k_stereo.f32le")
        if (output.exists()) output.delete()
        return if (mediaOperation?.convertToPcm(input, output, PcmFormat.DEMIX_FLOAT_44K_STEREO) == true) output else null
    }

    private fun copyUriToCache(uri: Uri, name: String, cache: File): File? = runCatching {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val output = File(cache, "input_$safeName")
        contentResolver.openInputStream(uri)?.use { input -> output.outputStream().use { input.copyTo(it, 1024 * 1024) } }
            ?: throw IllegalStateException("无法读取输入文件")
        output
    }.getOrNull()

    private fun selectedStems(): LinkedHashSet<VocalSeparationEngine.Stem> =
        uiState.selectedStems.toCollection(linkedSetOf())

    private fun isModelConfigured(stem: VocalSeparationEngine.Stem): Boolean {
        return isModelConfigured(stem.fileSuffix)
    }

    private fun isModelConfigured(modelKey: String): Boolean {
        val uriString = settings.getDemixModelUri(modelKey)
        if (uriString.isBlank()) return false
        return runCatching {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") {
                File(requireNotNull(uri.path)).isFile
            } else {
                contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }
        }.getOrDefault(false)
    }

    private fun discardInaccessibleModelUris() {
        var discarded = false
        val modelKeys = listOf(
            "general",
            VocalSeparationEngine.Stem.VOCALS.fileSuffix,
            VocalSeparationEngine.Stem.DRUMS.fileSuffix,
            VocalSeparationEngine.Stem.BASS.fileSuffix,
            VocalSeparationEngine.Stem.OTHER.fileSuffix
        )
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

    private fun <T> withDirectModelPath(uri: Uri, block: (path: String, size: Long?) -> T): T {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            return block(file.absolutePath, file.length())
        }
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("无法打开模型：${getFileName(uri)}")
        descriptor.use {
            val size = it.statSize.takeIf { value -> value >= 0L }
            return block("/proc/self/fd/${it.fd}", size)
        }
    }

    private fun confirmCancel() {
        if (!isRunning) return
        uiState = uiState.copy(dialog = VocalSeparationDialog.CANCEL)
    }

    private fun cancelSeparation() {
        if (!isRunning) return
        taskController.cancel()
        mediaOperation?.cancel()
        separationJob?.cancel()
        appendRuntimeLog("收到取消请求，正在停止当前处理")
    }

    private fun showProgress(status: String, progress: Int) {
        val update = { uiState = uiState.copy(progressStatus = status, progress = progress.coerceIn(0, 100)) }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) update() else runOnUiThread(update)
    }

    private fun appendRuntimeLog(message: String) {
        RuntimeLogManager.i("VocalSeparation", message)
        val render: () -> Unit = {
            val line = "[${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())}] $message"
            runtimeText.appendLine(line)
            if (runtimeText.length > 16000) runtimeText.delete(0, runtimeText.length - 16000)
            uiState = uiState.copy(log = runtimeText.toString())
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) render() else runOnUiThread(render)
    }

    private fun showError(message: String) {
        uiState = uiState.copy(dialog = VocalSeparationDialog.ERROR, errorMessage = message)
    }

    private fun getFileName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) return@runCatching cursor.getString(index)
            }
            uri.lastPathSegment ?: "unknown"
        }.getOrElse { uri.lastPathSegment ?: "unknown" }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "${"%.2f".format(Locale.getDefault(), bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        bytes >= 1024L * 1024L -> "${"%.2f".format(Locale.getDefault(), bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.1f".format(Locale.getDefault(), bytes / 1024.0)} KB"
    }

    private fun formatDuration(ms: Long): String =
        "${"%.1f".format(Locale.getDefault(), ms / 1000.0)} 秒"

    override fun onDestroy() {
        if (isRunning) {
            taskController.cancel()
            mediaOperation?.cancel()
        }
        taskController.cancel()
        mediaOperation?.cancel()
        separationJob?.cancel()
        super.onDestroy()
    }
}
