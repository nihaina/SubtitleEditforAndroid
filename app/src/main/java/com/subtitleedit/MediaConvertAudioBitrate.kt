package com.subtitleedit

import java.math.BigDecimal

/** Empty means automatic; ORIGINAL resolves the first audio stream of each input. Numbers are kbps. */
internal object MediaConvertAudioBitrate {
    const val ORIGINAL = "original"
    private val commonPresets = listOf(4000, 8000, 16000, 32000, 64000, 96000, 128000, 160000,
        192000, 224000, 256000, 264000, 320000, 384000, 512000, 640000, 768000, 1024000, 1411200, 1536000)

    fun range(codec: String, muxer: String): LongRange {
        val steps = MediaConvertAudioCodecs.profile(codec, muxer).bitrateValues
        if (steps.isNotEmpty()) return steps.first().toLong()..steps.last().toLong()
        return when (codec) {
            "libmp3lame" -> 8000L..320000L
            "libopus" -> 6000L..510000L
            "libspeex" -> 2150L..44000L
            "wmav1", "wmav2" -> 24000L..320000L
            "mp2" -> 8000L..384000L
            "ac3" -> 32000L..640000L
            "eac3" -> 32000L..6144000L
            else -> 1000L..6144000L
        }
    }

    fun presets(codec: String, muxer: String): List<String> {
        val steps = MediaConvertAudioCodecs.profile(codec, muxer).bitrateValues
        return (steps.ifEmpty { commonPresets }).filter { it.toLong() in range(codec, muxer) }
            .map { kbps(it.toLong()) }
    }

    fun parseKbps(value: String): Long? {
        if (!Regex("[0-9]+(?:\\.[0-9]{1,3})?").matches(value)) return null
        return runCatching { BigDecimal(value).movePointRight(3).longValueExact() }.getOrNull()?.takeIf { it > 0 }
    }

    fun isValidCustom(value: String, codec: String, muxer: String) = parseKbps(value)?.let { it in range(codec, muxer) } == true

    fun targetBitsPerSecond(value: String, sourceBitsPerSecond: Long?): Long? = when (value) {
        ORIGINAL -> requireNotNull(sourceBitsPerSecond?.takeIf { it > 0 }) { "Source audio bitrate is unavailable" }
        else -> parseKbps(value.trim())
    }

    fun kbps(bitsPerSecond: Long): String = BigDecimal.valueOf(bitsPerSecond, 3).stripTrailingZeros().toPlainString()
    fun label(bitsPerSecond: Long) = "${kbps(bitsPerSecond)} kbps"
}
