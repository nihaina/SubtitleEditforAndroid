package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaConvertAudioChannelsTest {
    private val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders)
    private fun build(index: Int, codec: String = "aac", sourceLayout: String? = null, count: Int = 2, ext: String = "mkv") =
        MediaConvertCommand.build("in", "out", formats.first { it.extension == ext },
            MediaConvertOptions(selectedAudioCodec = codec, channelIndex = index),
            MediaConvertSource(true, 1.0, audioSampleRate = 48000, audioChannels = count, audioChannelLayout = sourceLayout))
    private fun List<String>.value(flag: String) = indexOf(flag).takeIf { it >= 0 }?.let { get(it + 1) }

    @Test
    fun newChoicesSpecifyLayoutsAndPreserveLegacyIndices() {
        assertEquals("2", build(1).value("-ac"))
        assertEquals("1", build(2).value("-ac"))
        for ((index, count, layout) in listOf(Triple(3, 3, "2.1"), Triple(4, 4, "4.0"), Triple(5, 5, "5.0"),
            Triple(6, 6, "5.1"), Triple(7, 7, "7.0"), Triple(8, 8, "7.1"))) {
            val command = build(index)
            assertEquals(count.toString(), command.value("-ac"))
            assertEquals(layout, command.value("-channel_layout:a"))
        }
        assertEquals("1", build(2, codec = "auto", ext = "mp3").value("-ac"))
    }

    @Test
    fun choicesRespectLayoutsRatherThanJustChannelCount() {
        fun choices(codec: String, muxer: String = "matroska") = MediaConvertAudioChannels.available(codec, muxer).map { it.index }
        assertEquals(listOf(2, 1), choices("libmp3lame"))
        assertEquals(listOf(2), choices("libopencore_amrnb"))
        assertEquals(listOf(2), choices("libspeex", "flv"))
        assertFalse(3 in choices("libopus")) // 2.1 has an LFE; Opus's three-channel mapping is 3.0.
        assertFalse(7 in choices("libopus"))
        assertTrue(8 in choices("libopus"))
        assertEquals("5.1(side)", build(6, "dca").value("-channel_layout:a"))
        assertEquals("7.1(wide)", build(8, "alac").value("-channel_layout:a"))
        assertEquals("5.1(side)", build(6, "ac3").value("-channel_layout:a"))
        assertFalse(MediaConvertAudioChannels.recordsLayout("pcm_s16le", "matroska"))
        assertTrue(MediaConvertAudioChannels.recordsLayout("pcm_s16le", "mov"))
    }

    @Test
    fun originalLayoutIsResolvedPerInputAndUnsupportedLayoutsAdapt() {
        assertEquals("5.1(side)", build(0, sourceLayout = "5.1(side)", count = 6).value("-channel_layout:a"))
        assertEquals("7.1", build(0, sourceLayout = "7.1", count = 8).value("-channel_layout:a"))
        assertEquals("3.0", build(0, "flac", sourceLayout = "2.1", count = 3).value("-channel_layout:a"))
        assertEquals("stereo", build(8, "libmp3lame").value("-channel_layout:a"))
        assertNull(build(8, "copy").value("-channel_layout:a"))
        assertNull(build(8, "copy").value("-ac"))
    }
}
