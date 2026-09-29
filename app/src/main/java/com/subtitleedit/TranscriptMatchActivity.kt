package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.feature.ui.TranscriptMatchDialog
import com.subtitleedit.feature.ui.TranscriptMatchScreen
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
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class TranscriptMatchActivity : AppCompatActivity() {
    private val settings by lazy { SettingsManager.getInstance(this) }
    private val taskController by lazy {
        LongTaskController((application as SubtitleEditApplication).dependencies.taskStateStore, "transcript-match")
    }
    private var textUri: Uri? = null
    private var audioUri: Uri? = null
    private var textName = ""
    private var audioName = ""
    private var outputUri: Uri? = null
    private var textFileDisplay by mutableStateOf("未选择")
    private var audioFileDisplay by mutableStateOf("未选择")
    private var outputDirectoryDisplay by mutableStateOf("")
    private var pendingFilesText by mutableStateOf("")
    private val formats = listOf("SRT", "LRC", "VTT")
    private val languages = SettingsManager.TRANSCRIPTION_LANGUAGE_OPTIONS.drop(1)
    private var selectedFormat by mutableStateOf("SRT")
    private var selectedLanguage by mutableStateOf(languages.first())
    private var modelHint by mutableStateOf("")
    private var startEnabled by mutableStateOf(false)
    private var isRunningUi by mutableStateOf(false)
    private var isCancelling by mutableStateOf(false)
    private var progressValue by mutableStateOf(0)
    private var progressText by mutableStateOf("")
    private var dialog by mutableStateOf(TranscriptMatchDialog.NONE)
    private var dialogTitleText by mutableStateOf("")
    private var dialogMessageText by mutableStateOf("")
    @Volatile private var mediaOperation: NativeMediaOperation? = null

    private val textPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            textUri = uri
            textName = displayName(uri)
            textFileDisplay = textName
            updatePending()
        }
    }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audioUri = uri
            audioName = displayName(uri)
            audioFileDisplay = audioName
            updatePending()
        }
    }
    private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            outputUri = uri
            outputDirectoryDisplay = DirectoryDisplayPath.fromUri(this, uri)
            settings.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingFilesText = getString(R.string.transcript_match_pending_empty)
        setContent {
            SubtitleEditComposeTheme {
                TranscriptMatchScreen(
                    textFileName = textFileDisplay,
                    audioFileName = audioFileDisplay,
                    outputDirectory = outputDirectoryDisplay,
                    pendingFiles = pendingFilesText,
                    formats = formats,
                    selectedFormat = selectedFormat,
                    languages = languages,
                    selectedLanguage = selectedLanguage,
                    modelHint = modelHint,
                    startEnabled = startEnabled,
                    isRunning = isRunningUi,
                    isCancelling = isCancelling,
                    progress = progressValue,
                    progressText = progressText,
                    dialog = dialog,
                    dialogTitle = dialogTitleText,
                    dialogMessage = dialogMessageText,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onSelectText = {
                        textPicker.launch(arrayOf("text/*", "application/octet-stream"))
                    },
                    onSelectAudio = {
                        audioPicker.launch(arrayOf("audio/*", "application/octet-stream"))
                    },
                    onSelectOutputDirectory = { directoryPicker.launch(outputUri) },
                    onFormatSelected = { selectedFormat = it },
                    onLanguageSelected = { selectedLanguage = it },
                    onStartOrCancel = {
                        if (isRunningUi) confirmCancelMatching() else startMatching()
                    },
                    onDialogConfirm = ::confirmDialog,
                    onDialogDismiss = { dialog = TranscriptMatchDialog.NONE }
                )
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (taskController.isRunning && !taskController.isCancellationRequested) {
                    confirmBackDuringMatching()
                } else {
                    finish()
                }
            }
        })

        outputUri = settings.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)?.let(Uri::parse)
        outputDirectoryDisplay = outputUri?.let { DirectoryDisplayPath.fromUri(this, it) }
            ?: defaultOutputDirectory().absolutePath
        updateStartButtonState()
    }

    override fun onResume() {
        super.onResume()
        if (!taskController.isRunning) updateStartButtonState()
    }

    override fun onDestroy() {
        if (taskController.isRunning) taskController.cancel()
        super.onDestroy()
    }

    private fun updatePending() {
        pendingFilesText = if (textUri == null && audioUri == null) {
            getString(R.string.transcript_match_pending_empty)
        } else {
            "文本：${textName.ifBlank { "未选择" }}\n音频：${audioName.ifBlank { "未选择" }}"
        }
        updateStartButtonState()
    }

    private fun confirmBackDuringMatching() {
        dialogTitleText = getString(R.string.transcript_match_back_confirm_title)
        dialogMessageText = getString(R.string.transcript_match_back_confirm_message)
        dialog = TranscriptMatchDialog.BACK
    }

    private fun confirmCancelMatching() {
        if (!taskController.isRunning || taskController.isCancellationRequested) return
        dialogTitleText = getString(R.string.transcript_match_cancel_confirm_title)
        dialogMessageText = getString(R.string.transcript_match_cancel_confirm_message)
        dialog = TranscriptMatchDialog.CANCEL
    }

    private fun confirmDialog() {
        when (dialog) {
            TranscriptMatchDialog.BACK -> {
                dialog = TranscriptMatchDialog.NONE
                taskController.cancel()
                finish()
            }
            TranscriptMatchDialog.CANCEL -> {
                dialog = TranscriptMatchDialog.NONE
                if (!taskController.isRunning || taskController.isCancellationRequested) return
                taskController.cancel()
                isCancelling = true
                startEnabled = false
                progressText = getString(R.string.transcript_match_cancelling)
                toast(getString(R.string.transcript_match_cancelling))
            }
            else -> dialog = TranscriptMatchDialog.NONE
        }
    }

    private fun startMatching() {
        val selectedText = textUri ?: return toast("请先选择文本文件")
        val selectedAudio = audioUri ?: return toast("请先选择音频文件")
        if (!updateStartButtonState()) return toast(modelHint)
        val format = selectedFormat
        val language = selectedLanguage
        val directory = outputUri ?: Uri.fromFile(defaultOutputDirectory())
        taskController.launch(lifecycleScope) { task ->
            setRunning(true)
            val taskCache = File(cacheDir, "transcript_match_${task.id}").apply { mkdirs() }
            var cancelled = false
            try {
                updateProgress(1, "正在读取文稿")
                val transcript = withContext(Dispatchers.IO) {
                    DefaultSubtitleRepository().readUri(this@TranscriptMatchActivity, selectedText,
                        settings.getDefaultEncoding()).trim().trimStart('\uFEFF')
                }
                require(transcript.isNotBlank()) { "文本文件为空" }
                val audioFile = withContext(Dispatchers.IO) {
                    val extension = audioName.substringAfterLast('.', "bin")
                        .takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "bin"
                    val file = File(taskCache, "input_audio.$extension")
                    contentResolver.openInputStream(selectedAudio)?.use { input ->
                        file.outputStream().use(input::copyTo)
                    } ?: error("无法读取音频文件")
                    file
                }
                task.ensureActive()
                updateProgress(5, "正在提取 16kHz 音频")
                val pcmFile = File(taskCache, "audio_16k.wav")
                val operation = (application as SubtitleEditApplication).dependencies.nativeMediaEngine.openOperation()
                mediaOperation = operation
                task.onCancel(operation::cancel)
                check(withContext(Dispatchers.IO) {
                    operation.convertToPcm(audioFile, pcmFile, PcmFormat.SPEECH_WAV_16K_MONO)
                } && pcmFile.isFile) { "音频转换失败" }
                task.ensureActive()
                val aligned = withContext(Dispatchers.IO) {
                    TranscriptForcedAligner(this@TranscriptMatchActivity).align(
                        pcmFile, transcript, language,
                        onProgress = { progress, status ->
                            runOnUiThread { updateProgress(progress, status) }
                        },
                        isCancelled = { task.isCancellationRequested },
                    )
                }
                task.ensureActive()
                updateProgress(99, "正在保存字幕")
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
                    .trim().ifBlank { "文稿匹配" }
                val name = withContext(Dispatchers.IO) {
                    SubtitleOutputWriter.writeText(this@TranscriptMatchActivity, directory,
                        baseName, format.lowercase(), content)
                }
                updateProgress(100, "处理完成")
                showMessageDialog(
                    "文稿匹配完成",
                    "已生成 ${entries.size} 条字幕：$name\n输出目录：$outputDirectoryDisplay"
                )
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            } catch (error: Exception) {
                task.recordFailure(error)
                showMessageDialog("文稿匹配失败", error.message ?: "未知错误")
            } finally {
                withContext(NonCancellable) {
                    mediaOperation = null
                    withContext(Dispatchers.IO) { taskCache.deleteRecursively() }
                    setRunning(false)
                    if (cancelled && !isFinishing && !isDestroyed) {
                        toast(getString(R.string.transcript_match_cancelled))
                    }
                }
            }
        }
    }

    private fun setRunning(running: Boolean) {
        isRunningUi = running
        if (running) {
            isCancelling = false
            progressValue = 0
            progressText = ""
        } else {
            isCancelling = false
            updateStartButtonState()
        }
    }

    private fun updateProgress(percent: Int, status: String) {
        if (taskController.isCancellationRequested) return
        progressText = "$status（$percent%）"
        progressValue = percent
        taskController.progress(TaskProgress(status, percent.toLong(), 100L))
    }

    private fun updateStartButtonState(): Boolean {
        val issues = mutableListOf<String>()
        val tokenizer = localFile(settings.getQwen3AsrTokenizerPath())
        if (!QwenHuggingFaceTokenizer.hasRequiredFiles(tokenizer)) {
            issues += getString(R.string.transcript_match_tokenizer_required)
        } else if (!QwenHuggingFaceTokenizer.isAvailable()) {
            issues += getString(R.string.transcript_match_native_required)
        }
        val aligner = localFile(settings.getQwen3ForcedAlignerPath())
        if (!Qwen3ForcedAlignerModelFiles.isConfigured(aligner, filesDir)) {
            issues += getString(R.string.transcript_match_aligner_required)
        }
        if (!settings.isVadUseBuiltInModel() && !canReadVadModel(settings.getVadModelPath())) {
            issues += getString(R.string.transcript_match_vad_required)
        }
        modelHint = issues.joinToString("\n")
        startEnabled = issues.isEmpty() && textUri != null && audioUri != null
        return issues.isEmpty()
    }

    private fun showMessageDialog(title: String, message: String) {
        dialogTitleText = title
        dialogMessageText = message
        dialog = TranscriptMatchDialog.MESSAGE
    }

    private fun localFile(path: String): File? {
        if (path.isBlank()) return null
        val uri = Uri.parse(path)
        return if (uri.scheme.isNullOrEmpty() || uri.scheme == "file") File(uri.path ?: path)
        else null
    }

    private fun canReadVadModel(path: String): Boolean {
        if (path.isBlank()) return false
        val uri = Uri.parse(path)
        return if (uri.scheme == "content") {
            runCatching { contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true }
                .getOrDefault(false)
        } else {
            localFile(path)?.let { it.isFile && it.canRead() } == true
        }
    }

    private fun displayName(uri: Uri): String = contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "未知文件"

    private fun defaultOutputDirectory(): File =
        File(File(FileUtils.getDownloadDirectory(), "SubtitleEdit"), "TranscriptMatch")

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object { const val OUTPUT_DIRECTORY_KEY = "transcript_match" }
}
