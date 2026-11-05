package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaConvertCommandTest {
    private val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders + setOf("libx265", "libxvid", "libaom-av1", "libtheora"))
    private fun format(ext: String) = formats.first { it.extension == ext }
    private fun build(ext: String, options: MediaConvertOptions = MediaConvertOptions(), source: MediaConvertSource = MediaConvertSource(true, 2.0)) =
        MediaConvertCommand.build("input with spaces.mkv", "output.$ext", format(ext), options, source)
    private fun List<String>.value(flag: String) = getOrNull(indexOf(flag).takeIf { it >= 0 }?.plus(1) ?: -1)

    @Test
    fun onlyCompatibleAndAvailableEncodersAreOffered() {
        val baseline = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders)
        assertFalse(baseline.any { "libx265" in it.videoCodecs || "libaom-av1" in it.videoCodecs || "libxvid" in it.videoCodecs })
        assertTrue("libx265" in format("mp4").videoCodecs)
        assertTrue("libaom-av1" in format("webm").videoCodecs)
        assertFalse("libx264" in format("webm").videoCodecs)
        assertFalse("mpeg4" in format("flv").videoCodecs)
        assertFalse("libmp3lame" in format("3gp").audioCodecs)
        assertEquals(17, formats.count { !it.isAudioOnly })
    }

    @Test
    fun encoderProbeDoesNotConfuseDecodersAndAudioWithVideoEncoders() {
        val log = " V....D libx265 HEVC\n V....D libaom-av1 AV1\n A....D libopus Opus\n VFS... mpeg2video MPEG-2\n"
        assertEquals(setOf("libx265", "libaom-av1", "mpeg2video"), MediaConvertFormats.parseEncoders(log))
    }

    @Test
    fun defaultsUseRecommendedEncoderAndMuxerRatherThanExtensionAsMuxer() {
        assertEquals("libx264", build("mp4").value("-c:v"))
        assertEquals("aac", build("mp4").value("-c:a"))
        assertEquals("mpeg", build("mpg").value("-f"))
        assertEquals("asf", build("wmv").value("-f"))
        assertEquals("libtheora", build("ogv").value("-c:v"))
        assertEquals("ogg", build("ogv").value("-f"))
    }

    @Test
    fun copyIgnoresAllEncodingAndFilterSettings() {
        val args = build("mkv", MediaConvertOptions(selectedVideoCodec = "copy", selectedAudioCodec = "copy", resolutionIndex = 3,
            videoBitrate = "3000", audioBitrate = "192", selectedQualityId = "23", sampleRateIndex = 2, channelIndex = 1))
        assertEquals("copy", args.value("-c:v"))
        assertEquals("copy", args.value("-c:a"))
        listOf("-vf", "-pix_fmt", "-crf", "-q:v", "-b:v", "-b:a", "-ar", "-ac").forEach { assertFalse(it, it in args) }
        assertEquals(listOf("0:v:0", "0:a:0?"), args.withIndex().filter { it.value == "-map" }.map { args[it.index + 1] })
    }

    @Test
    fun audioOutputDoesNotApplyVideoSettings() {
        val args = build("mp3", MediaConvertOptions(resolutionIndex = 3, selectedQualityId = "23", videoBitrate = "3000", audioBitrate = "192"))
        assertEquals("0:a:0", args.value("-map"))
        assertEquals("192k", args.value("-b:a"))
        assertTrue("-vn" in args)
        assertFalse("-c:v" in args)
        assertFalse("-vf" in args)
        assertFalse("-crf" in args)
    }

    @Test
    fun blackVideoKeepsTheRealAudioInputAndDuration() {
        val args = build("mp4", MediaConvertOptions(selectedVideoCodec = "copy"), MediaConvertSource(false, 1.234))
        assertEquals(listOf("0:v:0", "1:a:0"), args.withIndex().filter { it.value == "-map" }.map { args[it.index + 1] })
        assertEquals("libx264", args.value("-c:v"))
        assertEquals("1.234", args.value("-t"))
        assertTrue("input with spaces.mkv" in args)
    }

    @Test
    fun qualityUsesEncoderSpecificRangesAndPresets() {
        assertEquals("33", build("mp4", MediaConvertOptions(selectedQualityId = "33")).value("-crf"))
        assertEquals("0", build("mp4", MediaConvertOptions(selectedQualityId = "custom", customQuality = "0")).value("-crf"))
        assertEquals("51", build("mp4", MediaConvertOptions(selectedQualityId = "custom", customQuality = "99")).value("-crf"))
        assertEquals("4", build("mpg", MediaConvertOptions(selectedQualityId = "23")).value("-q:v"))
        assertEquals("9", build("ogv", MediaConvertOptions(selectedQualityId = "18")).value("-q:v"))
    }

    @Test
    fun av1HasPracticalDefaultsAndDoesNotOverrideAnExplicitBitrate() {
        val automatic = build("webm", MediaConvertOptions(selectedVideoCodec = "libaom-av1"))
        assertEquals("6", automatic.value("-cpu-used"))
        assertEquals("30", automatic.value("-crf"))
        val limited = build("webm", MediaConvertOptions(selectedVideoCodec = "libaom-av1", videoBitrate = "1000", selectedQualityId = "23"))
        assertEquals(listOf("1000k"), limited.withIndex().filter { it.value == "-b:v" }.map { limited[it.index + 1] })
    }

    @Test
    fun mpegNormalizesFrameRateAndHevcUsesMp4CompatibilityTag() {
        assertEquals("25.000000", build("mpg", source = MediaConvertSource(true, 1.0, 26.0)).value("-r"))
        assertEquals("hvc1", build("mp4", MediaConvertOptions(selectedVideoCodec = "libx265")).value("-tag:v"))
        assertNull(build("mkv", MediaConvertOptions(selectedVideoCodec = "libx265")).value("-tag:v"))
    }

    @Test
    fun losslessAudioDoesNotReceiveLossyBitrate() {
        assertFalse("-b:a" in build("wav", MediaConvertOptions(audioBitrate = "128")))
        assertFalse("-b:a" in build("flac", MediaConvertOptions(audioBitrate = "128")))
        assertEquals("libopus", build("opus").value("-c:a"))
    }
}
