package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.ui.LogScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

class LogActivity : AppComposeActivity() {
    private val viewModel: LogViewModel by viewModels()

    private val exportDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let(viewModel::exportLogToDirectory)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectEvents(viewModel.events) { event ->
            when (event) {
                LogEvent.PickExportDirectory -> exportDirLauncher.launch(null)
            }
        }
        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                LogScreen(
                    sections = state.sections,
                    pageOptions = state.pageOptions,
                    pageFilter = state.pageFilter,
                    displayMode = state.displayMode,
                    infoText = state.infoText,
                    isRefreshing = state.isRefreshing,
                    isExportEnabled = state.isExportEnabled,
                    showClearedPlaceholder = state.showClearedPlaceholder,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onRefresh = { viewModel.refreshLog() },
                    onExport = viewModel::requestExport,
                    onClear = viewModel::clearLog,
                    onDisplayModeChange = viewModel::setDisplayMode,
                    onPageFilterChange = viewModel::setPageFilter
                )
            }
        }
    }
}
