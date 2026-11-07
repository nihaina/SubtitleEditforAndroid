package com.subtitleedit

/** The reference bitrate bands are LAME's V0–V9 quality levels, not target bitrates. */
internal object MediaConvertAudioVbr {
    const val OFF = -1
    val qualities = 0..9
    private val bitrateBands = listOf(
        "220…260", "190…250", "170…210", "150…195", "140…185",
        "120…150", "100…130", "80…120", "70…105", "45…85"
    )

    fun supports(codec: String) = codec == "libmp3lame"
    fun isValid(quality: Int) = quality == OFF || quality in qualities
    fun bitrateBand(quality: Int): String? = bitrateBands.getOrNull(quality)

    fun activeQuality(format: MediaConvertFormatInfo, options: MediaConvertOptions): Int? {
        if (!format.isAudioOnly || !supports(format.audioEncoder(options.selectedAudioCodec))) return null
        require(isValid(options.audioVbrQuality)) { "Invalid audio VBR quality" }
        return options.audioVbrQuality.takeIf { it != OFF }
    }
}
