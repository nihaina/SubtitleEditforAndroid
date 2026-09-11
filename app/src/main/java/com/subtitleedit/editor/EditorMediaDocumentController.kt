package com.subtitleedit.editor

import java.io.File
import kotlinx.coroutines.CancellationException

/** Chooses the media preparation path and subtitle companion file. */
internal class EditorMediaDocumentController {
    sealed interface Preparation {
        data class Companion(val file: File) : Preparation
        data object Empty : Preparation
    }

    fun subtitlePreparation(path: String?, restoreDocument: Boolean): Preparation {
        if (restoreDocument) return Preparation.Empty
        if (path.isNullOrBlank()) return Preparation.Empty
        val file = File(path)
        return if (file.exists()) Preparation.Companion(file) else Preparation.Empty
    }
}
