package com.subtitleedit

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivitySettingsBinding
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import java.io.File

/**
 * 设置界面
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager.getInstance(this)

        setupToolbar()
        setupEncodingSpinner()
        setupWaveformCacheSection()
        loadSettings()
        setupSaveButton()
        setupGithubLink()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    private fun setupEncodingSpinner() {
        val encodings = FileUtils.SUPPORTED_ENCODINGS.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, encodings)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerEncoding.adapter = adapter
    }

    // ==================== 波形/频谱图缓存设置 ====================

    private fun setupWaveformCacheSection() {
        binding.rgWaveformCache.setOnCheckedChangeListener { _, checkedId ->
            val isAppCache = (checkedId == binding.rbCacheApp.id)
            binding.btnClearWaveformCache.isEnabled    = isAppCache
            binding.btnClearSpectrogramCache.isEnabled = isAppCache
            refreshCacheSizeDisplay()
        }

        binding.btnClearWaveformCache.setOnClickListener {
            confirmClearWaveformCache()
        }
        binding.btnClearSpectrogramCache.setOnClickListener {
            confirmClearSpectrogramCache()
        }
    }

    private fun refreshCacheSizeDisplay() {
        val isAppCache = binding.rgWaveformCache.checkedRadioButtonId == binding.rbCacheApp.id
        if (isAppCache) {
            val waveSize = calcWaveformCacheSize()
            val specSize = calcSpectrogramCacheSize()
            binding.tvWaveformCacheSize.text    = "波形图缓存：${formatSize(waveSize)}"
            binding.tvSpectrogramCacheSize.text = "频谱图缓存：${formatSize(specSize)}"
            binding.tvWaveformCacheSize.visibility    = android.view.View.VISIBLE
            binding.tvSpectrogramCacheSize.visibility = android.view.View.VISIBLE
        } else {
            binding.tvWaveformCacheSize.visibility    = android.view.View.GONE
            binding.tvSpectrogramCacheSize.visibility = android.view.View.GONE
        }
    }

    private fun calcWaveformCacheSize(): Long {
        val dir = File(cacheDir, "waveform")
        if (!dir.exists()) return 0L
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "wave" }
            .sumOf { it.length() }
    }

    private fun calcSpectrogramCacheSize(): Long {
        val dir = File(cacheDir, "waveform")
        if (!dir.exists()) return 0L
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "png" && it.name.contains(".spec_") }
            .sumOf { it.length() }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024L        -> "$bytes B"
        bytes < 1024L * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else                 -> "${"%.2f".format(bytes / 1024.0 / 1024.0)} MB"
    }

    private fun confirmClearWaveformCache() {
        val size = calcWaveformCacheSize()
        if (size == 0L) {
            Toast.makeText(this, "暂无波形图缓存可清除", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清除波形图缓存")
            .setMessage("将删除 ${formatSize(size)} 的波形图缓存，下次打开音频时会重新生成。\n确定继续？")
            .setPositiveButton("清除") { _, _ ->
                val dir = File(cacheDir, "waveform")
                var count = 0
                dir.walkTopDown()
                    .filter { it.isFile && it.extension == "wave" }
                    .forEach { it.delete(); count++ }
                Toast.makeText(this, "已清除 $count 个波形图缓存文件", Toast.LENGTH_SHORT).show()
                refreshCacheSizeDisplay()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearSpectrogramCache() {
        val size = calcSpectrogramCacheSize()
        if (size == 0L) {
            Toast.makeText(this, "暂无频谱图缓存可清除", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清除频谱图缓存")
            .setMessage("将删除 ${formatSize(size)} 的频谱图缓存，下次查看频谱图时会重新生成。\n确定继续？")
            .setPositiveButton("清除") { _, _ ->
                val dir = File(cacheDir, "waveform")
                var count = 0
                dir.walkTopDown()
                    .filter { it.isFile && it.extension == "png" && it.name.contains(".spec_") }
                    .forEach { it.delete(); count++ }
                Toast.makeText(this, "已清除 $count 个频谱图缓存文件", Toast.LENGTH_SHORT).show()
                refreshCacheSizeDisplay()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 读写设置 ====================

    private fun loadSettings() {
        // 默认编码
        val currentEncoding = settingsManager.getDefaultEncoding()
        val encodingIndex = FileUtils.SUPPORTED_ENCODINGS.indexOfFirst { it.charset == currentEncoding }
        if (encodingIndex >= 0) binding.spinnerEncoding.setSelection(encodingIndex)

        // AI 设置
        binding.etApiKey.setText(settingsManager.getAiApiKey())
        binding.etModel.setText(settingsManager.getAiModel())
        binding.etSourceLanguage.setText(settingsManager.getAiSourceLanguage())
        binding.etTargetLanguage.setText(settingsManager.getAiTargetLanguage())

        // 波形缓存位置
        val cacheLocation = settingsManager.getWaveformCacheLocation()
        if (cacheLocation == SettingsManager.WAVEFORM_CACHE_SOURCE) {
            binding.rgWaveformCache.check(binding.rbCacheSource.id)
        } else {
            binding.rgWaveformCache.check(binding.rbCacheApp.id)   // 默认
        }
        // 初始化显示
        refreshCacheSizeDisplay()

        // 选中字幕循环播放
        binding.switchLoopSelectedSubtitle.isChecked = settingsManager.isLoopSelectedSubtitleEnabled()
    }

    private fun setupSaveButton() {
        binding.btnSaveSettings.setOnClickListener { saveSettings() }
    }

    private fun setupGithubLink() {
        binding.tvGithub.setOnClickListener {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/nihaina/SubtitleEditforAndroid")
            )
            startActivity(intent)
        }
    }

    private fun saveSettings() {
        // ① 编码设置——无需校验，直接保存
        val selectedEncoding = FileUtils.SUPPORTED_ENCODINGS[binding.spinnerEncoding.selectedItemPosition]
        settingsManager.setDefaultEncoding(selectedEncoding.charset)

        // ② 波形缓存位置——无需校验，直接保存
        val cacheLocation =
            if (binding.rgWaveformCache.checkedRadioButtonId == binding.rbCacheSource.id)
                SettingsManager.WAVEFORM_CACHE_SOURCE
            else
                SettingsManager.WAVEFORM_CACHE_APP
        settingsManager.setWaveformCacheLocation(cacheLocation)

        // ③ 循环播放开关——无需校验，直接保存
        settingsManager.setLoopSelectedSubtitleEnabled(binding.switchLoopSelectedSubtitle.isChecked)

        // ④ AI 设置——有内容才保存，为空时仅提示，不阻断其他设置
        val apiKey     = binding.etApiKey.text.toString().trim()
        val model      = binding.etModel.text.toString().trim()
        val sourceLang = binding.etSourceLanguage.text.toString().trim()
        val targetLang = binding.etTargetLanguage.text.toString().trim()

        val aiWarnings = mutableListOf<String>()
        if (apiKey.isEmpty())     aiWarnings.add("API Key")
        if (model.isEmpty())      aiWarnings.add("模型名称")
        if (targetLang.isEmpty()) aiWarnings.add("翻译目标语言")

        if (aiWarnings.isNotEmpty()) {
            // 其他设置已保存，仅提示 AI 字段缺失
            Toast.makeText(
                this,
                "设置已保存，但 AI 翻译功能缺少以下字段：${aiWarnings.joinToString("、")}",
                Toast.LENGTH_LONG
            ).show()
        } else {
            settingsManager.setAiApiKey(apiKey)
            settingsManager.setAiModel(model)
            settingsManager.setAiSourceLanguage(sourceLang)
            settingsManager.setAiTargetLanguage(targetLang)
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        }
    }
}
