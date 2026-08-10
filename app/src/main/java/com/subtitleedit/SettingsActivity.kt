package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.subtitleedit.databinding.ActivitySettingsBinding
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import java.io.File
import java.util.Locale

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
        setupEncodingSetting()
        setupCacheSection()
        setupModelSettings()
        setupTtsSettings()
        setupModelManagement()
        setupAiSettings()
        setupLogSettings()
        setupPlaybackSettings()
        setupUpdateSettings()
        setupThemeSettings()
        setupAbout()
        loadSettings()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    private fun setupModelSettings() {
        binding.layoutModelSettings.setOnClickListener {
            startActivity(Intent(this, ModelSettingsActivity::class.java))
        }
    }

    private fun setupTtsSettings() {
        binding.layoutTtsSettings.setOnClickListener {
            startActivity(Intent(this, TtsSettingsActivity::class.java))
        }
    }

    private fun setupModelManagement() {
        binding.layoutModelManagement.setOnClickListener {
            startActivity(Intent(this, ModelManagementActivity::class.java))
        }
    }

    private fun setupEncodingSetting() {
        binding.layoutEncoding.setOnClickListener { showEncodingDialog() }
    }

    private fun showEncodingDialog() {
        val current = FileUtils.SUPPORTED_ENCODINGS.indexOfFirst {
            it.charset == settingsManager.getDefaultEncoding()
        }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.activity_settings_text_01)
            .setSingleChoiceItems(
                FileUtils.SUPPORTED_ENCODINGS.map { it.displayName }.toTypedArray(),
                current
            ) { dialog, which ->
                settingsManager.setDefaultEncoding(FileUtils.SUPPORTED_ENCODINGS[which].charset)
                updateEncodingLabel()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateEncodingLabel() {
        binding.tvEncoding.text = FileUtils.SUPPORTED_ENCODINGS
            .firstOrNull { it.charset == settingsManager.getDefaultEncoding() }
            ?.displayName
            ?: settingsManager.getDefaultEncoding().displayName()
    }

    private fun setupAiSettings() {
        binding.layoutAiSettings.setOnClickListener {
            startActivity(Intent(this, AiSettingsActivity::class.java))
        }
    }

    private fun setupLogSettings() {
        binding.layoutLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    private fun setupPlaybackSettings() {
        // 循环播放开关即时保存
        binding.switchLoopSelectedSubtitle.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setLoopSelectedSubtitleEnabled(isChecked)
        }
    }

    // ==================== 缓存管理 ====================

    private fun setupCacheSection() {
        refreshTotalCacheSizeDisplay()
        binding.layoutClearCache.setOnClickListener {
            showClearCacheDialog()
        }
    }

    private fun refreshTotalCacheSizeDisplay() {
        val total = calcWaveformCacheSize() + calcSpectrogramCacheSize() + calcQuickTranscribeAudioCacheSize()
        binding.tvTotalCacheSize.text = if (total > 0) formatSize(total) else ""
    }

    private fun setupUpdateSettings() {
        binding.switchCheckUpdatesOnStartup.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setCheckUpdatesOnStartup(isChecked)
        }
    }

    private fun showClearCacheDialog() {
        val waveSize = calcWaveformCacheSize()
        val specSize = calcSpectrogramCacheSize()
        val audioSize = calcQuickTranscribeAudioCacheSize()
        val options = arrayOf(
            "波形图缓存（${formatSize(waveSize)}）",
            "频谱图缓存（${formatSize(specSize)}）",
            "快速转录音频缓存（${formatSize(audioSize)}）"
        )
        AlertDialog.Builder(this)
            .setTitle("清除缓存")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmClearWaveformCache(waveSize)
                    1 -> confirmClearSpectrogramCache(specSize)
                    2 -> confirmClearQuickTranscribeAudioCache(audioSize)
                }
            }
            .setNegativeButton("取消", null)
            .show()
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

    private fun calcQuickTranscribeAudioCacheSize(): Long {
        return quickTranscribeAudioCacheFiles().sumOf { it.length() }
    }

    private fun quickTranscribeAudioCacheFiles(): List<File> {
        return cacheDir.walkTopDown()
            .filter { file ->
                file.isFile && file.name.startsWith("quick_transcribe_") && file.name.endsWith("_16k.wav")
            }
            .toList()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024L        -> "$bytes B"
        bytes < 1024L * 1024 -> "${"%.1f".format(Locale.getDefault(), bytes / 1024.0)} KB"
        else                 -> "${"%.2f".format(Locale.getDefault(), bytes / 1024.0 / 1024.0)} MB"
    }

    private fun confirmClearWaveformCache(size: Long) {
        if (size == 0L) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "暂无波形图缓存可清除", Toast.LENGTH_SHORT).show()
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
                com.subtitleedit.util.OverwritingToast.makeText(this, "已清除 $count 个波形图缓存文件", Toast.LENGTH_SHORT).show()
                refreshTotalCacheSizeDisplay()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearSpectrogramCache(size: Long) {
        if (size == 0L) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "暂无频谱图缓存可清除", Toast.LENGTH_SHORT).show()
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
                com.subtitleedit.util.OverwritingToast.makeText(this, "已清除 $count 个频谱图缓存文件", Toast.LENGTH_SHORT).show()
                refreshTotalCacheSizeDisplay()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearQuickTranscribeAudioCache(size: Long) {
        if (size == 0L) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "暂无快速转录音频缓存可清除", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清除快速转录音频缓存")
            .setMessage("将删除 ${formatSize(size)} 的快速转录音频缓存，下次快速转录时会重新生成。\n确定继续？")
            .setPositiveButton("清除") { _, _ ->
                val count = quickTranscribeAudioCacheFiles().count { it.delete() }
                com.subtitleedit.util.OverwritingToast.makeText(this, "已清除 $count 个快速转录音频缓存文件", Toast.LENGTH_SHORT).show()
                refreshTotalCacheSizeDisplay()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 读写设置 ====================

    private fun loadSettings() {
        updateEncodingLabel()

        // 选中字幕循环播放
        binding.switchLoopSelectedSubtitle.isChecked = settingsManager.isLoopSelectedSubtitleEnabled()
        binding.switchCheckUpdatesOnStartup.isChecked = settingsManager.shouldCheckUpdatesOnStartup()
    }

    private fun setupAbout() {
        binding.layoutAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun setupThemeSettings() {
        updateThemeLabel()
        binding.layoutTheme.setOnClickListener { showThemeDialog() }
    }

    private fun updateThemeLabel() {
        binding.tvThemeMode.text = when (settingsManager.getThemeMode()) {
            SettingsManager.THEME_LIGHT -> "亮色"
            SettingsManager.THEME_DARK -> "深色"
            else -> "跟随系统"
        }
    }

    private fun showThemeDialog() {
        val options = arrayOf("亮色主题", "深色主题", "跟随系统")
        val current = when (settingsManager.getThemeMode()) {
            SettingsManager.THEME_LIGHT -> 0
            SettingsManager.THEME_DARK -> 1
            else -> 2
        }
        AlertDialog.Builder(this)
            .setTitle("主题")
            .setSingleChoiceItems(options, current) { dialog, which ->
                val mode = when (which) {
                    0 -> SettingsManager.THEME_LIGHT
                    1 -> SettingsManager.THEME_DARK
                    else -> SettingsManager.THEME_SYSTEM
                }
                settingsManager.setThemeMode(mode)
                AppCompatDelegate.setDefaultNightMode(
                    when (mode) {
                        SettingsManager.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                        SettingsManager.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
                updateThemeLabel()
                dialog.dismiss()
            }
            .show()
    }
}
