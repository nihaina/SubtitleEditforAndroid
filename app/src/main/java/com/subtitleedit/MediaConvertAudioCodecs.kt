package com.subtitleedit

import kotlin.math.abs

internal data class MediaConvertAudioProfile(
    val sampleRates: List<Int> = emptyList(),
    val channels: List<Int> = emptyList(),
    val adjustableBitrate: Boolean = true,
    val bitrateValues: List<Int> = emptyList(),
    val defaultBitrate: Int? = null,
    val experimental: Boolean = false,
    val sampleRateRange: IntRange? = null
)

internal data class MediaConvertAudioParameters(val sampleRate: Int?, val channels: Int?, val bitrate: Long?, val channelLayout: String? = null)

internal object MediaConvertAudioCodecs {
    val pcmInteger = listOf("pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s24be", "pcm_s32le", "pcm_s32be")
    val pcmFloat = listOf("pcm_f32le", "pcm_f64le")
    val pcmFloatBigEndian = listOf("pcm_f32be", "pcm_f64be")
    val riffAudio = listOf("aac", "ac3", "eac3", "dca", "flac", "mp2", "libmp3lame", "pcm_alaw", "pcm_mulaw", "pcm_u8", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_f64le", "libspeex", "libvorbis", "wmav1", "wmav2", "adpcm_swf", "adpcm_ima_wav", "adpcm_ms", "libopencore_amrnb", "libvo_amrwbenc")
    val matroska = listOf("aac", "ac3", "eac3", "adpcm_swf", "libopencore_amrnb", "libvo_amrwbenc", "dca", "flac", "mp2", "libmp3lame", "libopus", "pcm_alaw", "pcm_mulaw", "pcm_u8") +
        pcmInteger + pcmFloat + listOf("libspeex", "real_144", "tta", "libvorbis", "wavpack", "wmav1", "wmav2", "alac", "truehd", "mlp", "adpcm_ima_wav", "adpcm_ms")
    val mp4 = listOf("aac", "libmp3lame", "ac3", "eac3", "alac", "flac", "libopus", "dca", "truehd", "libvorbis") + pcmInteger + pcmFloat + pcmFloatBigEndian
    val mov = listOf("aac", "libmp3lame", "ac3", "eac3", "alac", "mp2", "dca", "libvorbis", "pcm_alaw", "pcm_mulaw", "pcm_s8", "pcm_u8") +
        pcmInteger + pcmFloat + pcmFloatBigEndian + listOf("adpcm_ima_qt", "adpcm_ima_wav", "adpcm_ms", "adpcm_swf", "libspeex", "libopencore_amrnb", "libvo_amrwbenc", "nellymoser")

    val baselineEncoders = (matroska + mp4 + mov + riffAudio + listOf("pcm_dvd", "libvorbis", "libopus", "wmav2")).toSet() -
        setOf("libspeex", "libopencore_amrnb", "libvo_amrwbenc")

    private val monoStereo = listOf(1, 2)
    private val amrNbBitrates = listOf(4750, 5150, 5900, 6700, 7400, 7950, 10200, 12200)
    private val amrWbBitrates = listOf(6600, 8850, 12650, 14250, 15850, 18250, 19850, 23050, 23850)
    private val dtsBitrates = listOf(32000, 56000, 64000, 96000, 112000, 128000, 192000, 224000, 256000, 320000,
        384000, 448000, 512000, 576000, 640000, 768000, 960000, 1024000, 1152000, 1280000, 1344000, 1408000, 1411200, 1472000, 1536000)
    private val profiles = mapOf(
        "aac" to MediaConvertAudioProfile(listOf(7350, 8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000), (1..8).toList()),
        "libmp3lame" to MediaConvertAudioProfile(listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000), monoStereo),
        "libopus" to MediaConvertAudioProfile(listOf(8000, 12000, 16000, 24000, 48000), (1..8).toList()),
        "libopencore_amrnb" to MediaConvertAudioProfile(listOf(8000), listOf(1), bitrateValues = amrNbBitrates, defaultBitrate = 12200),
        "libvo_amrwbenc" to MediaConvertAudioProfile(listOf(16000), listOf(1), bitrateValues = amrWbBitrates, defaultBitrate = 23850),
        "libspeex" to MediaConvertAudioProfile(listOf(8000, 16000, 32000), monoStereo, defaultBitrate = 27000),
        "libvorbis" to MediaConvertAudioProfile(sampleRateRange = 1..200000),
        "real_144" to MediaConvertAudioProfile(listOf(8000), listOf(1), adjustableBitrate = false),
        "adpcm_swf" to MediaConvertAudioProfile(listOf(11025, 22050, 44100), monoStereo, adjustableBitrate = false),
        "adpcm_ima_wav" to MediaConvertAudioProfile(channels = monoStereo, adjustableBitrate = false),
        "adpcm_ms" to MediaConvertAudioProfile(channels = monoStereo, adjustableBitrate = false),
        "adpcm_ima_qt" to MediaConvertAudioProfile(channels = monoStereo, adjustableBitrate = false),
        "nellymoser" to MediaConvertAudioProfile(listOf(8000, 11025, 16000, 22050, 44100), listOf(1), adjustableBitrate = false),
        "wmav1" to MediaConvertAudioProfile(listOf(8000, 11025, 16000, 22050, 32000, 44100, 48000), monoStereo),
        "wmav2" to MediaConvertAudioProfile(listOf(8000, 11025, 16000, 22050, 32000, 44100, 48000), monoStereo),
        "mp2" to MediaConvertAudioProfile(listOf(16000, 22050, 24000, 32000, 44100, 48000), monoStereo),
        "ac3" to MediaConvertAudioProfile(listOf(32000, 44100, 48000), (1..6).toList()),
        "eac3" to MediaConvertAudioProfile(listOf(32000, 44100, 48000), (1..6).toList()),
        "dca" to MediaConvertAudioProfile(listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000), listOf(1, 2, 4, 5, 6), defaultBitrate = 1411200, experimental = true),
        "truehd" to MediaConvertAudioProfile(listOf(44100, 48000, 88200, 96000, 176400, 192000), (1..6).toList(), adjustableBitrate = false, experimental = true),
        "mlp" to MediaConvertAudioProfile(listOf(44100, 48000, 88200, 96000, 176400, 192000), (1..6).toList(), adjustableBitrate = false, experimental = true),
        "pcm_dvd" to MediaConvertAudioProfile(listOf(48000, 96000), listOf(1, 2, 6, 8), adjustableBitrate = false),
        "flac" to MediaConvertAudioProfile(channels = (1..8).toList(), adjustableBitrate = false),
        "alac" to MediaConvertAudioProfile(channels = (1..8).toList(), adjustableBitrate = false),
        "tta" to MediaConvertAudioProfile(adjustableBitrate = false),
        "wavpack" to MediaConvertAudioProfile(adjustableBitrate = false)
    )

    fun profile(codec: String, muxer: String): MediaConvertAudioProfile {
        val base = profiles[codec] ?: MediaConvertAudioProfile(adjustableBitrate = !codec.startsWith("pcm_"))
        if (muxer != "flv") return base
        return when (codec) {
            "libspeex" -> base.copy(sampleRates = listOf(16000), channels = listOf(1))
            "libmp3lame" -> base.copy(sampleRates = listOf(11025, 22050, 44100, 48000), channels = monoStereo)
            "pcm_s16le", "pcm_u8", "pcm_alaw", "pcm_mulaw", "adpcm_swf" -> base.copy(sampleRates = listOf(11025, 22050, 44100), channels = monoStereo)
            else -> base
        }
    }

    fun parameters(codec: String, muxer: String, options: MediaConvertOptions, source: MediaConvertSource?): MediaConvertAudioParameters {
        val profile = profile(codec, muxer)
        val explicitRate = MediaConvertCommand.sampleRates.getOrNull(options.sampleRateIndex)?.takeIf { it > 0 }
        val requestedRate = explicitRate ?: source?.audioSampleRate
        val rate = if (profile.sampleRates.isNotEmpty()) {
            profile.sampleRates.minBy { abs(it - (requestedRate ?: 44100)) }
        } else if (requestedRate != null && profile.sampleRateRange != null && requestedRate !in profile.sampleRateRange) {
            requestedRate.coerceIn(profile.sampleRateRange)
        } else explicitRate
        val channelParameters = MediaConvertAudioChannels.parameters(codec, muxer, options.channelIndex, source)
        val channels = channelParameters.channels
        val requestedBitrate = if (profile.adjustableBitrate) {
            MediaConvertAudioBitrate.targetBitsPerSecond(options.audioBitrate, source?.audioBitrateBps)
        } else null
        val bitrate = when {
            !profile.adjustableBitrate -> null
            profile.bitrateValues.isNotEmpty() -> profile.bitrateValues.minBy { abs(it.toLong() - (requestedBitrate ?: profile.defaultBitrate!!.toLong())) }.toLong()
            codec == "libspeex" -> (requestedBitrate ?: profile.defaultBitrate!!.toLong()).coerceIn(2150, if (rate == 8000) 24600 else 44000)
            codec == "dca" -> dtsBitrate(rate ?: 48000, channels ?: 2, requestedBitrate ?: 1411200L)
            codec == "mp2" && requestedBitrate != null -> {
                val kbps = if ((rate ?: 44100) >= 32000) listOf(32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
                    else listOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
                kbps.minBy { abs(it * 1000L - requestedBitrate) } * 1000L
            }
            else -> requestedBitrate
        }
        return MediaConvertAudioParameters(rate, channels, bitrate, channelParameters.layout)
    }

    fun sampleRateIndices(codec: String, muxer: String): List<Int> {
        val profile = profile(codec, muxer)
        return MediaConvertCommand.sampleRates.indices.filter { index ->
            val rate = MediaConvertCommand.sampleRates[index]
            index == 0 || ((profile.sampleRates.isEmpty() || rate in profile.sampleRates) &&
                (profile.sampleRateRange == null || rate in profile.sampleRateRange))
        }.sortedBy { MediaConvertCommand.sampleRates[it] }
    }

    private fun dtsBitrate(sampleRate: Int, channels: Int, requested: Long): Long {
        // dcaenc.c: a frame must fit the fixed header plus every full-band/LFE channel.
        val lfe = if (channels == 6) 1 else 0
        val minimumFrameBits = 132 + (493 + 28 * 32) * (channels - lfe) + 72 * lfe
        return dtsBitrates.filter { bitrate ->
            val frameBits = ((bitrate * 512L + sampleRate - 1) / sampleRate + 31) / 32 * 32
            frameBits >= minimumFrameBits
        }.minBy { abs(it.toLong() - requested) }.toLong()
    }

    fun parseEncoders(log: String): Set<String> = Regex("(?m)^\\s*A[A-Z.]{5}\\s+(\\S+)\\s")
        .findAll(log).map { it.groupValues[1] }.toSet()
}
