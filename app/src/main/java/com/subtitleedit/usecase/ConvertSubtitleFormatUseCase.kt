package com.subtitleedit.usecase

import com.subtitleedit.util.SubtitleParser

internal class ConvertSubtitleFormatUseCase {
    operator fun invoke(
        content: String,
        from: SubtitleParser.SubtitleFormat,
        to: SubtitleParser.SubtitleFormat
    ): String = SubtitleParser.convertFormat(content, from, to)
}
