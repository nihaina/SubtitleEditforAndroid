package com.subtitleedit

import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Editor document state kept separate from transient view and playback state.
 *
 * The fields remain mutable for now to preserve the editor's existing update flow. The
 * compatibility properties in [EditorViewModel] can be removed after callers migrate to this
 * boundary.
 */
internal class EditorDocumentState {
    var filePath = ""
    var currentFile: File? = null
    var subtitleFilePath = ""
    var subtitleFile: File? = null
    var documentUri: String? = null
    var documentTitle = "未命名"
    var subtitleEntries = mutableListOf<SubtitleEntry>()
    var lastIndexedEntryCount = -1
    var currentCharset: Charset = StandardCharsets.UTF_8
    var currentFormat: SubtitleParser.SubtitleFormat = SubtitleParser.SubtitleFormat.UNKNOWN
    var originalFileContent = ""
    var sourceViewContent = ""
    var sourceViewNeedsListSync = false
    var hasUnsavedChanges = false
    var isNewFile = true
    var currentFormatInfo = ""
    var clipboardTexts: List<String> = emptyList()
    var mediaType = EditorMediaType.SUBTITLE_ONLY
    var historyEntriesSnapshot: List<SubtitleEntry> = emptyList()
    var historySelectionSnapshot: Set<Long> = emptySet()
    var sourceHistoryTextSnapshot = ""
    var historyBaselineInitialized = false

    fun startNewSubtitleDocument() {
        clearSubtitleDocumentReference()
        if (!mediaType.hasPlayableMedia) {
            filePath = ""
            currentFile = null
        }
        documentTitle = titleForSubtitleDocument("未命名")
        isNewFile = true
    }

    fun openUriSubtitleDocument(uri: String, subtitleTitle: String) {
        clearSubtitleDocumentReference()
        if (!mediaType.hasPlayableMedia) {
            filePath = ""
            currentFile = null
        }
        documentUri = uri
        documentTitle = titleForSubtitleDocument(subtitleTitle)
        isNewFile = false
    }

    fun saveUriSubtitleDocument(uri: String, subtitleTitle: String) {
        subtitleFilePath = ""
        subtitleFile = null
        documentUri = uri
        documentTitle = titleForSubtitleDocument(subtitleTitle)
        isNewFile = false
    }

    private fun clearSubtitleDocumentReference() {
        subtitleFilePath = ""
        subtitleFile = null
        documentUri = null
    }

    private fun titleForSubtitleDocument(subtitleTitle: String): String {
        return if (mediaType.hasPlayableMedia) {
            currentFile?.name ?: subtitleTitle
        } else {
            subtitleTitle
        }
    }
}
