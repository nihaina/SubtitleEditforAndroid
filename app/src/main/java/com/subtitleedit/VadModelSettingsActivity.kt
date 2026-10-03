package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.feature.ui.VadModelSettingsScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

class VadModelSettingsActivity : AppComposeActivity() {
    private val viewModel: VadModelSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                VadModelSettingsScreen(
                    state = state,
                    onStateChange = viewModel::save,
                    onBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }
}
