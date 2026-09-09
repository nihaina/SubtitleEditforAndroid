package com.subtitleedit.usecase

import com.subtitleedit.repository.DefaultSubtitleRepository
import com.subtitleedit.repository.SubtitleRepository
import com.subtitleedit.util.subtitle.SubtitleDocument

internal class LoadSubtitleDocumentUseCase(
    private val repository: SubtitleRepository = DefaultSubtitleRepository()
) {
    operator fun invoke(content: String, fileName: String? = null): SubtitleDocument =
        repository.load(content, fileName)
}
