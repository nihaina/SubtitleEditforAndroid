package com.subtitleedit

/** Audio file extensions, muxers and encoders share the same parameter profiles as video audio tracks. */
internal object MediaConvertAudioFormats {
    val wave = listOf("pcm_s16le", "pcm_s24le", "pcm_f32le", "pcm_s32le", "pcm_f64le", "pcm_u8", "pcm_alaw", "pcm_mulaw",
        "adpcm_ima_wav", "adpcm_ms", "libmp3lame", "mp2", "ac3", "aac", "dca", "flac", "libgsm_ms")
    val aiff = listOf("pcm_s16be", "pcm_s24be", "pcm_s32be", "pcm_s8", "pcm_u8", "pcm_s16le", "pcm_f32be", "pcm_f64be",
        "pcm_alaw", "pcm_mulaw", "adpcm_ima_qt")
    val au = listOf("pcm_s16be", "pcm_s24be", "pcm_s32be", "pcm_s8", "pcm_f32be", "pcm_f64be", "pcm_alaw", "pcm_mulaw")
    val caf = listOf("pcm_s16le", "alac", "pcm_s8", "pcm_alaw", "pcm_mulaw", "adpcm_ima_qt", "adpcm_ima_wav", "adpcm_ms",
        "libopencore_amrnb", "libgsm", "libgsm_ms", "mp2", "libmp3lame", "flac", "ac3") +
        MediaConvertAudioCodecs.pcmInteger + MediaConvertAudioCodecs.pcmFloat + MediaConvertAudioCodecs.pcmFloatBigEndian
    val ircam = listOf("pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_s8", "pcm_f32le", "pcm_f64le", "pcm_alaw", "pcm_mulaw")
    private val ogg = listOf("libvorbis", "libopus", "flac", "libspeex")

    fun create(encoders: Set<String>): List<MediaConvertFormatInfo> {
        fun audio(ext: String, muxer: String, codecs: List<String>, mime: String): MediaConvertFormatInfo? {
            val available = codecs.distinct().filter { it in encoders }
            if (available.isEmpty()) return null
            return MediaConvertFormatInfo(ext, ext.uppercase(java.util.Locale.ROOT), muxer, emptyList(),
                listOf("auto") + available + "copy", isAudioOnly = true, mimeType = mime)
        }
        return listOfNotNull(
            audio("mp3", "mp3", listOf("libmp3lame"), "audio/mpeg"),
            audio("aac", "adts", listOf("aac"), "audio/aac"),
            audio("m4a", "ipod", listOf("aac", "alac", "ac3"), "audio/mp4"),
            audio("wav", "wav", wave, "audio/wav"),
            audio("flac", "flac", listOf("flac"), "audio/flac"),
            audio("ogg", "ogg", ogg, "audio/ogg"),
            audio("opus", "opus", listOf("libopus"), "audio/opus"),
            audio("wma", "asf", listOf("wmav2", "wmav1"), "audio/x-ms-wma"),
            audio("ac3", "ac3", listOf("ac3"), "audio/ac3"),
            audio("amr", "amr", listOf("libopencore_amrnb", "libvo_amrwbenc"), "audio/amr"),
            audio("awb", "amr", listOf("libvo_amrwbenc"), "audio/amr-wb"),
            audio("aiff", "aiff", aiff, "audio/x-aiff"),
            audio("aif", "aiff", aiff, "audio/x-aiff"),
            audio("mp2", "mp2", listOf("mp2"), "audio/mpeg"),
            audio("gsm", "gsm", listOf("libgsm"), "audio/x-gsm"),
            audio("dts", "dts", listOf("dca"), "audio/vnd.dts"),
            audio("au", "au", au, "audio/basic"),
            audio("snd", "au", au, "audio/basic"),
            audio("caf", "caf", caf, "audio/x-caf"),
            audio("wv", "wv", listOf("wavpack"), "audio/wavpack"),
            audio("oga", "ogg", ogg, "audio/ogg"),
            audio("w64", "w64", wave, "audio/x-w64"),
            audio("voc", "voc", listOf("pcm_u8", "pcm_s16le", "pcm_alaw", "pcm_mulaw"), "audio/x-voc"),
            audio("spx", "spx", listOf("libspeex"), "audio/ogg"),
            audio("ircam", "ircam", ircam, "audio/x-ircam"),
            audio("mka", "matroska", MediaConvertAudioCodecs.matroska, "audio/x-matroska"),
            audio("tta", "tta", listOf("tta"), "audio/x-tta"),
            audio("eac3", "eac3", listOf("eac3"), "audio/eac3"),
            audio("mlp", "mlp", listOf("mlp"), "audio/vnd.dolby.mlp"),
            audio("thd", "truehd", listOf("truehd"), "audio/vnd.dolby.mlp"),
            audio("m4b", "ipod", listOf("aac", "alac", "ac3"), "audio/mp4")
        )
    }
}
