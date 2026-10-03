package com.subtitleedit

import android.app.Application
import com.subtitleedit.util.SettingsManager

internal data class VocalSeparationSettingsUiState(
    val graphOptimizationEnabled: Boolean = false,
    val cpuArenaEnabled: Boolean = false
)

internal class VocalSeparationSettingsViewModel(
    application: Application
) : AppViewModel<VocalSeparationSettingsUiState, Nothing>(
    application,
    SettingsManager.getInstance(application).let {
        VocalSeparationSettingsUiState(
            graphOptimizationEnabled = it.isDemixOrtGraphOptimizationEnabled(),
            cpuArenaEnabled = it.isDemixOrtCpuArenaEnabled()
        )
    }
) {
    private val settings = SettingsManager.getInstance(application)

    fun setGraphOptimizationEnabled(enabled: Boolean) {
        setState { copy(graphOptimizationEnabled = enabled) }
        settings.setDemixOrtGraphOptimizationEnabled(enabled)
    }

    fun setCpuArenaEnabled(enabled: Boolean) {
        setState { copy(cpuArenaEnabled = enabled) }
        settings.setDemixOrtCpuArenaEnabled(enabled)
    }
}
