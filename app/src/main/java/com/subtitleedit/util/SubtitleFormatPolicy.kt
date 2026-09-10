package com.subtitleedit.util

/** 字幕格式的显示、扩展名和源文本标记规则。 */
internal object SubtitleFormatPolicy {
    fun displayName(format: SubtitleParser.SubtitleFormat): String = when (format) {
        SubtitleParser.SubtitleFormat.SRT -> "SRT"
        SubtitleParser.SubtitleFormat.LRC -> "LRC"
        SubtitleParser.SubtitleFormat.TXT -> "TXT"
        SubtitleParser.SubtitleFormat.ASS -> "ASS"
        SubtitleParser.SubtitleFormat.SSA -> "SSA"
        SubtitleParser.SubtitleFormat.VTT -> "WebVTT"
        else -> "未知"
    }

    fun extension(format: SubtitleParser.SubtitleFormat): String = when (format) {
        SubtitleParser.SubtitleFormat.SRT -> "srt"
        SubtitleParser.SubtitleFormat.LRC -> "lrc"
        SubtitleParser.SubtitleFormat.TXT -> "txt"
        SubtitleParser.SubtitleFormat.ASS -> "ass"
        SubtitleParser.SubtitleFormat.SSA -> "ssa"
        SubtitleParser.SubtitleFormat.VTT -> "vtt"
        else -> "srt"
    }

    fun containsSubtitleMarker(content: String, format: SubtitleParser.SubtitleFormat): Boolean =
        when (format) {
            SubtitleParser.SubtitleFormat.SRT,
            SubtitleParser.SubtitleFormat.VTT -> content.contains("-->")
            SubtitleParser.SubtitleFormat.LRC -> Regex("\\[-?\\d{1,4}[:.]\\d{1,2}").containsMatchIn(content)
            SubtitleParser.SubtitleFormat.TXT -> content.isNotBlank()
            else -> true
        }
}
