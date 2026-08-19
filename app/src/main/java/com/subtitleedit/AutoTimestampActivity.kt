package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.subtitleedit.databinding.ActivityAutoTimestampBinding
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleOutputWriter
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.TokenTimestampGenerator
import com.subtitleedit.util.VadTimestampGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自动打轴页面 - 自动检测语音段并生成时间轴
 */
class AutoTimestampActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAutoTimestampBinding
    private val selectedMediaFiles = mutableListOf<SelectedMediaFile>()
    private var selectedSubtitleFile: SelectedMediaFile? = null
    private var outputDirUri: Uri? = null
    private var generationJob: Job? = null
    private var isGenerating = false
    private var isCancelled = false
    private lateinit var settingsManager: SettingsManager
    private val operationLog = StringBuilder()

    private val formatOptions = arrayOf("SRT", "LRC")

    private data class SelectedMediaFile(
        val uri: Uri,
        val fileName: String
    )

    private data class MergeableSubtitleEntry(
        val entry: SubtitleEntry,
        val generatedPlaceholder: Boolean
    )

    // 音频文件选择器
    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) handleSelectedAudios(uris)
    }

    private val subtitlePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(::handleSelectedSubtitle)
    }

    // 输出目录选择器
    private val outputDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { handleSelectedOutputDir(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutoTimestampBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsManager = SettingsManager.getInstance(this)

        setupToolbar()
        setupSpinners()
        setupButtons()
        setupScrollableLogs()
        setupDefaultOutputDir()
        updateSecondaryProcessingAvailability()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isGenerating) {
                    AlertDialog.Builder(this@AutoTimestampActivity)
                        .setTitle("正在处理中")
                        .setMessage("自动打轴正在进行，确定要返回吗？返回后处理将被取消。")
                        .setPositiveButton("返回并取消") { _, _ ->
                            cancelGeneration(showToast = false)
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                        .setNegativeButton("继续处理", null)
                        .show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        updateSecondaryProcessingAvailability()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "自动打轴"

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupSpinners() {
        // 输出格式选择器
        val formatAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, formatOptions)
        formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerOutputFormat.adapter = formatAdapter
    }

    private fun setupButtons() {
        binding.btnSelectAudio.setOnClickListener {
            audioPickerLauncher.launch(arrayOf("audio/*", "video/*"))
        }

        binding.switchSecondaryProcessing.setOnCheckedChangeListener { _, checked ->
            updateSecondaryProcessingState(checked)
            updateGenerateButtonState()
        }

        binding.btnSelectSubtitle.setOnClickListener {
            subtitlePickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnSelectOutputDir.setOnClickListener {
            outputDirLauncher.launch(outputDirUri)
        }

        binding.btnGenerate.setOnClickListener {
            generateTimestamps()
        }

        binding.btnCancel.setOnClickListener {
            confirmCancelGeneration()
        }
    }

    private fun setupScrollableLogs() {
        binding.previewScroll.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

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
            Log.e("AutoTimestamp", "设置默认输出目录失败", e)
        }
    }

    private fun handleSelectedAudios(uris: List<Uri>) {
        try {
            selectedMediaFiles.clear()
            selectedMediaFiles.addAll(uris.map { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                SelectedMediaFile(uri, getFileNameFromUri(uri))
            })
            binding.tvAudioFile.text = buildString {
                append("已选择 ${selectedMediaFiles.size} 个文件：")
                selectedMediaFiles.forEachIndexed { index, file ->
                    append("\n${index + 1}. ${file.fileName}")
                }
            }
            updateGenerateButtonState()

        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSelectedSubtitle(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val fileName = getFileNameFromUri(uri)
            val extension = fileName.substringAfterLast('.', "").lowercase()
            if (extension !in setOf("srt", "lrc")) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    this,
                    "请选择 SRT 或 LRC 字幕文件",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            selectedSubtitleFile = SelectedMediaFile(uri, fileName)
            binding.tvSubtitleFile.text = fileName
            updateGenerateButtonState()
        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(
                this,
                "选择字幕文件失败：${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateSecondaryProcessingState(enabled: Boolean) {
        binding.btnSelectSubtitle.isEnabled = enabled
        binding.tvSubtitleFileTitle.alpha = if (enabled) 1f else 0.55f
        binding.btnSelectSubtitle.alpha = if (enabled) 1f else 0.55f
        binding.tvSubtitleFile.alpha = if (enabled) 1f else 0.55f
    }

    private fun updateSecondaryProcessingAvailability() {
        val tokenTimestampExperimentEnabled = isTokenTimestampExperimentEnabled()
        if (tokenTimestampExperimentEnabled && binding.switchSecondaryProcessing.isChecked) {
            binding.switchSecondaryProcessing.isChecked = false
        }
        binding.switchSecondaryProcessing.isEnabled = !tokenTimestampExperimentEnabled
        binding.switchSecondaryProcessing.alpha =
            if (tokenTimestampExperimentEnabled) 0.55f else 1f
        binding.tvSecondaryProcessingHint.text = getString(
            if (tokenTimestampExperimentEnabled) {
                R.string.activity_auto_timestamp_text_17
            } else {
                R.string.activity_auto_timestamp_text_13
            }
        )
        updateSecondaryProcessingState(
            !tokenTimestampExperimentEnabled && binding.switchSecondaryProcessing.isChecked
        )
        updateGenerateButtonState()
    }

    private fun updateGenerateButtonState() {
        val subtitleReady = !binding.switchSecondaryProcessing.isChecked ||
            selectedSubtitleFile != null
        binding.btnGenerate.isEnabled = selectedMediaFiles.isNotEmpty() && subtitleReady && !isGenerating
    }

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

    private fun generateTimestamps() {
        if (selectedMediaFiles.isEmpty()) return
        if (binding.switchSecondaryProcessing.isChecked) {
            if (selectedMediaFiles.size != 1) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    this,
                    "二次处理时请选择一个音频或视频文件",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            if (selectedSubtitleFile == null) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    this,
                    "请先选择要二次处理的字幕文件",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }
        val tokenTimestampExperimentEnabled = isTokenTimestampExperimentEnabled()
        if (
            tokenTimestampExperimentEnabled &&
            !TokenTimestampGenerator.isConfigured(this)
        ) {
            com.subtitleedit.util.OverwritingToast.makeText(
                this,
                "实验打轴需要先在模型设置中配置当前非 Whisper ASR 模型",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (
            !tokenTimestampExperimentEnabled &&
            !settingsManager.isVadUseBuiltInModel() &&
            settingsManager.getVadModelPath().isBlank()
        ) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "请先选择外部 VAD 模型，或在模型设置中勾选使用内置", Toast.LENGTH_SHORT).show()
            return
        }
        val outputDir = outputDirUri ?: run {
            com.subtitleedit.util.OverwritingToast.makeText(this, "请选择输出目录", Toast.LENGTH_SHORT).show()
            return
        }
        val format = formatOptions[binding.spinnerOutputFormat.selectedItemPosition]
        val extension = format.lowercase()

        if (selectedMediaFiles.any { file ->
                SubtitleOutputWriter.exists(
                    this,
                    outputDir,
                    outputSourceFileName(file).substringBeforeLast("."),
                    extension
                )
            }) {
            AlertDialog.Builder(this)
                .setTitle("文件名冲突")
                .setMessage("输出目录中已存在同名字幕文件。请选择处理方式。")
                .setPositiveButton("覆盖") { _, _ ->
                    generateTimestamps(overwriteOutput = true)
                }
                .setNeutralButton("自动重命名") { _, _ ->
                    generateTimestamps(overwriteOutput = false)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        generateTimestamps(overwriteOutput = false)
    }

    private fun generateTimestamps(overwriteOutput: Boolean) {
        val outputDir = outputDirUri ?: run {
            com.subtitleedit.util.OverwritingToast.makeText(this, "请选择输出目录", Toast.LENGTH_SHORT).show()
            return
        }
        val refinementSubtitle = selectedSubtitleFile.takeIf {
            binding.switchSecondaryProcessing.isChecked
        }

        binding.btnGenerate.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.btnCancel.visibility = android.view.View.VISIBLE
        binding.tvStatus.text = "正在处理..."
        operationLog.clear()
        binding.tvPreview.text = ""
        appendOperationLog("开始自动打轴")
        appendOperationLog("待处理文件：${selectedMediaFiles.size} 个")
        appendOperationLog("输出格式：${formatOptions[binding.spinnerOutputFormat.selectedItemPosition]}")
        appendOperationLog("输出目录：${binding.tvOutputDir.text}")
        appendOperationLog("预处理配置：FFmpeg 提取 16kHz 单声道 PCM WAV")
        if (refinementSubtitle != null) {
            appendOperationLog("二次处理：启用，方案一")
            appendOperationLog("参考字幕：${refinementSubtitle.fileName}")
        } else {
            appendOperationLog("二次处理：关闭")
        }
        appendVadConfig(refinementSubtitle != null)
        isGenerating = true
        isCancelled = false

        generationJob = lifecycleScope.launch {
            try {
                var successCount = 0
                val failedFiles = mutableListOf<String>()
                val writtenOutputBaseNames = mutableSetOf<String>()

                for ((index, file) in selectedMediaFiles.withIndex()) {
                    if (isCancelled) break

                    val outputBaseName = outputSourceFileName(file, refinementSubtitle)
                        .substringBeforeLast(".")
                        .lowercase()
                    val shouldOverwrite = overwriteOutput && writtenOutputBaseNames.add(outputBaseName)
                    val result = generateTimestampForFile(
                        file,
                        index + 1,
                        selectedMediaFiles.size,
                        outputDir,
                        shouldOverwrite,
                        refinementSubtitle
                    )
                    if (isCancelled) break

                    result.onSuccess { successCount++ }
                        .onFailure { error ->
                            failedFiles.add(file.fileName)
                            appendOperationLog("处理失败：${error.message ?: "未知错误"}")
                        }
                }

                if (!isCancelled) {
                    val summary = "自动打轴完成：成功 $successCount/${selectedMediaFiles.size}" +
                        if (failedFiles.isEmpty()) "" else "，失败 ${failedFiles.size}"
                    binding.tvStatus.text = summary
                    appendOperationLog(summary)
                    com.subtitleedit.util.OverwritingToast.makeText(this@AutoTimestampActivity, summary, Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                binding.tvStatus.text = if (isGenerating) "处理失败" else "已取消"
                if (isGenerating) {
                    com.subtitleedit.util.OverwritingToast.makeText(this@AutoTimestampActivity, "处理失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                binding.progressBar.visibility = android.view.View.GONE
                binding.btnCancel.visibility = android.view.View.GONE
                isGenerating = false
                updateGenerateButtonState()
                generationJob = null
            }
        }
    }

    private fun confirmCancelGeneration() {
        if (!isGenerating) return
        AlertDialog.Builder(this)
            .setTitle("确认取消")
            .setMessage("自动打轴正在进行，确定要取消吗？")
            .setPositiveButton("取消处理") { _, _ -> cancelGeneration() }
            .setNegativeButton("继续处理", null)
            .show()
    }

    private fun cancelGeneration(showToast: Boolean = true) {
        if (!isGenerating) return
        isCancelled = true
        generationJob?.cancel()
        isGenerating = false
        binding.progressBar.visibility = android.view.View.GONE
        binding.btnCancel.visibility = android.view.View.GONE
        updateGenerateButtonState()
        binding.tvStatus.text = "已取消"
        if (showToast) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "已取消", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun generateTimestampForFile(
        file: SelectedMediaFile,
        fileIndex: Int,
        fileCount: Int,
        outputDir: Uri,
        overwriteOutput: Boolean,
        refinementSubtitle: SelectedMediaFile?
    ): Result<Unit> {
        val taskCacheDir = File(
            cacheDir,
            "auto_timestamp_${System.currentTimeMillis()}_${System.nanoTime()}"
        ).apply { mkdirs() }
        val progressPrefix = "[$fileIndex/$fileCount]"

        return try {
            binding.tvStatus.text = "$progressPrefix 正在复制文件..."
            appendOperationLog("开始处理 $progressPrefix：${file.fileName}")
            appendOperationLog("预处理：复制输入文件到缓存目录")
            val cachedFile = withContext(Dispatchers.IO) {
                copyUriToCache(file.uri, file.fileName, taskCacheDir)
            } ?: return Result.failure(Exception("复制文件失败"))
            appendOperationLog("缓存文件：${cachedFile.name}，大小 ${formatBytes(cachedFile.length())}")

            if (isCancelled) return Result.failure(Exception("用户取消"))

            binding.tvStatus.text = "$progressPrefix 正在提取音频..."
            appendOperationLog("预处理：使用 FFmpeg 转换音频")
            val pcmFile = withContext(Dispatchers.IO) {
                convertToPcm(cachedFile, taskCacheDir)
            } ?: return Result.failure(Exception("音频转换失败"))
            appendOperationLog("PCM 文件：${pcmFile.name}，大小 ${formatBytes(pcmFile.length())}")

            if (isCancelled) return Result.failure(Exception("用户取消"))

            binding.tvStatus.text = "$progressPrefix 正在检测语音段..."
            val originalEntries = if (refinementSubtitle != null) {
                appendOperationLog("读取参考字幕：${refinementSubtitle.fileName}")
                withContext(Dispatchers.IO) {
                    loadSubtitleEntries(refinementSubtitle)
                }.getOrElse { return Result.failure(it) }
            } else {
                emptyList()
            }

            val tokenTimestampExperimentEnabled = isTokenTimestampExperimentEnabled()
            val segmentsResult = withContext(Dispatchers.IO) {
                if (tokenTimestampExperimentEnabled) {
                    val generator = TokenTimestampGenerator(this@AutoTimestampActivity)
                    val modelName = TokenTimestampGenerator.modelDisplayName(settingsManager)
                    val result = if (refinementSubtitle != null) {
                        generator.generateUncoveredSegments(
                            pcmFile = pcmFile,
                            occupiedTimeRangesMs = originalEntries.map { entry ->
                                entry.startTime to entry.endTime
                            },
                            progressCallback = { progress, status ->
                                runOnUiThread {
                                    binding.tvStatus.text =
                                        "$progressPrefix $modelName token 打轴：$status ($progress%)"
                                }
                            },
                            isCancelled = { isCancelled }
                        )
                    } else {
                        generator.generateSegments(
                            pcmFile = pcmFile,
                            progressCallback = { progress, status ->
                                runOnUiThread {
                                    binding.tvStatus.text =
                                        "$progressPrefix $modelName token 打轴：$status ($progress%)"
                                }
                            },
                            isCancelled = { isCancelled }
                        )
                    }
                    result.map { generatedSegments ->
                        generatedSegments.map { segment ->
                            VadTimestampGenerator.VadSegment(
                                startTime = segment.startTime,
                                endTime = segment.endTime
                            )
                        }
                    }
                } else {
                    runCatching {
                        val generator = VadTimestampGenerator(this@AutoTimestampActivity)
                        if (refinementSubtitle != null) {
                            generator.generateUncoveredSegments(
                                pcmFile = pcmFile,
                                occupiedTimeRangesMs = originalEntries.map { entry ->
                                    entry.startTime to entry.endTime
                                }
                            )
                        } else {
                            generator.generateSegments(pcmFile)
                        }
                    }
                }
            }
            val segments = segmentsResult.getOrElse { return Result.failure(it) }
            if (refinementSubtitle == null && segments.isEmpty()) {
                return Result.failure(Exception("未检测到任何语音段"))
            }
            appendOperationLog(
                if (tokenTimestampExperimentEnabled && refinementSubtitle != null) {
                    "Token 时间戳实验打轴：在未覆盖区间生成 ${segments.size} 个新增时间段"
                } else if (tokenTimestampExperimentEnabled) {
                    "Token 时间戳实验打轴：生成 ${segments.size} 个时间段"
                } else if (refinementSubtitle != null) {
                    "二次 VAD：在未覆盖区间检测到 ${segments.size} 个新增语音段"
                } else {
                    "VAD：检测到 ${segments.size} 个语音段"
                }
            )
            appendVadSegments(segments)

            val format = formatOptions[binding.spinnerOutputFormat.selectedItemPosition]
            appendOperationLog("生成字幕：$format 格式")
            val outputEntries = if (refinementSubtitle != null) {
                buildRefinedSubtitleEntries(originalEntries, segments)
            } else {
                buildGeneratedSubtitleEntries(segments)
            }
            val subtitleContent = generateSubtitle(outputEntries, format)

            binding.tvStatus.text = "$progressPrefix 正在保存..."
            appendOperationLog("保存：写入输出目录")
            val outputFileName = withContext(Dispatchers.IO) {
                saveToOutputDir(
                    outputDir,
                    outputSourceFileName(file, refinementSubtitle),
                    subtitleContent,
                    format,
                    overwriteOutput
                )
            }
            appendOperationLog("已保存字幕：$outputFileName")
            operationLog.append(
                "\n===== ${outputSourceFileName(file, refinementSubtitle)} 生成结果 =====\n"
            )
            operationLog.append(subtitleContent)
            binding.tvPreview.text = operationLog.toString()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                taskCacheDir.deleteRecursively()
            }
        }
    }

    private fun loadSubtitleEntries(file: SelectedMediaFile): Result<List<SubtitleEntry>> {
        return try {
            val charset = settingsManager.getDefaultEncoding()
            val content = FileUtils.readUri(this, file.uri, charset)
            val detectedFormat = SubtitleParser.detectFormat(content)
            if (
                detectedFormat != SubtitleParser.SubtitleFormat.SRT &&
                detectedFormat != SubtitleParser.SubtitleFormat.LRC
            ) {
                return Result.failure(Exception("参考字幕不是有效的 SRT 或 LRC 文件"))
            }
            val entries = SubtitleParser.parse(content, charset)
                .filter { entry -> entry.endTime > entry.startTime }
            if (entries.isEmpty()) {
                Result.failure(Exception("参考字幕中没有有效时间段"))
            } else {
                Result.success(entries)
            }
        } catch (e: Exception) {
            Result.failure(Exception("读取参考字幕失败：${e.message}", e))
        }
    }

    private fun buildGeneratedSubtitleEntries(
        segments: List<VadTimestampGenerator.VadSegment>
    ): List<SubtitleEntry> {
        return segments.mapIndexed { index, segment ->
            SubtitleEntry(
                index = index + 1,
                startTime = segment.startTime,
                endTime = segment.endTime,
                text = "请输入文本"
            )
        }
    }

    private fun buildRefinedSubtitleEntries(
        originalEntries: List<SubtitleEntry>,
        detectedSegments: List<VadTimestampGenerator.VadSegment>
    ): List<SubtitleEntry> {
        val mergeableEntries = originalEntries.map { entry ->
            MergeableSubtitleEntry(entry.copy(), generatedPlaceholder = false)
        } + detectedSegments.map { segment ->
            MergeableSubtitleEntry(
                entry = SubtitleEntry(
                    startTime = segment.startTime,
                    endTime = segment.endTime,
                    text = "请输入文本"
                ),
                generatedPlaceholder = true
            )
        }

        val sortedEntries = mergeableEntries.sortedWith(
            compareBy<MergeableSubtitleEntry> { it.entry.startTime }
                .thenBy { it.entry.endTime }
        )
        val outputEntries = if (
            !isTokenTimestampExperimentEnabled() &&
            settingsManager.isSpeechSecondaryVadMergeEnabled()
        ) {
            mergeSubtitleEntries(
                sortedEntries,
                settingsManager.getSpeechSecondaryVadMergeGapMs()
            )
        } else {
            sortedEntries.map { it.entry }
        }
        return outputEntries
            .mapIndexed { index, entry -> entry.apply { this.index = index + 1 } }
    }

    private fun mergeSubtitleEntries(
        entries: List<MergeableSubtitleEntry>,
        maxGapMs: Int
    ): List<SubtitleEntry> {
        if (entries.isEmpty()) return emptyList()

        val merged = mutableListOf<SubtitleEntry>()
        var group = mutableListOf(entries.first())
        var groupStart = entries.first().entry.startTime
        var groupEnd = entries.first().entry.endTime
        var groupTailIsGenerated = entries.first().generatedPlaceholder

        fun flushGroup() {
            if (group.size == 1) {
                merged.add(group.first().entry.copy())
            } else {
                val text = group.joinToString(separator = "") { item ->
                    if (item.generatedPlaceholder) {
                        "（${item.entry.text}）"
                    } else {
                        item.entry.text
                    }
                }
                merged.add(
                    SubtitleEntry(
                        startTime = groupStart,
                        endTime = groupEnd,
                        text = text
                    )
                )
            }
        }

        for (next in entries.drop(1)) {
            if (
                groupTailIsGenerated != next.generatedPlaceholder &&
                next.entry.startTime - groupEnd <= maxGapMs
            ) {
                group.add(next)
                groupEnd = maxOf(groupEnd, next.entry.endTime)
                groupTailIsGenerated = next.generatedPlaceholder
            } else {
                flushGroup()
                group = mutableListOf(next)
                groupStart = next.entry.startTime
                groupEnd = next.entry.endTime
                groupTailIsGenerated = next.generatedPlaceholder
            }
        }
        flushGroup()
        return merged
    }

    private fun generateSubtitle(entries: List<SubtitleEntry>, format: String): String {
        return when (format) {
            "SRT" -> SubtitleParser.toSRT(entries)
            "LRC" -> SubtitleParser.toLRC(entries)
            "TXT" -> SubtitleParser.toTXT(entries)
            else -> SubtitleParser.toSRT(entries)
        }
    }

    private fun outputSourceFileName(
        mediaFile: SelectedMediaFile,
        subtitleFile: SelectedMediaFile? = selectedSubtitleFile.takeIf {
            binding.switchSecondaryProcessing.isChecked
        }
    ): String {
        return subtitleFile?.fileName ?: mediaFile.fileName
    }

    /**
     * 保存到输出目录
     */
    private fun saveToOutputDir(
        dirUri: Uri,
        sourceFileName: String,
        content: String,
        format: String,
        overwrite: Boolean
    ): String {
        try {
            val baseFileName = sourceFileName.substringBeforeLast(".")
            val extension = format.lowercase()
            return SubtitleOutputWriter.writeText(this, dirUri, baseFileName, extension, content, overwrite)
        } catch (e: Exception) {
            throw Exception("保存文件失败: ${e.message}")
        }
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
            Log.e("AutoTimestamp", "复制文件失败", e)
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
                Log.e("AutoTimestamp", "FFmpeg 转换失败: ${session.getOutput()}")
                null
            }
        } catch (e: Exception) {
            Log.e("AutoTimestamp", "音频转换失败", e)
            null
        }
    }

    private fun appendOperationLog(message: String) {
        operationLog.append("[${formatClockTime()}] ").append(message).append("\n")
        binding.tvPreview.text = operationLog.toString()
        binding.previewScroll.post {
            binding.previewScroll.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun isTokenTimestampExperimentEnabled(): Boolean =
        settingsManager.isSpeechTokenTimestampEnabled() &&
            TokenTimestampGenerator.isSupported(settingsManager)

    private fun appendVadConfig(secondaryProcessing: Boolean) {
        if (isTokenTimestampExperimentEnabled()) {
            appendOperationLog("Token 时间戳实验打轴配置：")
            appendOperationLog(
                "  模型：${TokenTimestampGenerator.modelDisplayName(settingsManager)} " +
                    "(${Uri.parse(TokenTimestampGenerator.modelPath(settingsManager)).lastPathSegment})"
            )
            appendOperationLog(
                "  Tokens：${Uri.parse(TokenTimestampGenerator.tokensPath(settingsManager)).lastPathSegment}"
            )
            appendOperationLog(
                "  Token 切分间隔：${settingsManager.getSpeechTokenTimestampGapMs()}ms"
            )
            appendOperationLog(
                "  合并语音段：${if (settingsManager.isSpeechTokenTimestampMergeEnabled()) {
                    "启用，最大间隔 ${settingsManager.getSpeechTokenTimestampMergeGapMs()}ms"
                } else {
                    "关闭"
                }}"
            )
            appendOperationLog("  VAD 检测与分段设置：不使用")
            if (secondaryProcessing) {
                appendOperationLog("  二次处理：排除已有字幕覆盖范围")
            }
            return
        }
        appendOperationLog("VAD 配置：")
        appendOperationLog("  模型：${getVadModelDisplayText()}")
        appendOperationLog("  采样率：16000Hz，线程：2，provider：cpu")
        if (secondaryProcessing) {
            appendOperationLog("  自动打轴字幕二次处理：方案一（独立开关）")
            appendOperationLog(
                "  二次阈值：${settingsManager.getSpeechSecondaryVadThreshold()}，" +
                    "最小静音：${settingsManager.getSpeechSecondaryVadMinSilenceDuration()}s"
            )
            appendOperationLog(
                "  最小语音：${settingsManager.getSpeechSecondaryVadMinSpeechDuration()}s，" +
                    "最大语音：${settingsManager.getSpeechSecondaryVadMaxSpeechDuration()}s"
            )
            appendSecondaryVadMergeConfig()
        } else {
            appendOperationLog("  阈值：${settingsManager.getVadThreshold()}，最小静音：${settingsManager.getVadMinSilenceDuration()}s")
            appendOperationLog("  最小语音：${settingsManager.getVadMinSpeechDuration()}s，最大语音：${settingsManager.getVadMaxSpeechDuration()}s")
            val secondaryMode = settingsManager.getSpeechSecondaryVadMode()
            val secondaryModeText = when (secondaryMode) {
                SettingsManager.SECONDARY_VAD_MODE_UNCOVERED -> "方案一（处理未划分区间）"
                SettingsManager.SECONDARY_VAD_MODE_WITHIN_SEGMENTS -> "方案二（段内再次划分）"
                else -> "关闭"
            }
            appendOperationLog("  高级配置二次 VAD：$secondaryModeText")
            if (secondaryMode != SettingsManager.SECONDARY_VAD_MODE_NONE) {
                appendOperationLog(
                    "  二次阈值：${settingsManager.getSpeechSecondaryVadThreshold()}，" +
                        "最小静音：${settingsManager.getSpeechSecondaryVadMinSilenceDuration()}s"
                )
                appendOperationLog(
                    "  二次最小语音：${settingsManager.getSpeechSecondaryVadMinSpeechDuration()}s，" +
                        "最大语音：${settingsManager.getSpeechSecondaryVadMaxSpeechDuration()}s"
                )
                appendSecondaryVadMergeConfig()
            }
        }
    }

    private fun appendSecondaryVadMergeConfig() {
        appendOperationLog(
            "  合并语音段：${if (settingsManager.isSpeechSecondaryVadMergeEnabled()) {
                "启用，最大间隔 ${settingsManager.getSpeechSecondaryVadMergeGapMs()}ms"
            } else {
                "关闭"
            }}"
        )
    }

    private fun getVadModelDisplayText(): String {
        if (settingsManager.isVadUseBuiltInModel()) {
            return "内置 silero_vad.onnx"
        }
        val path = settingsManager.getVadModelPath()
        return if (path.isBlank()) {
            "外部模型（未选择）"
        } else {
            "外部模型 ${Uri.parse(path).lastPathSegment ?: path}"
        }
    }

    private fun appendVadSegments(segments: List<VadTimestampGenerator.VadSegment>) {
        for ((index, segment) in segments.withIndex()) {
            operationLog.append(
                String.format(
                    java.util.Locale.getDefault(),
                    "  #%02d %s --> %s，时长 %.2fs\n",
                    index + 1,
                    formatSubtitleTime(segment.startTime),
                    formatSubtitleTime(segment.endTime),
                    (segment.endTime - segment.startTime) / 1000.0
                )
            )
        }
        binding.tvPreview.text = operationLog.toString()
        binding.previewScroll.post {
            binding.previewScroll.fullScroll(android.view.View.FOCUS_DOWN)
        }
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

    override fun onDestroy() {
        isCancelled = true
        generationJob?.cancel()
        super.onDestroy()
    }
}
