package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.subtitleedit.databinding.ActivityAutoTimestampBinding
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.VadTimestampGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自动打轴页面 - 使用 VAD 自动检测语音段并生成时间轴
 */
class AutoTimestampActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAutoTimestampBinding
    private var selectedAudioUri: Uri? = null
    private var selectedFileName: String = ""
    private var outputDirUri: Uri? = null

    private val formatOptions = arrayOf("SRT", "LRC")

    // 音频文件选择器
    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedAudio(it) }
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

        setupToolbar()
        setupSpinners()
        setupButtons()
        setupDefaultOutputDir()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!binding.btnGenerate.isEnabled) {
                    AlertDialog.Builder(this@AutoTimestampActivity)
                        .setTitle("正在处理中")
                        .setMessage("自动打轴正在进行，确定要返回吗？返回后处理将被取消。")
                        .setPositiveButton("返回并取消") { _, _ ->
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

        binding.btnSelectOutputDir.setOnClickListener {
            outputDirLauncher.launch(outputDirUri)
        }

        binding.btnGenerate.setOnClickListener {
            generateTimestamps()
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
            binding.tvOutputDir.text = "Download/SubtitleEdit/Convert"
        } catch (e: Exception) {
            Log.e("AutoTimestamp", "设置默认输出目录失败", e)
        }
    }

    private fun handleSelectedAudio(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            selectedAudioUri = uri
            selectedFileName = getFileNameFromUri(uri)
            binding.tvAudioFile.text = selectedFileName
            binding.btnGenerate.isEnabled = true

        } catch (e: Exception) {
            Toast.makeText(this, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

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

    private fun generateTimestamps() {
        val audioUri = selectedAudioUri ?: return
        val outputDir = outputDirUri ?: run {
            Toast.makeText(this, "请选择输出目录", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnGenerate.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.tvStatus.text = "正在处理..."

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // 1. 复制文件到缓存
                    val cachedFile = copyUriToCache(audioUri, selectedFileName)
                    if (cachedFile == null) {
                        throw Exception("复制文件失败")
                    }

                    // 2. 转换为 16kHz PCM WAV
                    runOnUiThread { binding.tvStatus.text = "正在提取音频..." }
                    val pcmFile = convertToPcm(cachedFile)
                    if (pcmFile == null) {
                        throw Exception("音频转换失败")
                    }

                    // 3. 使用 VAD 生成时间轴
                    runOnUiThread { binding.tvStatus.text = "正在检测语音段..." }
                    val generator = VadTimestampGenerator(this@AutoTimestampActivity)
                    val segments = generator.generateSegments(pcmFile)

                    if (segments.isEmpty()) {
                        throw Exception("未检测到任何语音段")
                    }

                    // 4. 生成字幕内容
                    val format = formatOptions[binding.spinnerOutputFormat.selectedItemPosition]
                    val subtitleContent = generateSubtitle(segments, format)

                    // 5. 保存到输出目录
                    runOnUiThread { binding.tvStatus.text = "正在保存..." }
                    saveToOutputDir(outputDir, subtitleContent, format)

                    subtitleContent
                }

                binding.tvStatus.text = "生成完成"
                binding.progressBar.visibility = android.view.View.GONE
                binding.btnGenerate.isEnabled = true

                // 显示预览
                binding.tvPreview.text = result

                Toast.makeText(this@AutoTimestampActivity, "字幕已保存到输出目录", Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                binding.tvStatus.text = "处理失败"
                binding.progressBar.visibility = android.view.View.GONE
                binding.btnGenerate.isEnabled = true
                Toast.makeText(this@AutoTimestampActivity, "处理失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 生成字幕内容
     */
    private fun generateSubtitle(segments: List<VadTimestampGenerator.VadSegment>, format: String): String {
        val entries = segments.mapIndexed { index, segment ->
            SubtitleEntry(
                index = index + 1,
                startTime = segment.startTime,
                endTime = segment.endTime,
                text = "请输入文本"
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
     * 保存到输出目录
     */
    private fun saveToOutputDir(dirUri: Uri, content: String, format: String) {
        try {
            val baseFileName = selectedFileName.substringBeforeLast(".")
            val extension = format.lowercase()

            // 检查是否是 file:// URI（本地目录）
            if (dirUri.scheme == "file") {
                // 使用传统 File API
                val dir = File(dirUri.path!!)
                val fileName = getUniqueFileName(dir, baseFileName, extension)
                val outputFile = File(dir, fileName)

                outputFile.writeText(content, java.nio.charset.StandardCharsets.UTF_8)
                return
            }

            // 使用 DocumentFile API（content:// URI）
            val dir = DocumentFile.fromTreeUri(this, dirUri) ?: throw Exception("无法访问输出目录")

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
        } catch (e: Exception) {
            throw Exception("保存文件失败: ${e.message}")
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
            Log.e("AutoTimestamp", "复制文件失败", e)
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
                Log.e("AutoTimestamp", "FFmpeg 转换失败: ${session.output}")
                null
            }
        } catch (e: Exception) {
            Log.e("AutoTimestamp", "音频转换失败", e)
            null
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
}
