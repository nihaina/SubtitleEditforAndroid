package com.subtitleedit.usecase

import com.subtitleedit.repository.DefaultSubtitleRepository
import com.subtitleedit.repository.SubtitleRepository
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
        if (sourceViewMode) return sourceContent
        if (requireNonEmptyList && document.entries.isEmpty()) return null
        return repository.save(document)
    }
}
