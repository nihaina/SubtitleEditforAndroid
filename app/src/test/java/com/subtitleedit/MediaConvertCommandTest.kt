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
    fun subtitleTypeDefaultsToSoftTrack() {
        assertEquals(MediaConvertSubtitleType.SOFT, MediaConvertUiState().subtitleType)
    }

    @Test
    fun copyIgnoresAllEncodingAndFilterSettings() {
        val args = build("mkv", MediaConvertOptions(selectedVideoCodec = "copy", selectedAudioCodec = "copy", resolution = MediaConvertResolutionSettings(presetIndex = 3),
            videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "3000"), audioBitrate = "192", selectedQualityId = "23", sampleRateIndex = 2, channelIndex = 1))
        assertEquals("copy", args.value("-c:v"))
        assertEquals("copy", args.value("-c:a"))
        listOf("-vf", "-pix_fmt", "-crf", "-q:v", "-b:v", "-b:a", "-ar", "-ac", "-threads", "-filter_threads").forEach { assertFalse(it, it in args) }
        assertEquals(listOf("0:v:0", "0:a:0?"), args.withIndex().filter { it.value == "-map" }.map { args[it.index + 1] })
    }

    @Test
    fun softwareVideoEncodingUsesBoundedCpuThreadCount() {
        val source = MediaConvertSource(true, 2.0, videoCodecName = "h264", videoPixelFormat = "yuv420p", videoWidth = 1920, videoHeight = 1080)
        val options = MediaConvertOptions(selectedVideoCodec = "libx264")
        val args = MediaConvertCommand.build("input.mkv", "output.mp4", format("mp4"), options, source)
        val threads = args.value("-threads")?.toIntOrNull()
        assertNotNull(threads)
        assertTrue(threads!! in 1..16)
        assertFalse("-filter_threads" in args)
        val filtered = MediaConvertCommand.build("input.mkv", "output.mp4", format("mp4"), options, source.copy(videoWidth = 1921))
        assertNotNull(filtered.value("-filter_threads"))
    }

    @Test
    fun metadataOverridesAreAppliedToFinalOutputAndCoverNoneUnmapsAttachedPictures() {
        val metadata = MediaConvertMetadata(cover = MediaConvertCoverMode.NONE, title = "Film", author = "Director", comment = "Note")
        val args = MediaConvertCommand.build("in", "out.mp4", format("mp4"), MediaConvertOptions(),
            MediaConvertSource(true, 2.0, attachedPictureStreamIndex = 3), metadata = metadata)
        val tags = args.withIndex().filter { it.value == "-metadata" }.map { args[it.index + 1] }
        assertEquals(listOf("title=Film", "author=Director", "comment=Note"), tags)
        assertTrue(args.windowed(2).contains(listOf("-map", "-0:3")))
        val defaults = MediaConvertCommand.build("in", "out.mp4", format("mp4"), MediaConvertOptions(), MediaConvertSource(true, 2.0))
        assertFalse("-metadata" in defaults)
        assertFalse(defaults.contains("-0:v:disp:attached_pic"))
    }

    @Test
    fun coverDefaultPreservesAttachedPictureWhenVideoIsReencoded() {
        val source = MediaConvertSource(true, 2.0, attachedPictureStreamIndex = 3)
        val command = MediaConvertCommand.build("in", "out.mkv", format("mkv"),
            MediaConvertOptions(selectedVideoCodec = "libx264"), source, metadata = MediaConvertMetadata())

        assertTrue(command.windowed(2).contains(listOf("-map", "0:3")))
        assertTrue(command.windowed(2).contains(listOf("-c:v:1", "copy")))
        assertTrue(command.windowed(2).contains(listOf("-disposition:v:1", "attached_pic")))
    }

    @Test
    fun twoPassMetadataOnlyAppearsOnTheFinalPass() {
        val options = MediaConvertOptions(
            videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "1200"),
            videoSettings = MediaConvertVideoSettings(twoPass = true)
        )
        val plan = MediaConvertCommand.plan("in", "out.mp4", format("mp4"), options, MediaConvertSource(true, 2.0), "stats",
            metadata = MediaConvertMetadata(title = "Film"))
        assertFalse("-metadata" in plan.first())
        assertTrue(plan.last().contains("title=Film"))
    }

    @Test
    fun audioOutputDoesNotApplyVideoSettings() {
        val args = build("mp3", MediaConvertOptions(resolution = MediaConvertResolutionSettings(presetIndex = 3), selectedQualityId = "23", videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "3000"), audioBitrate = "192"))
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
        val limited = build("webm", MediaConvertOptions(selectedVideoCodec = "libaom-av1", videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "1000"), selectedQualityId = "23"))
        assertEquals(listOf("1000k"), limited.withIndex().filter { it.value == "-b:v" }.map { limited[it.index + 1] })
        assertNull(limited.value("-crf"))
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
