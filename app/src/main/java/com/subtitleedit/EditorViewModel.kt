package com.subtitleedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.editor.EditorSourceTextEdit
import com.subtitleedit.editor.EditorHistoryDescriptionFormatter
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
            timingDragSession.discardAll()
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
        // SourceEditorView already owns the visible document and emits a debounced preview.
        // History retains only the edited range, and the preview publishes parsed entries later.
        documentState.sourceViewContent = content
        documentState.originalFileContent = content
        documentState.sourceViewNeedsListSync = true
        documentState.hasUnsavedChanges = true
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
    var isVideoCollapsed: Boolean
        get() = uiState.value.isVideoCollapsed
        set(value) { onEvent(EditorEvent.SetVideoCollapsed(value)) }
    var isVideoMixed: Boolean
        get() = uiState.value.isVideoMixed
        set(value) { onEvent(EditorEvent.SetVideoMixed(value)) }
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
    val saveCoordinator = EditorSaveCoordinator()
    private val editHistory = EditorEditHistory()
    private val editHistoryController = EditorHistoryController(editHistory)
    private val loadSubtitleDocument = LoadSubtitleDocumentUseCase(subtitleRepository)
    private val saveSubtitleDocument = SaveSubtitleDocumentUseCase(subtitleRepository)
    private val applySubtitleEdit = ApplySubtitleEditUseCase()
    private val syncSourceDocument = SyncSourceDocumentUseCase()
    private val editSession = EditorEditSession(documentState, ::recordCommittedListEdit)
    private val formatEditSession = EditorFormatEditSession(documentState, recordHistory = { history ->
        recordListHistory(
            history.before, history.after, history.description, history.beforeSource, history.afterSource,
            beforeHeader = history.beforeHeader, afterHeader = history.afterHeader,
            beforeFooter = history.beforeFooter, afterFooter = history.afterFooter
        )
    })
    private val listEditSession = EditorListEditSession(
        documentState, ::recordCommittedListEdit,
        recordSource = { edit, before ->
            recordSourceHistory(edit, EditorHistoryDescriptionFormatter.describeSourceTextChange(edit, before))
        }
    )
    private val timingDragSession = EditorTimingDragSession(documentState, ::recordCommittedListEdit) { edit, before ->
        recordSourceHistory(edit, EditorHistoryDescriptionFormatter.describeSourceTextChange(edit, before))
    }

    private fun recordCommittedListEdit(
        before: EditorEditHistory.ListState, after: EditorEditHistory.ListState,
        beforeSource: String, afterSource: String
    ) {
        val difference = EditorEditHistory.difference(before, after)
        recordListHistory(
            before, after, EditorHistoryDescriptionFormatter.describeListStateChange(difference),
            beforeSource, afterSource, difference = difference
        )
    }

    fun beginWaveformDrag(key: Long, stableId: Long, selectedIds: Set<Long>): Boolean =
        !isSourceViewTransitioning && timingDragSession.begin(key, stableId, isSourceViewMode, selectedIds)

    fun waveformDrag(key: Long): EditorTimingDrag? = timingDragSession.current(key)

    fun isCurrentWaveformDrag(drag: EditorTimingDrag): Boolean =
        drag.sourceMode == isSourceViewMode && timingDragSession.isCurrent(drag)

    fun updateWaveformDrag(key: Long, range: EditorTimingRange): EditorEntryChange? = timingDragSession.update(key, range)

    fun finishWaveformDrag(key: Long, selectedIds: Set<Long>): EditorTimingDragEnd? =
        timingDragSession.finish(key, selectedIds)?.also { ended ->
            ended.change?.let { publishChangedDocument(setOf(it.position)) }
        }

    fun applyWaveformSource(drag: EditorTimingDrag, base: String, source: String, final: Boolean): Boolean {
        if (!isSourceViewMode) return false
        return timingDragSession.applySource(drag, base, source, final).also {
            // A source gesture may finish while the next queued gesture already
            // previews another row. Keep the published snapshot authoritative.
            if (it && final) publishChangedDocument()
        }
    }

    fun discardWaveformSourceEdits(): Boolean = timingDragSession.discardSourceDrags()

    val waveformSourceEditsPending: Boolean get() = timingDragSession.hasPendingSource

    fun commitSingleEdit(edit: EditorEntryEdit, selectedIds: Set<Long>): EditorEntryChange? {
        if (isSourceViewMode || isSourceViewTransitioning) return null
        val change = editSession.commit(edit, selectedIds) ?: return null
        publishChangedDocument(setOf(change.position))
        return change
    }

    fun commitListEdit(
        command: EditorCommand,
        selectedIds: Set<Long>,
        selectedAfter: Set<Long> = selectedIds,
        allowSourceMode: Boolean = false
    ): EditorListChange? {
        if (isSourceViewTransitioning || (isSourceViewMode && !allowSourceMode)) return null
        val change = listEditSession.commit(command, selectedIds, selectedAfter, isSourceViewMode) ?: return null
        publishChangedDocument(change.result.changedPositions.takeUnless { change.result.structureChanged })
        return change
    }

    private fun publishChangedDocument(changedPositions: Set<Int>? = null) {
        val previous = _document.value
        val current = documentState.subtitleEntries
        // Reuse already owned snapshot rows. A row edit allocates one row copy
        // and a shallow list instead of deep-copying the entire large document.
        val entries = if (changedPositions?.isEmpty() == true && previous.entries.size == current.size) {
            previous.entries
        } else if (changedPositions != null && previous.entries.size == current.size) {
            previous.entries.toMutableList().also { snapshot ->
                changedPositions.forEach { position ->
                    current.getOrNull(position)?.let { snapshot[position] = it.copy() }
                }
            }
        } else {
            val previousById = previous.entries.associateBy { it.stableId }
            current.map { entry -> previousById[entry.stableId]?.takeIf { it == entry } ?: entry.copy() }
        }
        _document.value = previous.copy(
            format = documentState.currentFormat, entries = entries,
            header = documentState.documentHeader, footer = documentState.documentFooter,
            lrcVariant = documentState.documentLrcVariant
        )
    }

    fun peekUndo(): EditorEditHistory.Operation? = editHistory.peekUndo()

    fun peekRedo(): EditorEditHistory.Operation? = editHistory.peekRedo()

    fun peekUndoWithoutSelection(): EditorEditHistory.Operation? =
        editHistory.peekUndoWithoutSelection()

    fun peekRedoWithoutSelection(): EditorEditHistory.Operation? =
        editHistory.peekRedoWithoutSelection()

    fun clearHistory() {
        editHistory.clear()
    }

    fun setHistoryBaseline(state: EditorEditHistory.ListState, sourceText: String, clear: Boolean) {
        if (clear) editHistory.clear()
        documentState.historyEntriesSnapshot = state.entries.map { it.copy() }.toMutableList()
        documentState.historySelectionSnapshot = state.selectedIds
        documentState.sourceHistoryTextSnapshot = sourceText
        documentState.historyBaselineInitialized = true
    }

    fun syncHistoryBaseline(state: EditorEditHistory.ListState, sourceText: String?, sourceMode: Boolean) {
        if (!documentState.historyBaselineInitialized) {
            setHistoryBaseline(state, sourceText.orEmpty(), clear = false)
            return
        }
        // Undo/redo normally changes only one row. Advance the already-owned snapshot by copying
        // changed rows instead of deep-copying the entire document at the end of each operation.
        documentState.historyEntriesSnapshot = advanceHistorySnapshot(
            previous = documentState.historyEntriesSnapshot,
            current = state.entries
        )
        documentState.historySelectionSnapshot = state.selectedIds
        if (sourceMode && sourceText != null) documentState.sourceHistoryTextSnapshot = sourceText
    }

    fun recordListHistory(
        before: EditorEditHistory.ListState,
        after: EditorEditHistory.ListState,
        description: String,
        beforeSourceText: String?,
        afterSourceText: String?,
        beforeHeader: String? = null,
        afterHeader: String? = null,
        difference: EditorEditHistory.ListDifference? = null,
        beforeFooter: String? = null,
        afterFooter: String? = null
    ): Boolean {
        val resolvedDifference = difference ?: EditorEditHistory.difference(before, after)
        if (resolvedDifference.isEmpty && beforeHeader == afterHeader && beforeFooter == afterFooter) return false
        editHistory.record(
            EditorEditHistory.Operation.ListChange.fromStates(
                before = before,
                after = after,
                description = description,
                beforeSourceText = beforeSourceText,
                afterSourceText = afterSourceText,
                beforeHeader = beforeHeader,
                afterHeader = afterHeader,
                beforeFooter = beforeFooter,
                afterFooter = afterFooter
            )
        )
        documentState.historyEntriesSnapshot = advanceHistorySnapshot(
            previous = before.entries,
            current = after.entries
        )
        documentState.historySelectionSnapshot = after.selectedIds
        return true
    }

    /**
     * Keep the history baseline independent from mutable document rows. The owned baseline list
     * is updated in place for same-order edits, copying only rows whose editable fields changed.
     * Structural changes rebuild the reference list while reusing still-equal owned rows.
     */
    @Suppress("UNCHECKED_CAST")
    private fun advanceHistorySnapshot(
        previous: List<SubtitleEntry>,
        current: List<SubtitleEntry>
    ): List<SubtitleEntry> {
        if (previous.size != current.size || previous.indices.any { previous[it].stableId != current[it].stableId }) {
            val byId = previous.associateBy { it.stableId }
            return current.map { entry -> byId[entry.stableId]?.takeIf { it == entry } ?: entry.copy() }.toMutableList()
        }
        var snapshot: MutableList<SubtitleEntry>? = previous as? MutableList<SubtitleEntry>
        current.forEachIndexed { index, entry ->
            val old = previous[index]
            if (EditorEditHistory.hasEditableDifference(old, entry)) {
                if (snapshot == null) snapshot = previous.toMutableList()
                snapshot!![index] = entry.copy()
            }
        }
        return snapshot ?: previous
    }

    fun recordSourceHistory(
        edit: EditorSourceTextEdit,
        description: String,
    ): Boolean {
        if (edit.isNoOp) return false
        editHistory.record(
            EditorEditHistory.Operation.SourceChange(
                edit = edit,
                description = description,
            )
        )
        return true
    }

    fun startNewSubtitleDocument() = documentState.startNewSubtitleDocument()

    fun openUriSubtitleDocument(uri: String, subtitleTitle: String) =
        documentState.openUriSubtitleDocument(uri, subtitleTitle)

    fun saveUriSubtitleDocument(uri: String, subtitleTitle: String) =
        documentState.saveUriSubtitleDocument(uri, subtitleTitle)

    fun replaceDocument(document: SubtitleDocument) {
        timingDragSession.discardAll()
        documentState.replaceDocument(document)
        publishDocument()
    }

    fun loadSubtitleContent(content: String, fileName: String? = null): SubtitleDocument {
        val document = loadSubtitleDocument(content, fileName)
        timingDragSession.discardAll()
        documentState.replaceDocument(document)
        documentState.originalFileContent = content
        documentState.sourceViewContent = content
        documentState.sourceViewNeedsListSync = false
        documentState.hasUnsavedChanges = false
        documentState.sourceHistoryTextSnapshot = content
        // The parser result is not mutated during the open flow. Reuse it for the initial
        // StateFlow publication instead of asking subtitleDocument to deep-copy every entry a
        // second time. The editable state remains isolated by replaceDocument(), and subsequent
        // mutations publish fresh snapshots through publishDocument().
        publishDocument(document)
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
        return execute(command, publish = true)
    }

    /**
     * Apply a list edit without publishing the full document snapshot immediately.
     *
     * List editing callers usually follow this with notifyEntriesChanged()/submitSubtitleList(),
     * which records the history delta and publishes the synchronized document once. Skipping the
     * eager snapshot avoids copying every subtitle entry twice for a single-row edit in a large
     * file. Callers that do not perform a subsequent UI/history synchronization must keep using
     * [execute].
     */
    fun executeWithoutPublish(command: EditorCommand): EditorCommandResult =
        execute(command, publish = false)

    private fun execute(
        command: EditorCommand,
        publish: Boolean
    ): EditorCommandResult {
        val result = applySubtitleEdit(documentState, command)
        if (publish && (result.changedPositions.isNotEmpty() || result.structureChanged)) {
            publishDocument()
        }
        return result
    }

    /** Formatting commits own their source, metadata, history and dirty state together. */
    fun applySubtitleColor(positions: Set<Int>, argb: Int, selectedIds: Set<Long>): EditorCommandResult {
        if (isSourceViewMode || isSourceViewTransitioning) return EditorCommandResult()
        return publishFormatChange(formatEditSession.applyColor(positions, argb, selectedIds))
    }

    fun applyVttDocument(
        updated: SubtitleDocument,
        selectedIds: Set<Long>,
        description: String
    ): EditorCommandResult {
        if (isSourceViewMode || isSourceViewTransitioning) return EditorCommandResult()
        return publishFormatChange(formatEditSession.applyVtt(updated, selectedIds, description))
    }

    /** Existing pure VTT operations borrow the current document only during preparation. */
    fun applyVttEdit(edit: EditorVttEdit, selectedIds: Set<Long>): EditorCommandResult =
        applyVttDocumentEdit(selectedIds, edit.description, edit::prepare)

    fun vttStyles() = com.subtitleedit.util.subtitle.WebVttStyleOps.list(formatDocumentView())

    fun vttVoices() = com.subtitleedit.util.subtitle.WebVttVoiceOps.list(formatDocumentView())

    private fun formatDocumentView() = SubtitleDocument(
        currentFormat, subtitleEntries, documentHeader, documentFooter, documentState.documentLrcVariant
    )

    fun applyVttDocumentEdit(
        selectedIds: Set<Long>,
        description: String,
        transform: (SubtitleDocument) -> SubtitleDocument
    ): EditorCommandResult {
        if (isSourceViewMode || isSourceViewTransitioning) return EditorCommandResult()
        return publishFormatChange(formatEditSession.applyVttEdit(selectedIds, description, transform))
    }

    private fun publishFormatChange(result: EditorCommandResult): EditorCommandResult {
        if (result.changedPositions.isNotEmpty() || result.documentMetadataChanged) {
            publishChangedDocument(result.changedPositions)
        }
        return result
    }

    fun executeHistoryCommand(
        command: EditorHistoryCommand,
        undo: Boolean
    ): EditorHistoryCommandResult {
        val result = command.execute(documentState, undo)
        if (result.entriesResolved) {
            if (command.isSelectionOnly()) {
                documentState.historySelectionSnapshot = result.selectedIds
                return result
            }
            // List history has already finished all fallible source work. Advance
            // the owned baseline and snapshot here; renderers only consume the result.
            syncHistoryBaseline(
                EditorEditHistory.ListState(result.entries, result.selectedIds),
                result.sourceText, isSourceViewMode
            )
            documentState.hasUnsavedChanges = true
            publishChangedDocument(result.updatedPositions.takeUnless { result.structureChanged })
        } else {
            publishDocument()
        }
        return result
    }

    fun refreshDocument() {
        publishDocument()
    }

    fun syncListChangesToSource(
        beforeEntries: List<SubtitleEntry>,
        afterEntries: List<SubtitleEntry>,
        publish: Boolean = true
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
        if (publish) publishDocument()
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
            is EditorEvent.SetVideoCollapsed ->
                updateUiState {
                    copy(isVideoCollapsed = event.value, isVideoMixed = isVideoMixed && event.value)
                }
            is EditorEvent.SetVideoMixed ->
                updateUiState {
                    copy(isVideoMixed = event.value, isVideoCollapsed = isVideoCollapsed || event.value)
                }
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
            is EditorEvent.ExecuteCommand -> execute(event.command)
            EditorEvent.RequestOptionsMenuRefresh ->
                _effects.tryEmit(EditorEffect.InvalidateOptionsMenu)
        }
    }

    private inline fun updateUiState(transform: EditorUiState.() -> EditorUiState) {
        _uiState.value = transform(_uiState.value)
    }

    private fun publishDocument(snapshot: SubtitleDocument? = null) {
        _document.value = snapshot ?: documentState.subtitleDocument
    }
}

internal class EditorViewModelFactory(
    private val subtitleRepository: SubtitleRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EditorViewModel::class.java)) {
            return EditorViewModel(subtitleRepository) as T
        }
        throw IllegalArgumentException("未知的 ViewModel：${modelClass.name}")
    }
}
