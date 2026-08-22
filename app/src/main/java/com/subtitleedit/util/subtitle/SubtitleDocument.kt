package com.subtitleedit.util.subtitle

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser

/** 格式无关的字幕文档；entries 对应 Subtitle Edit 的 Paragraphs。 */
data class SubtitleDocument(
    val format: SubtitleParser.SubtitleFormat,
    val entries: List<SubtitleEntry>,
    val header: String = "",
    val footer: String = ""
)
