package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.setContent
import com.subtitleedit.feature.ui.LlmSettingsScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager

class LlmSettingsActivity : AppComposeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsManager.getInstance(this)
        setContent {
            SubtitleEditComposeTheme {
                LlmSettingsScreen(
                    settingsManager = settings,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }
}
