package com.subtitleedit.usecase

import com.subtitleedit.repository.DefaultSubtitleRepository
import com.subtitleedit.repository.SubtitleRepository
import com.subtitleedit.util.SubtitleParser

internal class ConvertSubtitleFormatUseCase(
    private val repository: SubtitleRepository = DefaultSubtitleRepository()
) {
    operator fun invoke(
        content: String,
        from: SubtitleParser.SubtitleFormat,
        to: SubtitleParser.SubtitleFormat
    ): String = repository.convert(content, from, to)
}
