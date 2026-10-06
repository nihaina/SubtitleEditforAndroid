package com.subtitleedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.subtitleedit.feature.ui.TranscriptMatchDialog
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.nativebridge.NativeMediaOperation
import com.subtitleedit.nativebridge.PcmFormat
import com.subtitleedit.repository.DefaultSubtitleRepository
import com.subtitleedit.task.LongTaskController
import com.subtitleedit.task.TaskProgress
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.Qwen3ForcedAlignerModelFiles
import com.subtitleedit.util.QwenHuggingFaceTokenizer
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleOutputWriter
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.TranscriptForcedAligner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

internal data class TranscriptMatchUiState(
    val textFileName: String,
    val audioFileName: String,
    val outputDirectory: String,
    val pendingFiles: String,
    val selectedFormat: String = "SRT",
    val selectedLanguage: String,
    val modelHint: String = "",
    val startEnabled: Boolean = false,
    val isRunning: Boolean = false,
    val isCancelling: Boolean = false,
    val progress: Int = 0,
    val progressText: String = "",
    val dialog: TranscriptMatchDialog = TranscriptMatchDialog.NONE,
    val dialogTitle: String = "",
    val dialogMessage: String = ""
)

internal class TranscriptMatchViewModel(application: Application) :
    AppViewModel<TranscriptMatchUiState, Nothing>(
        application,
        TranscriptMatchUiState(
            textFileName = application.getString(R.string.transcript_match_unselected),
            audioFileName = application.getString(R.string.transcript_match_unselected),
            outputDirectory = "",
            pendingFiles = application.getString(R.string.transcript_match_pending_empty),
            selectedLanguage = SettingsManager.TRANSCRIPTION_LANGUAGE_OPTIONS.drop(1).first()
        )
    ) {
    companion object { private const val OUTPUT_DIRECTORY_KEY = "transcript_match" }

    val formats = listOf("SRT", "LRC", "VTT")
    val languages = SettingsManager.TRANSCRIPTION_LANGUAGE_OPTIONS.drop(1)
    private val settings = SettingsManager.getInstance(application)
    private val taskController = LongTaskController(dependencies.taskStateStore, "transcript-match")
    private var textUri: Uri? = null
    private var audioUri: Uri? = null
    private var textName = ""
    private var audioName = ""
    private var outputUri: Uri? = null
    @Volatile private var mediaOperation: NativeMediaOperation? = null

    val outputDirectoryUri: Uri? get() = outputUri
    val isRunning: Boolean get() = currentState.isRunning || taskController.isRunning

    init {
        outputUri = settings.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)?.let(Uri::parse)
        setState {
            copy(outputDirectory = outputUri?.let { DirectoryDisplayPath.fromUri(app, it) }
                ?: defaultOutputDirectory().absolutePath)
        }
        refreshModelState()
    }

    fun selectText(uri: Uri) {
        textUri = uri
        textName = displayName(uri)
        setState { copy(textFileName = textName) }
        updatePending()
    }

    fun selectAudio(uri: Uri) {
        audioUri = uri
        audioName = displayName(uri)
        setState { copy(audioFileName = audioName) }
        updatePending()
    }

    fun selectOutputDirectory(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        outputUri = uri
        settings.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
        setState { copy(outputDirectory = DirectoryDisplayPath.fromUri(app, uri)) }
    }

    fun setFormat(value: String) = setState { copy(selectedFormat = value) }
    fun setLanguage(value: String) = setState { copy(selectedLanguage = value) }

    fun requestBack(): Boolean {
        if (!isRunning) return true
        setState {
            copy(
                dialog = TranscriptMatchDialog.BACK,
                dialogTitle = string(R.string.transcript_match_back_confirm_title),
                dialogMessage = string(R.string.transcript_match_back_confirm_message)
            )
        }
        return false
    }

    fun requestCancel() {
        if (!isRunning || taskController.isCancellationRequested) return
        setState {
            copy(
                dialog = TranscriptMatchDialog.CANCEL,
                dialogTitle = string(R.string.transcript_match_cancel_confirm_title),
                dialogMessage = string(R.string.transcript_match_cancel_confirm_message)
            )
        }
    }

    fun dismissDialog() = setState { copy(dialog = TranscriptMatchDialog.NONE) }

    /** Returns whether the caller may start immediately. */
    fun confirmDialog(): Boolean {
        return when (currentState.dialog) {
            TranscriptMatchDialog.BACK -> {
                dismissDialog()
                taskController.cancel()
                true
            }
            TranscriptMatchDialog.CANCEL -> {
                dismissDialog()
                if (!isRunning || taskController.isCancellationRequested) return false
                taskController.cancel()
                setState { copy(isCancelling = true, startEnabled = false, progressText = string(R.string.transcript_match_cancelling)) }
                toast(R.string.transcript_match_cancelling)
                false
            }
            else -> { dismissDialog(); false }
        }
    }

    fun start() {
        val selectedText = textUri ?: return toast(R.string.transcript_match_select_text_first)
        val selectedAudio = audioUri ?: return toast(R.string.transcript_match_select_audio_first)
        if (!refreshModelState()) return toast(currentState.modelHint)
        val format = currentState.selectedFormat
        val language = currentState.selectedLanguage
        val directory = outputUri ?: Uri.fromFile(defaultOutputDirectory())
        taskController.launch(viewModelScope) { task ->
            setState { copy(isRunning = true, isCancelling = false, progress = 0, progressText = "") }
            val taskCache = File(app.cacheDir, "transcript_match_${task.id}").apply { mkdirs() }
            var cancelled = false
            try {
                updateProgress(1, string(R.string.transcript_match_reading_document))
                val transcript = withContext(Dispatchers.IO) {
                    DefaultSubtitleRepository().readUri(app, selectedText, settings.getConfiguredEncoding())
                        .trim().trimStart('\uFEFF')
                }
                require(transcript.isNotBlank()) { string(R.string.transcript_match_empty_document) }
                val audioFile = withContext(Dispatchers.IO) {
                    val extension = audioName.substringAfterLast('.', "bin")
                        .takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "bin"
                    val file = File(taskCache, "input_audio.$extension")
                    app.contentResolver.openInputStream(selectedAudio)?.use { input ->
                        file.outputStream().use(input::copyTo)
                    } ?: error(string(R.string.transcript_match_audio_read_failed))
                    file
                }
                task.ensureActive()
                updateProgress(5, string(R.string.transcript_match_extract_audio))
                val pcmFile = File(taskCache, "audio_16k.wav")
                val operation = dependencies.nativeMediaEngine.openOperation()
                mediaOperation = operation
                task.onCancel(operation::cancel)
                check(withContext(Dispatchers.IO) {
                    operation.convertToPcm(audioFile, pcmFile, PcmFormat.SPEECH_WAV_16K_MONO)
                } && pcmFile.isFile) { string(R.string.transcript_match_audio_convert_failed) }
                task.ensureActive()
                val aligned = withContext(Dispatchers.IO) {
                    TranscriptForcedAligner(app).align(
                        pcmFile, transcript, language,
                        onProgress = { progress, status -> updateProgress(progress, status) },
                        isCancelled = { task.isCancellationRequested }
                    )
                }
                task.ensureActive()
                updateProgress(99, string(R.string.transcript_match_saving))
                val entries = aligned.mapIndexed { index, segment ->
                    SubtitleEntry(index + 1, segment.startMs, segment.endMs, segment.text)
                }
                val content = when (format) {
                    "SRT" -> SubtitleParser.toSRT(entries)
                    "LRC" -> SubtitleParser.toLRC(entries)
                    else -> SubtitleParser.toVTT(entries)
                }
                val baseName = textName.substringBeforeLast('.', textName)
                    .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                    .trim().ifBlank { string(R.string.transcript_match_default_name) }
                val name = withContext(Dispatchers.IO) {
                    SubtitleOutputWriter.writeText(app, directory, baseName, format.lowercase(), content)
                }
                updateProgress(100, string(R.string.transcript_match_completed))
                setState {
                    copy(
                        dialog = TranscriptMatchDialog.MESSAGE,
                        dialogTitle = string(R.string.transcript_match_completed_title),
                        dialogMessage = string(R.string.transcript_match_completed_message, entries.size, name, currentState.outputDirectory)
                    )
                }
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            } catch (error: Exception) {
                task.recordFailure(error)
                setState {
                    copy(
                        dialog = TranscriptMatchDialog.MESSAGE,
                        dialogTitle = string(R.string.transcript_match_failed_title),
                        dialogMessage = error.message ?: string(R.string.transcript_match_unknown_error)
                    )
                }
            } finally {
                withContext(NonCancellable) {
                    mediaOperation = null
                    withContext(Dispatchers.IO) { taskCache.deleteRecursively() }
                    setState { copy(isRunning = false, isCancelling = false) }
                    if (cancelled) toast(R.string.transcript_match_cancelled)
                }
            }
        }
    }

    private fun updatePending() {
        val pending = if (textUri == null && audioUri == null) {
            string(R.string.transcript_match_pending_empty)
        } else {
            string(R.string.transcript_match_pending_files, textName.ifBlank { string(R.string.transcript_match_unselected) }, audioName.ifBlank { string(R.string.transcript_match_unselected) })
        }
        setState { copy(pendingFiles = pending) }
        refreshModelState()
    }

    fun refreshModelState(): Boolean {
        val issues = mutableListOf<String>()
        val tokenizer = localFile(settings.getQwen3AsrTokenizerPath())
        if (!QwenHuggingFaceTokenizer.hasRequiredFiles(tokenizer)) {
            issues += string(R.string.transcript_match_tokenizer_required)
        } else if (!QwenHuggingFaceTokenizer.isAvailable()) {
            issues += string(R.string.transcript_match_native_required)
        }
        val aligner = localFile(settings.getQwen3ForcedAlignerPath())
        if (!Qwen3ForcedAlignerModelFiles.isConfigured(aligner, app.filesDir)) {
            issues += string(R.string.transcript_match_aligner_required)
        }
        if (!settings.isVadUseBuiltInModel() && !canReadVadModel(settings.getVadModelPath())) {
            issues += string(R.string.transcript_match_vad_required)
        }
        val enabled = issues.isEmpty() && textUri != null && audioUri != null
        setState { copy(modelHint = issues.joinToString("\n"), startEnabled = enabled) }
        return issues.isEmpty()
    }

    private fun updateProgress(percent: Int, status: String) {
        if (taskController.isCancellationRequested) return
        setState { copy(progressText = string(R.string.transcript_match_progress, status, percent), progress = percent) }
        taskController.progress(TaskProgress(status, percent.toLong(), 100L))
    }

    private fun localFile(path: String): File? {
        if (path.isBlank()) return null
        val uri = Uri.parse(path)
        return if (uri.scheme.isNullOrEmpty() || uri.scheme == "file") File(uri.path ?: path) else null
    }

    private fun canReadVadModel(path: String): Boolean {
        if (path.isBlank()) return false
        val uri = Uri.parse(path)
        return if (uri.scheme == "content") {
            runCatching { app.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true }.getOrDefault(false)
        } else localFile(path)?.let { it.isFile && it.canRead() } == true
    }

    private fun displayName(uri: Uri): String = app.contentResolver.query(
        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: uri.lastPathSegment ?: string(R.string.transcript_match_unknown_file)

    private fun defaultOutputDirectory(): File =
        File(com.subtitleedit.util.ModelDirectoryManager.softwareDirectory(), "TranscriptMatch")
}
