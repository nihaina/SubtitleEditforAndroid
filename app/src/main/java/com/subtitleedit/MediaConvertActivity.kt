package com.subtitleedit

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import android.widget.Space
import android.widget.TableLayout
import android.widget.TableRow
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.databinding.ActivityMediaConvertBinding
import com.subtitleedit.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * 媒体格式转换界面
 *
 * 支持：
 * - 视频格式：MP4, MKV, AVI, MOV, WebM, FLV, TS, M4V, 3GP, WMV
 * - 音频格式：MP3, AAC, WAV, FLAC, OGG, M4A, OPUS, WMA, AC3
 * - 仅提取音频（视频 → 音频）
 * - 高级选项：视频编码器、音频编码器、分辨率、码率、CRF、采样率、声道数
 */
class MediaConvertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaConvertBinding

    // ==================== 格式定义 ====================

    data class FormatInfo(
        val extension: String,
        val displayName: String,
        val videoCodecs: List<String>,   // 空 = 纯音频格式
        val audioCodecs: List<String>,
        val isAudioOnly: Boolean = false
    )

    private val FORMAT_LIST = listOf(
        // 视频格式
        FormatInfo("mp4",  "MP4",  listOf("copy","libx264","mpeg4"),          listOf("copy","aac","libmp3lame","ac3")),
        FormatInfo("mkv",  "MKV",  listOf("copy","libx264","libvpx-vp9"),     listOf("copy","aac","libmp3lame","libopus","libvorbis","flac","ac3")),
        FormatInfo("avi",  "AVI",  listOf("copy","libx264","mpeg4"),           listOf("copy","libmp3lame","aac","ac3")),
        FormatInfo("mov",  "MOV",  listOf("copy","libx264","mpeg4"),           listOf("copy","aac","libmp3lame","ac3")),
        FormatInfo("webm", "WebM", listOf("copy","libvpx","libvpx-vp9"),      listOf("copy","libvorbis","libopus")),
        FormatInfo("flv",  "FLV",  listOf("copy","libx264","flv1"),            listOf("copy","aac","libmp3lame")),
        FormatInfo("ts",   "TS",   listOf("copy","libx264","mpeg2video"),      listOf("copy","aac","libmp3lame","ac3")),
        FormatInfo("m4v",  "M4V",  listOf("copy","libx264","mpeg4"),           listOf("copy","aac")),
        FormatInfo("3gp",  "3GP",  listOf("copy","libx264","mpeg4"),           listOf("copy","aac","libmp3lame")),
        FormatInfo("wmv",  "WMV",  listOf("copy","wmv2","msmpeg4v3"),          listOf("copy","wmav2")),
        // 纯音频格式
        FormatInfo("mp3",  "MP3",  emptyList(), listOf("libmp3lame"),           isAudioOnly = true),
        FormatInfo("aac",  "AAC",  emptyList(), listOf("aac"),                  isAudioOnly = true),
        FormatInfo("m4a",  "M4A",  emptyList(), listOf("aac"),                  isAudioOnly = true),
        FormatInfo("wav",  "WAV",  emptyList(), listOf("pcm_s16le","pcm_s24le","pcm_f32le"), isAudioOnly = true),
        FormatInfo("flac", "FLAC", emptyList(), listOf("flac"),                 isAudioOnly = true),
        FormatInfo("ogg",  "OGG",  emptyList(), listOf("libvorbis","libopus"),  isAudioOnly = true),
        FormatInfo("opus", "OPUS", emptyList(), listOf("libopus"),              isAudioOnly = true),
        FormatInfo("wma",  "WMA",  emptyList(), listOf("wmav2"),                isAudioOnly = true),
        FormatInfo("ac3",  "AC3",  emptyList(), listOf("ac3"),                  isAudioOnly = true),
    )

    private val RESOLUTIONS = listOf(
        "原始分辨率", "3840x2160 (4K)", "2560x1440 (2K)", "1920x1080 (1080p)",
        "1280x720 (720p)", "854x480 (480p)", "640x360 (360p)", "426x240 (240p)"
    )
    private val SAMPLE_RATES = listOf("原始采样率", "48000 Hz", "44100 Hz", "22050 Hz", "16000 Hz", "8000 Hz")
    private val CHANNELS     = listOf("原始声道", "立体声 (2ch)", "单声道 (1ch)")
    private val CRF_LABELS   = listOf("无损 / 最高质量", "极高质量 (CRF 18)", "高质量 (CRF 23)",
                                       "中等质量 (CRF 28)", "低质量 (CRF 33)", "自定义")
    private val CRF_VALUES    = listOf("-1", "18", "23", "28", "33", "custom")

    // ==================== 状态 ====================

    private var sourceUri: Uri? = null
    private var sourceFileName: String = ""
    private var sourceMimeType: String = ""
    private var isSourceVideo: Boolean = false

    private var selectedFormat: FormatInfo? = null
    private var convertJob: Job? = null
    private var currentSession: FFmpegSession? = null
    private var outputFile: File? = null
    private var outputDirectoryUri: Uri? = null

    // 所有格式按钮（用于统一取消选中）
    private val allFormatButtons = mutableListOf<TextView>()
    private var selectedFormatButton: TextView? = null

    // ==================== 文件选择器 ====================

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onSourceFileSelected(it) }
    }

    // 目录选择器
    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            outputDirectoryUri = uri
            val path = getDirectoryPathFromUri(uri) ?: uri.toString()
            binding.tvOutputDir.text = "输出目录：$path"
        }
    }

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaConvertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupFormatGroup()
        setupAdvancedOptions()
        setupOutputDirectory()
        setupButtons()
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentSession?.cancel()
    }

    // ==================== 初始化 ====================

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "格式转换"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupFormatGroup() {
        allFormatButtons.clear()
        selectedFormatButton = null

        buildFormatGrid(
            container   = binding.rgVideoFormats,
            formats     = FORMAT_LIST.filter { !it.isAudioOnly },
            cols        = 5
        )
        buildFormatGrid(
            container   = binding.rgAudioFormats,
            formats     = FORMAT_LIST.filter { it.isAudioOnly },
            cols        = 5
        )
    }

    /**
     * 将 formats 按 cols 列分行填入 container（RadioGroup 仅作垂直 LinearLayout 使用）。
     * 每个格子是等宽 TextView，不含圆圈。
     */
    private fun buildFormatGrid(container: RadioGroup, formats: List<FormatInfo>, cols: Int) {
        container.removeAllViews()
        container.orientation = RadioGroup.VERTICAL

        formats.chunked(cols).forEach { row ->
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
                )
            }

            row.forEach { fmt ->
                val btn = makeFormatButton(fmt)
                allFormatButtons.add(btn)
                rowLayout.addView(btn)
            }

            // 用空白填满剩余列，保证每行等宽
            repeat(cols - row.size) {
                rowLayout.addView(Space(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }

            container.addView(rowLayout)
        }
    }

    private fun makeFormatButton(fmt: FormatInfo): TextView {
        val btn = TextView(this).apply {
            text      = fmt.displayName
            setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
            tag       = fmt.extension
            textSize  = 12f
            isSingleLine = true
            gravity   = android.view.Gravity.CENTER
            background = makeButtonBackground(isSelected = false)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(3, 3, 3, 3)
            }
            setPadding(0, 14, 0, 14)
            setOnClickListener { onFormatButtonClicked(this, fmt) }
        }
        return btn
    }

    /** 生成按钮背景 Drawable：选中时蓝色填充，未选中时深灰边框 */
    private fun makeButtonBackground(isSelected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f
            if (isSelected) {
                setColor(android.graphics.Color.parseColor("#1976D2"))
                setStroke(0, android.graphics.Color.TRANSPARENT)
            } else {
                setColor(android.graphics.Color.parseColor("#2C2C2C"))
                setStroke(1, android.graphics.Color.parseColor("#555555"))
            }
        }
    }

    private fun onFormatButtonClicked(btn: TextView, fmt: FormatInfo) {
        // 取消上一个选中
        selectedFormatButton?.let {
            it.background = makeButtonBackground(isSelected = false)
            it.setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
        }

        if (selectedFormatButton === btn) {
            // 再次点击同一个：取消选中
            selectedFormatButton = null
            selectedFormat = null
        } else {
            btn.background = makeButtonBackground(isSelected = true)
            btn.setTextColor(android.graphics.Color.WHITE)
            selectedFormatButton = btn
            selectedFormat = fmt
        }

        updateCodecSpinners()
        updateUI()
    }

    private fun setupAdvancedOptions() {
        // 视频编码器：未选择格式时显示占位
        binding.spinnerVideoCodec.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, listOf("（请先选择视频格式）"))

        // 音频编码器：未选择格式时显示占位
        binding.spinnerAudioCodec.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, listOf("（请先选择格式）"))

        // 分辨率
        binding.spinnerResolution.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, RESOLUTIONS)
        // 采样率
        binding.spinnerSampleRate.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, SAMPLE_RATES)
        // 声道
        binding.spinnerChannels.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, CHANNELS)
        // CRF / 质量
        binding.spinnerCrf.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, CRF_LABELS)
        binding.spinnerCrf.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                binding.etCustomCrf.visibility =
                    if (CRF_VALUES[pos] == "custom") View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        // 展开/折叠高级选项
        binding.btnToggleAdvanced.setOnClickListener {
            val expanded = binding.layoutAdvanced.visibility == View.VISIBLE
            binding.layoutAdvanced.visibility = if (expanded) View.GONE else View.VISIBLE
            binding.btnToggleAdvanced.text = if (expanded) "▶ 高级选项" else "▼ 高级选项"
        }
    }

    private fun setupOutputDirectory() {
        binding.btnSelectOutputDir.setOnClickListener {
            directoryPickerLauncher.launch(null)
        }
    }

    private fun setupButtons() {
        binding.btnPickFile.setOnClickListener {
            pickFileLauncher.launch(arrayOf("video/*", "audio/*"))
        }
        binding.btnConvert.setOnClickListener { startConvert() }
        binding.btnCancel.setOnClickListener  { cancelConvert() }
        binding.btnShareOutput.setOnClickListener { shareOutput() }
    }

    private fun getDirectoryPathFromUri(uri: Uri): String? {
        return try {
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 获取转换输出目录：Download/SubtitleEdit/Convert
     * 如果目录不存在则创建
     */
    private fun getConvertOutputDirectory(): File {
        val subtitleEditDir = File(FileUtils.getDownloadDirectory(), "SubtitleEdit")
        val convertDir = File(subtitleEditDir, "Convert")
        if (!convertDir.exists()) {
            convertDir.mkdirs()
        }
        return convertDir
    }

    private fun copyFileToOutputDirectory(sourceFile: File, fileName: String): Uri? {
        return try {
            val finalOutputUri = outputDirectoryUri ?: run {
                // 默认使用 Download/SubtitleEdit/Convert 目录
                val convertDir = getConvertOutputDirectory()
                Uri.fromFile(convertDir)
            }

            if (finalOutputUri.scheme == "file") {
                // 使用传统 File API
                val dir = File(finalOutputUri.path!!)
                val outputFile = File(dir, fileName)
                if (outputFile.exists()) {
                    outputFile.delete()
                }
                FileInputStream(sourceFile).use { ins ->
                    FileOutputStream(outputFile).use { out -> ins.copyTo(out) }
                }
                Uri.fromFile(outputFile)
            } else {
                // 使用 DocumentFile
                var documentFile = DocumentFile.fromTreeUri(this, finalOutputUri)
                if (documentFile == null) {
                    documentFile = DocumentFile.fromSingleUri(this, finalOutputUri)
                }

                if (documentFile == null || !documentFile.canWrite()) {
                    // 使用 DocumentsContract 创建文件
                    val newFileUri = DocumentsContract.createDocument(
                        contentResolver,
                        finalOutputUri,
                        getMimeTypeFromFileName(fileName),
                        fileName
                    )
                    if (newFileUri != null) {
                        contentResolver.openOutputStream(newFileUri, "wt")?.use { outputStream ->
                            FileInputStream(sourceFile).use { ins ->
                                ins.copyTo(outputStream)
                            }
                        }
                        return newFileUri
                    } else {
                        throw Exception("无法创建文件：$fileName")
                    }
                }

                // 查找并删除已存在的同名文件
                val existingFile = documentFile?.findFile(fileName)
                if (existingFile != null && existingFile.exists()) {
                    existingFile.delete()
                }

                // 创建新文件
                val mimeType = getMimeTypeFromFileName(fileName)
                val newFile = documentFile?.createFile(mimeType, fileName)
                if (newFile != null) {
                    contentResolver.openOutputStream(newFile.uri, "wt")?.use { outputStream ->
                        FileInputStream(sourceFile).use { ins ->
                            ins.copyTo(outputStream)
                        }
                    }
                    newFile.uri
                } else {
                    throw Exception("无法创建文件：$fileName")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getMimeTypeFromFileName(fileName: String): String {
        return when {
            fileName.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            fileName.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
            fileName.endsWith(".avi", ignoreCase = true) -> "video/x-msvideo"
            fileName.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
            fileName.endsWith(".webm", ignoreCase = true) -> "video/webm"
            fileName.endsWith(".flv", ignoreCase = true) -> "video/x-flv"
            fileName.endsWith(".ts", ignoreCase = true) -> "video/mp2t"
            fileName.endsWith(".m4v", ignoreCase = true) -> "video/x-m4v"
            fileName.endsWith(".3gp", ignoreCase = true) -> "video/3gpp"
            fileName.endsWith(".wmv", ignoreCase = true) -> "video/x-ms-wmv"
            fileName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            fileName.endsWith(".aac", ignoreCase = true) -> "audio/aac"
            fileName.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
            fileName.endsWith(".wav", ignoreCase = true) -> "audio/wav"
            fileName.endsWith(".flac", ignoreCase = true) -> "audio/flac"
            fileName.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
            fileName.endsWith(".opus", ignoreCase = true) -> "audio/opus"
            fileName.endsWith(".wma", ignoreCase = true) -> "audio/x-ms-wma"
            fileName.endsWith(".ac3", ignoreCase = true) -> "audio/ac3"
            else -> "*/*"
        }
    }

    // ==================== 文件选中 ====================

    private fun onSourceFileSelected(uri: Uri) {
        sourceUri = uri
        sourceFileName = getFileName(uri)
        sourceMimeType = contentResolver.getType(uri) ?: ""
        isSourceVideo  = sourceMimeType.startsWith("video/")

        binding.tvSourceFile.text = sourceFileName
        binding.tvSourceInfo.text = "正在读取媒体信息..."
        binding.tvSourceInfo.visibility = View.VISIBLE

        // 重置格式选择
        selectedFormatButton?.let {
            it.background = makeButtonBackground(isSelected = false)
            it.setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
        }
        selectedFormatButton = null
        selectedFormat = null

        // 异步探针
        lifecycleScope.launch(Dispatchers.IO) {
            val copiedPath = copyUriToCache(uri, sourceFileName) ?: return@launch
            val info = probeMediaInfo(copiedPath)
            withContext(Dispatchers.Main) {
                binding.tvSourceInfo.text = info
                updateUI()
            }
        }

        updateUI()
    }

    private fun getFileName(uri: Uri): String {
        var name = "media_file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && col >= 0) name = cursor.getString(col)
        }
        return name
    }

    private fun copyUriToCache(uri: Uri, name: String): String? {
        return try {
            val dest = File(cacheDir, "convert_src_$name")
            contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(dest).use { out -> ins.copyTo(out) }
            }
            dest.absolutePath
        } catch (e: Exception) { null }
    }

    private fun probeMediaInfo(path: String): String {
        val session = FFprobeKit.getMediaInformation(path)
        val info    = session.mediaInformation ?: return "无法读取媒体信息"
        val sb = StringBuilder()
        sb.append("时长：${formatDuration(info.duration?.toDoubleOrNull() ?: 0.0)}\n")
        sb.append("比特率：${info.bitrate ?: "未知"} kb/s\n")
        info.streams?.forEach { stream ->
            when (stream.type) {
                "video" -> sb.append("视频：${stream.codec} ${stream.width}x${stream.height}" +
                        " @${stream.averageFrameRate} fps\n")
                "audio" -> sb.append("音频：${stream.codec} ${stream.sampleRate} Hz" +
                        " ${stream.channelLayout}\n")
            }
        }
        return sb.toString().trimEnd()
    }

    private fun formatDuration(sec: Double): String {
        val h = (sec / 3600).toInt()
        val m = ((sec % 3600) / 60).toInt()
        val s = (sec % 60).toInt()
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%d:%02d".format(m, s)
    }

    // ==================== 格式选中 ====================

    private fun updateCodecSpinners() {
        val fmt = selectedFormat

        if (fmt == null) {
            // 未选择格式：重置所有编码器选项为占位文字
            binding.spinnerVideoCodec.adapter = ArrayAdapter(this,
                android.R.layout.simple_spinner_dropdown_item, listOf("（请先选择视频格式）"))
            binding.spinnerAudioCodec.adapter = ArrayAdapter(this,
                android.R.layout.simple_spinner_dropdown_item, listOf("（请先选择格式）"))
            binding.layoutVideoCodec.visibility = View.GONE
            binding.layoutResolution.visibility = View.GONE
            binding.layoutCrf.visibility        = View.GONE
            return
        }

        val isAudioOnly = fmt.isAudioOnly

        // 视频编码器 / 分辨率 / CRF：仅视频输出时显示
        binding.layoutVideoCodec.visibility = if (!isAudioOnly) View.VISIBLE else View.GONE
        binding.layoutResolution.visibility = if (!isAudioOnly) View.VISIBLE else View.GONE
        binding.layoutCrf.visibility        = if (!isAudioOnly) View.VISIBLE else View.GONE

        if (!isAudioOnly) {
            binding.spinnerVideoCodec.adapter = ArrayAdapter(this,
                android.R.layout.simple_spinner_dropdown_item, fmt.videoCodecs)
        }

        // 音频编码器：始终更新为当前格式的编码器列表
        binding.spinnerAudioCodec.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, fmt.audioCodecs)
    }

    // ==================== 转换 ====================

    private fun startConvert() {
        val uri = sourceUri ?: run {
            Toast.makeText(this, "请先选择源文件", Toast.LENGTH_SHORT).show(); return
        }
        val fmt = selectedFormat ?: run {
            Toast.makeText(this, "请选择输出格式", Toast.LENGTH_SHORT).show(); return
        }

        convertJob = lifecycleScope.launch {
            setConvertingState(true)
            binding.progressBar.progress = 0
            binding.tvLog.text = ""
            outputFile = null

            try {
                // 1. 复制源文件到缓存
                appendLog("正在复制源文件...")
                val srcPath = withContext(Dispatchers.IO) {
                    copyUriToCache(uri, sourceFileName)
                } ?: throw Exception("复制源文件失败")

                // 2. 构建输出路径（缓存目录）
                val baseName = File(sourceFileName).nameWithoutExtension
                val tempOutFile  = File(cacheDir, "convert_out_${baseName}.${fmt.extension}")
                if (tempOutFile.exists()) tempOutFile.delete()

                // 3. 构建 FFmpeg 命令
                val cmd = buildFFmpegCommand(srcPath, tempOutFile.absolutePath, fmt)
                appendLog("执行命令：\nffmpeg $cmd\n")

                // 4. 执行
                var lastProgress = 0
                val session = withContext(Dispatchers.IO) {
                    FFmpegKit.executeAsync(cmd,
                        { /* complete */ },
                        { log -> runOnUiThread { appendLog(log.message) } },
                        { stats ->
                            val progress = if (stats.time > 0) {
                                // 粗略进度估算
                                (lastProgress + 1).coerceAtMost(95)
                            } else 0
                            lastProgress = progress
                            runOnUiThread { binding.progressBar.progress = progress }
                        }
                    )
                }
                currentSession = session

                // 等待完成（session 已在后台线程执行，这里轮询）
                withContext(Dispatchers.IO) {
                    while (!session.state.name.let {
                            it == "COMPLETED" || it == "FAILED" || it == "CANCELLED"
                        }) {
                        Thread.sleep(100)
                    }
                }

                binding.progressBar.progress = 100

                if (ReturnCode.isSuccess(session.returnCode)) {
                    // 5. 将文件复制到输出目录
                    val outputFileName = "${baseName}.${fmt.extension}"
                    val finalOutputUri = copyFileToOutputDirectory(tempOutFile, outputFileName)
                    
                    if (finalOutputUri != null) {
                        outputFile = tempOutFile
                        val outputPath = outputDirectoryUri?.let { 
                            getDirectoryPathFromUri(it) ?: it.toString() 
                        } ?: getConvertOutputDirectory().absolutePath
                        appendLog("\n✅ 转换成功！\n输出目录：$outputPath\n输出文件：$outputFileName\n文件大小：${formatSize(tempOutFile.length())}")
                        binding.btnShareOutput.visibility = View.VISIBLE
                    } else {
                        appendLog("\n⚠️ 转换成功但复制文件失败")
                    }
                } else if (ReturnCode.isCancel(session.returnCode)) {
                    appendLog("\n⚠️ 已取消转换")
                } else {
                    // 打印 FFmpeg 完整错误日志，方便定位具体原因
                    val failLog = session.allLogsAsString ?: "无日志"
                    // 从完整日志里提取关键错误行（含 "Error" 或 "Invalid" 或 "Unknown"）
                    val keyLines = failLog.lines()
                        .filter { it.contains("Error", ignoreCase = true)
                               || it.contains("Invalid", ignoreCase = true)
                               || it.contains("Unknown", ignoreCase = true)
                               || it.contains("No such", ignoreCase = true) }
                        .takeLast(8)
                        .joinToString("\n")
                    appendLog("\n❌ 转换失败\n关键错误：\n${keyLines.ifBlank { failLog.takeLast(300) }}")
                }

            } catch (e: Exception) {
                appendLog("\n❌ 错误：${e.message}")
            } finally {
                setConvertingState(false)
            }
        }
    }

    /**
     * 根据用户选项构建 FFmpeg 命令字符串
     */
    private fun buildFFmpegCommand(srcPath: String, outPath: String, fmt: FormatInfo): String {
        val sb = StringBuilder()
        sb.append("-y -i \"$srcPath\"")

        val isAudioOnly = fmt.isAudioOnly

        // ---- 视频流 ----
        if (!isAudioOnly) {
            val vcodec = binding.spinnerVideoCodec.selectedItem?.toString() ?: "libx264"
            sb.append(" -c:v $vcodec")

            // CRF
            val crfIdx   = binding.spinnerCrf.selectedItemPosition
            val crfValue = CRF_VALUES.getOrElse(crfIdx) { "23" }
            if (crfValue != "-1") {
                val crf = if (crfValue == "custom") {
                    binding.etCustomCrf.text.toString().toIntOrNull()?.toString() ?: "23"
                } else crfValue
                // vp8/vp9 用 -crf，其他用 -crf
                when {
                    vcodec.startsWith("libvpx") -> sb.append(" -crf $crf -b:v 0")
                    vcodec == "prores"           -> { /* prores 不用 crf */ }
                    else                         -> sb.append(" -crf $crf")
                }
            }

            // 分辨率
            val resIdx = binding.spinnerResolution.selectedItemPosition
            if (resIdx > 0) {
                val res = RESOLUTIONS[resIdx].substringBefore(" ") // e.g. "1920x1080"
                sb.append(" -vf scale=$res")
            }

            // 视频码率（如果填写了）
            val vBitrate = binding.etVideoBitrate.text.toString().trim()
            if (vBitrate.isNotEmpty()) sb.append(" -b:v ${vBitrate}k")

        } else {
            // 纯音频输出：去掉视频流
            sb.append(" -vn")
        }

        // ---- 音频流 ----
        val acodec = binding.spinnerAudioCodec.selectedItem?.toString() ?: "aac"
        if (acodec.startsWith("pcm_")) {
            // WAV 无损，不需要码率
            sb.append(" -c:a $acodec")
        } else {
            sb.append(" -c:a $acodec")

            // 音频码率
            val aBitrate = binding.etAudioBitrate.text.toString().trim()
            if (aBitrate.isNotEmpty()) sb.append(" -b:a ${aBitrate}k")

            // 采样率
            val srIdx = binding.spinnerSampleRate.selectedItemPosition
            if (srIdx > 0) {
                val sr = SAMPLE_RATES[srIdx].substringBefore(" ")
                sb.append(" -ar $sr")
            }

            // 声道数
            val chIdx = binding.spinnerChannels.selectedItemPosition
            if (chIdx > 0) {
                val ch = if (chIdx == 1) "2" else "1"
                sb.append(" -ac $ch")
            }
        }

        // 格式强制指定（FFmpeg 内部名称与扩展名不一定相同）
        val formatName = when (fmt.extension) {
            "mp4"  -> "mp4"
            "mkv"  -> "matroska"    // ← mkv 容器名是 matroska
            "avi"  -> "avi"
            "mov"  -> "mov"
            "webm" -> "webm"
            "flv"  -> "flv"
            "ts"   -> "mpegts"
            "m4v"  -> "mp4"         // ← m4v 本质是 mp4 容器
            "3gp"  -> "3gp"
            "wmv"  -> "asf"         // ← wmv/asf 容器名是 asf
            "mp3"  -> "mp3"
            "aac"  -> "adts"
            "m4a"  -> "ipod"
            "wav"  -> "wav"
            "flac" -> "flac"
            "ogg"  -> "ogg"
            "opus" -> "opus"
            "wma"  -> "asf"
            "ac3"  -> "ac3"
            else   -> fmt.extension
        }
        sb.append(" -f $formatName")

        sb.append(" \"$outPath\"")
        return sb.toString()
    }

    private fun cancelConvert() {
        currentSession?.cancel()
        convertJob?.cancel()
        appendLog("\n⚠️ 正在取消...")
    }

    // ==================== 输出分享 ====================

    private fun shareOutput() {
        val file = outputFile ?: return
        val uri  = androidx.core.content.FileProvider.getUriForFile(
            this, "${packageName}.provider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享转换结果"))
    }

    // ==================== 辅助 ====================

    private fun setConvertingState(converting: Boolean) {
        binding.btnConvert.isEnabled    = !converting
        binding.btnPickFile.isEnabled   = !converting
        binding.btnCancel.visibility    = if (converting) View.VISIBLE else View.GONE
        binding.btnShareOutput.visibility = View.GONE
        binding.progressBar.visibility  = if (converting) View.VISIBLE else View.VISIBLE
        if (!converting) binding.progressBar.visibility = View.VISIBLE  // 保留显示最终值
    }

    private fun updateUI() {
        val hasSource = sourceUri != null
        val hasFmt    = selectedFormat != null
        binding.btnConvert.isEnabled  = hasSource && hasFmt
        binding.groupFormatSelect.visibility = if (hasSource) View.VISIBLE else View.VISIBLE
        // 无源文件时提示
        binding.tvNoSource.visibility = if (!hasSource) View.VISIBLE else View.GONE
    }

    private fun appendLog(msg: String) {
        if (msg.isBlank()) return
        val current = binding.tvLog.text.toString()
        binding.tvLog.text = current + msg
        // 滚动到底部
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024L        -> "$bytes B"
        bytes < 1024L * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else                 -> "${"%.2f".format(bytes / 1024.0 / 1024.0)} MB"
    }
}
