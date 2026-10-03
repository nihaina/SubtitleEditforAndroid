package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.ui.settings.WhisperSettingsScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

/** Whisper-specific controls. Shared recognition-flow settings are configured globally. */
class WhisperSettingsActivity : AppComposeActivity() {
    private val viewModel: WhisperSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                WhisperSettingsScreen(
                    state = state,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenVadSettings = {
                        startActivity(Intent(this, VadModelSettingsActivity::class.java))
                    },
                    onThreadsChanged = viewModel::setThreads,
                    onHotwordsEnabledChanged = viewModel::setHotwordsEnabled,
                    onHotwordsChanged = viewModel::setHotwords,
                    onSaveHotwords = viewModel::saveHotwords,
                    onHotwordsScoreChanged = viewModel::setHotwordsScore
                )
            }
        }
    }
}
