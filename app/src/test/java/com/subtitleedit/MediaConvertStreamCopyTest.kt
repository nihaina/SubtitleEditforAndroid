package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaConvertStreamCopyTest {
    private val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders + setOf("libx265", "libxvid", "libaom-av1", "libtheora"))
    private fun format(ext: String) = formats.first { it.extension == ext }
    private val h264 = MediaConvertSource(true, 2.0, 25.0, videoCodecName = "h264", audioCodecName = "aac",
        audioSampleRate = 48000, audioChannels = 2)
    private fun build(ext: String, options: MediaConvertOptions = MediaConvertOptions(), source: MediaConvertSource = h264,
                      subtitle: MediaConvertSubtitleInput? = null) =
        MediaConvertCommand.build("input.mkv", "output.$ext", format(ext), options, source, subtitle = subtitle)
    private fun List<String>.value(flag: String) = getOrNull(indexOf(flag).takeIf { it >= 0 }?.plus(1) ?: -1)

    @Test
    fun containerChangeAloneRemuxesInsteadOfReEncoding() {
        val command = build("mp4")
        assertEquals("copy", command.value("-c:v"))
        assertEquals("copy", command.value("-c:a"))
        assertFalse("-vf" in command)
        assertFalse("-pix_fmt" in command)
        assertFalse("-crf" in command)
        assertFalse("-b:a" in command)
    }

    @Test
    fun copiedStreamsStillRespectMuxerSpecificTagging() {
        val hevc = h264.copy(videoCodecName = "hevc")
        val command = build("mp4", source = hevc)
        assertEquals("copy", command.value("-c:v"))
        assertEquals("hvc1", command.value("-tag:v"))
    }

    @Test
    fun codecTheTargetContainerCannotHoldIsReEncoded() {
        assertEquals("libvpx-vp9", build("webm").value("-c:v"))
        assertEquals("libopus", build("webm").value("-c:a"))
        assertEquals("libx264", build("mp4", source = h264.copy(videoCodecName = "wmv2")).value("-c:v"))
    }

    @Test
    fun anyVideoSettingThatChangesPixelsDisablesVideoCopy() {
        val changes = listOf(
            MediaConvertOptions(resolution = MediaConvertResolutionSettings(presetIndex = 3)),
            MediaConvertOptions(videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.PRESET)),
            MediaConvertOptions(selectedQualityId = "23"),
            MediaConvertOptions(videoSettings = MediaConvertVideoSettings(frameRate = "30")),
            MediaConvertOptions(videoSettings = MediaConvertVideoSettings(rotation = MediaConvertRotation.CLOCKWISE)),
            MediaConvertOptions(videoSettings = MediaConvertVideoSettings(flipHorizontal = true)),
            MediaConvertOptions(videoSettings = MediaConvertVideoSettings(flipVertical = true)),
            MediaConvertOptions(videoSettings = MediaConvertVideoSettings(crf = 20)),
            MediaConvertOptions(videoSettings = MediaConvertVideoSettings(sizeLimitPercent = 50)),
            MediaConvertOptions(videoSettings = MediaConvertVideoSettings(keyframeSeconds = "2")),
            MediaConvertOptions(videoSettings = MediaConvertVideoSettings(deinterlace = MediaConvertDeinterlace.ON)),
            MediaConvertOptions(videoSettings = MediaConvertVideoSettings(filter = MediaConvertVideoFilter.GRAYSCALE)),
            MediaConvertOptions(videoSettings = MediaConvertVideoSettings(pixelFormat = "yuv444p"))
        )
        val sized = h264.copy(fileSizeBytes = 10_000_000L, videoBitrateBps = 4_000_000L)
        changes.forEach { options ->
            assertFalse(options.toString(), MediaConvertStreamCopy.copiesVideo(format("mp4"), options, sized))
        }
    }

    @Test
    fun anyAudioSettingThatChangesSamplesDisablesAudioCopy() {
        val changes = listOf(
            MediaConvertOptions(audioBitrate = "128"),
            MediaConvertOptions(sampleRateIndex = 1),
            MediaConvertOptions(channelIndex = 2),
            MediaConvertOptions(audioVolumePercent = 150),
            MediaConvertOptions(audioVbrQuality = 2)
        )
        changes.forEach { options ->
            assertFalse(options.toString(), MediaConvertStreamCopy.copiesAudio(format("mp4"), options, h264))
        }
    }

    @Test
    fun burnedInSubtitlesStillEncodeVideoWhileSoftSubtitlesCopyIt() {
        val burn = MediaConvertSubtitleInput("/cache/subtitle.srt", "subtitle.srt", MediaConvertSubtitleType.BURN_IN)
        val soft = MediaConvertSubtitleInput("/cache/subtitle.srt", "subtitle.srt", MediaConvertSubtitleType.SOFT)
        assertEquals("libx264", build("mp4", subtitle = burn).value("-c:v"))
        assertEquals("copy", build("mp4", subtitle = soft).value("-c:v"))
    }

    @Test
    fun anExplicitEncoderChoiceIsNeverOverriddenByCopy() {
        assertEquals("libx265", build("mp4", MediaConvertOptions(selectedVideoCodec = "libx265")).value("-c:v"))
        assertEquals("libmp3lame", build("mkv", MediaConvertOptions(selectedAudioCodec = "libmp3lame")).value("-c:a"))
    }

    @Test
    fun audioOnlyOutputAndMissingStreamsFallBackToEncoding() {
        assertEquals("copy", build("m4a").value("-c:a"))
        assertEquals("libmp3lame", build("mp3").value("-c:a"))
        assertFalse(MediaConvertStreamCopy.copiesVideo(format("mp4"), MediaConvertOptions(), h264.copy(hasVideo = false)))
        assertFalse(MediaConvertStreamCopy.copiesAudio(format("mp4"), MediaConvertOptions(), h264.copy(hasAudio = false)))
        assertFalse(MediaConvertStreamCopy.copiesVideo(format("mp4"), MediaConvertOptions(), h264.copy(videoCodecName = null)))
    }
}
