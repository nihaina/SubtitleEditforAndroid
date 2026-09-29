package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.feature.ui.VadModelSettingsScreen
import com.subtitleedit.feature.ui.VadSettingsState
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager

class VadModelSettingsActivity : AppCompatActivity() {
    private lateinit var settings: SettingsManager
    private var vadSettings by mutableStateOf<VadSettingsState?>(null)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        settings = SettingsManager.getInstance(this)
        vadSettings = VadSettingsState.read(settings)

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
        settings.setSpeechVadMergeEnabled(state.mergeEnabled)
        settings.setSpeechVadMergeGapMs(state.mergeGapMs)
        settings.setVadThreshold(state.threshold)
        settings.setVadMinSilenceDuration(state.minSilence)
        settings.setVadMinSpeechDuration(state.minSpeech)
        settings.setVadMaxSpeechDuration(state.maxSpeech)
        settings.setSpeechSecondaryVadMode(state.secondaryMode)
        settings.setSpeechSecondaryVadMergeEnabled(state.secondaryMergeEnabled)
        settings.setSpeechSecondaryVadMergeGapMs(state.secondaryMergeGapMs)
        settings.setSpeechSecondaryVadThreshold(state.secondaryThreshold)
        settings.setSpeechSecondaryVadMinSilenceDuration(state.secondaryMinSilence)
        settings.setSpeechSecondaryVadMinSpeechDuration(state.secondaryMinSpeech)
        settings.setSpeechSecondaryVadMaxSpeechDuration(state.secondaryMaxSpeech)
    }
}
