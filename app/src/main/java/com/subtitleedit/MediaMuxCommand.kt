package com.subtitleedit

import java.util.Locale

internal data class MediaMuxStream(val index: Long, val type: String, val codec: String, val picture: Boolean = false)
internal data class MediaMuxProbe(val format: String, val streams: List<MediaMuxStream>, val durationSeconds: Double?) {
    val videos get() = streams.filter { it.type == "video" && !it.picture }
    val audios get() = streams.filter { it.type == "audio" }
}
internal data class MediaMuxAudioFormat(val extension: String, val muxer: String)

/** Commands preserve coded packets; unsupported container/codec combinations fail explicitly. */
internal object MediaMuxCommand {
    fun videoMuxer(fileName: String, probeFormat: String): String = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "mkv" -> "matroska"
        "webm" -> "webm"
        "mp4", "m4v" -> "mp4"
        "mov" -> "mov"
        "avi" -> "avi"
        "wmv", "asf" -> "asf"
        "ts", "mts", "m2ts" -> "mpegts"
        "mpg", "mpeg" -> "mpeg"
        "3gp" -> "3gp"
        else -> probeFormat.split(',').firstOrNull().orEmpty().also { require(it.isNotBlank()) { "无法确定视频封装格式" } }
    }

    fun audioFormat(codecs: List<String>): MediaMuxAudioFormat {
        require(codecs.isNotEmpty()) { "视频没有可分离的音轨" }
        if (codecs.size > 1) return MediaMuxAudioFormat("mka", "matroska")
        return when (val codec = codecs.single()) {
            "aac", "alac" -> MediaMuxAudioFormat("m4a", "ipod")
            "mp3" -> MediaMuxAudioFormat("mp3", "mp3")
            "mp2" -> MediaMuxAudioFormat("mp2", "mp2")
            "flac" -> MediaMuxAudioFormat("flac", "flac")
            "opus" -> MediaMuxAudioFormat("opus", "opus")
            "vorbis" -> MediaMuxAudioFormat("ogg", "ogg")
            "ac3" -> MediaMuxAudioFormat("ac3", "ac3")
            "eac3" -> MediaMuxAudioFormat("eac3", "eac3")
            "dts" -> MediaMuxAudioFormat("dts", "dts")
            "truehd" -> MediaMuxAudioFormat("thd", "truehd")
            "wmav1", "wmav2", "wmapro", "wmalossless" -> MediaMuxAudioFormat("wma", "asf")
            "pcm_u8", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_f64le",
            "pcm_alaw", "pcm_mulaw" -> MediaMuxAudioFormat("wav", "wav")
            else -> MediaMuxAudioFormat("mka", "matroska")
        }
    }

    fun merge(video: String, audio: String, output: String, muxer: String, source: MediaMuxProbe,
              external: MediaMuxProbe, action: MediaMuxAudioAction): List<String> = buildList {
        require(source.videos.isNotEmpty()) { "左列文件没有视频轨" }
        require(external.audios.isNotEmpty()) { "中列文件没有音轨" }
        addAll(listOf("-y", "-hide_banner", "-i", video, "-i", audio))
        source.videos.forEach { addAll(listOf("-map", "0:${it.index}")) }
        if (action == MediaMuxAudioAction.ADD) source.audios.forEach { addAll(listOf("-map", "0:${it.index}")) }
        external.audios.forEach { addAll(listOf("-map", "1:${it.index}")) }
        addAll(listOf("-c", "copy", "-map_metadata", "0", "-map_chapters", "0", "-f", muxer, output))
    }

    fun splitVideo(input: String, output: String, muxer: String, probe: MediaMuxProbe): List<String> = buildList {
        require(probe.videos.isNotEmpty()) { "左列文件没有视频轨" }
        addAll(listOf("-y", "-hide_banner", "-i", input))
        probe.videos.forEach { addAll(listOf("-map", "0:${it.index}")) }
        addAll(listOf("-c", "copy", "-an", "-map_metadata", "0", "-map_chapters", "0", "-f", muxer, output))
    }

    fun splitAudio(input: String, output: String, muxer: String, probe: MediaMuxProbe): List<String> = buildList {
        require(probe.audios.isNotEmpty()) { "视频没有可分离的音轨" }
        addAll(listOf("-y", "-hide_banner", "-i", input))
        probe.audios.forEach { addAll(listOf("-map", "0:${it.index}")) }
        addAll(listOf("-c", "copy", "-vn", "-map_metadata", "0", "-f", muxer, output))
    }
}
