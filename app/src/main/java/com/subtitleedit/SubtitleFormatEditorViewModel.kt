package com.subtitleedit

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.subtitleedit.feature.ui.SubtitleFormatEditorOptions
import com.subtitleedit.feature.ui.SubtitleFormatEditorRow
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleFormattingOptions
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleTextFormatter
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SubtitleFormatEditorUiState(
    val fileName: String = "",
    val fileInfo: String = "",
    val items: List<SubtitleFormatEditorRow> = emptyList(),
    val isLoading: Boolean = true,
    val isApplying: Boolean = false,
    val showSaveConfirmation: Boolean = false,
    val showDiscardConfirmation: Boolean = false
)

internal sealed interface SubtitleFormatEditorEvent {
    data object Finish : SubtitleFormatEditorEvent
}

internal class SubtitleFormatEditorViewModel(
    application: Application
) : AppViewModel<SubtitleFormatEditorUiState, SubtitleFormatEditorEvent>(
    application,
    SubtitleFormatEditorUiState()
) {
    private var initialized = false
    private lateinit var sourceUri: Uri
    private lateinit var charset: Charset
    private var fileName = ""
    private var format = SubtitleParser.SubtitleFormat.UNKNOWN
    private var entries = emptyList<SubtitleEntry>()
    private var hasChanges = false
    private var formattingJob: Job? = null

    private val previewItems: List<SubtitleFormatEditorRow> get() = currentState.items

    /** Reads the intent extras once; returns false when the page cannot be opened. */
    fun initialize(uriText: String?, fileNameExtra: String?): Boolean {
        if (initialized) return true
        if (uriText.isNullOrBlank()) return false
        initialized = true
        sourceUri = Uri.parse(uriText)
        fileName = fileNameExtra.orEmpty().ifBlank { string(R.string.format_editor_default_file_name) }
        charset = SettingsManager.getInstance(app).getDefaultEncoding()
        setState { copy(fileName = fileName) }
        loadSubtitle()
        return true
    }

    private fun loadSubtitle() {
        viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    val content = FileUtils.readUri(app, sourceUri, charset)
                    SubtitleParser.parseDocument(content, fileName)
                }
                format = document.format
                entries = document.entries
                if (format == SubtitleParser.SubtitleFormat.UNKNOWN || entries.isEmpty()) {
                    throw IllegalArgumentException(string(R.string.format_editor_no_valid_content))
                }
                val items = entries.mapIndexed { index, entry ->
                    SubtitleFormatEditorRow(entryPosition = index, text = entry.text)
                }
                setState { copy(items = items, fileInfo = fileInfoFor(items), isLoading = false) }
            } catch (e: Exception) {
                toast(R.string.format_editor_load_failed, e.message.toString(), duration = Toast.LENGTH_LONG)
                sendEvent(SubtitleFormatEditorEvent.Finish)
            }
        }
    }

    private fun fileInfoFor(items: List<SubtitleFormatEditorRow>): String =
        string(R.string.format_editor_file_info, fileName, items.size, format.name)

    private fun setItems(items: List<SubtitleFormatEditorRow>, updateInfo: Boolean = false) = setState {
        copy(items = items, fileInfo = if (updateInfo) fileInfoFor(items) else fileInfo)
    }

    fun selectAll() {
        val selected = previewItems.isNotEmpty() && previewItems.any { !it.selected }
        setItems(previewItems.map { it.copy(selected = selected) })
    }

    fun selectRange(startInclusive: Int, endInclusive: Int) {
        setItems(previewItems.mapIndexed { index, item ->
            item.copy(selected = index in startInclusive..endInclusive)
        })
    }

    fun setSelection(position: Int, selected: Boolean) {
        val item = previewItems.getOrNull(position) ?: return
        setItems(previewItems.toMutableList().also {
            it[position] = item.copy(selected = selected)
        })
    }

    fun editItem(position: Int, text: String) {
        val item = previewItems.getOrNull(position) ?: return
        if (item.text == text) return
        setItems(previewItems.toMutableList().also {
            it[position] = item.copy(text = text)
        })
        hasChanges = true
    }

    fun onInvalidRange() = toast(R.string.format_editor_invalid_range)

    fun applyFormatting(options: SubtitleFormatEditorOptions) {
        if (currentState.isLoading || formattingJob?.isActive == true) return
        val selected = previewItems.filter { it.selected }
        if (selected.isEmpty()) {
            toast(R.string.format_editor_no_selection)
            return
        }
        if (!options.removeSpaces && options.innerPunctuation.isEmpty() &&
            options.startPunctuation.isEmpty() && options.endPunctuation.isEmpty() &&
            options.replaceFrom.isEmpty() && options.addEndPunctuation.isEmpty()
        ) {
            toast(R.string.format_editor_no_options)
            return
        }
        val formattingOptions = SubtitleFormattingOptions(
            removeSpaces = options.removeSpaces,
            innerPunctuation = options.innerPunctuation,
            startPunctuation = options.startPunctuation,
            endPunctuation = options.endPunctuation,
            replaceFrom = options.replaceFrom,
            replaceTo = options.replaceTo,
            replacementScope = options.replacementScope,
            addEndPunctuation = options.addEndPunctuation
        )
        setState { copy(isApplying = true) }
        formattingJob = viewModelScope.launch {
            try {
                val formattedTexts = withContext(Dispatchers.Default) {
                    selected.associate { item ->
                        item.entryPosition to SubtitleTextFormatter.format(item.text, formattingOptions)
                    }
                }
                var changed = 0
                var removed = 0
                val updated = previewItems.mapNotNull { item ->
                    val formatted = formattedTexts[item.entryPosition] ?: return@mapNotNull item
                    if (formatted == item.text) return@mapNotNull item
                    changed++
                    if (formatted.isBlank()) {
                        removed++
                        null
                    } else {
                        item.copy(text = formatted)
                    }
                }
                setItems(updated, updateInfo = true)
                hasChanges = hasChanges || changed > 0
                if (removed > 0) {
                    toast(R.string.format_editor_formatted_with_removed, changed, removed)
                } else {
                    toast(R.string.format_editor_formatted, changed)
                }
            } finally {
                setState { copy(isApplying = false) }
            }
        }
    }

    fun requestSave() {
        if (currentState.isLoading || entries.isEmpty()) {
            toast(R.string.format_editor_not_loaded)
            return
        }
        if (formattingJob?.isActive == true) {
            toast(R.string.format_editor_formatting_in_progress)
            return
        }
        setState { copy(showSaveConfirmation = true) }
    }

    fun dismissSaveConfirmation() = setState { copy(showSaveConfirmation = false) }

    fun confirmSave() {
        dismissSaveConfirmation()
        saveToSource()
    }

    private fun saveToSource() {
        if (currentState.isLoading || entries.isEmpty()) return
        try {
            val outputEntries = previewItems.mapIndexed { index, item ->
                entries[item.entryPosition].copy(index = index + 1, text = item.text)
            }
            val content = when (format) {
                SubtitleParser.SubtitleFormat.SRT -> SubtitleParser.toSRT(outputEntries)
                SubtitleParser.SubtitleFormat.LRC -> SubtitleParser.toLRC(outputEntries)
                SubtitleParser.SubtitleFormat.TXT -> SubtitleParser.toTXT(outputEntries)
                SubtitleParser.SubtitleFormat.VTT -> {
                    val document = SubtitleParser.parseDocument(
                        FileUtils.readUri(app, sourceUri, charset),
                        fileName,
                        SubtitleParser.SubtitleFormat.VTT
                    )
                    SubtitleParser.toVTT(outputEntries, document.header, document.footer)
                }
                else -> throw IllegalStateException(string(R.string.format_editor_unsupported_format))
            }
            app.contentResolver.openOutputStream(sourceUri, "wt")?.use {
                it.write(content.toByteArray(charset))
                it.flush()
            } ?: throw IllegalStateException(string(R.string.format_editor_open_source_failed))
            entries = outputEntries
            setItems(previewItems.mapIndexed { index, item -> item.copy(entryPosition = index) }, updateInfo = true)
            hasChanges = false
            toast(R.string.format_editor_saved, fileName)
        } catch (e: Exception) {
            toast(R.string.format_editor_save_failed, e.message.toString(), duration = Toast.LENGTH_LONG)
        }
    }

    /** Returns true when the page may close immediately. */
    fun requestBack(): Boolean {
        if (!hasChanges) return true
        setState { copy(showDiscardConfirmation = true) }
        return false
    }

    fun dismissDiscardConfirmation() = setState { copy(showDiscardConfirmation = false) }
}
