package com.subtitleedit

import java.util.Locale

internal enum class MediaConvertSubtitleType { BURN_IN, SOFT }

/** Paths refer to task-owned UTF-8 copies, never to a SAF URL inside a filter expression. */
internal data class MediaConvertSubtitleInput(
    val path: String,
    val fileName: String,
    val type: MediaConvertSubtitleType,
    val fontsDirectory: String? = null
)

/** Soft subtitle streams already stored in the selected source container. */
internal data class MediaConvertSourceSubtitle(
    val streamIndex: Long,
    val codec: String,
    val language: String = "",
    val title: String = "",
    val isDefault: Boolean = false
)

internal object MediaConvertSubtitles {
    private val textExtensions = setOf("srt", "ass", "ssa", "vtt", "lrc")
    private val lyricExtensions = textExtensions + "txt"
    val importExtensions: Set<String> get() = lyricExtensions
    private val movMuxers = setOf("mp4", "mov", "ipod", "3gp", "3g2")
    private val textCodecs = setOf("subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text", "lrc")
    private val matroskaCopyCodecs = setOf("subrip", "srt", "ass", "ssa", "webvtt", "dvd_subtitle", "dvb_subtitle", "hdmv_pgs_subtitle")

    fun allowedExtensions(format: MediaConvertFormatInfo?, type: MediaConvertSubtitleType, videoCodec: String,
                          burnInAvailable: Boolean = true): Set<String> {
        if (format == null) return emptySet()
        if (format.isAudioOnly) return if (type == MediaConvertSubtitleType.SOFT &&
            MediaConvertAudioLyrics.maxFiles(format) > 0) lyricExtensions else emptySet()
        return when (type) {
            MediaConvertSubtitleType.BURN_IN -> if (videoCodec == "copy" || !burnInAvailable) emptySet() else textExtensions
            MediaConvertSubtitleType.SOFT -> if (format.formatName in movMuxers || format.formatName in setOf("matroska", "webm")) textExtensions else emptySet()
        }
    }

    /** Null means this source track cannot be retained by the chosen output container. */
    fun sourceEncoder(format: MediaConvertFormatInfo?, codec: String): String? {
        if (format == null || (format.isAudioOnly && format.formatName !in setOf("ipod", "matroska"))) return null
        return when {
            format.formatName == "matroska" && codec in matroskaCopyCodecs -> "copy"
            codec !in textCodecs -> null
            format.formatName in movMuxers -> "mov_text"
            format.formatName == "webm" -> "webvtt"
            format.formatName == "matroska" -> "srt"
            else -> null
        }
    }

    fun validate(format: MediaConvertFormatInfo, options: MediaConvertOptions, subtitles: List<MediaConvertSubtitleInput>) {
        require(subtitles.map { it.type }.distinct().size <= 1) { "Subtitle modes cannot be mixed" }
        require(subtitles.count { it.type == MediaConvertSubtitleType.BURN_IN } <= 1) { "Only one subtitle can be burned into the video" }
        subtitles.forEach { validate(format, options, it) }
    }

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun hasBurnInFilter(log: String) = Regex("(?m)^\\s*[.A-Z|]+\\s+subtitles\\s+V->V").containsMatchIn(log)

    fun encoder(format: MediaConvertFormatInfo, extension: String): String = when (format.formatName) {
        in movMuxers -> "mov_text"
        "webm" -> "webvtt"
        "matroska" -> when (extension) {
            "ass", "ssa" -> "ass"
            "vtt" -> "webvtt"
            else -> "srt"
        }
        else -> error("This output container cannot mux the selected text subtitle")
    }

    fun validate(format: MediaConvertFormatInfo, options: MediaConvertOptions, subtitle: MediaConvertSubtitleInput) {
        require(extension(subtitle.fileName) in allowedExtensions(format, subtitle.type,
            if (format.isAudioOnly) "" else format.videoEncoder(options.selectedVideoCodec))) {
            "Subtitle type or format is incompatible with the output container or video encoder"
        }
    }

    fun burnFilter(subtitle: MediaConvertSubtitleInput): String = buildString {
        append("subtitles=filename=").append(filterValue(subtitle.path)).append(":charenc=UTF-8")
        subtitle.fontsDirectory?.let { append(":fontsdir=").append(filterValue(it)) }
    }

    /** Escape both the filter's option parser and the surrounding filtergraph parser. */
    private fun filterValue(path: String): String {
        val option = buildString {
            path.replace('\\', '/').forEach { character ->
                if (character in "\\:'") append('\\')
                append(character)
            }
        }
        return buildString {
            option.forEach { character ->
                if (character in "\\'[],;") append('\\')
                append(character)
            }
        }
    }
}
