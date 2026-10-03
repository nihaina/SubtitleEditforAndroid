package com.subtitleedit

import android.app.Application
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.TokenTimestampGenerator

internal data class Qwen3AsrSettingsUiState(
    val forcedAlignmentAvailable: Boolean = false,
    /** Bumped on every resume so the screen re-reads settings changed on other pages. */
    val refreshKey: Int = 0
)

internal class Qwen3AsrSettingsViewModel(
    application: Application
) : AppViewModel<Qwen3AsrSettingsUiState, Nothing>(application, Qwen3AsrSettingsUiState()) {
    val settingsManager: SettingsManager = SettingsManager.getInstance(application)

    init {
        setState { copy(forcedAlignmentAvailable = isForcedAlignmentAvailable()) }
    }

    fun refresh() {
        setState { copy(forcedAlignmentAvailable = isForcedAlignmentAvailable(), refreshKey = refreshKey + 1) }
    }

    private fun isForcedAlignmentAvailable(): Boolean =
        settingsManager.getAsrModelType() == SettingsManager.ASR_MODEL_QWEN3_ASR &&
            TokenTimestampGenerator.isConfigured(app)
}
