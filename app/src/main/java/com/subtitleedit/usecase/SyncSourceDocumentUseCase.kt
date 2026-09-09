package com.subtitleedit.usecase

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleSourceSynchronizer

internal class SyncSourceDocumentUseCase {
    operator fun invoke(
        content: String,
        format: SubtitleParser.SubtitleFormat,
        oldEntries: List<SubtitleEntry>,
        newEntries: List<SubtitleEntry>
    ): String = SubtitleSourceSynchronizer.apply(
        content = content,
        format = format,
        oldEntries = oldEntries,
        newEntries = newEntries
    )
}
