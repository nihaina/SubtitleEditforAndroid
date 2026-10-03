package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.feature.ui.AboutScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.UpdateChecker
import kotlinx.coroutines.launch

class AboutActivity : AppComposeActivity() {
    private val viewModel: AboutViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                AboutScreen(
                    versionName = viewModel.versionName,
                    isCheckingForUpdates = state.isCheckingForUpdates,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() },
                    onCheckForUpdates = ::checkForUpdates,
                    onOpenGithub = {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/nihaina/SubtitleEditforAndroid")
                            )
                        )
                    }
                )
            }
        }
    }

    // UpdateChecker needs an Activity (dialog host), so the short network check stays
    // on lifecycleScope; the finally block always clears the VM flag, even on recreation.
    private fun checkForUpdates() {
        if (!viewModel.beginUpdateCheck()) return
        lifecycleScope.launch {
            try {
                when (val result = UpdateChecker.checkResult(this@AboutActivity)) {
                    is UpdateChecker.CheckResult.UpdateAvailable ->
                        UpdateChecker.showUpdateDialog(this@AboutActivity, result.update)
                    UpdateChecker.CheckResult.UpToDate -> viewModel.onUpToDate()
                    UpdateChecker.CheckResult.Failure -> viewModel.onCheckFailed()
                }
            } finally {
                viewModel.endUpdateCheck()
            }
        }
    }
}
