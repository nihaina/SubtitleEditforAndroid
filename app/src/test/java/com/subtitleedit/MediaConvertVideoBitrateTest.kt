package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaConvertVideoBitrateTest {
    private val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders)
    private fun build(settings: MediaConvertVideoBitrateSettings, sourceBps: Long? = null, codec: String = "auto", extension: String = "mp4") =
        MediaConvertCommand.build("input.mkv", "output.$extension", formats.first { it.extension == extension },
            MediaConvertOptions(selectedVideoCodec = codec, videoBitrate = settings, selectedQualityId = "23"),
            MediaConvertSource(true, 1.0, videoBitrateBps = sourceBps))
    private fun List<String>.value(flag: String) = indexOf(flag).takeIf { it >= 0 }?.let { get(it + 1) }

    @Test
    fun customRangeAndPresetListMatchTheDialog() {
        for (value in listOf("", "0", "127", "1000000", "-128", "1.5", "N/A", " 128")) {
            assertFalse(value, MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, value).isValid)
        }
        assertEquals(128000L, MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "128").targetBitsPerSecond(null))
        assertEquals(999999000L, MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "999999").targetBitsPerSecond(null))
        assertEquals(listOf(250, 2000, 5000, 8000, 16000, 25000), MediaConvertVideoBitrate.presets)
        assertFalse(MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.PRESET, presetKbps = 1234).isValid)
    }

    @Test
    fun videoMetadataUsesPositiveStreamOrBpsTagValues() {
        assertEquals(14993123L, MediaConvertVideoBitrate.sourceBitsPerSecond("14993123", "15000000"))
        assertEquals(15000000L, MediaConvertVideoBitrate.sourceBitsPerSecond("N/A", "15000000"))
        assertNull(MediaConvertVideoBitrate.sourceBitsPerSecond(null, "", "0", "-1", "NaN"))
        assertEquals("14993 kbps", MediaConvertVideoBitrate.label(14993123))
    }

    @Test
    fun originalModeResolvesEachInputSeparatelyWithoutRoundingTheEncodingTarget() {
        val original = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.ORIGINAL)
        assertEquals("14993123", build(original, 14993123).value("-b:v"))
        assertEquals("8000k", build(original, 8000000).value("-b:v"))
        assertThrows(IllegalArgumentException::class.java) { build(original) }
    }

    @Test
    fun bitrateTargetsSuppressCrfAndQuantizerWhileAutoRetainsQuality() {
        val custom = build(MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "3000"))
        assertEquals("3000k", custom.value("-b:v"))
        assertNull(custom.value("-crf"))
        assertNull(custom.value("-q:v"))
        val preset = build(MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.PRESET, presetKbps = 5000))
        assertEquals("5000k", preset.value("-b:v"))
        val automatic = build(MediaConvertVideoBitrateSettings())
        assertEquals("23", automatic.value("-crf"))
        assertNull(automatic.value("-b:v"))
    }

    @Test
    fun copyAndAudioOutputDoNotRequireOrApplySourceVideoBitrate() {
        val original = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.ORIGINAL)
        assertNull(build(original, codec = "copy").value("-b:v"))
        assertNull(build(original, extension = "mp3").value("-b:v"))
        assertNull(build(original, extension = "mp3").value("-crf"))
    }
}
