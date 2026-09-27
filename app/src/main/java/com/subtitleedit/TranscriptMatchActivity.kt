package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.databinding.ActivityTranscriptMatchBinding
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
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class TranscriptMatchActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTranscriptMatchBinding
    private val settings by lazy { SettingsManager.getInstance(this) }
    private val taskController by lazy {
        LongTaskController((application as SubtitleEditApplication).dependencies.taskStateStore, "transcript-match")
    }
    private var textUri: Uri? = null
    private var audioUri: Uri? = null
    private var textName = ""
    private var audioName = ""
    private var outputUri: Uri? = null
    @Volatile private var mediaOperation: NativeMediaOperation? = null

    private val textPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            textUri = uri
            textName = displayName(uri)
            binding.tvTextFile.text = textName
            updatePending()
        }
    }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audioUri = uri
            audioName = displayName(uri)
            binding.tvAudioFile.text = audioName
            updatePending()
        }
    }
    private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            outputUri = uri
            binding.tvOutputDir.text = DirectoryDisplayPath.fromUri(this, uri)
            settings.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranscriptMatchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (taskController.isRunning && !taskController.isCancellationRequested) {
                    confirmBackDuringMatching()
                } else {
                    finish()
                }
            }
        })

        val formats = listOf("SRT", "LRC", "VTT")
        binding.spinnerFormat.adapter = spinnerAdapter(formats)
        val languages = SettingsManager.TRANSCRIPTION_LANGUAGE_OPTIONS.drop(1)
        binding.spinnerLanguage.adapter = spinnerAdapter(languages)
        binding.btnSelectText.setOnClickListener { textPicker.launch(arrayOf("text/*", "application/octet-stream")) }
        binding.btnSelectAudio.setOnClickListener { audioPicker.launch(arrayOf("audio/*", "application/octet-stream")) }
        binding.btnSelectOutputDir.setOnClickListener { directoryPicker.launch(outputUri) }
        binding.btnStart.setOnClickListener {
            if (taskController.isRunning) confirmCancelMatching() else startMatching()
        }
        outputUri = settings.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)?.let(Uri::parse)
        binding.tvOutputDir.text = outputUri?.let { DirectoryDisplayPath.fromUri(this, it) }
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

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun updatePending() {
        binding.tvPendingFiles.text = if (textUri == null && audioUri == null) {
            getString(R.string.transcript_match_pending_empty)
        } else {
            "文本：${textName.ifBlank { "未选择" }}\n音频：${audioName.ifBlank { "未选择" }}"
        }
        updateStartButtonState()
    }

    private fun confirmBackDuringMatching() {
        AlertDialog.Builder(this)
            .setTitle(R.string.transcript_match_back_confirm_title)
            .setMessage(R.string.transcript_match_back_confirm_message)
            .setPositiveButton(R.string.transcript_match_back_and_cancel) { _, _ ->
                taskController.cancel()
                finish()
            }
            .setNegativeButton(R.string.transcript_match_continue, null)
            .show()
    }

    private fun confirmCancelMatching() {
        if (!taskController.isRunning || taskController.isCancellationRequested) return
        AlertDialog.Builder(this)
            .setTitle(R.string.transcript_match_cancel_confirm_title)
            .setMessage(R.string.transcript_match_cancel_confirm_message)
            .setPositiveButton(R.string.transcript_match_cancel) { _, _ ->
                if (!taskController.isRunning || taskController.isCancellationRequested) return@setPositiveButton
                taskController.cancel()
                binding.btnStart.isEnabled = false
                binding.tvProgress.text = getString(R.string.transcript_match_cancelling)
                binding.progressBar.isIndeterminate = true
                toast(getString(R.string.transcript_match_cancelling))
            }
            .setNegativeButton(R.string.transcript_match_continue, null)
            .show()
    }

    private fun startMatching() {
        val selectedText = textUri ?: return toast("请先选择文本文件")
        val selectedAudio = audioUri ?: return toast("请先选择音频文件")
        if (!updateStartButtonState()) return toast(binding.tvModelHint.text.toString())
        val format = binding.spinnerFormat.selectedItem.toString()
        val language = binding.spinnerLanguage.selectedItem.toString()
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
                AlertDialog.Builder(this@TranscriptMatchActivity).setTitle("文稿匹配完成")
                    .setMessage("已生成 ${entries.size} 条字幕：$name\n输出目录：${binding.tvOutputDir.text}")
                    .setPositiveButton("确定", null).show()
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            } catch (error: Exception) {
                task.recordFailure(error)
                AlertDialog.Builder(this@TranscriptMatchActivity).setTitle("文稿匹配失败")
                    .setMessage(error.message ?: "未知错误").setPositiveButton("确定", null).show()
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
        binding.btnSelectText.isEnabled = !running
        binding.btnSelectAudio.isEnabled = !running
        binding.btnSelectOutputDir.isEnabled = !running
        binding.spinnerFormat.isEnabled = !running
        binding.spinnerLanguage.isEnabled = !running
        binding.btnStart.isEnabled = if (running) true else updateStartButtonState()
        binding.btnStart.setText(if (running) R.string.transcript_match_cancel else R.string.transcript_match_start)
        binding.tvProgress.visibility = if (running) View.VISIBLE else View.GONE
        binding.progressBar.visibility = if (running) View.VISIBLE else View.GONE
        binding.progressBar.isIndeterminate = false
    }

    private fun updateProgress(percent: Int, status: String) {
        if (taskController.isCancellationRequested) return
        binding.tvProgress.text = "$status（$percent%）"
        binding.progressBar.progress = percent
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
        binding.tvModelHint.text = issues.joinToString("\n")
        binding.tvModelHint.visibility = if (issues.isEmpty()) View.GONE else View.VISIBLE
        binding.btnStart.isEnabled = issues.isEmpty() && textUri != null && audioUri != null
        return issues.isEmpty()
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
