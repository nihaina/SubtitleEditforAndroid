package com.subtitleedit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.google.android.material.card.MaterialCardView
import com.subtitleedit.databinding.ActivityModelManagementBinding
import com.subtitleedit.repository.ModelRepository
import com.subtitleedit.util.InternalModelExport
import com.subtitleedit.util.ModelDownloadProgressDialog
import com.subtitleedit.util.ModelDownloader
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.Qwen3ForcedAlignerModelFiles
import com.subtitleedit.util.SenseVoiceNpuModelImporter
import com.subtitleedit.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class ModelManagementActivity : AppCompatActivity() {
    private lateinit var binding: ActivityModelManagementBinding
    private lateinit var settingsManager: SettingsManager
    private val modelRepository: ModelRepository
        get() = (application as SubtitleEditApplication).dependencies.modelRepository
    private var requestedStorageAccess = false
    private var modelScanVersion = 0
    private var pendingExportItem: ModelItem? = null
    private var exportJob: Job? = null

    private lateinit var asrImportController: AsrModelImportController
    private lateinit var demucsImportController: DemucsModelImportController

    private data class ModelItem(
        val category: String,
        val displayName: String,
        val file: File,
        val size: Long,
        val exportKind: InternalModelExport.Kind? = null,
        val canDelete: Boolean = true
    )

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { handleStorageAccessResult() }

    private val writeStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { handleStorageAccessResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsManager = SettingsManager.getInstance(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "模型导入"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        asrImportController = AsrModelImportController(this, binding.asrModelImport) {
            loadModels()
        }
        demucsImportController = DemucsModelImportController(this, binding.demucsModelImport)
        setupPageNavigation()
        binding.tvModelsDirectory.text =
            "下载模型目录：${modelRepository.modelsDirectory().absolutePath}\n" +
                "SenseVoice NPU BIN 保存在应用内部目录"

        loadModels()
    }

    override fun onResume() {
        super.onResume()
        if (::asrImportController.isInitialized) {
            asrImportController.refresh()
            demucsImportController.refresh()
        }
        if (binding.pagePager.currentItem == 1) loadModels()
    }

    private fun setupPageNavigation() {
        val pages = listOf(binding.scrollImport, binding.managePage)
        val titles = listOf("模型导入", "模型管理")
        // Keep the inflated pages and their controller bindings while the pager owns attachment.
        pages.forEach { binding.pagePager.removeView(it) }
        binding.pagePager.adapter = object : PagerAdapter() {
            override fun getCount(): Int = pages.size

            override fun isViewFromObject(view: View, item: Any): Boolean = view === item

            override fun instantiateItem(container: ViewGroup, position: Int): Any = pages[position].also {
                container.addView(it)
            }

            override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
                container.removeView(item as View)
            }

            override fun getPageTitle(position: Int): CharSequence = titles[position]
        }
        binding.pageTabs.setupWithViewPager(binding.pagePager)
        binding.pagePager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                supportActionBar?.title = titles[position]
                if (position == 0) {
                    asrImportController.refresh()
                } else {
                    loadModels()
                    if (!hasStorageAccess() && !requestedStorageAccess) requestStorageAccess()
                }
            }
        })
    }

    private fun requestStorageAccess() {
        requestedStorageAccess = true
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
                requestedStorageAccess = false
                loadModels()
            }
        } else {
            writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun handleStorageAccessResult() {
        requestedStorageAccess = false
        loadModels()
        val pending = pendingExportItem
        pendingExportItem = null
        if (pending != null) {
            if (hasStorageAccess()) confirmExportModel(pending)
            else OverwritingToast.makeText(this, "导出模型需要下载目录存储权限", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasStorageAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun loadModels() {
        val scanVersion = ++modelScanVersion
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { scanModels() } }
            if (scanVersion != modelScanVersion) return@launch
            binding.progressBar.visibility = View.GONE
            result.onSuccess { items ->
                asrImportController.refreshForcedAlignerStatus()
                renderModels(items)
            }.onFailure {
                binding.modelContainer.removeAllViews()
                binding.tvEmpty.text = "模型目录读取失败：${it.message}"
                binding.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun scanModels(): List<ModelItem> {
        val items = mutableListOf<ModelItem>()
        val root = modelRepository.modelsDirectory()
        val canScanDownloads = hasStorageAccess()
        if (canScanDownloads && root.isDirectory) {
            root.listFiles().orEmpty()
                .filterNot { it.name.startsWith(".") || it.name.contains(".part.") || it.name.endsWith(".backup") }
                .forEach { file ->
                    when {
                        file.isDirectory && file.name.startsWith("sherpa-onnx-sense-voice-") -> {
                            items += ModelItem("SenseVoice 模型", "SenseVoice", file, calculateSize(file))
                        }
                        file.isDirectory && file.name.startsWith("sherpa-onnx-qnn-") &&
                            file.name.contains("sense-voice") -> {
                            val option = modelRepository.senseVoiceNpuModels
                                .firstOrNull { it.directoryName == file.name }
                            items += ModelItem(
                                "SenseVoice 模型",
                                "SenseVoice NPU ${option?.displayName ?: file.name}",
                                file,
                                calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name.startsWith("sherpa-onnx-whisper-") -> {
                            val variant = file.name.removePrefix("sherpa-onnx-whisper-")
                            items += ModelItem(
                                "Whisper 模型",
                                "Whisper ${formatVariantName(variant)}",
                                file,
                                calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name == modelRepository.parakeetTdtModel.directoryName -> {
                            items += ModelItem(
                                "Parakeet 模型",
                                modelRepository.parakeetTdtModel.displayName,
                                file,
                                calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name == modelRepository.parakeetCtcJaModel.directoryName -> {
                            items += ModelItem(
                                "Parakeet 模型",
                                modelRepository.parakeetCtcJaModel.displayName,
                                file,
                                calculateSize(file)
                            )
                        }
                        file.isDirectory && modelRepository.qwen3AsrModels.any { it.directoryName == file.name } -> {
                            val option = modelRepository.qwen3AsrModels.first { it.directoryName == file.name }
                            items += ModelItem(
                                "Qwen3-ASR 模型",
                                "Qwen3-ASR ${option.displayName}",
                                file,
                                calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name == Qwen3ForcedAlignerModelFiles.DIRECTORY_NAME -> {
                            val complete = Qwen3ForcedAlignerModelFiles.findCompleteGraph(file) != null
                            items += ModelItem(
                                "Qwen3 强制对齐模型",
                                if (complete) "Qwen3 ForcedAligner"
                                else "Qwen3 ForcedAligner（文件不完整）",
                                file, calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name in listOf(
                            InternalModelExport.Kind.SENSEVOICE_NPU_5.directoryName,
                            InternalModelExport.Kind.SENSEVOICE_NPU_10.directoryName
                        ) -> {
                            val seconds = if (file.name == InternalModelExport.Kind.SENSEVOICE_NPU_5.directoryName) 5 else 10
                            items += ModelItem(
                                "SenseVoice 模型", "SenseVoice NPU $seconds 秒 BIN（已导出）",
                                file, calculateSize(file)
                            )
                        }
                        file.isDirectory && file.name == modelRepository.separationDirectoryName -> {
                            file.listFiles().orEmpty().filterNot { it.name.startsWith(".") }.forEach { model ->
                                items += ModelItem("人声分离模型", model.name, model, calculateSize(model))
                            }
                        }
                        else -> {
                            items += ModelItem("其他模型文件", file.name, file, calculateSize(file))
                        }
                    }
                }
        }
        val selectedPath = settingsManager.getQwen3ForcedAlignerPath()
        val selectedUri = runCatching { Uri.parse(selectedPath) }.getOrNull()
        val selectedGraph = if (selectedUri?.scheme.isNullOrEmpty() || selectedUri?.scheme == "file") {
            selectedUri?.path?.let(::File)
        } else null
        val downloadedDirectory = File(root, Qwen3ForcedAlignerModelFiles.DIRECTORY_NAME)
        if (Qwen3ForcedAlignerModelFiles.isConfigured(selectedGraph, filesDir) &&
            runCatching { selectedGraph!!.parentFile?.canonicalFile != downloadedDirectory.canonicalFile }
                .getOrDefault(false)
        ) {
            val graph = requireNotNull(selectedGraph)
            items += ModelItem(
                "Qwen3 强制对齐模型", "Qwen3 ForcedAligner（已选择）",
                graph,
                graph.length() + Qwen3ForcedAlignerModelFiles.dataFile(graph).length(),
                canDelete = false
            )
        }
        val npuImporter = SenseVoiceNpuModelImporter(this, contentResolver)
        listOf(5, 10).mapNotNull(npuImporter::findInstalledModel).forEach { model ->
            val directory = requireNotNull(model.contextBinary.parentFile)
            items += ModelItem(
                "SenseVoice 模型",
                "SenseVoice NPU ${model.durationSeconds} 秒 BIN",
                directory,
                calculateSize(directory),
                if (model.durationSeconds == 5) InternalModelExport.Kind.SENSEVOICE_NPU_5
                else InternalModelExport.Kind.SENSEVOICE_NPU_10
            )
        }
        val categoryOrder = mapOf(
            "SenseVoice 模型" to 0,
            "Whisper 模型" to 1,
            "Parakeet 模型" to 2,
            "Qwen3-ASR 模型" to 3,
            "Qwen3 强制对齐模型" to 4,
            "人声分离模型" to 5,
            "其他模型文件" to 6
        )
        return items.sortedWith(
            compareBy<ModelItem> { categoryOrder[it.category] ?: Int.MAX_VALUE }
                .thenBy { it.displayName.lowercase() }
        )
    }

    private fun renderModels(items: List<ModelItem>) {
        binding.modelContainer.removeAllViews()
        if (items.isEmpty()) {
            binding.tvEmpty.text = if (hasStorageAccess()) {
                "未发现模型"
            } else {
                "需要存储权限才能扫描下载模型"
            }
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvEmpty.visibility = View.GONE
        items.groupBy { it.category }.forEach { (category, models) ->
            binding.modelContainer.addView(createCategoryTitle(category))
            models.forEach { binding.modelContainer.addView(createModelCard(it)) }
        }
    }

    private fun createCategoryTitle(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.on_surface))
        setPadding(0, dp(16), 0, dp(8))
    }

    private fun createModelCard(item: ModelItem): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(8).toFloat()
            cardElevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = item.displayName
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.on_surface))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (item.exportKind != null) {
            titleRow.addView(TextView(this).apply {
                text = "导出"
                contentDescription = "导出 ${item.displayName}"
                textSize = 14f
                gravity = Gravity.CENTER
                minimumHeight = dp(40)
                setPadding(dp(8), 0, dp(8), 0)
                setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.primary))
                isClickable = true
                isFocusable = true
                background = selectableItemBackgroundBorderless()
                setOnClickListener { confirmExportModel(item) }
            })
        }
        details.addView(titleRow)
        details.addView(TextView(this).apply {
            text = item.file.absolutePath
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.on_surface_variant))
        })
        details.addView(TextView(this).apply {
            text = formatSize(item.size)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.primary))
        })
        val deleteAction = TextView(this).apply {
            text = "删除文件"
            textSize = 14f
            gravity = Gravity.CENTER
            minWidth = dp(72)
            minimumHeight = dp(40)
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.error))
            isClickable = true
            isFocusable = true
            background = selectableItemBackgroundBorderless()
            setOnClickListener {
                if (exportJob?.isActive != true) confirmDeleteModel(item, this)
            }
        }
        row.addView(details)
        if (item.canDelete) row.addView(deleteAction)
        card.addView(row)
        ToolCardShadow.remove(card)
        return card
    }

    private fun confirmExportModel(item: ModelItem) {
        val kind = item.exportKind ?: return
        if (exportJob?.isActive == true) return
        if (!hasStorageAccess()) {
            pendingExportItem = item
            if (!requestedStorageAccess) requestStorageAccess()
            return
        }
        val destination = InternalModelExport.directory(modelRepository.modelsDirectory(), kind)
        if (destination.exists()) {
            AlertDialog.Builder(this)
                .setTitle("覆盖已导出的模型？")
                .setMessage("下载模型目录中已存在 ${item.displayName}。覆盖前会完整复制并校验新模型。")
                .setPositiveButton("覆盖导出") { _, _ -> startExportModel(item, kind) }
                .setNegativeButton("取消", null)
                .show()
        } else {
            startExportModel(item, kind)
        }
    }

    private fun startExportModel(item: ModelItem, kind: InternalModelExport.Kind) {
        if (exportJob?.isActive == true) return
        val dialog = ModelDownloadProgressDialog(this, "导出 ${item.displayName}") {
            exportJob?.cancel()
        }
        dialog.show()
        exportJob = lifecycleScope.launch {
            try {
                val destination = withContext(Dispatchers.IO) {
                    InternalModelExport.export(item.file, modelRepository.modelsDirectory(), kind) { copied, total ->
                        withContext(Dispatchers.Main) {
                            dialog.update(ModelDownloader.Progress("正在导出模型", copied, total))
                        }
                    }
                }
                OverwritingToast.makeText(
                    this@ModelManagementActivity,
                    "已导出至 ${destination.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
                loadModels()
            } catch (error: CancellationException) {
                OverwritingToast.makeText(this@ModelManagementActivity, "已取消模型导出", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                OverwritingToast.makeText(
                    this@ModelManagementActivity,
                    "导出失败：${error.message}", Toast.LENGTH_LONG
                ).show()
            } finally {
                dialog.dismiss()
                exportJob = null
            }
        }
    }

    private fun confirmDeleteModel(item: ModelItem, action: TextView) {
        AlertDialog.Builder(this)
            .setTitle("删除模型文件")
            .setMessage(
                "确定永久删除“${item.displayName}”吗？\n\n" +
                    "对应模型文件及相关模型选择将被清除，此操作无法撤销。"
            )
            .setPositiveButton("删除") { _, _ -> deleteModel(item, action) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteModel(item: ModelItem, action: TextView) {
        action.isEnabled = false
        action.alpha = 0.5f
        action.text = "删除中"
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching {
                    val removed = if (item.file.isDirectory) item.file.deleteRecursively() else item.file.delete()
                    if (removed || !item.file.exists()) {
                        clearSettingsReferencing(item.file)
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
            }
            if (deleted) {
                OverwritingToast.makeText(
                    this@ModelManagementActivity,
                    "已删除 ${item.displayName}",
                    Toast.LENGTH_SHORT
                ).show()
                loadModels()
            } else {
                action.isEnabled = true
                action.alpha = 1f
                action.text = "删除文件"
                OverwritingToast.makeText(
                    this@ModelManagementActivity,
                    "删除失败，请检查存储权限",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun clearSettingsReferencing(target: File) {
        val whisperPaths = listOf(
            settingsManager.getWhisperEncoderPath(),
            settingsManager.getWhisperDecoderPath(),
            settingsManager.getWhisperTokensPath()
        )
        if (whisperPaths.any { pointsInsideTarget(it, target) }) {
            settingsManager.clearWhisperModelPaths()
        }
        listOf(
            SettingsManager.SENSEVOICE_PROVIDER_CPU,
            SettingsManager.SENSEVOICE_PROVIDER_NPU
        ).forEach { provider ->
            val senseVoicePaths = listOf(
                settingsManager.getSenseVoiceModelPath(provider),
                settingsManager.getSenseVoiceTokensPath(provider)
            )
            if (senseVoicePaths.any { pointsInsideTarget(it, target) }) {
                settingsManager.clearSenseVoiceModelPaths(provider)
            }
        }
        val parakeetTdtPaths = listOf(
            settingsManager.getParakeetTdtEncoderPath(),
            settingsManager.getParakeetTdtDecoderPath(),
            settingsManager.getParakeetTdtJoinerPath(),
            settingsManager.getParakeetTdtTokensPath()
        )
        if (parakeetTdtPaths.any { pointsInsideTarget(it, target) }) {
            settingsManager.clearParakeetTdtModelPaths()
        }
        val parakeetCtcPaths = listOf(
            settingsManager.getParakeetCtcModelPath(),
            settingsManager.getParakeetCtcTokensPath()
        )
        if (parakeetCtcPaths.any { pointsInsideTarget(it, target) }) {
            settingsManager.clearParakeetCtcModelPaths()
        }
        ModelDownloader.QWEN3_ASR_MODELS.forEach { option ->
            val qwen3Paths = listOf(
                settingsManager.getQwen3AsrEncoderPath(option.id),
                settingsManager.getQwen3AsrDecoderPath(option.id),
                settingsManager.getQwen3AsrConvFrontendPath(option.id),
                settingsManager.getQwen3AsrTokenizerPath(option.id)
            )
            if (qwen3Paths.any { pointsInsideTarget(it, target) }) {
                settingsManager.clearQwen3AsrModelPaths(option.id)
            }
        }
        if (pointsInsideTarget(settingsManager.getQwen3ForcedAlignerPath(), target)) {
            settingsManager.clearQwen3ForcedAlignerPath()
        }
        if (pointsInsideTarget(settingsManager.getVadModelPath(), target)) {
            settingsManager.setVadModelPath("")
            settingsManager.setVadUseBuiltInModel(true)
        }
        listOf("general", "vocals", "drums", "bass", "other").forEach { key ->
            if (pointsInsideTarget(settingsManager.getDemixModelUri(key), target)) {
                settingsManager.setDemixModelUri(key, "")
            }
        }
    }

    private fun pointsInsideTarget(uriString: String, target: File): Boolean {
        if (uriString.isBlank()) return false
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        if (!uri.scheme.isNullOrBlank() && uri.scheme != "file") return false
        val path = uri.path ?: uriString
        return runCatching {
            File(path).canonicalFile.toPath().startsWith(target.canonicalFile.toPath())
        }.getOrDefault(false)
    }

    private fun calculateSize(file: File): Long = runCatching {
        when {
            file.isFile -> file.length()
            file.isDirectory -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            else -> 0L
        }
    }.getOrDefault(0L)

    private fun formatVariantName(value: String): String = when (value.lowercase()) {
        "tiny" -> "Tiny"
        "small" -> "Small"
        "large-v3" -> "Large v3"
        "turbo" -> "Turbo"
        else -> value
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun selectableItemBackgroundBorderless() = TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        ContextCompat.getDrawable(this, value.resourceId)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::asrImportController.isInitialized) asrImportController.dispose()
        if (::demucsImportController.isInitialized) demucsImportController.dispose()
        super.onDestroy()
    }
}
