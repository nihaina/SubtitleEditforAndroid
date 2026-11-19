package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.subtitleedit.translation.FoxletPageTranslation
import com.subtitleedit.ui.settings.PageTranslationSettingsScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

class PageTranslationSettingsActivity : AppComposeActivity() {
    private val viewModel: PageTranslationSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.state.collectAsState()
            val configuration = LocalConfiguration.current
            val systemLocale = remember(configuration) { FoxletPageTranslation.systemLocale() }
            SubtitleEditComposeTheme {
                PageTranslationSettingsScreen(
                    state = state,
                    systemLocale = systemLocale,
                    onSourceChanged = viewModel::selectSource,
                    onTargetChanged = viewModel::selectTarget,
                    onDownload = viewModel::download,
                    onDownloadMissing = { viewModel.downloadMissing(systemLocale) },
                    onCancelDownload = viewModel::cancelDownload,
                    onDelete = viewModel::delete,
                    onRetry = viewModel::refreshModels,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                )
            }
        }
    }
}
