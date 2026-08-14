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
import com.google.android.material.card.MaterialCardView
import com.subtitleedit.databinding.ActivityModelManagementBinding
import com.subtitleedit.util.ModelDownloader
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class ModelManagementActivity : AppCompatActivity() {
    private lateinit var binding: ActivityModelManagementBinding
    private lateinit var settingsManager: SettingsManager
    private var requestedStorageAccess = false

    private data class ModelItem(
        val category: String,
        val displayName: String,
        val file: File,
        val size: Long
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
        supportActionBar?.title = "模型管理"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.tvModelsDirectory.text =
            "下载模型目录：${ModelDownloader.modelsDirectory().absolutePath}"

        if (hasStorageAccess()) loadModels() else requestStorageAccess()
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
                showStorageAccessRequired()
            }
        } else {
            writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun handleStorageAccessResult() {
        requestedStorageAccess = false
        if (hasStorageAccess()) loadModels() else showStorageAccessRequired()
    }

    private fun hasStorageAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun showStorageAccessRequired() {
        binding.progressBar.visibility = View.GONE
        binding.modelContainer.removeAllViews()
        binding.tvEmpty.text = "需要存储权限才能扫描和删除模型"
        binding.tvEmpty.visibility = View.VISIBLE
    }

    private fun loadModels() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { scanModels() } }
            binding.progressBar.visibility = View.GONE
            result.onSuccess(::renderModels).onFailure {
                binding.modelContainer.removeAllViews()
                binding.tvEmpty.text = "模型目录读取失败：${it.message}"
                binding.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun scanModels(): List<ModelItem> {
        val items = mutableListOf<ModelItem>()
        val root = ModelDownloader.modelsDirectory()
        if (!root.isDirectory) return emptyList()
        root.listFiles().orEmpty()
            .filterNot { it.name.startsWith(".") || it.name.contains(".part.") || it.name.endsWith(".backup") }
            .forEach { file ->
                when {
                    file.isDirectory && file.name.startsWith("sherpa-onnx-sense-voice-") -> {
                        items += ModelItem("SenseVoice 模型", "SenseVoice", file, calculateSize(file))
                    }
                    file.isDirectory && file.name.startsWith("sherpa-onnx-qnn-") &&
                        file.name.contains("sense-voice") -> {
                        val option = ModelDownloader.SENSEVOICE_NPU_MODELS
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
                    file.isDirectory && file.name == ModelDownloader.PARAKEET_TDT_MODEL.directoryName -> {
                        items += ModelItem(
                            "Parakeet 模型",
                            ModelDownloader.PARAKEET_TDT_MODEL.displayName,
                            file,
                            calculateSize(file)
                        )
                    }
                    file.isDirectory && file.name == ModelDownloader.PARAKEET_CTC_JA_MODEL.directoryName -> {
                        items += ModelItem(
                            "Parakeet 模型",
                            ModelDownloader.PARAKEET_CTC_JA_MODEL.displayName,
                            file,
                            calculateSize(file)
                        )
                    }
                    file.isDirectory && file.name == ModelDownloader.SEPARATION_DIRECTORY_NAME -> {
                        file.listFiles().orEmpty().filterNot { it.name.startsWith(".") }.forEach { model ->
                            items += ModelItem("人声分离模型", model.name, model, calculateSize(model))
                        }
                    }
                    else -> {
                        items += ModelItem("其他模型文件", file.name, file, calculateSize(file))
                    }
                }
            }
        val categoryOrder = mapOf(
            "SenseVoice 模型" to 0,
            "Whisper 模型" to 1,
            "Parakeet 模型" to 2,
            "人声分离模型" to 3,
            "其他模型文件" to 4
        )
        return items.sortedWith(
            compareBy<ModelItem> { categoryOrder[it.category] ?: Int.MAX_VALUE }
                .thenBy { it.displayName.lowercase() }
        )
    }

    private fun renderModels(items: List<ModelItem>) {
        binding.modelContainer.removeAllViews()
        if (items.isEmpty()) {
            binding.tvEmpty.text = "未发现模型"
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
        details.addView(TextView(this).apply {
            text = item.displayName
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.on_surface))
        })
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
            setOnClickListener { confirmDeleteModel(item, this) }
        }
        row.addView(details)
        row.addView(deleteAction)
        card.addView(row)
        ToolCardShadow.remove(card)
        return card
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
}
