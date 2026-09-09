package com.subtitleedit.usecase

import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.SubtitleDocument

internal class SaveSubtitleDocumentUseCase {
    operator fun invoke(
        document: SubtitleDocument,
        sourceContent: String,
        sourceViewMode: Boolean,
        requireNonEmptyList: Boolean = false
    ): String? {
        if (sourceViewMode) return sourceContent
        if (requireNonEmptyList && document.entries.isEmpty()) return null
        return SubtitleParser.serialize(document)
    }
}
