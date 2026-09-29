package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.feature.ui.AboutScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.UpdateChecker
import kotlinx.coroutines.launch

class AboutActivity : AppCompatActivity() {

    private var isCheckingForUpdates by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        setContent {
            SubtitleEditComposeTheme {
                AboutScreen(
                    versionName = versionName,
                    isCheckingForUpdates = isCheckingForUpdates,
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

    private fun checkForUpdates() {
        if (isCheckingForUpdates) return
        isCheckingForUpdates = true
        lifecycleScope.launch {
            try {
                when (val result = UpdateChecker.checkResult(this@AboutActivity)) {
                    is UpdateChecker.CheckResult.UpdateAvailable -> {
                        UpdateChecker.showUpdateDialog(this@AboutActivity, result.update)
                    }
                    UpdateChecker.CheckResult.UpToDate -> {
                        OverwritingToast.makeText(
                            this@AboutActivity,
                            "当前已是最新版本",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    UpdateChecker.CheckResult.Failure -> {
                        OverwritingToast.makeText(
                            this@AboutActivity,
                            "检测更新失败，请检查网络后重试",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } finally {
                isCheckingForUpdates = false
            }
        }
    }
}
