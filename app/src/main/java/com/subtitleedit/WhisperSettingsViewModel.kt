package com.subtitleedit

import android.app.Application
import com.subtitleedit.ui.settings.WhisperSettingsState
import com.subtitleedit.util.SettingsManager
import kotlin.math.roundToInt

internal class WhisperSettingsViewModel(
    application: Application
) : AppViewModel<WhisperSettingsState, Nothing>(application, WhisperSettingsState()) {
    private val settings = SettingsManager.getInstance(application)

    init {
        setState {
            WhisperSettingsState(
                dynamicPaddingEnabled = settings.isSpeechVadDynamicPaddingEnabled(
                    SettingsManager.ASR_MODEL_WHISPER
                ),
                threads = settings.getSpeechWhisperThreads(),
                hotwordsEnabled = settings.isSpeechHotwordsEnabled(),
                hotwords = settings.getSpeechHotwords(),
                hotwordsScore = settings.getSpeechHotwordsScore()
            )
        }
    }

    fun setDynamicPaddingEnabled(enabled: Boolean) {
        settings.setSpeechVadDynamicPaddingEnabled(enabled, SettingsManager.ASR_MODEL_WHISPER)
        setState { copy(dynamicPaddingEnabled = enabled) }
    }

    fun setThreads(value: Float) {
        val threads = value.roundToInt().coerceIn(1, 8)
        setState { copy(threads = threads) }
        settings.setSpeechWhisperThreads(threads)
    }

    fun setHotwordsEnabled(enabled: Boolean) {
        setState { copy(hotwordsEnabled = enabled) }
        settings.setSpeechHotwordsEnabled(enabled)
    }

    /** Edits are kept in memory until [saveHotwords]. */
    fun setHotwords(hotwords: String) = setState { copy(hotwords = hotwords) }

    fun saveHotwords() = settings.setSpeechHotwords(currentState.hotwords)

    fun setHotwordsScore(score: Float) {
        setState { copy(hotwordsScore = score) }
        settings.setSpeechHotwordsScore(score)
    }
}
