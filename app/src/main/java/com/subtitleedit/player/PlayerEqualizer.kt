package com.subtitleedit.player

/**
 * Ten-band curve carried by a lavfi graph. Brackets quote the graph for mpv; the inner
 * single quotes preserve gain-expression commas for FFmpeg's graph parser.
 */
internal object PlayerEqualizer {
    const val BAND_COUNT = 10
    const val MIN_GAIN = -12f
    const val MAX_GAIN = 12f

    val FREQUENCIES: List<Double> = listOf(
        31.5, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    )
    val FLAT: List<Float> = List(BAND_COUNT) { 0f }

    data class Preset(val id: String, val gains: List<Float>)

    val PRESETS: List<Preset> = listOf(
        Preset("flat", FLAT),
        Preset("bass", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("vocal", listOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 2f, 0f, -1f)),
        Preset("treble", listOf(0f, 0f, 0f, 0f, 1f, 2f, 3f, 4f, 5f, 5f)),
        Preset("asmr", listOf(3f, 2f, 1f, 2f, 3f, 2f, 1f, 0f, -1f, -2f)),
        Preset("night", listOf(-4f, -3f, -2f, -1f, 0f, 0f, -1f, -2f, -3f, -4f))
    )

    fun clamp(gain: Float): Float = if (gain.isFinite()) gain.coerceIn(MIN_GAIN, MAX_GAIN) else 0f

    fun normalize(gains: List<Float>): List<Float> =
        List(BAND_COUNT) { index -> clamp(gains.getOrNull(index) ?: 0f) }

    fun isFlat(gains: List<Float>): Boolean = normalize(gains).all { it == 0f }

    /**
     * mpv `af` value for the state, or an empty string when the filter should be removed.
     * The table is pinned at 0 Hz and 20 kHz so the curve stays flat outside the band range.
     */
    fun afFilter(state: PlayerEqualizerState): String {
        if (!state.enabled || isFlat(state.gains)) return ""
        val gains = normalize(state.gains)
        val entries = buildList {
            add(entry(0.0, gains.first()))
            FREQUENCIES.forEachIndexed { index, frequency -> add(entry(frequency, gains[index])) }
            add(entry(20000.0, gains.last()))
        }
        return "lavfi=[firequalizer=gain_entry='${entries.joinToString(";")}']"
    }

    private fun entry(frequency: Double, gain: Float): String =
        "entry(${formatNumber(frequency)},${formatNumber(gain.toDouble())})"

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(java.util.Locale.US, "%.1f", value)

    fun formatFrequency(hertz: Double): String =
        if (hertz >= 1000.0) {
            val khz = hertz / 1000.0
            (if (khz == khz.toLong().toDouble()) khz.toLong().toString()
            else String.format(java.util.Locale.US, "%.1f", khz)) + "k"
        } else {
            hertz.toLong().toString()
        }

    fun formatGain(gain: Float): String =
        "${if (gain > 0f) "+" else ""}${if (gain == gain.toLong().toFloat()) gain.toLong() else gain}dB"
}
