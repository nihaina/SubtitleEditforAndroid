package com.subtitleedit.usecase

import com.subtitleedit.EditorCommand
import com.subtitleedit.EditorCommandResult
import com.subtitleedit.EditorDocumentState
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.WebVttSubtitleFormatHandler

internal class ApplySubtitleEditUseCase {
    operator fun invoke(
        state: EditorDocumentState,
        command: EditorCommand
    ): EditorCommandResult {
        val result = command.execute(state)
        if (state.currentFormat == SubtitleParser.SubtitleFormat.VTT &&
            (command is EditorCommand.UpdateText || command is EditorCommand.UpdateTexts)
        ) {
            // Text and derived alignment metadata must change together, including
            // when the command is prepared on a detached row before committing.
            result.changedPositions.forEach { position ->
                state.subtitleEntries.getOrNull(position)?.let { entry ->
                    entry.cueSettings = WebVttSubtitleFormatHandler.getCueSettings(entry)
                }
            }
        }
        return result
    }
}
