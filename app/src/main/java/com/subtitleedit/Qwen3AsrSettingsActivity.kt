package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.feature.ui.Qwen3AsrSettingsScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

class Qwen3AsrSettingsActivity : AppComposeActivity() {
    private val viewModel: Qwen3AsrSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                Qwen3AsrSettingsScreen(
                    settingsManager = viewModel.settingsManager,
                    forcedAlignmentAvailable = state.forcedAlignmentAvailable,
                    refreshKey = state.refreshKey,
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
        viewModel.refresh()
    }
}
