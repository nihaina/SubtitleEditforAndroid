package com.subtitleedit

import android.app.Application
import com.subtitleedit.feature.ui.VadSettingsState
import com.subtitleedit.util.SettingsManager

internal class VadModelSettingsViewModel(
    application: Application
) : AppViewModel<VadSettingsState, Nothing>(
    application,
    VadSettingsState.read(SettingsManager.getInstance(application))
) {
    private val settingsManager = SettingsManager.getInstance(application)

    fun save(state: VadSettingsState) {
        setState { state }
        settingsManager.setSpeechVadMergeEnabled(state.mergeEnabled)
        settingsManager.setSpeechVadMergeGapMs(state.mergeGapMs)
        settingsManager.setVadThreshold(state.threshold)
        settingsManager.setVadMinSilenceDuration(state.minSilence)
        settingsManager.setVadMinSpeechDuration(state.minSpeech)
        settingsManager.setVadMaxSpeechDuration(state.maxSpeech)
        settingsManager.setSpeechSecondaryVadMode(state.secondaryMode)
        settingsManager.setSpeechSecondaryVadMergeEnabled(state.secondaryMergeEnabled)
        settingsManager.setSpeechSecondaryVadMergeGapMs(state.secondaryMergeGapMs)
        settingsManager.setSpeechSecondaryVadThreshold(state.secondaryThreshold)
        settingsManager.setSpeechSecondaryVadMinSilenceDuration(state.secondaryMinSilence)
        settingsManager.setSpeechSecondaryVadMinSpeechDuration(state.secondaryMinSpeech)
        settingsManager.setSpeechSecondaryVadMaxSpeechDuration(state.secondaryMaxSpeech)
    }
}
