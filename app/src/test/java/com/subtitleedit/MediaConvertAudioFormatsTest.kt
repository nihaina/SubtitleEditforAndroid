package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaConvertAudioFormatsTest {
    private val audioEncoders = MediaConvertAudioCodecs.baselineEncoders + setOf("libspeex", "libopencore_amrnb", "libvo_amrwbenc", "libgsm", "libgsm_ms")
    private val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders, audioEncoders)
    private fun List<String>.value(flag: String) = indexOf(flag).takeIf { it >= 0 }?.let { get(it + 1) }

    @Test
    fun audioFormatsIncludeReferencesAndFilterUnavailableEncoders() {
        val audio = formats.filter { it.isAudioOnly }
        assertTrue(audio.map { it.extension }.containsAll(listOf("amr", "awb", "aiff", "mp2", "gsm", "dts", "au", "caf", "wv", "oga", "w64", "voc", "snd", "spx", "ircam")))
        assertEquals(audio.size, audio.distinctBy { it.extension }.size)
        assertTrue(audio.all { it.videoCodecs.isEmpty() && it.audioCodecs.size == it.audioCodecs.distinct().size })
        assertFalse(MediaConvertAudioFormats.create(emptySet()).any())
        assertEquals(listOf("auto", "aac", "copy"), MediaConvertAudioFormats.create(setOf("aac")).first { it.extension == "m4a" }.audioCodecs)
        assertFalse(MediaConvertAudioFormats.create(setOf("aac")).any { it.extension == "gsm" })
        assertTrue("alac" in audio.first { it.extension == "m4a" }.audioCodecs)
    }

    @Test
    fun audioOnlyOutputUsesTheSameOriginalBitrateAndChannelRules() {
        val m4a = formats.first { it.extension == "m4a" }
        val options = MediaConvertOptions(audioBitrate = MediaConvertAudioBitrate.ORIGINAL, sampleRateIndex = MediaConvertCommand.sampleRates.indexOf(64000), channelIndex = 6)
        val command = MediaConvertCommand.build("in.wav", "out.m4a", m4a, options,
            MediaConvertSource(false, 1.0, audioSampleRate = 96000, audioChannels = 8, audioBitrateBps = 192123))
        assertEquals("192123", command.value("-b:a"))
        assertEquals("64000", command.value("-ar"))
        assertEquals("6", command.value("-ac"))
        assertEquals("5.1", command.value("-channel_layout:a"))
        assertEquals("0:a:0", command.value("-map"))
        assertFalse("-c:v" in command)
        assertThrows(IllegalArgumentException::class.java) { MediaConvertCommand.build("in", "out", m4a, options, null) }
    }

    @Test
    fun fixedRateSpeechAndLosslessOrCopyUseSharedProfiles() {
        val source = MediaConvertSource(false, 1.0, audioSampleRate = 96000, audioChannels = 8, audioBitrateBps = 192000)
        val options = MediaConvertOptions(audioBitrate = "192", channelIndex = 8)
        val gsm = MediaConvertCommand.build("in", "out.gsm", formats.first { it.extension == "gsm" }, options, source)
        assertEquals("8000", gsm.value("-ar"))
        assertEquals("1", gsm.value("-ac"))
        assertFalse("-b:a" in gsm)
        assertEquals(listOf(0, MediaConvertCommand.sampleRates.indexOf(8000)), MediaConvertAudioCodecs.sampleRateIndices("pcm_u8", "voc"))
        val lossless = MediaConvertCommand.build("in", "out.m4a", formats.first { it.extension == "m4a" }, options.copy(selectedAudioCodec = "alac"), source)
        assertFalse("-b:a" in lossless)
        val copy = MediaConvertCommand.build("in", "out.m4a", formats.first { it.extension == "m4a" }, options.copy(selectedAudioCodec = "copy"), source)
        for (flag in listOf("-b:a", "-ar", "-ac", "-channel_layout:a")) assertFalse(flag, flag in copy)
    }

    @Test
    fun adtsUsesPceForEightChannelAacAndVorbisAdaptsNominalBitrate() {
        val source = MediaConvertSource(false, 1.0, audioSampleRate = 96000, audioChannels = 8, audioChannelLayout = "7.1")
        val options = MediaConvertOptions(audioBitrate = "192", channelIndex = 8)
        val adts = MediaConvertCommand.build("in", "out.aac", formats.first { it.extension == "aac" }, options, source)
        assertEquals("1", adts.value("-aac_pce"))
        val vorbis = MediaConvertCommand.build("in", "out.ogg", formats.first { it.extension == "ogg" }, options, source)
        assertEquals("48000", vorbis.value("-ar"))
        assertEquals("256k", vorbis.value("-b:a"))
        val automatic = MediaConvertCommand.build("in", "out.ogg", formats.first { it.extension == "ogg" }, options.copy(audioBitrate = ""), source)
        assertFalse("-b:a" in automatic)
        assertFalse("-ar" in automatic)
        val opus = MediaConvertCommand.build("in", "out.opus", formats.first { it.extension == "opus" },
            options.copy(audioBitrate = MediaConvertAudioBitrate.ORIGINAL, channelIndex = 2), source.copy(audioBitrateBps = 18432000))
        assertEquals("256k", opus.value("-b:a"))
    }
}
