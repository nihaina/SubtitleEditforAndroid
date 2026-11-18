package com.subtitleedit.util

import com.subtitleedit.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTechnicalDetailsTest {
    @Test fun pcmShowsItsStoredBitDepthAndCodec() {
        val info = FfprobeMediaInformation.parse("""
            {
              "streams": [{"index":0,"codec_type":"audio","codec_name":"pcm_s24le","codec_long_name":"PCM signed 24-bit little-endian",
                "sample_rate":"44100","channels":2,"bits_per_sample":24,"bits_per_raw_sample":"0"}],
              "format": {"format_name":"wav","duration":"3671.2","bit_rate":"2116800"}
            }
        """.trimIndent())
        val details = MediaTechnicalDetails.fromProbe(File("song.wav"), info, info.getStreams().single(), null)
        assertEquals("1:01:11", formatMediaDuration(details.durationMs))
        assertEquals(mapOf(
            R.string.media_details_bitrate to "2116 kbps",
            R.string.media_details_sample_rate to "44100 Hz",
            R.string.media_details_bit_depth to "24 bit",
            R.string.media_details_format to "WAV",
            R.string.media_details_codec to "PCM signed 24-bit little-endian",
            R.string.media_details_channels to "立体声"
        ), details.properties(isVideo = false).associate { it.label to it.value })
    }

    @Test fun validRawBitsTakePrecedenceOverPaddedStorageBits() {
        val info = FfprobeMediaInformation.parse("""
            {"streams": [{"index":0,"codec_type":"audio","codec_name":"pcm_s32le",
                "bits_per_sample":32,"bits_per_raw_sample":"24","sample_fmt":"s32"}]}
        """.trimIndent())
        val details = MediaTechnicalDetails.fromProbe(File("song.wav"), info, info.getStreams().single(), null)
        assertEquals("24 bit", details.properties(isVideo = false).single { it.label == R.string.media_details_bit_depth }.value)
    }

    @Test fun videoShowsBothCodecsAndDoesNotInventPrecisionFromDecodedSamples() {
        val info = FfprobeMediaInformation.parse("""
            {"streams": [
                {"index":0,"codec_type":"video","codec_name":"h264","width":1920,"height":1080,
                 "avg_frame_rate":"30000/1001","side_data_list":[{"rotation":-90}]},
                {"index":1,"codec_type":"audio","codec_name":"aac","sample_rate":"48000","channels":1,
                 "bits_per_sample":0,"bits_per_raw_sample":"N/A","sample_fmt":"fltp"}
            ]}
        """.trimIndent())
        val (video, audio) = info.getStreams()
        val fields = MediaTechnicalDetails.fromProbe(File("clip.mp4"), info, audio, video)
            .properties(isVideo = true).associate { it.label to it.value }
        assertEquals("h264", fields[R.string.media_details_video_codec])
        assertEquals("aac", fields[R.string.media_details_audio_codec])
        assertEquals("1080 × 1920", fields[R.string.media_details_resolution])
        assertEquals("29.97 fps", fields[R.string.media_details_frame_rate])
        assertEquals("48000 Hz", fields[R.string.media_details_sample_rate])
        assertEquals("—", fields[R.string.media_details_bit_depth])
    }
}
