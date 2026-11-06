package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaConvertVideoSettingsTest {
    private val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders + setOf("libx265", "libxvid", "libaom-av1", "libtheora"))
    private val source = MediaConvertSource(true, 60.0, frameRate = 25.0, videoBitrateBps = 2_000_000,
        audioBitrateBps = 128_000, audioChannels = 2, audioSampleRate = 48000, videoPixelFormat = "yuv422p10le", fileSizeBytes = 100L * 1_048_576)
    private val mp4 = formats.first { it.extension == "mp4" }
    private fun args(settings: MediaConvertVideoSettings, options: MediaConvertOptions = MediaConvertOptions(), media: MediaConvertSource = source) =
        MediaConvertCommand.build("input.mkv", "output.mp4", mp4, options.copy(videoSettings = settings), media)
    private fun List<String>.value(flag: String) = indexOf(flag).takeIf { it >= 0 }?.let { get(it + 1) }

    @Test fun rejectsInvalidNumbersAndAllowsFractionalFrameRates() {
        assertTrue(MediaConvertVideoSettings(frameRate = "24000/1001", keyframeSeconds = "0.5", crf = 0).isValid)
        for (rate in listOf("0", "241", "NaN", "1/0", "1/2/3", "Infinity")) assertFalse(rate, MediaConvertVideoSettings(frameRate = rate).isValid)
        assertFalse(MediaConvertVideoSettings(sizeLimitPercent = 0).isValid)
        assertFalse(MediaConvertVideoSettings(sizeLimitPercent = 95).isValid)
        assertFalse(MediaConvertVideoSettings(sizeLimitPercent = 15).isValid)
        assertFalse(MediaConvertVideoSettings(keyframeSeconds = "NaN").isValid)
        assertFalse(MediaConvertVideoSettings(pixelFormat = "arbitrary filter").isValid)
    }

    @Test fun filtersApplyDeinterlaceRotationFlipSizingAndEffectInOrder() {
        val settings = MediaConvertVideoSettings(rotation = MediaConvertRotation.CLOCKWISE, flipHorizontal = true,
            flipVertical = true, deinterlace = MediaConvertDeinterlace.AUTO, filter = MediaConvertVideoFilter.RED)
        assertEquals("yadif=mode=send_frame:parity=auto:deint=interlaced,transpose=clock,hflip,vflip,scale=trunc(iw/2)*2:trunc(ih/2)*2,colorchannelmixer=rr=1:gg=0.65:bb=0.65",
            args(settings).value("-vf"))
        assertEquals(1, args(settings).count { it == "-vf" })
    }

    @Test fun crfOverridesLegacyQualityButTargetBitrateOverridesCrf() {
        val settings = MediaConvertVideoSettings(crf = 19)
        val options = MediaConvertOptions(selectedQualityId = "33")
        assertEquals("19", args(settings, options).value("-crf"))
        val target = args(settings, options.copy(videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "2000")))
        assertNull(target.value("-crf"))
        assertEquals("2000k", target.value("-b:v"))
        val unsupported = args(settings, options.copy(selectedVideoCodec = "mpeg4"))
        assertNull(unsupported.value("-crf"))
        assertEquals("12", unsupported.value("-q:v"))
    }

    @Test fun sizeBudgetReservesAudioAndDoesNotTruncateDuration() {
        val settings = MediaConvertVideoSettings(sizeLimitPercent = 10, crf = 18)
        val options = MediaConvertOptions(audioBitrate = "128", videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "9999"))
        val expected = (10 * 1_048_576.0 * 8 * 0.97 / 60 - 128_000).toLong()
        val command = args(settings, options)
        assertEquals(MediaConvertVideoBitrate.ffmpegValue(expected), command.value("-b:v"))
        assertFalse("-fs" in command)
        assertFalse("-t" in command)
        assertFalse("-crf" in command)
        assertEquals(expected + 128_000, MediaConvertVideoSettingsSupport.targetBitrate(mp4, options.copy(videoSettings = settings), source.copy(hasAudio = false)))
        assertEquals((10 * 1_048_576.0 * 8 * 0.97 / 60 - 48000 * 16).toLong(),
            MediaConvertVideoSettingsSupport.targetBitrate(mp4, options.copy(selectedAudioCodec = "pcm_s16le", videoSettings = settings), source.copy(audioChannels = 1)))
        assertThrows(IllegalArgumentException::class.java) { args(settings, options, source.copy(durationSeconds = null)) }
        assertThrows(MediaConvertVideoSizeException::class.java) { args(settings, options, source.copy(fileSizeBytes = null)) }
        assertThrows(IllegalArgumentException::class.java) { args(settings, options.copy(selectedAudioCodec = "copy"), source.copy(audioBitrateBps = null)) }
        assertThrows(IllegalArgumentException::class.java) { args(settings, options, source.copy(durationSeconds = 36000.0)) }
    }

    @Test fun sizeRatiosUseEachSourceFileAndAvoidLongOverflow() {
        for (percent in 90 downTo 10 step 10) {
            val bytes = MediaConvertVideoSettingsSupport.targetSizeBytes(source.fileSizeBytes, percent)
            assertEquals(percent * 1_048_576L, bytes)
            val options = MediaConvertOptions(audioBitrate = "128", videoSettings = MediaConvertVideoSettings(sizeLimitPercent = percent))
            assertEquals((percent * 1_048_576.0 * 8 * 0.97 / 60 - 128_000).toLong(),
                MediaConvertVideoSettingsSupport.targetBitrate(mp4, options, source))
            assertEquals((percent * 2 * 1_048_576.0 * 8 * 0.97 / 60 - 128_000).toLong(),
                MediaConvertVideoSettingsSupport.targetBitrate(mp4, options, source.copy(fileSizeBytes = 200L * 1_048_576)))
        }
        assertEquals(89L, MediaConvertVideoSettingsSupport.targetSizeBytes(99, 90))
        assertEquals(8_301_034_833_169_298_226L, MediaConvertVideoSettingsSupport.targetSizeBytes(Long.MAX_VALUE, 90))
        assertNull(MediaConvertVideoSettingsSupport.targetSizeBytes(null, 90))
        assertNull(MediaConvertVideoSettingsSupport.targetSizeBytes(0, 90))
        assertNull(MediaConvertVideoSettingsSupport.targetSizeBytes(1000, 15))
    }

    @Test fun pixelFormatsFollowActualEncoderCapabilitiesAndSourcePerFile() {
        assertEquals(listOf("yuv420p", "nv12", "yuv420p10le"),
            MediaConvertVideoSettingsSupport.parsePixelFormats("Encoder test\n Supported pixel formats: yuv420p nv12 rgb24 yuv420p10le\nOther options"))
        assertEquals("yuv422p10le", args(MediaConvertVideoSettings(pixelFormat = "original")).value("-pix_fmt"))
        assertEquals("yuv420p", args(MediaConvertVideoSettings(pixelFormat = "nv20le")).value("-pix_fmt"))
        assertEquals("yuv420p10le", MediaConvertCommand.build("in", "out", mp4,
            MediaConvertOptions(selectedVideoCodec = "libx265"), source, listOf("yuv420p10le")).value("-pix_fmt"))
    }

    @Test fun keyframeSecondsAndRequestedFrameRateReachCommand() {
        val command = args(MediaConvertVideoSettings(frameRate = "24000/1001", keyframeSeconds = "2.5"))
        assertEquals("24000/1001", command.value("-r"))
        assertEquals("60", command.value("-g"))
        assertEquals("expr:gte(t,n_forced*2.5)", command.value("-force_key_frames"))
        assertEquals("25.000000", MediaConvertVideoSettingsSupport.frameRate("mpeg2video", MediaConvertVideoSettings(frameRate = "26"), 60.0))
    }

    @Test fun twoPassPlanSharesFiltersAndStatsAndOnlyFinalPassEncodesAudio() {
        val options = MediaConvertOptions(audioVolumePercent = 200,
            videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "1000"),
            videoSettings = MediaConvertVideoSettings(twoPass = true, flipHorizontal = true))
        val plan = MediaConvertCommand.plan("input with spaces", "output.mp4", mp4, options, source, "private cache/pass")
        assertEquals(2, plan.size)
        assertEquals("1", plan[0].value("-pass"))
        assertEquals("2", plan[1].value("-pass"))
        assertEquals(plan[0].value("-passlogfile"), plan[1].value("-passlogfile"))
        assertEquals(plan[0].value("-vf"), plan[1].value("-vf"))
        assertEquals("null", plan[0].value("-f"))
        assertTrue("-an" in plan[0])
        assertFalse("-c:a" in plan[0])
        assertFalse("-af" in plan[0])
        assertTrue("-c:a" in plan[1])
        assertEquals("volume=2", plan[1].value("-af"))
        assertEquals(1, MediaConvertCommand.plan("in", "out", mp4, options.copy(videoBitrate = MediaConvertVideoBitrateSettings()), source, "stats").size)
        assertEquals(1, MediaConvertCommand.plan("in", "out", formats.first { it.extension == "mkv" }, options.copy(selectedVideoCodec = "libxvid"), source, "stats").size)
    }

    @Test fun hevcUsesItsOwnStatsOptions() {
        val options = MediaConvertOptions(selectedVideoCodec = "libx265", videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "1000"),
            videoSettings = MediaConvertVideoSettings(twoPass = true))
        val plan = MediaConvertCommand.plan("in", "out", mp4, options, source, "/private cache/pass")
        assertEquals("pass=1:stats=/private cache/pass", plan[0].value("-x265-params"))
        assertEquals("pass=2:stats=/private cache/pass", plan[1].value("-x265-params"))
    }

    @Test fun copyAndAudioOutputsIgnoreAllVideoSettings() {
        val options = MediaConvertOptions(selectedVideoCodec = "copy", videoSettings = MediaConvertVideoSettings(rotation = MediaConvertRotation.HALF,
            crf = 19, sizeLimitPercent = 50, twoPass = true, keyframeSeconds = "2", frameRate = "60", pixelFormat = "nv12"))
        for (format in listOf(mp4, formats.first { it.extension == "mp3" })) {
            val plan = MediaConvertCommand.plan("in", "out", format, options, source.copy(durationSeconds = null), "stats")
            assertEquals(1, plan.size)
            for (flag in listOf("-r", "-g", "-vf", "-crf", "-pix_fmt", "-pass")) assertFalse(flag, flag in plan.single())
        }
    }
}
