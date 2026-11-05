package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaConvertAudioCodecsTest {
    private val allAudio = MediaConvertAudioCodecs.baselineEncoders + setOf("libspeex", "libopencore_amrnb", "libvo_amrwbenc")
    private val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders, allAudio)

    @Test
    fun audioEncoderDiscoveryAndContainerChoicesAreIndependent() {
        assertEquals(setOf("libspeex", "dca"), MediaConvertAudioCodecs.parseEncoders(" A....D libspeex Speex\n A..X.D dca DTS\n V....D libx264 AVC\n"))
        val mkv = formats.first { it.extension == "mkv" }
        assertTrue(mkv.audioCodecs.containsAll(listOf("adpcm_swf", "libopencore_amrnb", "dca", "pcm_alaw", "pcm_mulaw", "pcm_s16be", "pcm_s24le", "pcm_s32le", "libspeex", "real_144", "tta", "wavpack", "wmav1", "wmav2")))
        assertEquals(listOf("auto", "libopus", "libvorbis", "copy"), formats.first { it.extension == "webm" }.audioCodecs)
        assertFalse("libspeex" in MediaConvertFormats.create(MediaConvertFormats.baselineEncoders).first { it.extension == "mkv" }.audioCodecs)
        assertFalse("libmp3lame" in formats.first { it.extension == "3gp" }.audioCodecs)
        assertTrue(formats.all { it.audioCodecs.size == it.audioCodecs.distinct().size })
    }

    @Test
    fun amrUsesItsRequiredRateChannelsAndSupportedBitrateStep() {
        val source = MediaConvertSource(true, 1.0, audioSampleRate = 48000, audioChannels = 6)
        val options = MediaConvertOptions(audioBitrate = "192", sampleRateIndex = 2, channelIndex = 1)
        assertEquals(MediaConvertAudioParameters(8000, 1, 12200), MediaConvertAudioCodecs.parameters("libopencore_amrnb", "3gp", options, source))
        assertEquals(MediaConvertAudioParameters(16000, 1, 23850), MediaConvertAudioCodecs.parameters("libvo_amrwbenc", "3g2", options, source))
        assertEquals(7950L, MediaConvertAudioCodecs.parameters("libopencore_amrnb", "matroska", options.copy(audioBitrate = "8"), source).bitrate)
    }

    @Test
    fun flvSpeexAndLegacyCodecsAdaptIncompatibleSurroundInput() {
        val source = MediaConvertSource(true, 1.0, audioSampleRate = 96000, audioChannels = 6)
        val options = MediaConvertOptions(audioBitrate = "192")
        assertEquals(MediaConvertAudioParameters(16000, 1, 44000), MediaConvertAudioCodecs.parameters("libspeex", "flv", options, source))
        assertEquals(MediaConvertAudioParameters(44100, 2, null), MediaConvertAudioCodecs.parameters("adpcm_swf", "matroska", options, source))
        assertEquals(MediaConvertAudioParameters(8000, 1, null), MediaConvertAudioCodecs.parameters("real_144", "matroska", options, source))
        assertEquals(MediaConvertAudioParameters(48000, 2, 192000), MediaConvertAudioCodecs.parameters("wmav1", "asf", options, source))
    }

    @Test
    fun losslessAndFixedRateEncodersDoNotReceiveArbitraryBitrate() {
        val options = MediaConvertOptions(audioBitrate = "192")
        for (codec in listOf("flac", "alac", "tta", "wavpack", "pcm_s16be", "pcm_alaw", "adpcm_swf", "real_144", "truehd")) {
            assertNull(codec, MediaConvertAudioCodecs.parameters(codec, "matroska", options, null).bitrate)
        }
    }

    @Test
    fun experimentalAudioGetsStrictOptionAndCopyGetsNoAudioFilters() {
        val source = MediaConvertSource(true, 1.0, audioSampleRate = 48000, audioChannels = 2)
        val mkv = formats.first { it.extension == "mkv" }
        val dts = MediaConvertCommand.build("in", "out", mkv, MediaConvertOptions(selectedAudioCodec = "dca"), source)
        assertEquals("-2", dts[dts.indexOf("-strict") + 1])
        val copied = MediaConvertCommand.build("in", "out", mkv, MediaConvertOptions(selectedAudioCodec = "copy", audioBitrate = "192", sampleRateIndex = 1, channelIndex = 2), source)
        assertFalse("-ar" in copied)
        assertFalse("-ac" in copied)
        assertFalse("-b:a" in copied)
    }

    @Test
    fun dtsBitrateFitsTheFrameForStereoAndSurround() {
        val options = MediaConvertOptions(audioBitrate = "192")
        assertEquals(320000L, MediaConvertAudioCodecs.parameters("dca", "matroska", options,
            MediaConvertSource(true, 1.0, audioSampleRate = 48000, audioChannels = 2)).bitrate)
        assertEquals(768000L, MediaConvertAudioCodecs.parameters("dca", "matroska", options,
            MediaConvertSource(true, 1.0, audioSampleRate = 96000, audioChannels = 6)).bitrate)
    }
}
