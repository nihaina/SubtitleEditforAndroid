package com.subtitleedit

import java.math.BigDecimal
import kotlin.math.roundToInt

internal object MediaConvertAudioVolume {
    const val DEFAULT = 100
    const val MIN = 0
    const val MAX = 1000

    fun filter(percent: Int): String? {
        require(percent in MIN..MAX) { "Invalid audio volume" }
        return if (percent == DEFAULT) null else "volume=${BigDecimal.valueOf(percent.toLong(), 2).stripTrailingZeros().toPlainString()}"
    }

    // The middle of the slider is the original volume; either half has its own linear scale.
    fun sliderPosition(percent: Int): Float = if (percent <= DEFAULT) {
        percent.coerceIn(MIN, DEFAULT) / 200f
    } else 0.5f + (percent.coerceAtMost(MAX) - DEFAULT) / 1800f

    fun percentAt(position: Float): Int = if (position <= 0.5f) {
        (position.coerceAtLeast(0f) * 200).roundToInt()
    } else (DEFAULT + (position.coerceAtMost(1f) - 0.5f) * 1800).roundToInt()
}
