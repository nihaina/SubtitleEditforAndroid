package com.subtitleedit

internal data class MediaConvertFormatInfo(
    val extension: String,
    val displayName: String,
    val formatName: String,
    val videoCodecs: List<String>,
    val audioCodecs: List<String>,
    val isAudioOnly: Boolean = false,
    val mimeType: String
) {
    fun videoEncoder(selected: String) = selected.takeIf { it != "auto" && it in videoCodecs }
        ?: videoCodecs.first { it != "auto" && it != "copy" }

    fun audioEncoder(selected: String) = selected.takeIf { it != "auto" && it in audioCodecs }
        ?: audioCodecs.first { it != "auto" && it != "copy" }
}

/** Container compatibility is separate from whether the bundled FFmpeg has an encoder. */
internal object MediaConvertFormats {
    val baselineEncoders = setOf("libx264", "mpeg1video", "mpeg2video", "mpeg4", "libvpx", "libvpx-vp9", "wmv2", "msmpeg4v3", "flv")

    fun create(encoders: Set<String>, audioEncoders: Set<String> = MediaConvertAudioCodecs.baselineEncoders): List<MediaConvertFormatInfo> {
        fun video(ext: String, muxer: String, codecs: List<String>, audio: List<String>, mime: String) =
            MediaConvertFormatInfo(ext, if (ext == "webm") "WebM" else ext.uppercase(java.util.Locale.ROOT), muxer,
                listOf("auto") + codecs.filter { it in encoders } + "copy", listOf("auto") + audio.distinct().filter { it in audioEncoders } + "copy", mimeType = mime)
        return listOf(
            video("mp4", "mp4", listOf("libx264", "libx265", "mpeg4", "libaom-av1"), MediaConvertAudioCodecs.mp4, "video/mp4"),
            video("mkv", "matroska", listOf("libx264", "libx265", "mpeg1video", "mpeg2video", "mpeg4", "libxvid", "libvpx", "libvpx-vp9", "libaom-av1"), MediaConvertAudioCodecs.matroska, "video/x-matroska"),
            video("avi", "avi", listOf("mpeg4", "libxvid", "libx264", "mpeg1video", "mpeg2video"), listOf("libmp3lame", "ac3", "pcm_s16le") + MediaConvertAudioCodecs.riffAudio, "video/x-msvideo"),
            video("mov", "mov", listOf("libx264", "libx265", "mpeg4"), MediaConvertAudioCodecs.mov, "video/quicktime"),
            video("mpg", "mpeg", listOf("mpeg2video", "mpeg1video"), listOf("mp2", "ac3", "libmp3lame", "dca", "pcm_dvd"), "video/mpeg"),
            video("mpeg", "mpeg", listOf("mpeg2video", "mpeg1video"), listOf("mp2", "ac3", "libmp3lame", "dca", "pcm_dvd"), "video/mpeg"),
            video("m4v", "ipod", listOf("libx264", "mpeg4"), listOf("aac", "alac", "ac3"), "video/mp4"),
            video("wmv", "asf", listOf("wmv2", "msmpeg4v3"), listOf("wmav2", "wmav1") + MediaConvertAudioCodecs.riffAudio, "video/x-ms-wmv"),
            video("flv", "flv", listOf("libx264", "flv"), listOf("aac", "libmp3lame", "libspeex", "adpcm_swf", "nellymoser", "pcm_alaw", "pcm_mulaw", "pcm_u8", "pcm_s16le", "libopus", "flac", "ac3", "eac3"), "video/x-flv"),
            video("vob", "vob", listOf("mpeg2video", "mpeg1video"), listOf("ac3", "mp2", "libmp3lame", "dca", "pcm_dvd"), "video/mpeg"),
            video("ts", "mpegts", listOf("libx264", "libx265", "mpeg2video", "mpeg1video", "mpeg4"), listOf("aac", "libmp3lame", "ac3", "mp2", "eac3", "dca", "libopus", "truehd"), "video/mp2t"),
            video("webm", "webm", listOf("libvpx-vp9", "libvpx", "libaom-av1"), listOf("libopus", "libvorbis"), "video/webm"),
            video("asf", "asf", listOf("wmv2", "msmpeg4v3"), listOf("wmav2", "wmav1", "libmp3lame") + MediaConvertAudioCodecs.riffAudio, "video/x-ms-asf"),
            video("3gp", "3gp", listOf("mpeg4", "libx264"), listOf("aac", "libopencore_amrnb", "libvo_amrwbenc"), "video/3gpp"),
            video("f4v", "f4v", listOf("libx264"), listOf("aac", "libmp3lame"), "video/mp4"),
            video("3g2", "3g2", listOf("mpeg4", "libx264"), listOf("aac", "libopencore_amrnb", "libvo_amrwbenc"), "video/3gpp2"),
            video("ogv", "ogg", listOf("libtheora", "libvpx"), listOf("libvorbis", "libopus", "flac", "libspeex"), "video/ogg")
        ) + MediaConvertAudioFormats.create(audioEncoders)
    }

