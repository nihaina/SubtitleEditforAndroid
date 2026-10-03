package com.subtitleedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import com.subtitleedit.ui.LOG_ALL_PAGES_ID
import com.subtitleedit.ui.LogSection
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.util.RuntimeLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LogUiState(
    val displayMode: RuntimeLogManager.DisplayMode = RuntimeLogManager.DisplayMode.SIMPLE,
    val isRefreshing: Boolean = false,
    val isExportEnabled: Boolean = false,
    val showClearedPlaceholder: Boolean = false,
    val sections: List<LogSection> = emptyList(),
    val pageFilter: String = LOG_ALL_PAGES_ID,
    val pageOptions: List<AppOption<String>> = emptyList(),
    val infoText: String = ""
)

internal sealed interface LogEvent {
    /** The log is loaded; ask the user for an export directory. */
    data object PickExportDirectory : LogEvent
}

internal class LogViewModel(
    application: Application
) : AppViewModel<LogUiState, LogEvent>(application, LogUiState()) {
    private var hasLoadedLog = false
    private var refreshGeneration = 0

    init {
        setState { copy(pageOptions = defaultPageOptions()) }
        refreshLog()
    }

    fun setDisplayMode(mode: RuntimeLogManager.DisplayMode) {
        if (currentState.displayMode == mode) return
        setState { copy(displayMode = mode) }
        refreshLog()
    }

    fun setPageFilter(filter: String) {
        // The legacy spinner replaced the cleared placeholder with
        // the filtered adapter as soon as a selection was made.
        setState { copy(pageFilter = filter, showClearedPlaceholder = false) }
    }

    fun refreshLog(onComplete: (() -> Unit)? = null) {
        val generation = ++refreshGeneration
        val mode = currentState.displayMode
        setState {
            copy(
                isRefreshing = true,
                isExportEnabled = false,
                showClearedPlaceholder = false,
                infoText = string(R.string.log_loading)
            )
        }

        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    RuntimeLogManager.captureRecent(app, mode)
                }
            }
            if (generation != refreshGeneration) return@launch

            result.onSuccess { snapshot ->
                val sections = buildLogSections(snapshot.content)
                val options = defaultPageOptions() + sections
                    .map { it.title.substringBefore(" - ") }
                    .distinct()
                    .map { AppOption(it, it) }
                hasLoadedLog = true
                val info = buildString {
                    append(string(R.string.log_info_loaded, snapshot.packageName, snapshot.matchedLineCount))
                    if (snapshot.isPreviewTruncated) append(string(R.string.log_info_preview_truncated))
                }
                setState {
                    copy(
                        sections = sections,
                        pageOptions = options,
                        pageFilter = if (options.none { it.id == pageFilter }) LOG_ALL_PAGES_ID else pageFilter,
                        infoText = info,
                        isExportEnabled = true
                    )
                }
                onComplete?.invoke()
            }.onFailure { error ->
                hasLoadedLog = false
                val info = string(R.string.log_read_failed, error.message ?: string(R.string.error_unknown))
                setState {
                    copy(
                        sections = emptyList(),
                        pageOptions = defaultPageOptions(),
                        pageFilter = LOG_ALL_PAGES_ID,
                        infoText = info,
                        isExportEnabled = true
                    )
                }
                toast(info, Toast.LENGTH_LONG)
            }
            setState { copy(isRefreshing = false) }
        }
    }

    fun clearLog() {
        // Ignore an in-flight capture so a pre-clear snapshot cannot repopulate the view.
        refreshGeneration++
        RuntimeLogManager.clear(app)
        hasLoadedLog = false
        setState {
            copy(
                isRefreshing = false,
                isExportEnabled = true,
                showClearedPlaceholder = true,
                sections = emptyList(),
                pageOptions = defaultPageOptions(),
                pageFilter = LOG_ALL_PAGES_ID,
                infoText = string(R.string.log_info_cleared, app.packageName)
            )
        }
        toast(R.string.log_cleared)
    }

    fun requestExport() {
        if (!hasLoadedLog) {
            refreshLog { sendEvent(LogEvent.PickExportDirectory) }
        } else {
            sendEvent(LogEvent.PickExportDirectory)
        }
    }

    fun exportLogToDirectory(uri: Uri) {
        val mode = currentState.displayMode
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    val dir = DocumentFile.fromTreeUri(app, uri)
                        ?: throw IllegalStateException(string(R.string.log_export_dir_inaccessible))
                    val fileName = uniqueFileName(dir, RuntimeLogManager.exportFileName())
                    val file = dir.createFile("text/plain", fileName)
                        ?: throw IllegalStateException(string(R.string.log_export_create_failed))
                    app.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
                        RuntimeLogManager.exportRecent(app, mode, output)
                    } ?: throw IllegalStateException(string(R.string.log_export_write_failed))
                    fileName
                }
            }

            result.onSuccess { fileName ->
                toast(R.string.log_exported, fileName)
            }.onFailure { error ->
                toast(R.string.log_export_failed, error.message.toString(), duration = Toast.LENGTH_LONG)
            }
        }
    }

    private fun defaultPageOptions() = listOf(AppOption(LOG_ALL_PAGES_ID, string(R.string.log_all_pages)))

    private fun uniqueFileName(dir: DocumentFile, originalName: String): String {
        val name = originalName.substringBeforeLast(".")
        val extension = originalName.substringAfterLast(".", "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        var fileName = originalName
        var index = 1
        while (dir.findFile(fileName) != null) {
            fileName = "$name ($index)$suffix"
            index++
        }
        return fileName
    }

    private fun buildLogSections(content: String): List<LogSection> {
        val sections = mutableListOf<LogSection>()
        val sectionLines = mutableListOf<String>()
        val speechTitle = string(R.string.log_section_speech_to_subtitle)
        var title = string(R.string.log_section_startup)
        var startedAt = ""
        val pageBoundary = Regex("^(.{19}).*INFO/Navigation: ([A-Za-z]+Activity) resumed$")

        fun addSection() {
            if (sectionLines.isNotEmpty()) {
                sections.add(LogSection(title, startedAt, sectionLines.joinToString("\n"), sectionLines.size))
                sectionLines.clear()
            }
        }
        content.lineSequence().forEach { line ->
            val match = pageBoundary.matchEntire(line)
            if (match != null) {
                addSection()
                startedAt = match.groupValues[1]
                title = activitySectionTitle(match.groupValues[2])
            } else if (title == speechTitle && isSpeechRecognitionLine(line)) {
                addSection()
                title = string(R.string.log_section_speech_recognition, speechTitle)
                startedAt = line.take(19)
            }
            sectionLines.add(line)
        }
        addSection()
        return sections
    }

    private fun isSpeechRecognitionLine(line: String): Boolean =
        line.contains("ffmpeg-kit") ||
            line.contains("WhisperRecognizer") ||
            line.contains("sherpa-onnx") ||
            line.contains("Pcm16Wav")

    private fun activitySectionTitle(activity: String): String = when (activity) {
        "SpeechToSubtitleActivity" -> string(R.string.log_section_speech_to_subtitle)
        "SenseVoiceSettingsActivity" -> string(R.string.log_section_sense_voice_settings)
        "ParakeetSettingsActivity" -> string(R.string.log_section_parakeet_settings)
        "SpeechToSubtitleSettingsActivity" -> string(R.string.log_section_speech_settings)
        "AutoTimestampActivity" -> string(R.string.log_section_auto_timestamp)
        "EditorActivity" -> string(R.string.log_section_editor)
        "SettingsActivity" -> string(R.string.log_section_settings)
        "LogActivity" -> string(R.string.log_section_log)
        else -> activity.removeSuffix("Activity")
    }
}
