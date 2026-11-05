package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaConvertAudioBitrateTest {
    private val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders)
    private fun build(value: String, sourceBps: Long? = null, codec: String = "auto", extension: String = "mkv") =
        MediaConvertCommand.build("in", "out", formats.first { it.extension == extension },
            MediaConvertOptions(selectedAudioCodec = codec, audioBitrate = value),
            MediaConvertSource(true, 1.0, audioSampleRate = 48000, audioChannels = 2, audioBitrateBps = sourceBps))
    private fun List<String>.value(flag: String) = indexOf(flag).takeIf { it >= 0 }?.let { get(it + 1) }

    @Test
    fun decimalKbpsAreExactAndInvalidInputIsRejected() {
        for (value in listOf("", "0", "-1", "NaN", "1e3", "4.1234", ".5", "4.", "4..5", "99999999999999999999")) {
            assertNull(value, MediaConvertAudioBitrate.parseKbps(value))
        }
        assertEquals(4750L, MediaConvertAudioBitrate.parseKbps("4.75"))
        assertEquals(192123L, MediaConvertAudioBitrate.parseKbps("192.123"))
        assertEquals("4.75 kbps", MediaConvertAudioBitrate.label(4750))
        assertEquals("192123", build("192.123").value("-b:a"))
    }

    @Test
    fun speechPresetsKeepFractionalBitratesAndEncoderLimits() {
        val presets = MediaConvertAudioBitrate.presets("libopencore_amrnb", "3gp")
        assertEquals(listOf("4.75", "5.15", "5.9", "6.7", "7.4", "7.95", "10.2", "12.2"), presets)
        assertFalse(MediaConvertAudioBitrate.isValidCustom("4", "libopencore_amrnb", "3gp"))
        assertTrue(MediaConvertAudioBitrate.isValidCustom("4.75", "libopencore_amrnb", "3gp"))
        assertFalse(MediaConvertAudioBitrate.isValidCustom("321", "libmp3lame", "matroska"))
        assertFalse(MediaConvertAudioBitrate.isValidCustom("16", "wmav2", "asf"))
        val options = MediaConvertOptions(audioBitrate = "4.75")
        assertEquals(4750L, MediaConvertAudioCodecs.parameters("libopencore_amrnb", "3gp", options, null).bitrate)
    }

    @Test
    fun originalUsesEachInputsAudioStreamBitrateAndRequiresMetadata() {
        val original = MediaConvertAudioBitrate.ORIGINAL
        assertEquals("256k", build(original, 256000).value("-b:a"))
        assertEquals("192123", build(original, 192123).value("-b:a"))
        assertThrows(IllegalArgumentException::class.java) { build(original) }
        assertThrows(IllegalArgumentException::class.java) { build(original, 0) }
        assertNull(build("").value("-b:a"))
    }

    @Test
    fun originalStillAdaptsToEncoderAndFixedRateOrCopyIgnoresIt() {
        val original = MediaConvertAudioBitrate.ORIGINAL
        assertEquals(320000L, MediaConvertAudioCodecs.parameters("dca", "matroska",
            MediaConvertOptions(audioBitrate = original),
            MediaConvertSource(true, 1.0, audioSampleRate = 48000, audioChannels = 2, audioBitrateBps = 192000)).bitrate)
        assertNull(build(original, codec = "copy").value("-b:a"))
        assertNull(build(original, codec = "flac").value("-b:a"))
        assertEquals("192k", build("192", extension = "mp3").value("-b:a"))
    }
}
