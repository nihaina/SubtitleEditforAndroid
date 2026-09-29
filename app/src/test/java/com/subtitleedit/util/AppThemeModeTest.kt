package com.subtitleedit.util

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeModeTest {
    @Test
    fun followingLightSystemUsesTheSameResourcesAsExplicitLightMode() {
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_NO,
            AppThemeMode.resolveNightMode(SettingsManager.THEME_SYSTEM, systemIsNight = false)
        )
    }

    @Test
    fun followingDarkSystemUsesTheSameResourcesAsExplicitDarkMode() {
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_YES,
            AppThemeMode.resolveNightMode(SettingsManager.THEME_SYSTEM, systemIsNight = true)
        )
    }

    @Test
    fun explicitChoiceOverridesSystemMode() {
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_NO,
            AppThemeMode.resolveNightMode(SettingsManager.THEME_LIGHT, systemIsNight = true)
        )
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_YES,
            AppThemeMode.resolveNightMode(SettingsManager.THEME_DARK, systemIsNight = false)
        )
    }
}
