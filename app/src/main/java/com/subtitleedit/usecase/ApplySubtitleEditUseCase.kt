package com.subtitleedit.usecase

import com.subtitleedit.EditorCommand
import com.subtitleedit.EditorCommandResult
import com.subtitleedit.EditorDocumentState

internal class ApplySubtitleEditUseCase {
    operator fun invoke(
        state: EditorDocumentState,
        command: EditorCommand
    ): EditorCommandResult = command.execute(state)
}
