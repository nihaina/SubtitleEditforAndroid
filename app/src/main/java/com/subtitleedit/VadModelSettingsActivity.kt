package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.feature.ui.VadModelSettingsScreen
import com.subtitleedit.feature.ui.VadSettingsState
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager

class VadModelSettingsActivity : AppComposeActivity() {
    private lateinit var settingsManager: SettingsManager
    private var vadSettings by mutableStateOf<VadSettingsState?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager.getInstance(this)
        vadSettings = VadSettingsState.read(settingsManager)
        setContent {
            SubtitleEditComposeTheme {
                vadSettings?.let { current ->
                    VadModelSettingsScreen(
                        state = current,
                        onStateChange = ::saveSettings,
                        onBack = { onBackPressedDispatcher.onBackPressed() }
                    )
                }
            }
        }
    }

    private fun saveSettings(state: VadSettingsState) {
        vadSettings = state
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
