package com.subtitleedit.player

import kotlin.math.log10

/** RMS measurements of decoded media, displayed on a -60..0 dBFS meter. */
internal object PlayerLoudness {
    const val FILTER_LABEL = "player-loudness"
    const val RMS_PROPERTY = "af-metadata/$FILTER_LABEL/lavfi.astats.Overall.RMS_level"
    const val SAMPLE_INTERVAL_MS = 100L
    private const val FLOOR_DB = -60.0
    private const val ANALYSIS_FILTER =
        "@$FILTER_LABEL:lavfi=[astats=metadata=1:reset=1:measure_perchannel=none:measure_overall=RMS_level]"

    /** The labeled analysis filter follows EQ and passes audio through unchanged. */
    fun audioFilters(equalizerFilter: String, enabled: Boolean): String = when {
        !enabled -> equalizerFilter
        equalizerFilter.isBlank() -> ANALYSIS_FILTER
        else -> "$equalizerFilter,$ANALYSIS_FILTER"
    }

    /** mpv v0.41 uses (volume / 100)^3 downstream of filters; include that gain. */
    fun level(rmsDbText: String?, volumePercent: Int): Float {
        val rmsDb = rmsDbText?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return 0f
        if (volumePercent <= 0) return 0f
        val gainDb = 60.0 * log10(volumePercent.coerceAtMost(200) / 100.0)
        return ((rmsDb + gainDb - FLOOR_DB) / -FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
    }
}
