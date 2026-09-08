package com.subtitleedit

import androidx.lifecycle.ViewModel
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class EditorViewModel : ViewModel() {
    /** Document-owned state. Compatibility properties below keep the current UI unchanged. */
    val documentState = EditorDocumentState()

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    var initialized: Boolean
        get() = uiState.value.initialized
        set(value) { updateUiState { copy(initialized = value) } }
    var documentLoaded: Boolean
        get() = uiState.value.documentLoaded
        set(value) { updateUiState { copy(documentLoaded = value) } }
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

    var isSourceViewMode: Boolean
        get() = uiState.value.isSourceViewMode
        set(value) { updateUiState { copy(isSourceViewMode = value) } }
    var savedScrollPosition: Int
        get() = uiState.value.savedScrollPosition
        set(value) { updateUiState { copy(savedScrollPosition = value) } }
    var savedFirstVisibleItemPosition: Int
        get() = uiState.value.savedFirstVisibleItemPosition
        set(value) { updateUiState { copy(savedFirstVisibleItemPosition = value) } }
    var selectedIndices: Set<Int>
        get() = uiState.value.selectedIndices
        set(value) { updateUiState { copy(selectedIndices = value) } }
    var playbackPositionMs: Long
        get() = uiState.value.playbackPositionMs
        set(value) { updateUiState { copy(playbackPositionMs = value) } }
    var playbackSpeed: Float
        get() = uiState.value.playbackSpeed
        set(value) { updateUiState { copy(playbackSpeed = value) } }
    var selectedAudioStreamIndex: Int?
        get() = uiState.value.selectedAudioStreamIndex
        set(value) { updateUiState { copy(selectedAudioStreamIndex = value) } }
    var isAudioOnlyFromVideo: Boolean
        get() = uiState.value.isAudioOnlyFromVideo
        set(value) { updateUiState { copy(isAudioOnlyFromVideo = value) } }
    val saveCoordinator = EditorSaveCoordinator()
    val editHistory = EditorEditHistory()

    fun startNewSubtitleDocument() = documentState.startNewSubtitleDocument()

    fun openUriSubtitleDocument(uri: String, subtitleTitle: String) =
        documentState.openUriSubtitleDocument(uri, subtitleTitle)

    fun saveUriSubtitleDocument(uri: String, subtitleTitle: String) =
        documentState.saveUriSubtitleDocument(uri, subtitleTitle)

    private inline fun updateUiState(transform: EditorUiState.() -> EditorUiState) {
        _uiState.value = transform(_uiState.value)
    }
}
