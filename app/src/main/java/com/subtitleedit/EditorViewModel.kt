package com.subtitleedit

import androidx.lifecycle.ViewModel
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import java.io.File
import java.nio.charset.Charset

internal class EditorViewModel : ViewModel() {
    /** Document-owned state. Compatibility properties below keep the current UI unchanged. */
    val documentState = EditorDocumentState()

    var initialized = false
    var documentLoaded = false
    var filePath: String
        get() = documentState.filePath
        set(value) { documentState.filePath = value }
    var currentFile: File?
        get() = documentState.currentFile
        set(value) { documentState.currentFile = value }
    var subtitleFilePath: String
        get() = documentState.subtitleFilePath
        set(value) { documentState.subtitleFilePath = value }
    var subtitleFile: File?
        get() = documentState.subtitleFile
        set(value) { documentState.subtitleFile = value }
    var documentUri: String?
        get() = documentState.documentUri
        set(value) { documentState.documentUri = value }
    var documentTitle: String
        get() = documentState.documentTitle
        set(value) { documentState.documentTitle = value }
    var subtitleEntries: MutableList<SubtitleEntry>
        get() = documentState.subtitleEntries
        set(value) { documentState.subtitleEntries = value }
    var lastIndexedEntryCount: Int
        get() = documentState.lastIndexedEntryCount
        set(value) { documentState.lastIndexedEntryCount = value }
    var currentCharset: Charset
        get() = documentState.currentCharset
        set(value) { documentState.currentCharset = value }
    var currentFormat: SubtitleParser.SubtitleFormat
        get() = documentState.currentFormat
        set(value) { documentState.currentFormat = value }
    var originalFileContent: String
        get() = documentState.originalFileContent
        set(value) { documentState.originalFileContent = value }
    var sourceViewContent: String
        get() = documentState.sourceViewContent
        set(value) { documentState.sourceViewContent = value }
    var sourceViewNeedsListSync: Boolean
        get() = documentState.sourceViewNeedsListSync
        set(value) { documentState.sourceViewNeedsListSync = value }
    var hasUnsavedChanges: Boolean
        get() = documentState.hasUnsavedChanges
        set(value) { documentState.hasUnsavedChanges = value }
    var isNewFile: Boolean
        get() = documentState.isNewFile
        set(value) { documentState.isNewFile = value }
    var currentFormatInfo: String
        get() = documentState.currentFormatInfo
        set(value) { documentState.currentFormatInfo = value }
    var clipboardTexts: List<String>
        get() = documentState.clipboardTexts
        set(value) { documentState.clipboardTexts = value }
    var mediaType: EditorMediaType
        get() = documentState.mediaType
        set(value) { documentState.mediaType = value }
    var historyEntriesSnapshot: List<SubtitleEntry>
        get() = documentState.historyEntriesSnapshot
        set(value) { documentState.historyEntriesSnapshot = value }
    var historySelectionSnapshot: Set<Long>
        get() = documentState.historySelectionSnapshot
        set(value) { documentState.historySelectionSnapshot = value }
    var sourceHistoryTextSnapshot: String
        get() = documentState.sourceHistoryTextSnapshot
        set(value) { documentState.sourceHistoryTextSnapshot = value }
    var historyBaselineInitialized: Boolean
        get() = documentState.historyBaselineInitialized
        set(value) { documentState.historyBaselineInitialized = value }

    var isSourceViewMode = false
    var savedScrollPosition = 0
    var savedFirstVisibleItemPosition = 0
    var selectedIndices: Set<Int> = emptySet()
    var playbackPositionMs = 0L
    var playbackSpeed = 1.0f
    var selectedAudioStreamIndex: Int? = null
    var isAudioOnlyFromVideo = false
    val saveCoordinator = EditorSaveCoordinator()
    val editHistory = EditorEditHistory()

    fun startNewSubtitleDocument() = documentState.startNewSubtitleDocument()

    fun openUriSubtitleDocument(uri: String, subtitleTitle: String) =
        documentState.openUriSubtitleDocument(uri, subtitleTitle)

    fun saveUriSubtitleDocument(uri: String, subtitleTitle: String) =
        documentState.saveUriSubtitleDocument(uri, subtitleTitle)
}
