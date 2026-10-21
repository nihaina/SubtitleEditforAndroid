package com.subtitleedit

import android.app.Application
import com.subtitleedit.util.SettingsManager

internal data class VocalSeparationSettingsUiState(
    val intraOpThreads: Int = 6,
    val chunkParallelEnabled: Boolean = false,
    val graphOptimizationEnabled: Boolean = true,
    val cpuArenaEnabled: Boolean = true
)

internal class VocalSeparationSettingsViewModel(
    application: Application
) : AppViewModel<VocalSeparationSettingsUiState, Nothing>(
    application,
    SettingsManager.getInstance(application).let {
        VocalSeparationSettingsUiState(
            intraOpThreads = it.getDemixOrtIntraOpThreads(),
            chunkParallelEnabled = it.isDemixOrtChunkParallelEnabled(),
            graphOptimizationEnabled = it.isDemixOrtGraphOptimizationEnabled(),
            cpuArenaEnabled = it.isDemixOrtCpuArenaEnabled()
        )
    }
) {
    private val settings = SettingsManager.getInstance(application)

    fun setIntraOpThreads(threadCount: Int) {
        val normalized = threadCount.coerceIn(1, 8)
        setState { copy(intraOpThreads = normalized) }
        settings.setDemixOrtIntraOpThreads(normalized)
    }

    fun setChunkParallelEnabled(enabled: Boolean) {
        setState { copy(chunkParallelEnabled = enabled) }
        settings.setDemixOrtChunkParallelEnabled(enabled)
    }

    fun setGraphOptimizationEnabled(enabled: Boolean) {
        setState { copy(graphOptimizationEnabled = enabled) }
        settings.setDemixOrtGraphOptimizationEnabled(enabled)
    }

    fun setCpuArenaEnabled(enabled: Boolean) {
        setState { copy(cpuArenaEnabled = enabled) }
        settings.setDemixOrtCpuArenaEnabled(enabled)
    }
}
