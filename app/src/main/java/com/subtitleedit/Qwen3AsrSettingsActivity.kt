package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.feature.ui.Qwen3AsrSettingsScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.TokenTimestampGenerator

class Qwen3AsrSettingsActivity : AppComposeActivity() {

    private lateinit var settingsManager: SettingsManager
    private var refreshKey by mutableIntStateOf(0)
    private var forcedAlignmentAvailable by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager.getInstance(this)
        updateForcedAlignmentAvailability()

        setContent {
            SubtitleEditComposeTheme {
                Qwen3AsrSettingsScreen(
                    settingsManager = settingsManager,
                    forcedAlignmentAvailable = forcedAlignmentAvailable,
                    refreshKey = refreshKey,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenVadSettings = {
                        startActivity(Intent(this, VadModelSettingsActivity::class.java))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::settingsManager.isInitialized) {
            updateForcedAlignmentAvailability()
            refreshKey++
        }
    }

    private fun updateForcedAlignmentAvailability() {
        forcedAlignmentAvailable =
            settingsManager.getAsrModelType() == SettingsManager.ASR_MODEL_QWEN3_ASR &&
                TokenTimestampGenerator.isConfigured(this)
    }
}
