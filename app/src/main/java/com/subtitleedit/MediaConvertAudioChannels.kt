package com.subtitleedit

internal data class MediaConvertChannelPreset(val index: Int, val channels: Int, val layout: String)
internal data class MediaConvertChannelParameters(val channels: Int?, val layout: String? = null)

internal object MediaConvertAudioChannels {
    // Indices 0/1/2 retain their existing original/stereo/mono meanings.
    val presets = listOf(
        MediaConvertChannelPreset(2, 1, "mono"), MediaConvertChannelPreset(1, 2, "stereo"),
        MediaConvertChannelPreset(3, 3, "2.1"), MediaConvertChannelPreset(4, 4, "4.0"),
        MediaConvertChannelPreset(5, 5, "5.0"), MediaConvertChannelPreset(6, 6, "5.1"),
        MediaConvertChannelPreset(7, 7, "7.0"), MediaConvertChannelPreset(8, 8, "7.1")
    )
    private val vorbisLayouts = listOf("mono", "stereo", "3.0", "quad", "5.0", "5.1", "6.1", "7.1")
    private val layoutCounts = mapOf("mono" to 1, "stereo" to 2, "2.1" to 3, "3.0" to 3, "3.0(back)" to 3,
        "quad" to 4, "quad(side)" to 4, "4.0" to 4, "3.1" to 4, "4.1" to 5,
        "5.0" to 5, "5.0(side)" to 5, "5.1" to 6, "5.1(side)" to 6,
        "6.1" to 7, "6.1(back)" to 7, "7.0" to 7, "7.1" to 8, "7.1(wide)" to 8, "7.1(wide-side)" to 8)
    private val dolbyLayouts = listOf("mono", "stereo", "2.1", "3.0", "3.0(back)", "quad", "quad(side)",
        "4.0", "3.1", "4.1", "5.0(side)", "5.1(side)")
    private val supportedLayouts = mapOf(
        "libopus" to vorbisLayouts, "libvorbis" to vorbisLayouts, "flac" to vorbisLayouts,
        "ac3" to dolbyLayouts, "eac3" to dolbyLayouts,
        "tta" to listOf("mono", "stereo", "2.1", "quad", "5.1", "6.1(back)", "7.1(wide-side)"),
        "dca" to listOf("mono", "stereo", "quad(side)", "5.0(side)", "5.1(side)"),
        "alac" to listOf("mono", "stereo", "3.0", "4.0", "5.0", "5.1", "6.1(back)", "7.1(wide)"),
        "mlp" to listOf("mono", "stereo", "3.0(back)", "quad", "2.1", "3.0", "4.0", "5.0", "3.1", "4.1", "5.1"),
        "truehd" to listOf("mono", "stereo", "2.1", "3.0", "3.1", "4.0", "4.1", "5.0(side)", "5.1(side)"),
        "pcm_dvd" to listOf("mono", "stereo", "5.1(side)", "7.1")
    )

    fun layout(codec: String, preset: MediaConvertChannelPreset): String? {
        val supported = supportedLayouts[codec] ?: return preset.layout
        val candidates = when (preset.layout) {
            "5.0", "5.1" -> listOf(preset.layout, "${preset.layout}(side)")
            "7.1" -> listOf("7.1", "7.1(wide)", "7.1(wide-side)")
            else -> listOf(preset.layout)
        }
        return candidates.firstOrNull { it in supported }
    }

    fun available(codec: String, muxer: String): List<MediaConvertChannelPreset> {
        val counts = MediaConvertAudioCodecs.profile(codec, muxer).channels
        return presets.filter { (counts.isEmpty() || it.channels in counts) && layout(codec, it) != null }
    }

    // These headers record a channel count without a speaker-position mask.
    fun recordsLayout(codec: String, muxer: String): Boolean =
        muxer !in setOf("au", "ircam", "voc") &&
            (muxer != "matroska" || !codec.startsWith("pcm_") || codec in setOf("pcm_alaw", "pcm_mulaw", "pcm_dvd"))

    fun parameters(codec: String, muxer: String, index: Int, source: MediaConvertSource?): MediaConvertChannelParameters {
        val requested = presets.firstOrNull { it.index == index }
        val counts = MediaConvertAudioCodecs.profile(codec, muxer).channels
        val available = available(codec, muxer)
        val desiredCount = requested?.channels ?: source?.audioChannels
        val count = if (counts.isNotEmpty()) counts.lastOrNull { it <= (desiredCount ?: 2) } ?: counts.first() else requested?.channels
        if (requested != null && requested in available) {
            return MediaConvertChannelParameters(requested.channels, layout(codec, requested).takeIf { index > 2 })
        }
        val sourceLayout = source?.audioChannelLayout?.takeIf { it.isNotBlank() && it != "unknown" && !it.contains("channels") }
        val supported = supportedLayouts[codec]
        if (requested == null && sourceLayout != null && (count == null || count == source?.audioChannels) &&
            (supported == null || sourceLayout in supported)) {
            return MediaConvertChannelParameters(count, sourceLayout)
        }
        if (requested != null || (sourceLayout != null && supported != null && sourceLayout !in supported)) {
            val nativeFallback = supported?.filter { layout ->
                val size = layoutCounts[layout] ?: return@filter false
                size <= (desiredCount ?: 2) && (counts.isEmpty() || size in counts)
            }?.maxByOrNull { layoutCounts.getValue(it) }
            if (nativeFallback != null) return MediaConvertChannelParameters(layoutCounts.getValue(nativeFallback), nativeFallback.takeIf { index > 2 || requested == null })
            val fallback = available.lastOrNull { it.channels <= (desiredCount ?: 2) } ?: available.first()
            return MediaConvertChannelParameters(fallback.channels, layout(codec, fallback).takeIf { index > 2 || requested == null })
        }
        return MediaConvertChannelParameters(count)
    }
}
