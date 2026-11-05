package com.subtitleedit

import kotlin.math.abs
import kotlin.math.roundToInt

internal data class MediaConvertVideoSize(val width: Int, val height: Int, val sampleAspectRatio: Double = 1.0) {
    val label: String get() = "${width}×${height}"
    val aspectRatio: Double get() = width.toDouble() * sampleAspectRatio / height

    companion object {
        fun fromStream(width: Int, height: Int, sampleAspect: String?, rotation: Double): MediaConvertVideoSize? {
            if (width <= 0 || height <= 0) return null
            val parts = sampleAspect?.split(':')
            val sar = parts?.firstOrNull()?.toDoubleOrNull()?.let { numerator ->
                parts.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0 }?.let { numerator / it }
            }?.takeIf { it.isFinite() && it > 0 } ?: 1.0
            val angle = rotation.takeIf { it.isFinite() } ?: 0.0
            return if (abs((angle / 90).roundToInt()) % 2 == 1) MediaConvertVideoSize(height, width, 1.0 / sar)
                else MediaConvertVideoSize(width, height, sar)
        }
    }
}

internal enum class MediaConvertFitMode { FIT, FILL, STRETCH, CENTER }

internal data class MediaConvertResolutionSettings(
    val presetIndex: Int = 0,
    val customWidth: Int? = null,
    val customHeight: Int? = null,
    val aspectLocked: Boolean = false,
    val fitMode: MediaConvertFitMode = MediaConvertFitMode.FIT
) {
    val size: MediaConvertVideoSize? get() = if (presetIndex == MediaConvertResolution.CUSTOM) {
        if (customWidth != null && customHeight != null) MediaConvertVideoSize(customWidth, customHeight) else null
    } else MediaConvertResolution.presets.getOrNull(presetIndex)

    val isValid: Boolean get() = if (presetIndex == MediaConvertResolution.CUSTOM) {
        MediaConvertResolution.validDimension(customWidth) && MediaConvertResolution.validDimension(customHeight)
    } else presetIndex in MediaConvertResolution.presets.indices
}

internal object MediaConvertResolution {
    const val CUSTOM = -1
    const val MIN = 2
    const val MAX = 4096

    // Preserve the existing preset indices; new portrait/landscape sizes follow them.
    val presets: List<MediaConvertVideoSize?> = listOf(null,
        MediaConvertVideoSize(3840, 2160), MediaConvertVideoSize(2560, 1440),
        MediaConvertVideoSize(1920, 1080), MediaConvertVideoSize(1280, 720),
        MediaConvertVideoSize(854, 480), MediaConvertVideoSize(640, 360), MediaConvertVideoSize(426, 240),
        MediaConvertVideoSize(2160, 3840), MediaConvertVideoSize(1440, 2560),
        MediaConvertVideoSize(1080, 1920), MediaConvertVideoSize(720, 1280),
        MediaConvertVideoSize(480, 720), MediaConvertVideoSize(480, 640),
        MediaConvertVideoSize(240, 320), MediaConvertVideoSize(144, 176), MediaConvertVideoSize(128, 160),
        MediaConvertVideoSize(720, 480), MediaConvertVideoSize(640, 480),
        MediaConvertVideoSize(320, 240), MediaConvertVideoSize(176, 144), MediaConvertVideoSize(160, 128)
    )

    fun validDimension(value: Int?) = value != null && value in MIN..MAX && value % 2 == 0

    /** Return null when keeping the ratio would push the other side outside the allowed range. */
    fun linkedDimension(value: String, ratio: Double, editingWidth: Boolean): String {
        val dimension = value.toIntOrNull()?.takeIf { it in MIN..MAX } ?: return ""
        if (!ratio.isFinite() || ratio <= 0) return ""
        val result = if (editingWidth) dimension / ratio else dimension * ratio
        if (!result.isFinite() || result < MIN - 1 || result > MAX + 1) return ""
        val even = (result / 2).roundToInt() * 2
        return even.takeIf(::validDimension)?.toString().orEmpty()
    }

    fun filter(settings: MediaConvertResolutionSettings): String {
        require(settings.isValid) { "Invalid video resolution" }
        val size = settings.size ?: return "scale=trunc(iw/2)*2:trunc(ih/2)*2"
        val w = size.width
        val h = size.height
        return when (settings.fitMode) {
            MediaConvertFitMode.FIT -> "scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2:reset_sar=1,pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1"
            MediaConvertFitMode.FILL -> "scale=$w:$h:force_original_aspect_ratio=increase:force_divisible_by=2:reset_sar=1,crop=$w:$h:(iw-ow)/2:(ih-oh)/2,setsar=1"
            MediaConvertFitMode.STRETCH -> "scale=$w:$h,setsar=1"
            MediaConvertFitMode.CENTER -> "scale=trunc(iw*sar/2)*2:trunc(ih/2)*2,setsar=1,crop=min(iw\\,$w):min(ih\\,$h):(iw-ow)/2:(ih-oh)/2,pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1"
        }
    }
}