    val codecNames = mapOf(
        "libx264" to "H.264 (AVC)", "libx265" to "H.265 (HEVC, 10-bit)",
        "mpeg1video" to "MPEG-1", "mpeg2video" to "MPEG-2", "mpeg4" to "MPEG-4 Part 2",
        "libxvid" to "Xvid (MPEG-4 Part 2)", "libvpx" to "VP8", "libvpx-vp9" to "VP9",
        "libaom-av1" to "AV1", "libtheora" to "Theora", "wmv2" to "WMV 8", "msmpeg4v3" to "MS MPEG-4 v3",
        "flv" to "FLV1 (Sorenson Spark)", "aac" to "AAC", "libmp3lame" to "MP3",
        "libopus" to "Opus", "libvorbis" to "Vorbis", "flac" to "FLAC", "ac3" to "AC-3",
        "mp2" to "MP2", "wmav1" to "WMA v1", "wmav2" to "WMA v2",
        "eac3" to "E-AC-3 (Dolby Digital Plus)", "dca" to "DTS (DCA)", "alac" to "ALAC (Apple Lossless)",
        "adpcm_swf" to "SWF ADPCM", "adpcm_ima_wav" to "IMA ADPCM (WAV)", "adpcm_ms" to "Microsoft ADPCM", "adpcm_ima_qt" to "IMA ADPCM (QuickTime)",
        "libopencore_amrnb" to "AMR-NB", "libvo_amrwbenc" to "AMR-WB", "libspeex" to "Speex",
        "real_144" to "RealAudio 1.0 (14.4K)", "tta" to "TTA (True Audio Lossless)", "wavpack" to "WavPack (Lossless)",
        "truehd" to "Dolby TrueHD", "mlp" to "MLP (Meridian Lossless)", "nellymoser" to "Nellymoser",
        "pcm_alaw" to "PCM A-Law (G.711)", "pcm_mulaw" to "PCM μ-Law (G.711)", "pcm_s8" to "PCM S8", "pcm_u8" to "PCM U8",
        "pcm_s16le" to "PCM S16LE", "pcm_s16be" to "PCM S16BE", "pcm_s24le" to "PCM S24LE", "pcm_s24be" to "PCM S24BE",
        "pcm_s32le" to "PCM S32LE", "pcm_s32be" to "PCM S32BE", "pcm_f32le" to "PCM Float 32LE", "pcm_f32be" to "PCM Float 32BE",
        "pcm_f64le" to "PCM Float 64LE", "pcm_f64be" to "PCM Float 64BE", "pcm_dvd" to "DVD LPCM",
        "libgsm" to "GSM 06.10", "libgsm_ms" to "GSM (Microsoft)"
    )

    fun parseEncoders(log: String): Set<String> = Regex("(?m)^\\s*V[A-Z.]{5}\\s+(\\S+)\\s")
        .findAll(log).map { it.groupValues[1] }.toSet()
}

internal data class MediaConvertOptions(
    val selectedVideoCodec: String = "auto",
    val selectedAudioCodec: String = "auto",
    val resolution: MediaConvertResolutionSettings = MediaConvertResolutionSettings(),
    val videoBitrate: MediaConvertVideoBitrateSettings = MediaConvertVideoBitrateSettings(),
    val selectedQualityId: String = "-1",
    val customQuality: String = "",
    val audioBitrate: String = "",
    val sampleRateIndex: Int = 0,
    val channelIndex: Int = 0,
    val audioVolumePercent: Int = MediaConvertAudioVolume.DEFAULT,
    val audioVbrQuality: Int = MediaConvertAudioVbr.OFF,
    val videoSettings: MediaConvertVideoSettings = MediaConvertVideoSettings()
)
