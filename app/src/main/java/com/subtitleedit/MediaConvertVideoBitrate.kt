package com.subtitleedit

import kotlin.math.roundToLong

internal enum class MediaConvertBitrateMode { AUTO, ORIGINAL, CUSTOM, PRESET }

internal data class MediaConvertVideoBitrateSettings(
    val mode: MediaConvertBitrateMode = MediaConvertBitrateMode.AUTO,
    val customKbps: String = "",
    val presetKbps: Int = 2000
) {
    val isValid: Boolean get() = when (mode) {
        MediaConvertBitrateMode.CUSTOM -> customKbps.toIntOrNull() in MediaConvertVideoBitrate.MIN..MediaConvertVideoBitrate.MAX
        MediaConvertBitrateMode.PRESET -> presetKbps in MediaConvertVideoBitrate.presets
        else -> true
    }

    fun targetBitsPerSecond(sourceBitsPerSecond: Long?): Long? {
        require(isValid) { "Invalid video bitrate" }
        return when (mode) {
            MediaConvertBitrateMode.AUTO -> null
            MediaConvertBitrateMode.ORIGINAL -> requireNotNull(sourceBitsPerSecond?.takeIf { it > 0 }) { "Source video bitrate is unavailable" }
            MediaConvertBitrateMode.CUSTOM -> customKbps.toLong() * 1000L
            MediaConvertBitrateMode.PRESET -> presetKbps.toLong() * 1000L
        }
    }
}

internal object MediaConvertVideoBitrate {
    const val MIN = 128
    const val MAX = 999999
    val presets = listOf(250, 2000, 5000, 8000, 16000, 25000)

    /** Stream bit_rate and Matroska BPS tags are video-only; container bitrate is not a substitute. */
    fun sourceBitsPerSecond(vararg candidates: String?): Long? = candidates.firstNotNullOfOrNull {
        it?.trim()?.toLongOrNull()?.takeIf { value -> value > 0 }
    }

    fun label(bitsPerSecond: Long) = "${(bitsPerSecond / 1000.0).roundToLong().coerceAtLeast(1)} kbps"
    fun ffmpegValue(bitsPerSecond: Long) = if (bitsPerSecond % 1000L == 0L) "${bitsPerSecond / 1000}k" else bitsPerSecond.toString()
}
