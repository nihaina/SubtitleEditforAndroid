package com.subtitleedit.util.subtitle

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser

/** LRC timestamp/output variants supported by Subtitle Edit. */
enum class LrcVariant {
    /** [mm:ss.xx] with optional explicit end marker lines. */
    CENTISECONDS,

    /** [mm:ss.xxx] with optional explicit end marker lines. */
    MILLISECONDS,

    /** [mm:ss.xx] start tags only; end marker lines are never written. */
    NO_END_TIME
}

/** 格式无关的字幕文档；entries 对应 Subtitle Edit 的 Paragraphs。 */
data class SubtitleDocument(
    val format: SubtitleParser.SubtitleFormat,
    val entries: List<SubtitleEntry>,
    val header: String = "",
    val footer: String = "",
    val lrcVariant: LrcVariant = LrcVariant.CENTISECONDS
)
