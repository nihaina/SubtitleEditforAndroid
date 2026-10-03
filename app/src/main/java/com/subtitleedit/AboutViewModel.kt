package com.subtitleedit

import android.app.Application
import android.widget.Toast
import com.subtitleedit.util.UpdateChecker

internal data class AboutUiState(val isCheckingForUpdates: Boolean = false)

/**
 * About page state. The update check itself stays in the Activity because
 * [UpdateChecker.checkResult] and its dialog require an Activity; only the
 * "checking" flag and result messages live here.
 */
internal class AboutViewModel(
    application: Application
) : AppViewModel<AboutUiState, Nothing>(application, AboutUiState()) {
    val versionName: String = application.packageManager
        .getPackageInfo(application.packageName, 0).versionName.orEmpty()

    /** Returns false when a check is already running. */
    fun beginUpdateCheck(): Boolean {
        if (currentState.isCheckingForUpdates) return false
        setState { copy(isCheckingForUpdates = true) }
        return true
    }

    fun endUpdateCheck() = setState { copy(isCheckingForUpdates = false) }

    fun onUpToDate() = toast(R.string.about_up_to_date)

    fun onCheckFailed() = toast(R.string.about_update_check_failed, duration = Toast.LENGTH_LONG)
}
