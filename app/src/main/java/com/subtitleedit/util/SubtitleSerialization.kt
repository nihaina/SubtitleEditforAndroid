package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.subtitle.SubtitleDocument

internal object SubtitleSerialization {
    fun serialize(
        document: SubtitleDocument,
        format: SubtitleParser.SubtitleFormat,
        entries: List<SubtitleEntry>,
        sourceContent: String
    ): String {
        if (format == SubtitleParser.SubtitleFormat.ASS ||
            format == SubtitleParser.SubtitleFormat.SSA
        ) return sourceContent

        return SubtitleParser.serialize(
            document.copy(
                format = format,
                entries = entries.map { it.copy() }
            )
        )
    }
}
