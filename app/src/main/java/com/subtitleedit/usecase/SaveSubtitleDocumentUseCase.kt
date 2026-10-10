package com.subtitleedit.usecase

import com.subtitleedit.repository.DefaultSubtitleRepository
import com.subtitleedit.repository.SubtitleRepository
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.LrcSubtitleFormatHandler
import com.subtitleedit.util.subtitle.SubtitleDocument

internal class SaveSubtitleDocumentUseCase(
    private val repository: SubtitleRepository = DefaultSubtitleRepository()
) {
    operator fun invoke(
        document: SubtitleDocument,
        sourceContent: String,
        sourceViewMode: Boolean,
        requireNonEmptyList: Boolean = false
    ): String? {
        if (sourceViewMode) return if (document.format == SubtitleParser.SubtitleFormat.LRC) {
            LrcSubtitleFormatHandler.normalizeSourceForWrite(sourceContent)
        } else sourceContent
        if (requireNonEmptyList && document.entries.isEmpty()) return null
        return repository.save(document)
    }
}
