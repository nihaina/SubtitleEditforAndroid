package com.subtitleedit

import androidx.lifecycle.ViewModel
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.SubtitleDocument
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class EditorViewModel : ViewModel() {
    /** Document-owned state. Compatibility properties below keep the current UI unchanged. */
    val documentState = EditorDocumentState()

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()
    private val _document = MutableStateFlow(documentState.subtitleDocument)
    val document: StateFlow<SubtitleDocument> = _document.asStateFlow()
    private val _effects = MutableSharedFlow<EditorEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<EditorEffect> = _effects.asSharedFlow()

    var initialized: Boolean
        get() = uiState.value.initialized
        set(value) { onEvent(EditorEvent.SetInitialized(value)) }
    var documentLoaded: Boolean
        get() = uiState.value.documentLoaded
        set(value) { onEvent(EditorEvent.SetDocumentLoaded(value)) }
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
        set(value) {
            documentState.subtitleEntries = value
            publishDocument()
        }
    var lastIndexedEntryCount: Int
        get() = documentState.lastIndexedEntryCount
        set(value) { documentState.lastIndexedEntryCount = value }
    var currentCharset: Charset
        get() = documentState.currentCharset
        set(value) { documentState.currentCharset = value }
    var currentFormat: SubtitleParser.SubtitleFormat
        get() = documentState.currentFormat
        set(value) {
            documentState.currentFormat = value
            publishDocument()
        }
    var documentHeader: String
        get() = documentState.documentHeader
        set(value) {
            documentState.documentHeader = value
            publishDocument()
        }
    var documentFooter: String
        get() = documentState.documentFooter
        set(value) {
            documentState.documentFooter = value
            publishDocument()
        }
    val subtitleDocument: SubtitleDocument
        get() = documentState.subtitleDocument
    var originalFileContent: String
        get() = documentState.originalFileContent
        set(value) { documentState.originalFileContent = value }
    var sourceViewContent: String
        get() = documentState.sourceViewContent
        set(value) { documentState.sourceViewContent = value }
    var sourceViewNeedsListSync: Boolean
        get() = documentState.sourceViewNeedsListSync
        set(value) { documentState.sourceViewNeedsListSync = value }
    fun setSourceDocumentContent(content: String) {
        onEvent(EditorEvent.SetSourceDocumentContent(content))
    }
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
        set(value) { onEvent(EditorEvent.SetSourceViewMode(value)) }
    var isSourceViewTransitioning: Boolean
        get() = uiState.value.isSourceViewTransitioning
        set(value) { onEvent(EditorEvent.SetSourceViewTransitioning(value)) }
    var sourceViewEntryCount: Int
        get() = uiState.value.sourceViewEntryCount
        set(value) { onEvent(EditorEvent.SetSourceViewEntryCount(value)) }
    var savedScrollPosition: Int
        get() = uiState.value.savedScrollPosition
        set(value) { onEvent(EditorEvent.SetSavedScrollPosition(value)) }
    var savedFirstVisibleItemPosition: Int
        get() = uiState.value.savedFirstVisibleItemPosition
        set(value) { onEvent(EditorEvent.SetSavedFirstVisibleItemPosition(value)) }
    var isVideoFullscreen: Boolean
        get() = uiState.value.isVideoFullscreen
        set(value) { onEvent(EditorEvent.SetVideoFullscreen(value)) }
    var previousRequestedOrientation: Int
        get() = uiState.value.previousRequestedOrientation
        set(value) { onEvent(EditorEvent.SetPreviousRequestedOrientation(value)) }
    var videoViewportInlineIndex: Int
        get() = uiState.value.videoViewportInlineIndex
        set(value) { onEvent(EditorEvent.SetVideoViewportInlineIndex(value)) }
    var selectedIndices: Set<Int>
        get() = uiState.value.selectedIndices
        set(value) { onEvent(EditorEvent.SetSelectedIndices(value)) }
    var playbackPositionMs: Long
        get() = uiState.value.playbackPositionMs
        set(value) { onEvent(EditorEvent.SetPlaybackPositionMs(value)) }
    var playbackSpeed: Float
        get() = uiState.value.playbackSpeed
        set(value) { onEvent(EditorEvent.SetPlaybackSpeed(value)) }
    var selectedAudioStreamIndex: Int?
        get() = uiState.value.selectedAudioStreamIndex
        set(value) { onEvent(EditorEvent.SetSelectedAudioStreamIndex(value)) }
    var isAudioOnlyFromVideo: Boolean
        get() = uiState.value.isAudioOnlyFromVideo
        set(value) { onEvent(EditorEvent.SetAudioOnlyFromVideo(value)) }
    val saveCoordinator = EditorSaveCoordinator()
    val editHistory = EditorEditHistory()
    private val editHistoryController = EditorHistoryController(editHistory)

    fun startNewSubtitleDocument() = documentState.startNewSubtitleDocument()

    fun openUriSubtitleDocument(uri: String, subtitleTitle: String) =
        documentState.openUriSubtitleDocument(uri, subtitleTitle)

    fun saveUriSubtitleDocument(uri: String, subtitleTitle: String) =
        documentState.saveUriSubtitleDocument(uri, subtitleTitle)

    fun replaceDocument(document: SubtitleDocument) {
        documentState.replaceDocument(document)
        publishDocument()
    }

    fun loadSubtitleContent(content: String, fileName: String? = null): SubtitleDocument {
        val document = SubtitleParser.parseDocument(content, fileName)
        documentState.replaceDocument(document)
        documentState.originalFileContent = content
        documentState.sourceViewContent = content
        documentState.sourceViewNeedsListSync = false
        documentState.hasUnsavedChanges = false
        documentState.sourceHistoryTextSnapshot = content
        publishDocument()
        onEvent(EditorEvent.SetDocumentLoaded(true))
        return document
    }

    fun buildSaveContent(
        sourceContent: String? = null,
        requireNonEmptyList: Boolean = false
    ): String? {
        if (uiState.value.isSourceViewMode) return sourceContent ?: documentState.sourceViewContent
        if (requireNonEmptyList && documentState.subtitleEntries.isEmpty()) return null
        return SubtitleParser.serialize(documentState.subtitleDocument)
    }

    fun execute(command: EditorCommand): EditorCommandResult {
        val result = command.execute(documentState)
        if (result.changedPositions.isNotEmpty() || result.structureChanged) publishDocument()
        return result
    }

    fun refreshDocument() {
        publishDocument()
    }

    fun undo(
        isSourceViewMode: Boolean,
        apply: (EditorEditHistory.Operation, Boolean) -> Unit
    ): Boolean = editHistoryController.undo(isSourceViewMode, apply)

    fun redo(
        isSourceViewMode: Boolean,
        apply: (EditorEditHistory.Operation, Boolean) -> Unit
    ): Boolean = editHistoryController.redo(isSourceViewMode, apply)

    fun onEvent(event: EditorEvent) {
        when (event) {
            is EditorEvent.SetInitialized -> updateUiState { copy(initialized = event.value) }
            is EditorEvent.SetDocumentLoaded -> updateUiState { copy(documentLoaded = event.value) }
            is EditorEvent.SetSourceViewMode -> updateUiState { copy(isSourceViewMode = event.value) }
            is EditorEvent.SetSourceViewTransitioning ->
                updateUiState { copy(isSourceViewTransitioning = event.value) }
            is EditorEvent.SetSourceViewEntryCount ->
                updateUiState { copy(sourceViewEntryCount = event.value) }
            is EditorEvent.SetSourceDocumentContent -> {
                documentState.sourceViewContent = event.value
                documentState.originalFileContent = event.value
                documentState.sourceViewNeedsListSync = true
                documentState.hasUnsavedChanges = true
                publishDocument()
            }
            is EditorEvent.SetSavedScrollPosition ->
                updateUiState { copy(savedScrollPosition = event.value) }
            is EditorEvent.SetSavedFirstVisibleItemPosition ->
                updateUiState { copy(savedFirstVisibleItemPosition = event.value) }
            is EditorEvent.SetVideoFullscreen ->
                updateUiState { copy(isVideoFullscreen = event.value) }
            is EditorEvent.SetPreviousRequestedOrientation ->
                updateUiState { copy(previousRequestedOrientation = event.value) }
            is EditorEvent.SetVideoViewportInlineIndex ->
                updateUiState { copy(videoViewportInlineIndex = event.value) }
            is EditorEvent.SetSelectedIndices -> updateUiState { copy(selectedIndices = event.value) }
            is EditorEvent.SetPlaybackPositionMs ->
                updateUiState { copy(playbackPositionMs = event.value) }
            is EditorEvent.SetPlaybackSpeed -> updateUiState { copy(playbackSpeed = event.value) }
            is EditorEvent.SetSelectedAudioStreamIndex ->
                updateUiState { copy(selectedAudioStreamIndex = event.value) }
            is EditorEvent.SetAudioOnlyFromVideo ->
                updateUiState { copy(isAudioOnlyFromVideo = event.value) }
            is EditorEvent.ExecuteCommand -> execute(event.command)
            EditorEvent.RequestOptionsMenuRefresh ->
                _effects.tryEmit(EditorEffect.InvalidateOptionsMenu)
        }
    }

    private inline fun updateUiState(transform: EditorUiState.() -> EditorUiState) {
        _uiState.value = transform(_uiState.value)
    }

    private fun publishDocument() {
        _document.value = documentState.subtitleDocument
    }
}
