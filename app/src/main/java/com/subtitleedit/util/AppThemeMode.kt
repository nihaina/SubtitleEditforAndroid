package com.subtitleedit.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/** Resolve system mode before applying AppCompat's night mode. */
object AppThemeMode {
    fun apply(context: Context, selectedMode: String) {
        val systemIsNight = when (
            (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).nightMode
        ) {
            UiModeManager.MODE_NIGHT_YES -> true
            UiModeManager.MODE_NIGHT_NO -> false
            else -> (context.applicationContext.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        apply(selectedMode, systemIsNight)
    }

    fun onSystemConfigurationChanged(context: Context, configuration: Configuration) {
        if (SettingsManager.getInstance(context).getThemeMode() != SettingsManager.THEME_SYSTEM) return
        apply(
            SettingsManager.THEME_SYSTEM,
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        )
    }

    internal fun resolveNightMode(selectedMode: String, systemIsNight: Boolean): Int =
        when (selectedMode) {
            SettingsManager.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            SettingsManager.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> if (systemIsNight) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
        }

    private fun apply(selectedMode: String, systemIsNight: Boolean) {
        val mode = resolveNightMode(selectedMode, systemIsNight)
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
