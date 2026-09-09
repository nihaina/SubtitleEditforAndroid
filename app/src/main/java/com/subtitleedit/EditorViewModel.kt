package com.subtitleedit

import androidx.lifecycle.ViewModel
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.SubtitleDocument
import com.subtitleedit.repository.DefaultSubtitleRepository
import com.subtitleedit.repository.SubtitleRepository
import com.subtitleedit.usecase.ApplySubtitleEditUseCase
import com.subtitleedit.usecase.LoadSubtitleDocumentUseCase
import com.subtitleedit.usecase.SaveSubtitleDocumentUseCase
import com.subtitleedit.usecase.SyncSourceDocumentUseCase
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class EditorViewModel(
    val subtitleRepository: SubtitleRepository = DefaultSubtitleRepository()
) : ViewModel() {
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
    private val editHistory = EditorEditHistory()
    private val editHistoryController = EditorHistoryController(editHistory)
    private val loadSubtitleDocument = LoadSubtitleDocumentUseCase(subtitleRepository)
    private val saveSubtitleDocument = SaveSubtitleDocumentUseCase(subtitleRepository)
    private val applySubtitleEdit = ApplySubtitleEditUseCase()
    private val syncSourceDocument = SyncSourceDocumentUseCase()

    fun peekUndo(): EditorEditHistory.Operation? = editHistory.peekUndo()

    fun peekRedo(): EditorEditHistory.Operation? = editHistory.peekRedo()

    fun peekUndoWithoutSelection(): EditorEditHistory.Operation? =
        editHistory.peekUndoWithoutSelection()

    fun peekRedoWithoutSelection(): EditorEditHistory.Operation? =
        editHistory.peekRedoWithoutSelection()

    fun clearHistory() {
        editHistory.clear()
    }

    fun updateLatestSourceHistory(afterText: String, entries: List<SubtitleEntry>) {
        editHistory.updateLatestSourceAfterEntries(afterText, entries)
    }

    fun setHistoryBaseline(state: EditorEditHistory.ListState, sourceText: String, clear: Boolean) {
        if (clear) editHistory.clear()
        documentState.historyEntriesSnapshot = state.entries
        documentState.historySelectionSnapshot = state.selectedIds
        documentState.sourceHistoryTextSnapshot = sourceText
        documentState.historyBaselineInitialized = true
    }

    fun syncHistoryBaseline(state: EditorEditHistory.ListState, sourceText: String?, sourceMode: Boolean) {
        if (!documentState.historyBaselineInitialized) {
            setHistoryBaseline(state, sourceText.orEmpty(), clear = false)
            return
        }
        documentState.historyEntriesSnapshot = state.entries
        documentState.historySelectionSnapshot = state.selectedIds
        if (sourceMode && sourceText != null) documentState.sourceHistoryTextSnapshot = sourceText
    }

    fun recordListHistory(
        before: EditorEditHistory.ListState,
        after: EditorEditHistory.ListState,
        description: String,
        beforeSourceText: String?,
        afterSourceText: String?
    ): Boolean {
        val difference = EditorEditHistory.difference(before, after)
        if (difference.isEmpty) return false
        editHistory.record(
            EditorEditHistory.Operation.ListChange(
                before = before,
                after = after,
                description = description,
                beforeSourceText = beforeSourceText,
                afterSourceText = afterSourceText
            )
        )
        documentState.historyEntriesSnapshot = after.entries
        documentState.historySelectionSnapshot = after.selectedIds
        return true
    }

    fun recordSourceHistory(
        beforeText: String,
        afterText: String,
        description: String,
        beforeEntries: List<SubtitleEntry>,
        beforeEntriesText: String?
    ): Boolean {
        if (beforeText == afterText) return false
        editHistory.record(
            EditorEditHistory.Operation.SourceChange(
                beforeText = beforeText,
                afterText = afterText,
                description = description,
                beforeEntries = beforeEntries,
                beforeEntriesText = beforeEntriesText
            )
        )
        documentState.sourceHistoryTextSnapshot = afterText
        return true
    }

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
        val document = loadSubtitleDocument(content, fileName)
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
        return saveSubtitleDocument(
            document = documentState.subtitleDocument,
            sourceContent = sourceContent ?: documentState.sourceViewContent,
            sourceViewMode = uiState.value.isSourceViewMode,
            requireNonEmptyList = requireNonEmptyList
        )
    }

    fun execute(command: EditorCommand): EditorCommandResult {
        val result = applySubtitleEdit(documentState, command)
        if (result.changedPositions.isNotEmpty() || result.structureChanged) publishDocument()
        return result
    }

    fun executeHistoryCommand(
        command: EditorHistoryCommand,
        undo: Boolean
    ): EditorHistoryCommandResult {
        val result = command.execute(documentState, undo)
        publishDocument()
        return result
    }

    fun refreshDocument() {
        publishDocument()
    }

    fun syncListChangesToSource(
        beforeEntries: List<SubtitleEntry>,
        afterEntries: List<SubtitleEntry>
    ) {
        val updatedSource = syncSourceDocument(
            content = documentState.originalFileContent,
            format = documentState.currentFormat,
            oldEntries = beforeEntries,
            newEntries = afterEntries
        )
        documentState.originalFileContent = updatedSource
        documentState.sourceViewContent = updatedSource
        documentState.sourceViewNeedsListSync = false
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

    fun undoCommand(
        isSourceViewMode: Boolean,
        apply: (EditorHistoryCommand, Boolean) -> Unit
    ): Boolean = editHistoryController.undoCommand(isSourceViewMode, apply)

    fun redoCommand(
        isSourceViewMode: Boolean,
        apply: (EditorHistoryCommand, Boolean) -> Unit
    ): Boolean = editHistoryController.redoCommand(isSourceViewMode, apply)

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
