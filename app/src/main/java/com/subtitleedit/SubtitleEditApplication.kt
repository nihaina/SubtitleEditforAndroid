package com.subtitleedit

import android.app.Application
import com.subtitleedit.di.AppDependencies
import com.subtitleedit.util.RuntimeLogManager

class SubtitleEditApplication : Application() {
    internal val dependencies: AppDependencies by lazy { AppDependencies(this) }

    override fun onCreate() {
        super.onCreate()
        RuntimeLogManager.install(this)
    }
}
