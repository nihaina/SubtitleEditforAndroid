package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.ui.settings.SettingsScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.FileUtils

/** Settings screen host. Settings and cache operations live in [SettingsViewModel]. */
class SettingsActivity : AppComposeActivity() {
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                SettingsScreen(
                    state = state,
                    encodings = FileUtils.SUPPORTED_ENCODINGS,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onEncodingSelected = viewModel::selectEncoding,
                    onThemeSelected = viewModel::selectTheme,
                    onCheckUpdatesChanged = viewModel::setCheckUpdatesOnStartup,
                    onPreserveDirectoriesChanged = viewModel::setPreserveOutputDirectories,
                    onLoopSelectedChanged = viewModel::setLoopSelectedSubtitle,
                    onSelectPlayingChanged = viewModel::setSelectPlayingSubtitle,
                    onOpenAiSettings = { open(AiSettingsActivity::class.java) },
                    onOpenModelManagement = { open(ModelManagementActivity::class.java) },
                    onOpenTtsSettings = { open(TtsSettingsActivity::class.java) },
                    onOpenLogs = { open(LogActivity::class.java) },
                    onOpenAbout = { open(AboutActivity::class.java) },
                    onCacheClear = viewModel::clearCache,
                    onEmptyCacheClear = viewModel::onEmptyCacheClear
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun open(activity: Class<*>) {
        startActivity(Intent(this, activity))
    }
}
