package com.subtitleedit

import android.app.Application
import android.content.res.Configuration
import com.subtitleedit.di.AppDependencies
import com.subtitleedit.util.AppThemeMode
import com.subtitleedit.util.RuntimeLogManager
import com.subtitleedit.util.SettingsManager

class SubtitleEditApplication : Application() {
    internal val dependencies: AppDependencies by lazy { AppDependencies(this) }

    override fun onCreate() {
        super.onCreate()
        AppThemeMode.apply(this, SettingsManager.getInstance(this).getThemeMode())
        RuntimeLogManager.install(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppThemeMode.onSystemConfigurationChanged(this, newConfig)
    }
}
