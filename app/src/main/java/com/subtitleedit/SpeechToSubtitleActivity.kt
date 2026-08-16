package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.subtitleedit.databinding.ActivitySpeechToSubtitleBinding
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.SenseVoiceTimestampGenerator
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleOutputWriter
import com.subtitleedit.util.WhisperRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 语音转字幕功能页面
 * 支持音频/视频文件转字幕，多种语言识别
 * 使用 sherpa-onnx + Whisper 进行离线语音识别
 */
class SpeechToSubtitleActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpeechToSubtitleBinding
    private lateinit var settingsManager: SettingsManager

    private val selectedMediaFiles = mutableListOf<SelectedMediaFile>()
    private var encoderPath: String = ""
    private var decoderPath: String = ""
    private var joinerPath: String = ""
    private var tokensPath: String = ""
    private var modelType: String = SettingsManager.ASR_MODEL_WHISPER
    private var vadModelPath: String = ""
    private var outputDirUri: Uri? = null
    private var conversionJob: Job? = null
    private var isConverting = false
    private var isCancelled = false
    private var pendingSubtitleContent: String = ""
    private val realtimeResults = StringBuilder()
    private var logRenderScheduled = false
    private var lastProgressLog = ""

    private companion object {
        private const val MAX_VISIBLE_LOG_CHARS = 16_000
        private const val LOG_RENDER_INTERVAL_MS = 100L
    }

    private data class SelectedMediaFile(
        val uri: Uri,
        val fileName: String
    )

    // 语言选项
    private val languageOptions = SettingsManager.TRANSCRIPTION_LANGUAGE_OPTIONS

    // 输出格式选项
    private val formatOptions = listOf(
        "SRT",
        "LRC",
        "TXT"
    )

    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) handleSelectedFiles(uris)
    }

    // 输出目录选择器
    private val outputDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { handleSelectedOutputDir(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpeechToSubtitleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager.getInstance(this)

        setupToolbar()
        setupSpinners()
        setupButtons()
        setupScrollableLogs()
        loadSavedModel()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isConverting) {
                    AlertDialog.Builder(this@SpeechToSubtitleActivity)
                        .setTitle("正在识别中")
                        .setMessage("语音识别正在进行，确定要返回吗？返回后识别将被取消。")
                        .setPositiveButton("返回并取消") { _, _ ->
                            cancelConversion()
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                        .setNegativeButton("继续识别", null)
                        .show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "语音转字幕"

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupSpinners() {
        // 语言选择器
        val languageAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languageOptions
        )
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSourceLanguage.adapter = languageAdapter

        // 输出格式选择器
        val formatAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            formatOptions
        )
        formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerOutputFormat.adapter = formatAdapter
        binding.spinnerOutputFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateVadOptionState()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        updateVadOptionState()
    }

    private fun setupButtons() {
        binding.btnModelSettings.setOnClickListener {
            startActivity(Intent(this, ModelSettingsActivity::class.java))
        }

        // 选择文件按钮
        binding.btnSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("audio/*", "video/*"))
        }

        // 选择输出目录按钮
        binding.btnSelectOutputDir.setOnClickListener {
            outputDirLauncher.launch(outputDirUri)
        }

        // 开始转换按钮
        binding.btnStart.setOnClickListener {
            startConversion()
        }

        // 取消按钮
        binding.btnCancel.setOnClickListener {
            confirmCancelConversion()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isConverting) loadSavedModel(resetOutputDirectory = false)
    }

    private fun setupScrollableLogs() {
        binding.realtimeResultScroll.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    /**
     * 加载已保存的模型路径
     */
    private fun loadSavedModel(resetOutputDirectory: Boolean = true) {
        modelType = settingsManager.getAsrModelType()
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
        vadModelPath = settingsManager.getVadModelPath()

        if (resetOutputDirectory) {
            setupDefaultOutputDir()
        }

        updateStartButtonState()
    }

    /**
     * 处理选择的音频/视频文件
     */
    private fun handleSelectedFiles(uris: List<Uri>) {
        selectedMediaFiles.clear()
        selectedMediaFiles.addAll(uris.map { uri ->
            SelectedMediaFile(uri, getFileNameFromUri(uri))
        })
        binding.tvSelectedFile.text = buildString {
            append("已选择 ${selectedMediaFiles.size} 个文件：")
            selectedMediaFiles.forEachIndexed { index, file ->
                append("\n${index + 1}. ${file.fileName}")
            }
        }
        updateStartButtonState()
    }

    /**
     * 处理选择的输出目录
     */
    private fun handleSelectedOutputDir(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            outputDirUri = uri
            binding.tvOutputDir.text = DirectoryDisplayPath.fromUri(this, uri)

        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "选择目录失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 设置默认输出目录
     */
    private fun setupDefaultOutputDir() {
        try {
            val defaultPath = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "SubtitleEdit/Convert"
            )

            if (!defaultPath.exists()) {
                defaultPath.mkdirs()
            }

            outputDirUri = Uri.fromFile(defaultPath)
            binding.tvOutputDir.text = defaultPath.absolutePath
        } catch (e: Exception) {
            Log.e("SpeechToSubtitle", "设置默认输出目录失败", e)
        }
    }

    /**
     * 从 URI 获取文件名
     */
    private fun getFileNameFromUri(uri: Uri): String {
        var fileName = "未知文件"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && nameIndex >= 0) {
                fileName = it.getString(nameIndex)
            }
        }
        return fileName
    }

    /**
     * 更新开始按钮状态
     */
    private fun updateStartButtonState() {
        binding.btnStart.isEnabled = selectedMediaFiles.isNotEmpty() && isCurrentAsrModelComplete()
    }

    /**
     * 开始转换
     */
    private fun startConversion() {
        if (
            shouldUseSenseVoiceTimestampExperiment() &&
            !isSenseVoiceTimestampExperimentConfigured()
        ) {
            com.subtitleedit.util.OverwritingToast.makeText(
                this,
                "实验打轴需要先在模型设置中配置 SenseVoice int8 模型和 tokens.txt",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (selectedMediaFiles.isEmpty() || !isCurrentAsrModelComplete()) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "请先选择文件和模型", Toast.LENGTH_SHORT).show()
            return
        }
        if (shouldUseVad() && !settingsManager.isVadUseBuiltInModel() && vadModelPath.isBlank()) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "请先选择外部 VAD 模型，或在模型设置中勾选使用内置", Toast.LENGTH_SHORT).show()
            return
        }
        val outputDir = outputDirUri ?: run {
            com.subtitleedit.util.OverwritingToast.makeText(this, "输出目录未设置", Toast.LENGTH_SHORT).show()
            return
        }
        val format = formatOptions[binding.spinnerOutputFormat.selectedItemPosition]
        val extension = format.lowercase()

        if (selectedMediaFiles.any { file ->
                SubtitleOutputWriter.exists(
                    this,
                    outputDir,
                    file.fileName.substringBeforeLast("."),
                    extension
                )
            }) {
            AlertDialog.Builder(this)
                .setTitle("文件名冲突")
                .setMessage("输出目录中已存在同名字幕文件。请选择处理方式。")
                .setPositiveButton("覆盖") { _, _ ->
                    startConversion(overwriteOutput = true)
                }
                .setNeutralButton("自动重命名") { _, _ ->
                    startConversion(overwriteOutput = false)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        startConversion(overwriteOutput = false)
    }

    private fun startConversion(overwriteOutput: Boolean) {
        isCancelled = false
        realtimeResults.clear()
        lastProgressLog = ""
        conversionJob = lifecycleScope.launch {
            try {
                isConverting = true
                showProgress("正在准备...", 0)
                binding.tvRealtimeResult.text = ""
                appendRuntimeLog("开始语音转字幕")
                appendRuntimeLog("待处理文件：${selectedMediaFiles.size} 个")
                appendRuntimeLog("输出格式：${formatOptions[binding.spinnerOutputFormat.selectedItemPosition]}")
                appendRuntimeLog("源语言：${languageOptions[binding.spinnerSourceLanguage.selectedItemPosition]}")
                appendRuntimeLog("输出目录：${binding.tvOutputDir.text}")
                appendSpeechModelConfig()
                binding.btnStart.isEnabled = false

                var successCount = 0
                val failedFiles = mutableListOf<String>()
                val writtenOutputBaseNames = mutableSetOf<String>()

                for ((index, file) in selectedMediaFiles.withIndex()) {
                    if (isCancelled) break

                    val outputBaseName = file.fileName.substringBeforeLast(".").lowercase()
                    val shouldOverwrite = overwriteOutput && writtenOutputBaseNames.add(outputBaseName)
                    val result = convertFile(file, index + 1, selectedMediaFiles.size, shouldOverwrite)
                    if (isCancelled) break

                    result.onSuccess { successCount++ }
                        .onFailure { error ->
                            failedFiles.add(file.fileName)
                            appendRuntimeLog("处理失败：${error.message ?: "未知错误"}")
                        }
                }

                if (!isCancelled) {
                    showProgress("全部处理完成", 100)
                    val summary = "转录完成：成功 $successCount/${selectedMediaFiles.size}" +
                        if (failedFiles.isEmpty()) "" else "，失败 ${failedFiles.size}"
                    appendRuntimeLog(summary)
                    com.subtitleedit.util.OverwritingToast.makeText(this@SpeechToSubtitleActivity, summary, Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                if (!isCancelled) {
                    showError(e.message ?: "未知错误")
                }
            } finally {
                isConverting = false
                hideProgress()
                updateStartButtonState()
            }
        }
    }

    /**
     * 取消转换
     */
    private fun confirmCancelConversion() {
        if (!isConverting) return
        AlertDialog.Builder(this)
            .setTitle("确认取消")
            .setMessage("语音识别正在进行，确定要取消吗？")
            .setPositiveButton("取消识别") { _, _ -> cancelConversion() }
            .setNegativeButton("继续识别", null)
            .show()
    }

    private fun cancelConversion() {
        if (!isConverting) return
        isCancelled = true
        conversionJob?.cancel()
        isConverting = false
        hideProgress()
        updateStartButtonState()
        com.subtitleedit.util.OverwritingToast.makeText(this, "已取消", Toast.LENGTH_SHORT).show()
    }

    /**
     * 复制 URI 到缓存目录
     */
    private fun copyUriToCache(uri: Uri, fileName: String, taskCacheDir: File): File? {
        return try {
            val cacheFile = File(taskCacheDir, "input_$fileName")
            contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            cacheFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 转换为 16kHz PCM WAV
     */
    private fun convertToPcm(inputFile: File, taskCacheDir: File): File? {
        return try {
            val outputFile = File(taskCacheDir, "${inputFile.nameWithoutExtension}_16k.wav")
            if (outputFile.exists()) outputFile.delete()

            val cmd = "-y -i \"${inputFile.absolutePath}\" -ar 16000 -ac 1 -c:a pcm_s16le \"${outputFile.absolutePath}\""
            val session = FFmpegKit.execute(cmd)

            if (session.getReturnCode()?.isValueSuccess() == true && outputFile.exists()) {
                outputFile
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 生成字幕内容
     */
    private fun generateSubtitle(segments: List<WhisperRecognizer.SubtitleSegment>): String {
        val format = formatOptions[binding.spinnerOutputFormat.selectedItemPosition]

        // 转换为 SubtitleEntry
        val entries = segments.mapIndexed { index, segment ->
            SubtitleEntry(
                index = index + 1,
                startTime = segment.startTime,
                endTime = segment.endTime,
                text = segment.text
            )
        }

        return when (format) {
            "SRT" -> SubtitleParser.toSRT(entries)
            "LRC" -> SubtitleParser.toLRC(entries)
            "TXT" -> SubtitleParser.toTXT(entries)
            else -> SubtitleParser.toSRT(entries)
        }
    }

    private fun shouldUseVad(): Boolean {
        return !settingsManager.isSpeechSenseVoiceTimestampEnabled() &&
            shouldPrepareTimeline()
    }

    private fun shouldPrepareTimeline(): Boolean {
        val format = formatOptions[binding.spinnerOutputFormat.selectedItemPosition]
        if (format != "TXT") {
            return true
        }
        return !binding.switchDisableVadForTxt.isChecked
    }

    private fun updateVadOptionState() {
        val isTxt = formatOptions[binding.spinnerOutputFormat.selectedItemPosition] == "TXT"
        binding.switchDisableVadForTxt.isEnabled = isTxt
        binding.tvDisableVadHint.alpha = if (isTxt) 1f else 0.6f
    }

    /**
     * 保存字幕文件
     */
    private fun saveSubtitleFile(
        sourceFileName: String,
        content: String,
        overwrite: Boolean,
        segmentCount: Int
    ): String {
        val outputDir = outputDirUri ?: throw IllegalStateException("输出目录未设置")
        val format = formatOptions[binding.spinnerOutputFormat.selectedItemPosition]
        val baseFileName = sourceFileName.substringBeforeLast(".")
        val extension = format.lowercase()
        val fileName = SubtitleOutputWriter.writeText(this, outputDir, baseFileName, extension, content, overwrite)
        com.subtitleedit.util.OverwritingToast.makeText(this, "字幕已保存：$fileName（共 $segmentCount 条）", Toast.LENGTH_LONG).show()
        return fileName
    }

    private suspend fun convertFile(
        file: SelectedMediaFile,
        fileIndex: Int,
        fileCount: Int,
        overwriteOutput: Boolean
    ): Result<Unit> {
        val taskCacheDir = File(
            cacheDir,
            "speech_to_subtitle_${System.currentTimeMillis()}_${System.nanoTime()}"
        ).apply { mkdirs() }
        val progressPrefix = "[$fileIndex/$fileCount]"

        return try {
            showProgress("$progressPrefix 正在准备...", 0)
            appendRuntimeLog("开始处理 $progressPrefix：${file.fileName}")
            appendRuntimeLog("预处理：复制输入文件到缓存目录")
            val cachedFile = withContext(Dispatchers.IO) {
                copyUriToCache(file.uri, file.fileName, taskCacheDir)
            } ?: return Result.failure(Exception("复制文件失败"))
            appendRuntimeLog("缓存文件：${cachedFile.name}，大小 ${formatBytes(cachedFile.length())}")

            if (isCancelled) return Result.failure(Exception("用户取消"))

            showProgress("$progressPrefix 正在提取音频...", 5)
            appendRuntimeLog("预处理：使用 FFmpeg 提取 16kHz 单声道 PCM WAV")
            val pcmFile = withContext(Dispatchers.IO) {
                convertToPcm(cachedFile, taskCacheDir)
            } ?: return Result.failure(Exception("音频转换失败"))
            appendRuntimeLog("PCM 文件：${pcmFile.name}，大小 ${formatBytes(pcmFile.length())}")

            if (isCancelled) return Result.failure(Exception("用户取消"))

            showProgress("$progressPrefix 正在识别语音...", 10)
            val selectedLanguage = languageOptions[binding.spinnerSourceLanguage.selectedItemPosition]
            val timestampExperiment = isSenseVoiceTimestampExperimentConfigured()
            val recognitionResult = if (timestampExperiment) {
                recognizeWithSenseVoiceTimeline(
                    pcmFile = pcmFile,
                    selectedLanguage = selectedLanguage,
                    progressPrefix = progressPrefix
                )
            } else {
                appendRuntimeLog("识别：初始化 ${currentAsrModelDisplayName()} 模型并开始识别")
                if (modelType == SettingsManager.ASR_MODEL_SENSEVOICE) {
                    appendRuntimeLog(
                        "SenseVoice 指定语言：$selectedLanguage " +
                            "(${senseVoiceLanguageCode(selectedLanguage)})"
                    )
                }
                val recognizer = WhisperRecognizer(
                    encoderPath = encoderPath,
                    decoderPath = decoderPath,
                    joinerPath = joinerPath,
                    tokensPath = tokensPath,
                    vadModelPath = getActiveVadModelPath(),
                    useVad = shouldUseVad(),
                    language = selectedLanguage,
                    contentResolver = contentResolver,
                    context = this@SpeechToSubtitleActivity,
                    modelType = modelType
                )
                withContext(Dispatchers.IO) {
                    recognizer.recognize(
                        audioFile = pcmFile,
                        progressCallback = { progress, status, segmentResult ->
                            runOnUiThread {
                                showProgress(
                                    "$progressPrefix $status",
                                    10 + (progress * 0.8).toInt()
                                )
                                segmentResult?.let(::appendRecognizedSegment)
                            }
                        },
                        isCancelled = { isCancelled }
                    )
                }
            }

            if (isCancelled) return Result.failure(Exception("用户取消"))
            val segments = recognitionResult.getOrElse { return Result.failure(it) }
            if (segments.isEmpty()) return Result.failure(Exception("未识别到语音内容"))

            showProgress("$progressPrefix 正在生成字幕...", 95)
            appendRuntimeLog("识别完成：共 ${segments.size} 条字幕片段")
            val subtitleContent = generateSubtitle(segments)
            val outputFileName = saveSubtitleFile(file.fileName, subtitleContent, overwriteOutput, segments.size)
            appendRuntimeLog("已保存字幕：$outputFileName")
            showProgress("$progressPrefix 完成", 100)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                taskCacheDir.deleteRecursively()
            }
        }
    }

    private suspend fun recognizeWithSenseVoiceTimeline(
        pcmFile: File,
        selectedLanguage: String,
        progressPrefix: String
    ): Result<List<WhisperRecognizer.SubtitleSegment>> {
        appendRuntimeLog("实验打轴：初始化 SenseVoice int8 模型，仅生成时间轴")
        appendRuntimeLog(
            "SenseVoice 指定语言：$selectedLanguage " +
                "(${senseVoiceLanguageCode(selectedLanguage)})"
        )
        val timelineResult = withContext(Dispatchers.IO) {
            SenseVoiceTimestampGenerator(this@SpeechToSubtitleActivity).generateSegments(
                pcmFile = pcmFile,
                language = selectedLanguage,
                progressCallback = { progress, status ->
                    runOnUiThread {
                        showProgress(
                            "$progressPrefix 实验打轴：$status",
                            10 + (progress * 0.4).toInt()
                        )
                    }
                },
                isCancelled = { isCancelled }
            )
        }
        if (isCancelled) return Result.failure(Exception("用户取消"))

        val timelineSegments = timelineResult.getOrElse { return Result.failure(it) }
        if (!settingsManager.isSpeechSenseVoiceTimestampDiscardTextEnabled()) {
            appendRuntimeLog(
                "实验打轴完成：生成 ${timelineSegments.size} 个字幕段，保留 SenseVoice 文本"
            )
            return Result.success(
                timelineSegments.map { segment ->
                    WhisperRecognizer.SubtitleSegment(
                        startTime = segment.startTime,
                        endTime = segment.endTime,
                        text = segment.text
                    )
                }.also { segments ->
                    segments.forEach(::appendRecognizedSegment)
                }
            )
        }
        val ranges = timelineSegments.map { it.startTime..it.endTime }
        appendRuntimeLog("实验打轴完成：生成 ${ranges.size} 个时间范围，已丢弃 SenseVoice 文本")

        appendRuntimeLog("识别：初始化 ${currentAsrModelDisplayName()}，按实验时间轴生成字幕文本")
        val textRecognizer = WhisperRecognizer(
            encoderPath = encoderPath,
            decoderPath = decoderPath,
            joinerPath = joinerPath,
            tokensPath = tokensPath,
            vadModelPath = "",
            useVad = false,
            language = selectedLanguage,
            contentResolver = contentResolver,
            context = this@SpeechToSubtitleActivity,
            modelType = modelType
        )
        val textResult = withContext(Dispatchers.IO) {
            textRecognizer.recognizeRanges(
                audioFile = pcmFile,
                ranges = ranges,
                progressCallback = { current, total ->
                    runOnUiThread {
                        val rangeProgress = if (total > 0) current * 40 / total else 40
                        showProgress(
                            "$progressPrefix 正在按实验时间轴识别第 $current/$total 段...",
                            50 + rangeProgress
                        )
                    }
                },
                isCancelled = { isCancelled }
            )
        }
        if (isCancelled) return Result.failure(Exception("用户取消"))

        return textResult.mapCatching { texts ->
            if (texts.size != ranges.size) {
                error("按时间轴识别结果数量与时间范围不一致")
            }
            ranges.mapIndexed { index, range ->
                WhisperRecognizer.SubtitleSegment(
                    startTime = range.first,
                    endTime = range.last,
                    text = texts[index]
                )
            }.also { segments ->
                segments.forEach(::appendRecognizedSegment)
            }
        }
    }

    /**
     * 显示进度
     */
    private fun showProgress(status: String, progress: Int) {
        binding.layoutProgress.visibility = View.VISIBLE
        binding.progressIndicator.visibility = View.VISIBLE
        binding.btnCancel.visibility = View.VISIBLE
        binding.progressIndicator.progress = progress
        binding.tvProgressStatus.text = status
        if (status != lastProgressLog) {
            lastProgressLog = status
            appendRuntimeLog(status)
        }
    }

    /**
     * 隐藏进度
     */
    private fun hideProgress() {
        binding.progressIndicator.visibility = View.GONE
        binding.btnCancel.visibility = View.GONE
    }

    /**
     * 显示错误
     */
    private fun showError(message: String?) {
        AlertDialog.Builder(this)
            .setTitle("错误")
            .setMessage(message ?: "未知错误")
            .setPositiveButton("确定", null)
            .show()
    }

    private fun appendRuntimeLog(message: String) {
        realtimeResults.append("[${formatClockTime()}] $message\n")
        trimVisibleLog()
        scheduleLogRender()
    }

    private fun appendRecognizedSegment(segment: WhisperRecognizer.SubtitleSegment) {
        realtimeResults.append("\n")
        realtimeResults.append("[${formatSubtitleTime(segment.startTime)} --> ${formatSubtitleTime(segment.endTime)}]\n")
        realtimeResults.append(segment.text).append("\n")
        trimVisibleLog()
        scheduleLogRender()
    }

    private fun appendSpeechModelConfig() {
        appendRuntimeLog("模型配置：")
        appendRuntimeLog("  类型：${currentAsrModelDisplayName()}")
        val singleFileModel = modelType == SettingsManager.ASR_MODEL_SENSEVOICE ||
            modelType == SettingsManager.ASR_MODEL_PARAKEET_CTC_JA
        appendRuntimeLog("  ${if (singleFileModel) "模型" else "Encoder"}：${displayModelPath(encoderPath)}")
        if (!singleFileModel) appendRuntimeLog("  Decoder：${displayModelPath(decoderPath)}")
        if (modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT) {
            appendRuntimeLog("  Joiner：${displayModelPath(joinerPath)}")
        }
        appendRuntimeLog("  Tokens：${displayModelPath(tokensPath)}")
        if (modelType != SettingsManager.ASR_MODEL_SENSEVOICE) {
            appendRuntimeLog("  识别线程：${settingsManager.getSpeechWhisperThreads()}")
        }
        if (isSenseVoiceTimestampExperimentConfigured()) {
            appendRuntimeLog("  VAD：禁用，由 SenseVoice 实验 token 时间戳打轴替代")
            appendRuntimeLog(
                "  实验打轴模型：${displayModelPath(senseVoiceTimestampModelPath())}"
            )
            appendRuntimeLog(
                "  实验打轴 Tokens：${displayModelPath(senseVoiceTimestampTokensPath())}"
            )
            appendRuntimeLog(
                "  Token 切分间隔：${settingsManager.getSpeechSenseVoiceTimestampGapMs()}ms"
            )
            appendRuntimeLog(
                "  动态 Padding：${if (settingsManager.isSpeechVadDynamicPaddingEnabled()) "启用（前后最多各 500ms）" else "关闭"}"
            )
            if (
                modelType == SettingsManager.ASR_MODEL_WHISPER ||
                modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT
            ) {
                appendRuntimeLog("  热词：${if (settingsManager.isSpeechHotwordsEnabled()) "启用，权重 ${settingsManager.getSpeechHotwordsScore()}" else "未启用"}")
            }
            return
        }
        val useVad = shouldUseVad()
        val fixedSegmentText = if (
            modelType == SettingsManager.ASR_MODEL_SENSEVOICE &&
            settingsManager.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU
        ) {
            "NPU 模型固定分段 ${settingsManager.getSenseVoiceNpuDurationSeconds()} 秒"
        } else {
            "固定分段 ${settingsManager.getSpeechFixedSegmentSeconds()} 秒"
        }
        appendRuntimeLog("  VAD：${if (useVad) "启用" else "禁用，$fixedSegmentText"}")
        if (useVad) {
            appendRuntimeLog("  VAD 模型：${if (settingsManager.isVadUseBuiltInModel()) "内置 silero_vad.onnx" else displayModelPath(vadModelPath)}")
            appendRuntimeLog("  VAD 阈值：${settingsManager.getVadThreshold()}，最小静音：${settingsManager.getVadMinSilenceDuration()}s，最小语音：${settingsManager.getVadMinSpeechDuration()}s，最大语音：${settingsManager.getVadMaxSpeechDuration()}s")
            appendRuntimeLog("  动态 Padding：${if (settingsManager.isSpeechVadDynamicPaddingEnabled()) "启用（前后最多各 500ms）" else "关闭"}")
            appendRuntimeLog(
                "  识别流程合并语音段：${if (settingsManager.isSpeechVadMergeEnabled()) {
                    "启用，最大间隔 ${settingsManager.getSpeechVadMergeGapMs()}ms"
                } else {
                    "关闭"
                }}"
            )
            val secondaryVadMode = settingsManager.getSpeechSecondaryVadMode()
            val secondaryVadText = when (secondaryVadMode) {
                SettingsManager.SECONDARY_VAD_MODE_UNCOVERED -> "方案一（处理未划分区间）"
                SettingsManager.SECONDARY_VAD_MODE_WITHIN_SEGMENTS -> "方案二（段内再次划分）"
                else -> "关闭"
            }
            appendRuntimeLog("  二次 VAD：$secondaryVadText")
            if (secondaryVadMode != SettingsManager.SECONDARY_VAD_MODE_NONE) {
                appendRuntimeLog(
                    "  二次 VAD 参数：阈值 ${settingsManager.getSpeechSecondaryVadThreshold()}，" +
                        "最小静音 ${settingsManager.getSpeechSecondaryVadMinSilenceDuration()}s，" +
                        "最小语音 ${settingsManager.getSpeechSecondaryVadMinSpeechDuration()}s，" +
                        "最大语音 ${settingsManager.getSpeechSecondaryVadMaxSpeechDuration()}s"
                )
                appendRuntimeLog(
                    "  一次/二次 VAD 合并语音段：${if (settingsManager.isSpeechSecondaryVadMergeEnabled()) {
                        "启用，最大间隔 ${settingsManager.getSpeechSecondaryVadMergeGapMs()}ms"
                    } else {
                        "关闭"
                    }}"
                )
            }
        }
        if (modelType == SettingsManager.ASR_MODEL_WHISPER || modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT) {
            appendRuntimeLog("  热词：${if (settingsManager.isSpeechHotwordsEnabled()) "启用，权重 ${settingsManager.getSpeechHotwordsScore()}" else "未启用"}")
        }
    }

    private fun isCurrentAsrModelComplete(): Boolean {
        if (encoderPath.isBlank() || tokensPath.isBlank()) return false
        return when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE,
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> true
            SettingsManager.ASR_MODEL_PARAKEET_TDT -> decoderPath.isNotBlank() && joinerPath.isNotBlank()
            else -> decoderPath.isNotBlank()
        }
    }

    private fun currentAsrModelDisplayName(): String = asrModelDisplayName(modelType)

    private fun asrModelDisplayName(type: String): String = when (type) {
        SettingsManager.ASR_MODEL_SENSEVOICE -> "SenseVoice"
        SettingsManager.ASR_MODEL_PARAKEET_TDT -> "Parakeet TDT 0.6B v3"
        SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> "Parakeet CTC 0.6B 日语"
        else -> "Whisper"
    }

    private fun isSenseVoiceTimestampExperimentConfigured(): Boolean {
        return shouldUseSenseVoiceTimestampExperiment() &&
            SenseVoiceTimestampGenerator.isConfigured(this)
    }

    private fun shouldUseSenseVoiceTimestampExperiment(): Boolean =
        settingsManager.isSpeechSenseVoiceTimestampEnabled() && shouldPrepareTimeline()

    private fun senseVoiceTimestampModelPath(): String =
        SenseVoiceTimestampGenerator.modelPath(settingsManager)

    private fun senseVoiceTimestampTokensPath(): String =
        SenseVoiceTimestampGenerator.tokensPath(settingsManager)

    private fun getActiveVadModelPath(): String {
        return if (settingsManager.isVadUseBuiltInModel()) "" else vadModelPath
    }

    private fun displayModelPath(path: String): String {
        return if (path.isBlank()) "未设置" else Uri.parse(path).lastPathSegment ?: path
    }

    private fun senseVoiceLanguageCode(language: String): String = when (language) {
        "中文" -> "zh"
        "英语" -> "en"
        "日语" -> "ja"
        "韩语" -> "ko"
        else -> "auto"
    }

    private fun trimVisibleLog() {
        val overflow = realtimeResults.length - MAX_VISIBLE_LOG_CHARS
        if (overflow > 0) realtimeResults.delete(0, overflow)
    }

    private fun scheduleLogRender() {
        if (logRenderScheduled) return
        logRenderScheduled = true
        binding.tvRealtimeResult.postDelayed({
            binding.tvRealtimeResult.text = realtimeResults.toString()
            binding.realtimeResultScroll.fullScroll(View.FOCUS_DOWN)
            logRenderScheduled = false
        }, LOG_RENDER_INTERVAL_MS)
    }

    private fun formatClockTime(): String {
        return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
    }

    private fun formatSubtitleTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millis = timeMs % 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> String.format(java.util.Locale.getDefault(), "%.2f MB", bytes / 1024.0 / 1024.0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        conversionJob?.cancel()
    }
}
