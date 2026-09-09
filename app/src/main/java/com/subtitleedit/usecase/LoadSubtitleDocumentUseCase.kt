package com.subtitleedit.usecase

import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.SubtitleDocument

internal class LoadSubtitleDocumentUseCase {
    operator fun invoke(content: String, fileName: String? = null): SubtitleDocument =
        SubtitleParser.parseDocument(content, fileName)
}
