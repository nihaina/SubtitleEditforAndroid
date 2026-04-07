package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.subtitleedit.databinding.ActivitySpeechToSubtitleBinding
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.WhisperRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private var selectedFileUri: Uri? = null
    private var selectedFileName: String = ""
    private var encoderPath: String = ""
    private var decoderPath: String = ""
    private var tokensPath: String = ""
    private var vadModelPath: String = ""
    private var outputDirUri: Uri? = null
    private var conversionJob: Job? = null
    private var isCancelled = false
    private var pendingSubtitleContent: String = ""
    private val realtimeResults = StringBuilder()

    // 语言选项
    private val languageOptions = listOf(
        "自动检测",
        "中文",
        "英语",
        "日语",
        "韩语",
        "法语",
        "德语",
        "西班牙语",
        "俄语",
        "葡萄牙语",
        "意大利语",
        "土耳其语"
    )

    // 输出格式选项
    private val formatOptions = listOf(
        "SRT",
        "LRC",
        "TXT"
    )

    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedFile(it) }
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
        loadSavedModel()
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
    }

    private fun setupButtons() {
        // 选择文件按钮
        binding.btnSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("audio/*", "video/*"))
        }

        // 选择 Encoder 按钮
        binding.btnSelectEncoder.setOnClickListener {
            encoderPickerLauncher.launch(arrayOf("*/*"))
        }

        // 选择 Decoder 按钮
        binding.btnSelectDecoder.setOnClickListener {
            decoderPickerLauncher.launch(arrayOf("*/*"))
        }

        // 选择 Tokens 按钮
        binding.btnSelectTokens.setOnClickListener {
            tokensPickerLauncher.launch(arrayOf("*/*"))
        }

        // 选择 VAD 模型按钮
        binding.btnSelectVad.setOnClickListener {
            vadPickerLauncher.launch(arrayOf("*/*"))
        }

        // 选择输出目录按钮
        binding.btnSelectOutputDir.setOnClickListener {
            outputDirLauncher.launch(outputDirUri)
        }

        // 模型下载指引
        binding.tvModelGuide.setOnClickListener {
            showModelGuide()
        }

        // 开始转换按钮
        binding.btnStart.setOnClickListener {
            startConversion()
        }

        // 取消按钮
        binding.btnCancel.setOnClickListener {
            cancelConversion()
        }
    }

    /**
     * 加载已保存的模型路径
     */
    private fun loadSavedModel() {
        encoderPath = settingsManager.getWhisperEncoderPath()
        decoderPath = settingsManager.getWhisperDecoderPath()
        tokensPath = settingsManager.getWhisperTokensPath()
        vadModelPath = settingsManager.getVadModelPath()

        // 设置默认输出目录
        setupDefaultOutputDir()

        if (encoderPath.isNotEmpty()) {
            val uri = Uri.parse(encoderPath)
            binding.tvEncoderFile.text = getFileNameFromUri(uri)
        }
        if (decoderPath.isNotEmpty()) {
            val uri = Uri.parse(decoderPath)
            binding.tvDecoderFile.text = getFileNameFromUri(uri)
        }
        if (tokensPath.isNotEmpty()) {
            val uri = Uri.parse(tokensPath)
            binding.tvTokensFile.text = getFileNameFromUri(uri)
        }
        if (vadModelPath.isNotEmpty()) {
            val uri = Uri.parse(vadModelPath)
            binding.tvVadFile.text = "外部模型: ${getFileNameFromUri(uri)}"
        }

        updateStartButtonState()
    }

    /**
     * 处理选择的音频/视频文件
     */
    private fun handleSelectedFile(uri: Uri) {
        selectedFileUri = uri
        selectedFileName = getFileNameFromUri(uri)
        binding.tvSelectedFile.text = selectedFileName
        updateStartButtonState()
    }

    /**
     * 处理选择的 Encoder 文件
     */
    private fun handleSelectedEncoder(uri: Uri) {
        try {
            // 获取持久化权限
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            // 验证文件名
            if (!fileName.contains("encoder", ignoreCase = true) ||
                !fileName.endsWith(".onnx", ignoreCase = true)) {
                Toast.makeText(
                    this,
                    "请选择 encoder 模型文件（文件名应包含 'encoder' 且以 .onnx 结尾）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            encoderPath = uri.toString()
            settingsManager.setWhisperEncoderPath(encoderPath)
            binding.tvEncoderFile.text = fileName
            updateStartButtonState()

        } catch (e: Exception) {
            Toast.makeText(this, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 处理选择的 Decoder 文件
     */
    private fun handleSelectedDecoder(uri: Uri) {
        try {
            // 获取持久化权限
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            // 验证文件名
            if (!fileName.contains("decoder", ignoreCase = true) ||
                !fileName.endsWith(".onnx", ignoreCase = true)) {
                Toast.makeText(
                    this,
                    "请选择 decoder 模型文件（文件名应包含 'decoder' 且以 .onnx 结尾）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            decoderPath = uri.toString()
            settingsManager.setWhisperDecoderPath(decoderPath)
            binding.tvDecoderFile.text = fileName
            updateStartButtonState()

        } catch (e: Exception) {
            Toast.makeText(this, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 处理选择的 Tokens 文件
     */
    private fun handleSelectedTokens(uri: Uri) {
        try {
            // 获取持久化权限
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            // 验证文件名
            if (!fileName.contains("token", ignoreCase = true) ||
                !fileName.endsWith(".txt", ignoreCase = true)) {
                Toast.makeText(
                    this,
                    "请选择 tokens 文件（文件名应包含 'token' 且以 .txt 结尾）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            tokensPath = uri.toString()
            settingsManager.setWhisperTokensPath(tokensPath)
            binding.tvTokensFile.text = fileName
            updateStartButtonState()

        } catch (e: Exception) {
            Toast.makeText(this, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 处理选择的 VAD 模型文件
     */
    private fun handleSelectedVad(uri: Uri) {
        try {
            // 获取持久化权限
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            // 验证文件名
            if (!fileName.contains("vad", ignoreCase = true) ||
                !fileName.endsWith(".onnx", ignoreCase = true)) {
                Toast.makeText(
                    this,
                    "请选择 VAD 模型文件（文件名应包含 'vad' 且以 .onnx 结尾）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            vadModelPath = uri.toString()
            settingsManager.setVadModelPath(vadModelPath)
            binding.tvVadFile.text = "外部模型: $fileName"
            Toast.makeText(this, "外部 VAD 模型已选择", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
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
            val docFile = DocumentFile.fromTreeUri(this, uri)
            binding.tvOutputDir.text = docFile?.name ?: uri.path ?: "已选择"

        } catch (e: Exception) {
            Toast.makeText(this, "选择目录失败：${e.message}", Toast.LENGTH_LONG).show()
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
            binding.tvOutputDir.text = "Download/SubtitleEdit/Convert"
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
        binding.btnStart.isEnabled = selectedFileUri != null &&
            encoderPath.isNotEmpty() &&
            decoderPath.isNotEmpty() &&
            tokensPath.isNotEmpty()
    }

    /**
     * 显示模型下载指引
     */
    private fun showModelGuide() {
        val message = """
            Whisper 模型下载指引：

            1. 访问 Hugging Face 或 GitHub：
               • https://huggingface.co/k2-fsa/sherpa-onnx-whisper-large-v3
               • https://github.com/k2-fsa/sherpa-onnx/releases

            2. 下载模型文件（需要以下 3 个文件）：
               • encoder.onnx（或 large-v3-encoder.onnx）
               • decoder.onnx（或 large-v3-decoder.int8.onnx）
               • tokens.txt

            3. 分别点击"选择 Encoder"、"选择 Decoder"、"选择 Tokens"按钮选择对应文件

            推荐模型：
            • Whisper Tiny (~40MB) - 快速，适合实时
            • Whisper Base (~75MB) - 平衡性能和质量
            • Whisper Small (~250MB) - 高质量
            • Whisper Large V3 (~3GB) - 最高质量

            VAD 模型（可选，推荐使用）：
            • 下载地址：https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
            • 作用：精确检测语音段，提高字幕时间轴准确性
            • 大小：~2MB
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("模型下载指引")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .setNeutralButton("打开 GitHub") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/k2-fsa/sherpa-onnx/releases"))
                startActivity(intent)
            }
            .show()
    }

    /**
     * 开始转换
     */
    private fun startConversion() {
        if (selectedFileUri == null || encoderPath.isEmpty() || decoderPath.isEmpty() || tokensPath.isEmpty()) {
            Toast.makeText(this, "请先选择文件和模型", Toast.LENGTH_SHORT).show()
            return
        }

        isCancelled = false
        realtimeResults.clear()
        conversionJob = lifecycleScope.launch {
            try {
                showProgress("正在准备...", 0)
                binding.tvRealtimeResult.text = "等待识别..."
                binding.btnStart.isEnabled = false

                // 1. 复制文件到缓存
                val cachedFile = withContext(Dispatchers.IO) {
                    copyUriToCache(selectedFileUri!!, selectedFileName)
                }

                if (cachedFile == null) {
                    showError("复制文件失败")
                    return@launch
                }

                if (isCancelled) return@launch

                // 2. 转换为 16kHz PCM WAV
                showProgress("正在提取音频...", 5)
                val pcmFile = withContext(Dispatchers.IO) {
                    convertToPcm(cachedFile)
                }

                if (pcmFile == null) {
                    showError("音频转换失败")
                    return@launch
                }

                if (isCancelled) return@launch

                // 3. Whisper 识别
                showProgress("正在识别语音...", 10)
                val selectedLanguage = languageOptions[binding.spinnerSourceLanguage.selectedItemPosition]
                val recognizer = WhisperRecognizer(
                    encoderPath = encoderPath,
                    decoderPath = decoderPath,
                    tokensPath = tokensPath,
                    vadModelPath = vadModelPath,
                    language = selectedLanguage,
                    contentResolver = contentResolver,
                    context = this@SpeechToSubtitleActivity
                )

                val result = withContext(Dispatchers.IO) {
                    recognizer.recognize(
                        audioFile = pcmFile,
                        progressCallback = { progress, status, segmentResult ->
                            runOnUiThread {
                                // 10-90% 用于识别进度
                                showProgress(status, 10 + (progress * 0.8).toInt())

                                // 实时显示识别结果
                                segmentResult?.let { segment ->
                                    val timeStr = formatTime(segment.startTime)
                                    realtimeResults.append("[$timeStr] ${segment.text}\n\n")
                                    binding.tvRealtimeResult.text = realtimeResults.toString()
                                }
                            }
                        },
                        isCancelled = { isCancelled }
                    )
                }

                if (isCancelled) return@launch

                // 4. 生成字幕文件
                if (result.isSuccess) {
                    val segments = result.getOrNull()!!
                    showProgress("正在生成字幕...", 95)

                    if (segments.isEmpty()) {
                        showError("未识别到语音内容")
                        return@launch
                    }

                    val subtitleContent = generateSubtitle(segments)
                    showProgress("完成", 100)
                    saveSubtitleFile(subtitleContent)
                } else {
                    showError(result.exceptionOrNull()?.message ?: "识别失败")
                }

            } catch (e: Exception) {
                if (!isCancelled) {
                    showError(e.message ?: "未知错误")
                }
            } finally {
                hideProgress()
                binding.btnStart.isEnabled = true
            }
        }
    }

    /**
     * 取消转换
     */
    private fun cancelConversion() {
        isCancelled = true
        conversionJob?.cancel()
        hideProgress()
        binding.btnStart.isEnabled = true
        Toast.makeText(this, "已取消", Toast.LENGTH_SHORT).show()
    }

    /**
     * 复制 URI 到缓存目录
     */
    private fun copyUriToCache(uri: Uri, fileName: String): File? {
        return try {
            val cacheFile = File(cacheDir, "temp_$fileName")
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
    private fun convertToPcm(inputFile: File): File? {
        return try {
            val outputFile = File(cacheDir, "${inputFile.nameWithoutExtension}_16k.wav")
            if (outputFile.exists()) outputFile.delete()

            val cmd = "-y -i \"${inputFile.absolutePath}\" -ar 16000 -ac 1 -c:a pcm_s16le \"${outputFile.absolutePath}\""
            val session = FFmpegKit.execute(cmd)

            if (session.returnCode.isValueSuccess && outputFile.exists()) {
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

    /**
     * 保存字幕文件
     */
    private fun saveSubtitleFile(content: String) {
        val outputDir = outputDirUri ?: run {
            Toast.makeText(this, "输出目录未设置", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val format = formatOptions[binding.spinnerOutputFormat.selectedItemPosition]
            val baseFileName = selectedFileName.substringBeforeLast(".")
            val extension = format.lowercase()

            // 检查是否是 file:// URI（本地目录）
            if (outputDir.scheme == "file") {
                // 使用传统 File API
                val dir = File(outputDir.path!!)
                val fileName = getUniqueFileName(dir, baseFileName, extension)
                val outputFile = File(dir, fileName)

                outputFile.writeText(content, java.nio.charset.StandardCharsets.UTF_8)

                val segmentCount = content.lines().filter { line ->
                    line.matches(Regex("\\d+"))
                }.size
                Toast.makeText(this, "字幕已保存到输出目录（共 $segmentCount 条）", Toast.LENGTH_LONG).show()
                return
            }

            // 使用 DocumentFile API（content:// URI）
            val dir = DocumentFile.fromTreeUri(this, outputDir) ?: throw Exception("无法访问输出目录")

            val fileName = getUniqueFileNameForDocumentFile(dir, baseFileName, extension)
            val mimeType = when (format) {
                "SRT" -> "application/x-subrip"
                "LRC" -> "text/plain"
                "TXT" -> "text/plain"
                else -> "text/plain"
            }

            val outputFile = dir.createFile(mimeType, fileName)
            if (outputFile == null) {
                throw Exception("创建文件失败")
            }

            contentResolver.openOutputStream(outputFile.uri)?.use { output ->
                output.write(content.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            }

            val segmentCount = content.lines().filter { line ->
                line.matches(Regex("\\d+"))
            }.size
            Toast.makeText(this, "字幕已保存到输出目录（共 $segmentCount 条）", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 获取唯一的文件名（File API）
     */
    private fun getUniqueFileName(dir: File, baseName: String, extension: String): String {
        var fileName = "$baseName.$extension"
        var counter = 1

        while (File(dir, fileName).exists()) {
            fileName = "$baseName ($counter).$extension"
            counter++
        }

        return fileName
    }

    /**
     * 获取唯一的文件名（DocumentFile API）
     */
    private fun getUniqueFileNameForDocumentFile(dir: DocumentFile, baseName: String, extension: String): String {
        var fileName = "$baseName.$extension"
        var counter = 1

        while (dir.findFile(fileName) != null) {
            fileName = "$baseName ($counter).$extension"
            counter++
        }

        return fileName
    }

    /**
     * 显示进度
     */
    private fun showProgress(status: String, progress: Int) {
        binding.layoutProgress.visibility = View.VISIBLE
        binding.progressIndicator.progress = progress
        binding.tvProgressStatus.text = status
    }

    /**
     * 隐藏进度
     */
    private fun hideProgress() {
        binding.layoutProgress.visibility = View.GONE
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

    /**
     * 显示成功
     */
    private fun showSuccess(segmentCount: Int) {
        AlertDialog.Builder(this)
            .setTitle("转换完成")
            .setMessage("成功生成 $segmentCount 条字幕，请选择保存位置")
            .setPositiveButton("确定", null)
            .show()
    }

    /**
     * 格式化时间（毫秒转为 HH:MM:SS）
     */
    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        conversionJob?.cancel()
    }
}
