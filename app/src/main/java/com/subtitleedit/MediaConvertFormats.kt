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

    fun create(encoders: Set<String>): List<MediaConvertFormatInfo> {
        fun video(ext: String, muxer: String, codecs: List<String>, audio: List<String>, mime: String) =
            MediaConvertFormatInfo(ext, if (ext == "webm") "WebM" else ext.uppercase(java.util.Locale.ROOT), muxer,
                listOf("auto") + codecs.filter { it in encoders } + "copy", listOf("auto") + audio + "copy", mimeType = mime)
        fun audio(ext: String, muxer: String, codecs: List<String>, mime: String) =
            MediaConvertFormatInfo(ext, ext.uppercase(java.util.Locale.ROOT), muxer, emptyList(), listOf("auto") + codecs, true, mime)
        return listOf(
            video("mp4", "mp4", listOf("libx264", "libx265", "mpeg4", "libaom-av1"), listOf("aac", "libmp3lame"), "video/mp4"),
            video("mkv", "matroska", listOf("libx264", "libx265", "mpeg1video", "mpeg2video", "mpeg4", "libxvid", "libvpx", "libvpx-vp9", "libaom-av1"), listOf("aac", "libmp3lame", "libopus", "libvorbis", "flac", "ac3"), "video/x-matroska"),
            video("avi", "avi", listOf("mpeg4", "libxvid", "libx264", "mpeg1video", "mpeg2video"), listOf("libmp3lame", "ac3", "pcm_s16le"), "video/x-msvideo"),
            video("mov", "mov", listOf("libx264", "libx265", "mpeg4"), listOf("aac", "libmp3lame", "ac3", "pcm_s16le"), "video/quicktime"),
            video("mpg", "mpeg", listOf("mpeg2video", "mpeg1video"), listOf("mp2", "ac3"), "video/mpeg"),
            video("mpeg", "mpeg", listOf("mpeg2video", "mpeg1video"), listOf("mp2", "ac3"), "video/mpeg"),
            video("m4v", "ipod", listOf("libx264", "mpeg4"), listOf("aac"), "video/mp4"),
            video("wmv", "asf", listOf("wmv2", "msmpeg4v3"), listOf("wmav2"), "video/x-ms-wmv"),
            video("flv", "flv", listOf("libx264", "flv"), listOf("aac", "libmp3lame"), "video/x-flv"),
            video("vob", "vob", listOf("mpeg2video", "mpeg1video"), listOf("ac3", "mp2"), "video/mpeg"),
            video("ts", "mpegts", listOf("libx264", "libx265", "mpeg2video", "mpeg1video", "mpeg4"), listOf("aac", "libmp3lame", "ac3", "mp2"), "video/mp2t"),
            video("webm", "webm", listOf("libvpx-vp9", "libvpx", "libaom-av1"), listOf("libopus", "libvorbis"), "video/webm"),
            video("asf", "asf", listOf("wmv2", "msmpeg4v3"), listOf("wmav2", "libmp3lame"), "video/x-ms-asf"),
            video("3gp", "3gp", listOf("mpeg4", "libx264"), listOf("aac"), "video/3gpp"),
            video("f4v", "f4v", listOf("libx264"), listOf("aac", "libmp3lame"), "video/mp4"),
            video("3g2", "3g2", listOf("mpeg4", "libx264"), listOf("aac"), "video/3gpp2"),
            video("ogv", "ogg", listOf("libtheora", "libvpx"), listOf("libvorbis", "libopus"), "video/ogg"),
            audio("mp3", "mp3", listOf("libmp3lame"), "audio/mpeg"),
            audio("aac", "adts", listOf("aac"), "audio/aac"),
            audio("m4a", "ipod", listOf("aac"), "audio/mp4"),
            audio("wav", "wav", listOf("pcm_s16le", "pcm_s24le", "pcm_f32le"), "audio/wav"),
            audio("flac", "flac", listOf("flac"), "audio/flac"),
            audio("ogg", "ogg", listOf("libvorbis", "libopus"), "audio/ogg"),
            audio("opus", "opus", listOf("libopus"), "audio/opus"),
            audio("wma", "asf", listOf("wmav2"), "audio/x-ms-wma"),
            audio("ac3", "ac3", listOf("ac3"), "audio/ac3")
        )
    }

    val codecNames = mapOf(
        "libx264" to "H.264 (AVC)", "libx265" to "H.265 (HEVC, 10-bit)",
        "mpeg1video" to "MPEG-1", "mpeg2video" to "MPEG-2", "mpeg4" to "MPEG-4 Part 2",
        "libxvid" to "Xvid (MPEG-4 Part 2)", "libvpx" to "VP8", "libvpx-vp9" to "VP9",
        "libaom-av1" to "AV1", "libtheora" to "Theora", "wmv2" to "WMV 8", "msmpeg4v3" to "MS MPEG-4 v3",
        "flv" to "FLV1 (Sorenson Spark)", "aac" to "AAC", "libmp3lame" to "MP3",
        "libopus" to "Opus", "libvorbis" to "Vorbis", "flac" to "FLAC", "ac3" to "AC-3",
        "mp2" to "MP2", "wmav2" to "WMA 2", "pcm_s16le" to "PCM 16-bit", "pcm_s24le" to "PCM 24-bit", "pcm_f32le" to "PCM Float 32-bit"
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
    val advancedExpanded: Boolean = false
)
