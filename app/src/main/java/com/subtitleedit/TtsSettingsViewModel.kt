package com.subtitleedit

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import com.subtitleedit.util.SettingsManager

internal data class TtsEngineOption(val label: String, val packageName: String)

internal data class TtsSettingsUiState(
    val engineOptions: List<TtsEngineOption> = emptyList(),
    val selectedEngine: String = "",
    val selectedLanguage: String = SettingsManager.TTS_LANGUAGE_AUTO
) {
    val installedEngineCount: Int get() = (engineOptions.size - 1).coerceAtLeast(0)
}

internal class TtsSettingsViewModel(
    application: Application
) : AppViewModel<TtsSettingsUiState, Nothing>(application, TtsSettingsUiState()) {
    private val settingsManager = SettingsManager.getInstance(application)

    init {
        val engineOptions = loadEngineOptions()
        val savedEngine = settingsManager.getTtsEngine()
        // Keep the legacy fallback: a missing/uninstalled saved engine resets to
        // the system default instead of silently selecting the first installed one.
        val initialEngine = engineOptions.firstOrNull { it.packageName == savedEngine }
            ?: engineOptions.first()
        if (initialEngine.packageName != savedEngine) {
            settingsManager.setTtsEngine("")
        }
        val savedLanguage = settingsManager.getTtsLanguage()
        val initialLanguage = savedLanguage.takeIf { it in supportedLanguageValues }
            ?: SettingsManager.TTS_LANGUAGE_AUTO
        setState {
            copy(
                engineOptions = engineOptions,
                selectedEngine = initialEngine.packageName,
                selectedLanguage = initialLanguage
            )
        }
    }

    fun selectEngine(packageName: String) {
        setState { copy(selectedEngine = packageName) }
        settingsManager.setTtsEngine(packageName)
    }

    fun selectLanguage(language: String) {
        setState { copy(selectedLanguage = language) }
        settingsManager.setTtsLanguage(language)
    }

    @Suppress("DEPRECATION")
    private fun loadEngineOptions(): List<TtsEngineOption> {
        val packageManager = app.packageManager
        val installed = packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            PackageManager.MATCH_ALL
        ).mapNotNull { info ->
            val serviceInfo = info.serviceInfo ?: return@mapNotNull null
            TtsEngineOption(
                label = info.loadLabel(packageManager)?.toString()?.ifBlank { serviceInfo.packageName }
                    ?: serviceInfo.packageName,
                packageName = serviceInfo.packageName
            )
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }

        return listOf(TtsEngineOption(string(R.string.tts_settings_engine_system_default), "")) + installed
    }

    private companion object {
        val supportedLanguageValues = setOf(
            SettingsManager.TTS_LANGUAGE_AUTO,
            SettingsManager.TTS_LANGUAGE_SYSTEM,
            SettingsManager.TTS_LANGUAGE_JAPANESE,
            SettingsManager.TTS_LANGUAGE_CHINESE,
            SettingsManager.TTS_LANGUAGE_ENGLISH
        )
    }
}
