package com.subtitleedit.editor

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.subtitleedit.adapter.TranslationPreviewItem
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.WhisperRecognizer
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** 对选中的字幕行按各自时间范围执行离线语音转录，不持有字幕文档本身。 */
internal class EditorTranscribeController(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val cacheDir: File,
    private val previewDialog: EditorTextPreviewDialog,
    private val applyTexts: (List<TranslationPreviewItem>) -> Unit,
    private val showMessage: (String) -> Unit
) {
    private var transcribeJob: Job? = null
    private var transcribeCancelled = false

    fun start(
        selectedEntries: List<Pair<SubtitleEntry, Int>>,
        timelineEntries: List<SubtitleEntry>,
        audioFile: File,
        audioCacheKey: String,
        audioStreamIndex: Int? = null
    ) {
        if (selectedEntries.any { it.first.endTime <= it.first.startTime }) {
            showMessage("选中的字幕包含无效时间范围")
            return
        }

        val settings = SettingsManager.getInstance(activity)
        val modelType = settings.getAsrModelType()
        val sourceLanguage = settings.getQuickTranscribeSourceLanguage()
        val encoderPath: String
        val decoderPath: String
        val joinerPath: String
        val tokensPath: String
        when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE -> {
                encoderPath = settings.getSenseVoiceModelPath()
                decoderPath = ""
                joinerPath = ""
                tokensPath = settings.getSenseVoiceTokensPath()
            }
            SettingsManager.ASR_MODEL_PARAKEET_TDT -> {
                encoderPath = settings.getParakeetTdtEncoderPath()
                decoderPath = settings.getParakeetTdtDecoderPath()
                joinerPath = settings.getParakeetTdtJoinerPath()
                tokensPath = settings.getParakeetTdtTokensPath()
            }
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> {
                encoderPath = settings.getParakeetCtcModelPath()
                decoderPath = ""
                joinerPath = ""
                tokensPath = settings.getParakeetCtcTokensPath()
            }
            else -> {
                encoderPath = settings.getWhisperEncoderPath()
                decoderPath = settings.getWhisperDecoderPath()
                joinerPath = ""
                tokensPath = settings.getWhisperTokensPath()
            }
        }
        if (encoderPath.isBlank() || tokensPath.isBlank() ||
            (modelType == SettingsManager.ASR_MODEL_WHISPER && decoderPath.isBlank()) ||
            (modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT &&
                (decoderPath.isBlank() || joinerPath.isBlank()))
        ) {
            showMessage("请先在语音转字幕配置中设置识别模型")
            return
        }

        val (dialogView, languageSpinner) =
            createLanguageView(selectedEntries.size, sourceLanguage)
        AlertDialog.Builder(activity)
            .setTitle("快速转录")
            .setView(dialogView)
            .setPositiveButton("开始转录") { _, _ ->
                val selectedLanguage = SettingsManager.TRANSCRIPTION_LANGUAGE_OPTIONS[
                    languageSpinner.selectedItemPosition.coerceIn(
                        0,
                        SettingsManager.TRANSCRIPTION_LANGUAGE_OPTIONS.lastIndex
                    )
                ]
                settings.setQuickTranscribeSourceLanguage(selectedLanguage)
                startTranscription(
                    selectedEntries,
                    timelineEntries,
                    audioFile,
                    encoderPath,
                    decoderPath,
                    joinerPath,
                    tokensPath,
                    modelType,
                    selectedLanguage,
                    audioCacheKey,
                    audioStreamIndex
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun release() {
        transcribeCancelled = true
        transcribeJob?.cancel()
    }

    private fun createLanguageView(
        selectedCount: Int,
        sourceLanguage: String
    ): Pair<LinearLayout, Spinner> {
        val horizontalPadding = (16 * activity.resources.displayMetrics.density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        val summary = TextView(activity).apply {
            text = "将识别选中的 $selectedCount 条字幕对应音频，并在预览中确认后应用。"
            textSize = 14f
        }
        val label = TextView(activity).apply {
            text = "源语言"
            textSize = 14f
            setPadding(0, 24, 0, 0)
        }
        val spinner = Spinner(activity).apply {
            val spinnerAdapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_item,
                SettingsManager.TRANSCRIPTION_LANGUAGE_OPTIONS
            )
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            adapter = spinnerAdapter
            setSelection(
                SettingsManager.TRANSCRIPTION_LANGUAGE_OPTIONS.indexOf(sourceLanguage)
                    .coerceAtLeast(0)
            )
        }
        container.addView(summary)
        container.addView(label)
        container.addView(spinner)
        return container to spinner
    }

    private fun startTranscription(
        selectedEntries: List<Pair<SubtitleEntry, Int>>,
        timelineEntries: List<SubtitleEntry>,
        inputFile: File,
        encoderPath: String,
        decoderPath: String,
        joinerPath: String,
        tokensPath: String,
        modelType: String,
        sourceLanguage: String,
        audioCacheKey: String,
        audioStreamIndex: Int?
    ) {
        val cachedPcmFile = recognitionPcmCacheFile(audioCacheKey, audioStreamIndex)
        val hasCachedPcm = isRecognitionPcmCacheValid(cachedPcmFile)
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("正在转录")
            .setMessage(if (hasCachedPcm) "正在使用缓存音频..." else "正在准备音频...")
            .setNegativeButton("取消") { _, _ -> transcribeCancelled = true }
            .setCancelable(false)
            .create()
        progressDialog.show()
        transcribeCancelled = false

        transcribeJob = scope.launch {
            try {
                val pcmFile = withContext(Dispatchers.IO) {
                    convertAudioToRecognitionPcm(inputFile, audioStreamIndex, audioCacheKey)
                } ?: throw IllegalStateException("音频转换失败")
                if (transcribeCancelled) return@launch

                val recognizer = WhisperRecognizer(
                    encoderPath = encoderPath,
                    decoderPath = decoderPath,
                    joinerPath = joinerPath,
                    tokensPath = tokensPath,
                    useVad = false,
                    language = sourceLanguage,
                    contentResolver = activity.contentResolver,
                    context = activity,
                    modelType = modelType
                )
                val ranges = selectedEntries.map { it.first.startTime..it.first.endTime }
                val rangeContexts = selectedEntries.map { (entry, position) ->
                    buildRangeContext(entry, position, timelineEntries)
                }
                val result = withContext(Dispatchers.IO) {
                    recognizer.recognizeRanges(
                        audioFile = pcmFile,
                        ranges = ranges,
                        rangeContexts = rangeContexts,
                        progressCallback = { current, total ->
                            activity.runOnUiThread {
                                progressDialog.setMessage("正在转录第 $current/$total 条...")
                            }
                        },
                        isCancelled = { transcribeCancelled }
                    )
                }
                if (transcribeCancelled) return@launch

                progressDialog.dismiss()
                result.onSuccess { texts -> showTranscriptionResult(selectedEntries, texts) }
                    .onFailure { showMessage("转录失败：${it.message ?: "未知错误"}") }
            } catch (e: CancellationException) {
                // 协程被取消（例如 Activity 销毁）不是转录失败，不要弹提示
                throw e
            } catch (e: Exception) {
                if (!transcribeCancelled) showMessage("转录失败：${e.message ?: "未知错误"}")
            } finally {
                if (progressDialog.isShowing) progressDialog.dismiss()
                transcribeJob = null
            }
        }
    }

    /** Returns the quick-transcription cache inside the source media's MD5 directory. */
    private fun recognitionPcmCacheFile(audioCacheKey: String, audioStreamIndex: Int?): File {
        val mediaCacheDir = File(cacheDir, "quick_transcribe/$audioCacheKey").apply { mkdirs() }
        val streamKey = audioStreamIndex?.toString() ?: "default"
        return File(mediaCacheDir, "quick_transcribe_${streamKey}_16k.wav")
    }

    private fun isRecognitionPcmCacheValid(file: File): Boolean =
        file.isFile && file.length() > WAV_HEADER_SIZE

    private suspend fun convertAudioToRecognitionPcm(
        inputFile: File,
        audioStreamIndex: Int?,
        audioCacheKey: String
    ): File? {
        val outputFile = recognitionPcmCacheFile(audioCacheKey, audioStreamIndex)
        if (isRecognitionPcmCacheValid(outputFile)) return outputFile
        if (outputFile.exists() && !outputFile.delete()) {
            Log.w(TAG, "无法删除无效的快速转录缓存：${outputFile.absolutePath}")
        }

        val parent = outputFile.parentFile ?: return null
        val partFile = File(parent, ".quick_transcribe_${UUID.randomUUID()}.wav.part")
        return try {
            val arguments = mutableListOf(
                "-hide_banner",
                "-loglevel", "error",
                "-nostdin",
                "-y",
                "-i", inputFile.absolutePath
            )
            if (audioStreamIndex != null) {
                arguments += listOf("-map", "0:$audioStreamIndex")
            }
            arguments += listOf(
                "-vn",
                "-sn",
                "-dn",
                "-ar", RECOGNITION_SAMPLE_RATE.toString(),
                "-ac", "1",
                "-c:a", "pcm_s16le",
                "-f", "wav",
                partFile.absolutePath
            )

            val session = executeFfmpeg(arguments.toTypedArray())
            if (session.getReturnCode()?.isValueSuccess() != true ||
                !isRecognitionPcmCacheValid(partFile)
            ) {
                Log.e(TAG, "快速转录音频转换失败：${session.getOutput()}")
                return null
            }

            publishAtomically(partFile, outputFile)
            outputFile.takeIf(::isRecognitionPcmCacheValid)
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            Log.e(TAG, "快速转录音频转换失败", e)
            null
        } finally {
            if (partFile.exists() && !partFile.delete()) {
                Log.w(TAG, "无法删除快速转录临时文件：${partFile.absolutePath}")
            }
        }
    }

    private suspend fun executeFfmpeg(arguments: Array<String>): FFmpegSession {
        return suspendCancellableCoroutine { continuation ->
            val sessionRef = AtomicReference<FFmpegSession?>()
            continuation.invokeOnCancellation {
                sessionRef.get()?.cancel()
            }
            val session = FFmpegKit.executeWithArgumentsAsync(arguments) { completedSession ->
                if (continuation.isActive) {
                    continuation.resume(completedSession)
                }
            }
            sessionRef.set(session)
            if (!continuation.isActive) {
                session.cancel()
            }
        }
    }

    private fun buildRangeContext(
        target: SubtitleEntry,
        targetPosition: Int,
        timelineEntries: List<SubtitleEntry>
    ): WhisperRecognizer.RangeContext {
        val targetStart = target.startTime.coerceAtLeast(0L)
        val targetEnd = target.endTime.coerceAtLeast(targetStart)
        val previousEnd = timelineEntries.getOrNull(targetPosition - 1)
            ?.endTime
            ?.coerceIn(0L, targetStart)
        val nextStart = timelineEntries.getOrNull(targetPosition + 1)
            ?.startTime
            ?.coerceAtLeast(targetEnd)

        return WhisperRecognizer.RangeContext(
            previousEndTimeMs = previousEnd,
            nextStartTimeMs = nextStart
        )
    }

    private fun publishAtomically(partFile: File, outputFile: File) {
        try {
            Files.move(
                partFile.toPath(),
                outputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                partFile.toPath(),
                outputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun showTranscriptionResult(
        selectedEntries: List<Pair<SubtitleEntry, Int>>,
        transcribedTexts: List<String>
    ) {
        if (transcribedTexts.size != selectedEntries.size) {
            showMessage("转录结果数量不匹配")
            return
        }
        val previewItems = selectedEntries.mapIndexed { index, (entry, position) ->
            TranslationPreviewItem(position, entry.text, transcribedTexts[index])
        }
        previewDialog.show(
            title = "转录结果预览",
            editTitle = "编辑转录文本",
            previewItems = previewItems,
            onApply = applyTexts
        )
    }

    private companion object {
        const val TAG = "EditorActivity"
        const val RECOGNITION_SAMPLE_RATE = 16_000
        const val WAV_HEADER_SIZE = 44L
    }
}
