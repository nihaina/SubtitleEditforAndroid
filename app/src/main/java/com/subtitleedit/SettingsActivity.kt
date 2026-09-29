package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.ui.settings.SettingsCacheItem
import com.subtitleedit.ui.settings.SettingsPageState
import com.subtitleedit.ui.settings.SettingsScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.AppThemeMode
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import java.io.File
import java.util.Locale

/** Settings screen host. Android settings and cache operations stay outside the UI. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager
    private var pageState by mutableStateOf(SettingsPageState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager.getInstance(this)
        refreshPageState()

        setContent {
            SubtitleEditComposeTheme {
                SettingsScreen(
                    state = pageState,
                    encodings = FileUtils.SUPPORTED_ENCODINGS,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onEncodingSelected = { encoding ->
                        settingsManager.setDefaultEncoding(encoding.charset)
                        refreshPageState()
                    },
                    onThemeSelected = { mode ->
                        settingsManager.setThemeMode(mode)
                        AppThemeMode.apply(this, mode)
                        refreshPageState()
                    },
                    onCheckUpdatesChanged = { enabled ->
                        pageState = pageState.copy(checkUpdatesOnStartup = enabled)
                        settingsManager.setCheckUpdatesOnStartup(enabled)
                    },
                    onPreserveDirectoriesChanged = { enabled ->
                        pageState = pageState.copy(preserveOutputDirectories = enabled)
                        settingsManager.setOutputDirectoryPersistenceEnabled(enabled)
                    },
                    onLoopSelectedChanged = { enabled ->
                        pageState = pageState.copy(loopSelectedSubtitle = enabled)
                        settingsManager.setLoopSelectedSubtitleEnabled(enabled)
                    },
                    onOpenAiSettings = { open(AiSettingsActivity::class.java) },
                    onOpenModelManagement = { open(ModelManagementActivity::class.java) },
                    onOpenTtsSettings = { open(TtsSettingsActivity::class.java) },
                    onOpenLogs = { open(LogActivity::class.java) },
                    onOpenAbout = { open(AboutActivity::class.java) },
                    onCacheClear = ::clearCache,
                    onEmptyCacheClear = { showToast(it.emptyMessage) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::settingsManager.isInitialized) refreshPageState()
    }

    private fun open(activity: Class<*>) {
        startActivity(Intent(this, activity))
    }

    private fun refreshPageState() {
        val encoding = FileUtils.SUPPORTED_ENCODINGS.firstOrNull {
            it.charset == settingsManager.getDefaultEncoding()
        }?.displayName ?: settingsManager.getDefaultEncoding().displayName()
        val themeMode = settingsManager.getThemeMode()
        val cacheItems = cacheItems()
        val totalCacheSize = cacheItems.sumOf(SettingsCacheItem::sizeBytes)
        pageState = SettingsPageState(
            encoding = encoding,
            themeMode = themeMode,
            themeLabel = when (themeMode) {
                SettingsManager.THEME_LIGHT -> "亮色"
                SettingsManager.THEME_DARK -> "深色"
                else -> "跟随系统"
            },
            cacheSize = if (totalCacheSize > 0) formatSize(totalCacheSize) else "",
            checkUpdatesOnStartup = settingsManager.shouldCheckUpdatesOnStartup(),
            preserveOutputDirectories = settingsManager.isOutputDirectoryPersistenceEnabled(),
            loopSelectedSubtitle = settingsManager.isLoopSelectedSubtitleEnabled(),
            cacheItems = cacheItems
        )
    }

    private fun cacheItems(): List<SettingsCacheItem> {
        val waveformSize = waveformCacheFiles().sumOf(File::length)
        val spectrogramSize = spectrogramCacheFiles().sumOf(File::length)
        val audioSize = quickTranscribeAudioCacheFiles().sumOf(File::length)
        return listOf(
            SettingsCacheItem(
                key = CACHE_WAVEFORM,
                label = "波形图缓存",
                sizeBytes = waveformSize,
                emptyMessage = "暂无波形图缓存可清除",
                confirmationMessage = "将删除 ${formatSize(waveformSize)} 的波形图缓存，下次打开音频时会重新生成。\n确定继续？"
            ),
            SettingsCacheItem(
                key = CACHE_SPECTROGRAM,
                label = "频谱图缓存",
                sizeBytes = spectrogramSize,
                emptyMessage = "暂无频谱图缓存可清除",
                confirmationMessage = "将删除 ${formatSize(spectrogramSize)} 的频谱图缓存，下次查看频谱图时会重新生成。\n确定继续？"
            ),
            SettingsCacheItem(
                key = CACHE_QUICK_TRANSCRIBE,
                label = "快速转录音频缓存",
                sizeBytes = audioSize,
                emptyMessage = "暂无快速转录音频缓存可清除",
                confirmationMessage = "将删除 ${formatSize(audioSize)} 的快速转录音频缓存，下次快速转录时会重新生成。\n确定继续？"
            )
        )
    }

    private fun clearCache(item: SettingsCacheItem) {
        val files = when (item.key) {
            CACHE_WAVEFORM -> waveformCacheFiles()
            CACHE_SPECTROGRAM -> spectrogramCacheFiles()
            CACHE_QUICK_TRANSCRIBE -> quickTranscribeAudioCacheFiles()
            else -> return
        }
        val deletedCount = files.count(File::delete)
        val label = item.label.removeSuffix("缓存")
        showToast("已清除 $deletedCount 个${label}缓存文件")
        refreshPageState()
    }

    private fun waveformCacheFiles(): List<File> = cacheFilesInWaveformDirectory {
        it.extension == "wave"
    }

    private fun spectrogramCacheFiles(): List<File> = cacheFilesInWaveformDirectory {
        it.extension == "png" && it.name.contains(".spec_")
    }

    private fun cacheFilesInWaveformDirectory(matches: (File) -> Boolean): List<File> {
        val directory = File(cacheDir, "waveform")
        if (!directory.exists()) return emptyList()
        return directory.walkTopDown().filter { it.isFile && matches(it) }.toList()
    }

    private fun quickTranscribeAudioCacheFiles(): List<File> = cacheDir.walkTopDown()
        .filter { it.isFile && it.name.startsWith("quick_transcribe_") && it.name.endsWith("_16k.wav") }
        .toList()

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "${"%.1f".format(Locale.getDefault(), bytes / 1024.0)} KB"
        else -> "${"%.2f".format(Locale.getDefault(), bytes / 1024.0 / 1024.0)} MB"
    }

    private fun showToast(message: String) {
        OverwritingToast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val CACHE_WAVEFORM = "waveform"
        const val CACHE_SPECTROGRAM = "spectrogram"
        const val CACHE_QUICK_TRANSCRIBE = "quick_transcribe"
    }
}
