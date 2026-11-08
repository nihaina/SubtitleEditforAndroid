package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaConvertHardwareEncoderTest {
    private val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders +
        setOf("libx265", "libxvid", "libaom-av1", "libtheora", "h264_mediacodec", "hevc_mediacodec"))
    private fun format(ext: String) = formats.first { it.extension == ext }
    private val sdSource = MediaConvertSource(true, 2.0, 30.0, videoCodecName = "h264", videoPixelFormat = "yuv420p",
        videoWidth = 1920, videoHeight = 1080, audioCodecName = "aac", audioSampleRate = 48000, audioChannels = 2)
    private fun build(ext: String, options: MediaConvertOptions, source: MediaConvertSource = sdSource,
                      allowHardware: Boolean = true, hardwareCodecNames: Map<String, String> = emptyMap()) =
        MediaConvertCommand.build("input.mkv", "output.$ext", format(ext), options, source,
            allowHardware = allowHardware, hardwareCodecNames = hardwareCodecNames)
    private fun List<String>.value(flag: String) = getOrNull(indexOf(flag).takeIf { it >= 0 }?.plus(1) ?: -1)

    @Test
    fun hardwareH264GetsBitrateNv12AndNoSoftwareOnlyFlags() {
        val cmd = build("mp4", MediaConvertOptions(selectedVideoCodec = "h264_mediacodec"))
        assertEquals("h264_mediacodec", cmd.value("-c:v"))
        assertEquals("nv12", cmd.value("-pix_fmt"))
        // Prevents FFmpeg's 200 kb/s default from ruining 1080p output.
        assertNotNull(cmd.value("-b:v"))
        assertFalse("-crf" in cmd)
        assertFalse("-q:v" in cmd)
        assertFalse("-preset" in cmd)
        assertFalse("-force_key_frames" in cmd)
    }

    @Test
    fun hardwareHevcInMp4IsTaggedHvc1() {
        val cmd = build("mp4", MediaConvertOptions(selectedVideoCodec = "hevc_mediacodec"))
        assertEquals("hvc1", cmd.value("-tag:v"))
    }

    @Test
    fun hardwareDecodingTurnsOnWhenSourceIs8BitAvcAndEncoderIsHardware() {
        val cmd = build("mp4", MediaConvertOptions(selectedVideoCodec = "h264_mediacodec"))
        assertEquals("mediacodec", cmd.value("-hwaccel"))
        // Placement must precede -i or FFmpeg rejects it as an output option.
        assertTrue(cmd.indexOf("-hwaccel") < cmd.indexOf("-i"))
    }

    @Test
    fun hardwareDecodingStaysOffForTenBitOrNonHwDecodableSources() {
        val tenBit = sdSource.copy(videoPixelFormat = "yuv420p10le")
        assertFalse("-hwaccel" in build("mp4", MediaConvertOptions(selectedVideoCodec = "h264_mediacodec"), source = tenBit))
        val wmv = sdSource.copy(videoCodecName = "wmv2")
        assertFalse("-hwaccel" in build("mp4", MediaConvertOptions(selectedVideoCodec = "h264_mediacodec"), source = wmv))
        // Software encode gets no hw decode: libx264 is usually the bottleneck.
        val software = build("mp4", MediaConvertOptions(selectedVideoCodec = "libx264"))
        assertFalse("-hwaccel" in software)
    }

    @Test
    fun hardwareFallbackReplacesH264MediacodecWithLibx264() {
        val software = build("mp4", MediaConvertOptions(selectedVideoCodec = "h264_mediacodec"), allowHardware = false)
        assertEquals("libx264", software.value("-c:v"))
        assertFalse("-hwaccel" in software)
    }

    @Test
    fun codecNameIsPinnedWhenTheDeviceReportsOne() {
        val cmd = build("mp4", MediaConvertOptions(selectedVideoCodec = "h264_mediacodec"),
            hardwareCodecNames = mapOf("h264_mediacodec" to "c2.qti.avc.encoder"))
        // Avoids FFmpeg accidentally picking a software c2.android.* encoder.
        assertEquals("c2.qti.avc.encoder", cmd.value("-codec_name"))
    }

    @Test
    fun hardwareBitrateIsStillOverriddenByUserTarget() {
        val custom = MediaConvertOptions(selectedVideoCodec = "h264_mediacodec",
            videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, customKbps = "5000"))
        assertEquals("5000k", build("mp4", custom).value("-b:v"))
    }

    @Test
    fun usesHardwareEncoderMatchesTheActualPlanDecision() {
        val hardware = MediaConvertOptions(selectedVideoCodec = "h264_mediacodec")
        assertTrue(MediaConvertCommand.usesHardwareEncoder(format("mp4"), hardware, sdSource))
        val autoCopyable = MediaConvertSource(true, 2.0, 30.0, videoCodecName = "h264", audioCodecName = "aac")
        // Auto that streams-copies must not advertise a hardware attempt (there is no encoder running).
        assertFalse(MediaConvertCommand.usesHardwareEncoder(format("mp4"), MediaConvertOptions(), autoCopyable))
    }
}
