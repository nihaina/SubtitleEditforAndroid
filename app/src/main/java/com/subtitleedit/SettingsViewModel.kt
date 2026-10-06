package com.subtitleedit

import android.app.Application
import android.net.Uri
import com.subtitleedit.ui.settings.SettingsCacheItem
import com.subtitleedit.ui.settings.SettingsPageState
import com.subtitleedit.util.AppThemeMode
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.ModelDirectoryManager
import com.subtitleedit.util.ModelDirectoryMigration
import com.subtitleedit.util.SettingsManager
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Settings page state. Theme changes recreate the Activity on purpose; keeping the
 * state here means the page is not rebuilt from scratch when that happens.
 */
internal class SettingsViewModel(
    application: Application
) : AppViewModel<SettingsPageState, Nothing>(application, SettingsPageState()) {
    private val settingsManager = SettingsManager.getInstance(application)

    init {
        refresh()
    }

    /** Re-reads settings and cache sizes; called on every resume. */
    fun refresh() {
        val encoding = if (settingsManager.isDefaultEncodingAutomatic()) {
            SettingsManager.AUTO_ENCODING
        } else {
            settingsManager.getDefaultEncoding().name()
        }
        val themeMode = settingsManager.getThemeMode()
        val cacheItems = cacheItems()
        val totalCacheSize = cacheItems.sumOf(SettingsCacheItem::sizeBytes)
        val softwareDirectory = settingsManager.getSoftwareDirectoryPath()
        setState {
            SettingsPageState(
                encoding = encoding,
                themeMode = themeMode,
                themeLabel = string(
                    when (themeMode) {
                        SettingsManager.THEME_LIGHT -> R.string.settings_theme_light
                        SettingsManager.THEME_DARK -> R.string.settings_theme_dark
                        else -> R.string.settings_theme_system
                    }
                ),
                softwareDirectory = softwareDirectory,
                pendingSoftwareDirectory = pendingSoftwareDirectory,
                pendingSoftwareDirectoryModelCount = pendingSoftwareDirectoryModelCount,
                isMigratingSoftwareDirectory = isMigratingSoftwareDirectory,
                cacheSize = if (totalCacheSize > 0) formatSize(totalCacheSize) else "",
                checkUpdatesOnStartup = settingsManager.shouldCheckUpdatesOnStartup(),
                preserveOutputDirectories = settingsManager.isOutputDirectoryPersistenceEnabled(),
                loopSelectedSubtitle = settingsManager.isLoopSelectedSubtitleEnabled(),
                selectPlayingSubtitle = settingsManager.isSelectPlayingSubtitleEnabled(),
                cacheItems = cacheItems
            )
        }
    }

    fun selectEncoding(encoding: FileUtils.EncodingInfo) {
        if (encoding.isAuto) settingsManager.setDefaultEncodingAutomatic()
        else settingsManager.setDefaultEncoding(encoding.charset)
        refresh()
    }

    fun selectTheme(mode: String) {
        settingsManager.setThemeMode(mode)
        // Intentionally recreates activities when the night mode changes; state lives here.
        AppThemeMode.apply(app, mode)
        refresh()
    }

    fun setCheckUpdatesOnStartup(enabled: Boolean) {
        setState { copy(checkUpdatesOnStartup = enabled) }
        settingsManager.setCheckUpdatesOnStartup(enabled)
    }

    fun setPreserveOutputDirectories(enabled: Boolean) {
        setState { copy(preserveOutputDirectories = enabled) }
        settingsManager.setOutputDirectoryPersistenceEnabled(enabled)
    }

    fun setLoopSelectedSubtitle(enabled: Boolean) {
        setState { copy(loopSelectedSubtitle = enabled) }
        settingsManager.setLoopSelectedSubtitleEnabled(enabled)
    }

    fun setSelectPlayingSubtitle(enabled: Boolean) {
        setState { copy(selectPlayingSubtitle = enabled) }
        settingsManager.setSelectPlayingSubtitleEnabled(enabled)
    }

    /** Receives a selected software root and opens the migration confirmation. */
    fun selectSoftwareDirectory(uri: Uri) {
        val displayPath = DirectoryDisplayPath.fromUri(app, uri)
        requestSoftwareDirectory(displayPath)
    }

    private fun requestSoftwareDirectory(path: String) {
        val target = File(path).absoluteFile
        val currentRoot = settingsManager.getSoftwareDirectory().absoluteFile
        if (target.canonicalFile == currentRoot.canonicalFile) return
        val currentModels = settingsManager.getModelDirectory()
        val modelCount = ModelDirectoryMigration.findRecognizedModels(currentModels).size
        if (modelCount == 0) {
            switchSoftwareDirectory(target, migrateModels = false)
            return
        }
        setState {
            copy(
                pendingSoftwareDirectory = target.path,
                pendingSoftwareDirectoryModelCount = modelCount
            )
        }
    }

    fun confirmSoftwareDirectory() {
        val targetPath = currentState.pendingSoftwareDirectory ?: return
        val targetRoot = File(targetPath).absoluteFile
        setState {
            copy(
                pendingSoftwareDirectory = null,
                pendingSoftwareDirectoryModelCount = 0,
                isMigratingSoftwareDirectory = true
            )
        }
        switchSoftwareDirectory(targetRoot, migrateModels = true)
    }

    fun cancelSoftwareDirectory() {
        setState {
            copy(pendingSoftwareDirectory = null, pendingSoftwareDirectoryModelCount = 0)
        }
    }

    private fun switchSoftwareDirectory(targetRoot: File, migrateModels: Boolean) {
        val currentModels = settingsManager.getModelDirectory()
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val targetModels = File(targetRoot, ModelDirectoryManager.MODELS_DIRECTORY_NAME)
                    if (migrateModels && currentModels.canonicalFile != targetModels.canonicalFile) {
                        ModelDirectoryMigration.migrate(currentModels, targetModels)
                        settingsManager.rewriteModelDirectoryPaths(currentModels, targetModels)
                        currentModels.parentFile?.let {
                            settingsManager.rewriteModelDirectoryPaths(it, targetRoot)
                        }
                    }
                    settingsManager.setSoftwareDirectory(targetRoot.path)
                    settingsManager.clearPersistedOutputDirectories()
                    targetRoot.mkdirs()
                    targetModels.mkdirs()
                    listOf("Convert", "Translate", "Output", "TranscriptMatch").forEach {
                        File(targetRoot, it).mkdirs()
                    }
                }
            }.onFailure { error ->
                toast("软件目录切换失败：${error.message ?: "未知错误"}")
            }
            setState {
                copy(
                    pendingSoftwareDirectory = null,
                    pendingSoftwareDirectoryModelCount = 0,
                    isMigratingSoftwareDirectory = false
                )
            }
            refresh()
        }
    }

    fun onEmptyCacheClear(item: SettingsCacheItem) = toast(item.emptyMessage)

    private fun cacheItems(): List<SettingsCacheItem> {
        val waveformSize = waveformCacheFiles().sumOf(File::length)
        val spectrogramSize = spectrogramCacheFiles().sumOf(File::length)
        val audioSize = quickTranscribeAudioCacheFiles().sumOf(File::length)
        return listOf(
            SettingsCacheItem(
                key = CACHE_WAVEFORM,
                label = string(R.string.settings_cache_waveform_label),
                sizeBytes = waveformSize,
                emptyMessage = string(R.string.settings_cache_waveform_empty),
                confirmationMessage = string(R.string.settings_cache_waveform_confirm, formatSize(waveformSize))
            ),
            SettingsCacheItem(
                key = CACHE_SPECTROGRAM,
                label = string(R.string.settings_cache_spectrogram_label),
                sizeBytes = spectrogramSize,
                emptyMessage = string(R.string.settings_cache_spectrogram_empty),
                confirmationMessage = string(R.string.settings_cache_spectrogram_confirm, formatSize(spectrogramSize))
            ),
            SettingsCacheItem(
                key = CACHE_QUICK_TRANSCRIBE,
                label = string(R.string.settings_cache_quick_transcribe_label),
                sizeBytes = audioSize,
                emptyMessage = string(R.string.settings_cache_quick_transcribe_empty),
                confirmationMessage = string(R.string.settings_cache_quick_transcribe_confirm, formatSize(audioSize))
            )
        )
    }

    fun clearCache(item: SettingsCacheItem) {
        val files = when (item.key) {
            CACHE_WAVEFORM -> waveformCacheFiles()
            CACHE_SPECTROGRAM -> spectrogramCacheFiles()
            CACHE_QUICK_TRANSCRIBE -> quickTranscribeAudioCacheFiles()
            else -> return
        }
        // Keep the legacy result count semantics: waveform and spectrogram
        // report every matching file visited, while quick-transcribe reports
        // only successful deletions.
        val deletedCount = if (item.key == CACHE_QUICK_TRANSCRIBE) {
            files.count(File::delete)
        } else {
            var count = 0
            files.forEach {
                it.delete()
                count++
            }
            count
        }
        val kind = string(
            when (item.key) {
                CACHE_WAVEFORM -> R.string.settings_cache_waveform_kind
                CACHE_SPECTROGRAM -> R.string.settings_cache_spectrogram_kind
                else -> R.string.settings_cache_quick_transcribe_kind
            }
        )
        toast(R.string.settings_cache_cleared, deletedCount, kind)
        refresh()
    }

    private fun waveformCacheFiles(): List<File> = cacheFilesInWaveformDirectory {
        it.extension == "wave"
    }

    private fun spectrogramCacheFiles(): List<File> = cacheFilesInWaveformDirectory {
        it.extension == "png" && it.name.contains(".spec_")
    }

    private fun cacheFilesInWaveformDirectory(matches: (File) -> Boolean): List<File> {
        val directory = File(app.cacheDir, "waveform")
        if (!directory.exists()) return emptyList()
        return directory.walkTopDown().filter { it.isFile && matches(it) }.toList()
    }

    private fun quickTranscribeAudioCacheFiles(): List<File> = app.cacheDir.walkTopDown()
        .filter { it.isFile && it.name.startsWith("quick_transcribe_") && it.name.endsWith("_16k.wav") }
        .toList()

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "${"%.1f".format(Locale.getDefault(), bytes / 1024.0)} KB"
        else -> "${"%.2f".format(Locale.getDefault(), bytes / 1024.0 / 1024.0)} MB"
    }

    private companion object {
        const val CACHE_WAVEFORM = "waveform"
        const val CACHE_SPECTROGRAM = "spectrogram"
        const val CACHE_QUICK_TRANSCRIBE = "quick_transcribe"
    }
}
