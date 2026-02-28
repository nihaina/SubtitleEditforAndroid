package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.subtitleedit.databinding.ActivityBatchConvertBinding
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SubtitleParser
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 批量转换界面
 */
class BatchConvertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchConvertBinding
    // 存储 URI 和文件名的数据类
    data class ConvertFile(val uri: Uri, val fileName: String, val fileSize: Long)
    private val convertFiles = mutableListOf<ConvertFile>()
    private var outputDirectoryUri: Uri? = null
    
    private var targetFormat: SubtitleParser.SubtitleFormat = SubtitleParser.SubtitleFormat.LRC

    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            val fileName = getFileNameFromUri(uri) ?: "未知文件"
            val fileSize = getFileSizeFromUri(uri)
            val file = ConvertFile(uri, fileName, fileSize)
            if (!convertFiles.any { it.uri == uri }) {
                convertFiles.add(file)
                adapter.notifyItemInserted(convertFiles.size - 1)
            }
        }
        updateFileList()
    }
    
    // 目录选择器
    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 保存 URI 用于后续访问
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // 保存用户选择的输出目录 URI
            outputDirectoryUri = uri
            // 获取目录路径显示
            val path = getDirectoryPathFromUri(uri) ?: uri.toString()
            binding.tvOutputDir.text = "输出目录：$path"
        }
    }

    private lateinit var adapter: ConvertFileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchConvertBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupFormatSpinners()
        setupRecyclerView()
        setupButtons()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }
    
    private fun setupFormatSpinners() {
        // 目标格式
        val formats = arrayOf("SRT", "LRC")
        val targetAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, formats)
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTargetFormat.adapter = targetAdapter
        
        // 监听目标格式选择变化
        binding.spinnerTargetFormat.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                targetFormat = when (position) {
                    0 -> SubtitleParser.SubtitleFormat.SRT
                    1 -> SubtitleParser.SubtitleFormat.LRC
                    else -> SubtitleParser.SubtitleFormat.LRC
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        // 设置默认值为 LRC
        binding.spinnerTargetFormat.setSelection(1)
    }
    
    private fun setupRecyclerView() {
        adapter = ConvertFileAdapter(
            onItemClick = { file -> },
            onRemoveClick = { file ->
                val index = convertFiles.indexOf(file)
                if (index >= 0) {
                    convertFiles.removeAt(index)
                    adapter.notifyItemRemoved(index)
                }
            }
        )
        
        binding.rvFileList.apply {
            layoutManager = LinearLayoutManager(this@BatchConvertActivity)
            adapter = this@BatchConvertActivity.adapter
        }
    }
    
    private fun setupButtons() {
        binding.btnAddFiles.setOnClickListener {
            openFilePicker()
        }
        
        binding.btnSelectOutputDir.setOnClickListener {
            openDirectoryPicker()
        }
        
        binding.btnStartConvert.setOnClickListener {
            startConversion()
        }
    }
    
    private fun openFilePicker() {
        filePickerLauncher.launch("*/*")
    }
    
    private fun openDirectoryPicker() {
        directoryPickerLauncher.launch(null)
    }
    
    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun getFileSizeFromUri(uri: Uri): Long {
        var size = 0L
        try {
            val projection = arrayOf(android.provider.OpenableColumns.SIZE)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE)
                    if (!cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return size
    }
    
    private fun getDirectoryPathFromUri(uri: Uri): String? {
        return try {
            val projection = arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun createFileInDirectory(directoryUri: Uri, fileName: String, content: String) {
        try {
            // 检查是否是 file:// URI（本地目录）
            if (directoryUri.scheme == "file") {
                // 使用传统 File API
                val dir = File(directoryUri.path!!)
                val outputFile = File(dir, fileName)
                if (outputFile.exists()) {
                    outputFile.delete()
                }
                outputFile.writeText(content, StandardCharsets.UTF_8)
                return
            }
            
            // 尝试使用 fromTreeUri（适用于 SAF 树 URI）
            var documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, directoryUri)
            
            // 如果 fromTreeUri 返回 null，尝试使用 fromSingleUri（适用于单文档 URI）
            if (documentFile == null) {
                documentFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(this, directoryUri)
            }
            
            // 如果还是 null，尝试直接使用 DocumentsContract 创建文件
            if (documentFile == null || !documentFile.canWrite()) {
                // 使用 DocumentsContract 直接在目录中创建文件
                createFileUsingDocumentsContract(directoryUri, fileName, content)
                return
            }
            
            // 先尝试查找是否已存在同名文件
            val existingFile = documentFile?.findFile(fileName)
            
            // 如果文件已存在，先删除
            if (existingFile != null && existingFile.exists()) {
                existingFile.delete()
            }
            
            // 创建新文件
            val mimeType = getMimeTypeFromFileName(fileName)
            val newFile = documentFile?.createFile(mimeType, fileName)
            if (newFile != null) {
                contentResolver.openOutputStream(newFile.uri, "wt")?.use { outputStream ->
                    outputStream.write(content.toByteArray(StandardCharsets.UTF_8))
                    outputStream.flush()
                }
            } else {
                throw Exception("无法创建文件：$fileName")
            }
        } catch (e: Exception) {
            throw e
        }
    }
    
    private fun createFileUsingDocumentsContract(directoryUri: Uri, fileName: String, content: String) {
        try {
            // 使用 DocumentsContract 直接在目录中创建文件
            val newFileUri = android.provider.DocumentsContract.createDocument(
                contentResolver,
                directoryUri,
                getMimeTypeFromFileName(fileName),
                fileName
            )
            
            if (newFileUri != null) {
                contentResolver.openOutputStream(newFileUri, "wt")?.use { outputStream ->
                    outputStream.write(content.toByteArray(StandardCharsets.UTF_8))
                    outputStream.flush()
                }
            } else {
                throw Exception("无法创建文件：$fileName")
            }
        } catch (e: Exception) {
            throw e
        }
    }
    
    private fun getMimeTypeFromFileName(fileName: String): String {
        return when {
            fileName.endsWith(".srt", ignoreCase = true) -> "application/x-subrip"
            fileName.endsWith(".lrc", ignoreCase = true) -> "text/plain"
            fileName.endsWith(".txt", ignoreCase = true) -> "text/plain"
            else -> "text/plain"
        }
    }
    
    private fun updateFileList() {
        if (convertFiles.isEmpty()) {
            binding.rvFileList.visibility = View.GONE
        } else {
            binding.rvFileList.visibility = View.VISIBLE
        }
    }
    
    private fun startConversion() {
        if (convertFiles.isEmpty()) {
            Toast.makeText(this, "请先添加要转换的文件", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 不需要检查源格式和目标格式是否相同，因为源格式是自动检测的
        
        // 确保有输出目录 - 默认使用 Download/SubtitleEdit/Convert 目录
        val finalOutputUri = outputDirectoryUri ?: run {
            // 默认使用 Download/SubtitleEdit/Convert 目录
            val convertDir = getConvertOutputDirectory()
            android.net.Uri.fromFile(convertDir)
        }
        
        // 处理文件
        var successCount = 0
        var failCount = 0
        var skippedCount = 0
        val successFiles = mutableListOf<String>()
        val failFiles = mutableListOf<String>()
        val skippedFiles = mutableListOf<String>()
        
        convertFiles.forEach { convertFile ->
            try {
                // 使用 URI 读取文件内容
                val content = FileUtils.readUri(this, convertFile.uri, StandardCharsets.UTF_8)
                
                // 自动检测源格式
                val detectedFormat = SubtitleParser.detectFormat(content)
                
                // 如果检测到的格式与目标格式相同，跳过转换（直接计入成功）
                if (detectedFormat == targetFormat) {
                    skippedCount++
                    skippedFiles.add(convertFile.fileName)
                    return@forEach
                }
                
                // 转换格式
                val convertedContent = SubtitleParser.convertFormat(content, detectedFormat, targetFormat)
                
                // 确定输出文件名
                val targetExtension = when (targetFormat) {
                    SubtitleParser.SubtitleFormat.SRT -> "srt"
                    SubtitleParser.SubtitleFormat.LRC -> "lrc"
                    else -> "txt"
                }
                
                // 从 URI 获取文件名，去掉原扩展名
                val originalName = convertFile.fileName
                val nameWithoutExt = originalName.substringBeforeLast(".")
                val outputFileName = "${nameWithoutExt}.${targetExtension}"
                
                // 在用户选择的目录中创建文件
                createFileInDirectory(finalOutputUri, outputFileName, convertedContent)
                successCount++
                successFiles.add(convertFile.fileName)
            } catch (e: Exception) {
                failCount++
                failFiles.add(convertFile.fileName)
                e.printStackTrace()
            }
        }
        
        // 显示详细结果
        val message = buildString {
            appendLine("转换完成！")
            appendLine("成功：$successCount")
            appendLine("跳过（格式相同）：$skippedCount")
            appendLine("失败：$failCount")
            if (successFiles.isNotEmpty()) {
                appendLine("\n成功文件:")
                successFiles.forEach { appendLine("  - $it") }
            }
            if (skippedFiles.isNotEmpty()) {
                appendLine("\n跳过文件（格式已为目标格式）:")
                skippedFiles.forEach { appendLine("  - $it") }
            }
            if (failFiles.isNotEmpty()) {
                appendLine("\n失败文件:")
                failFiles.forEach { appendLine("  - $it") }
            }
            val outputPath = outputDirectoryUri?.let { 
                getDirectoryPathFromUri(it) ?: it.toString() 
            } ?: getConvertOutputDirectory().absolutePath
            appendLine("\n输出目录：$outputPath")
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("批量转换结果")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }
    
    inner class ConvertFileAdapter(
        private val onItemClick: (ConvertFile) -> Unit,
        private val onRemoveClick: (ConvertFile) -> Unit
    ) : RecyclerView.Adapter<ConvertFileAdapter.ViewHolder>() {
        
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvFileName: android.widget.TextView = itemView.findViewById(R.id.tvFileName)
            private val tvFilePath: android.widget.TextView = itemView.findViewById(R.id.tvFilePath)
            private val btnRemove: ImageButton = itemView.findViewById(R.id.btnRemove)
            
            fun bind(file: ConvertFile) {
                tvFileName.text = file.fileName
                tvFilePath.text = formatFileSize(file.fileSize)
                
                itemView.setOnClickListener { onItemClick(file) }
                btnRemove.setOnClickListener { onRemoveClick(file) }
            }
        }
        
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_convert_file, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(convertFiles[position])
        }
        
        override fun getItemCount(): Int = convertFiles.size
    }
    
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
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
}
