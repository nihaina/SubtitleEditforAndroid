package com.subtitleedit

/**
 * Resolves the "auto" codec choice to stream copy when the output would be a bit-for-bit
 * re-encode of the source. Remuxing is orders of magnitude faster than encoding, so a
 * container change alone should never run an encoder.
 */
internal object MediaConvertStreamCopy {
    // FFprobe reports decoder names; the format tables list encoder names.
    private val videoAliases = mapOf(
        "h264" to "libx264", "hevc" to "libx265", "vp8" to "libvpx", "vp9" to "libvpx-vp9",
        "av1" to "libaom-av1", "theora" to "libtheora", "msmpeg4" to "msmpeg4v3", "flv1" to "flv"
    )
    private val audioAliases = mapOf(
        "mp3" to "libmp3lame", "vorbis" to "libvorbis", "opus" to "libopus", "dts" to "dca",
        "amr_nb" to "libopencore_amrnb", "amr_wb" to "libvo_amrwbenc", "speex" to "libspeex",
        "gsm" to "libgsm", "gsm_ms" to "libgsm_ms"
    )

    fun videoEncoderName(codec: String?): String? = codec?.let { videoAliases[it] ?: it }
    fun audioEncoderName(codec: String?): String? = codec?.let { audioAliases[it] ?: it }

    fun copiesVideo(format: MediaConvertFormatInfo, options: MediaConvertOptions, source: MediaConvertSource?,
                    subtitle: MediaConvertSubtitleInput? = null): Boolean {
        if (format.isAudioOnly || options.selectedVideoCodec != "auto") return false
        if (source?.hasVideo != true) return false
        if (videoEncoderName(source.videoCodecName) !in format.videoCodecs) return false
        if (subtitle?.type == MediaConvertSubtitleType.BURN_IN) return false
        if (options.resolution.presetIndex != 0) return false
        if (options.videoBitrate.mode != MediaConvertBitrateMode.AUTO || options.selectedQualityId != "-1") return false
        val settings = options.videoSettings
        return settings.frameRate.isEmpty() && settings.rotation == MediaConvertRotation.NONE &&
            !settings.flipHorizontal && !settings.flipVertical && settings.crf == null &&
            settings.sizeLimitPercent == null && settings.keyframeSeconds.isEmpty() &&
            settings.deinterlace == MediaConvertDeinterlace.OFF && settings.filter == MediaConvertVideoFilter.NONE &&
            settings.pixelFormat in setOf("auto", "original")
    }

    fun copiesAudio(format: MediaConvertFormatInfo, options: MediaConvertOptions, source: MediaConvertSource?): Boolean {
        if (options.selectedAudioCodec != "auto") return false
        if (source?.hasAudio != true) return false
        if (audioEncoderName(source.audioCodecName) !in format.audioCodecs) return false
        return options.audioBitrate.isEmpty() && options.sampleRateIndex == 0 && options.channelIndex == 0 &&
            options.audioVolumePercent == MediaConvertAudioVolume.DEFAULT && options.audioVbrQuality == MediaConvertAudioVbr.OFF
    }
}
