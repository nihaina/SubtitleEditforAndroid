package com.subtitleedit.editor

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.arthenica.ffmpegkit.FFmpegKit
import com.subtitleedit.adapter.TranslationPreviewItem
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.FileHashUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.WhisperRecognizer
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
        audioFile: File,
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
                    audioFile,
                    encoderPath,
                    decoderPath,
                    joinerPath,
                    tokensPath,
                    modelType,
                    selectedLanguage,
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
        inputFile: File,
        encoderPath: String,
        decoderPath: String,
        joinerPath: String,
        tokensPath: String,
        modelType: String,
        sourceLanguage: String,
        audioStreamIndex: Int?
    ) {
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("正在转录")
            .setMessage("正在准备音频...")
            .setNegativeButton("取消") { _, _ -> transcribeCancelled = true }
            .setCancelable(false)
            .create()
        progressDialog.show()
        transcribeCancelled = false

        transcribeJob = scope.launch {
            try {
                val sourceMd5 = withContext(Dispatchers.IO) { FileHashUtils.md5(inputFile) }
                val cachedPcmFile = recognitionPcmCacheFile(sourceMd5, audioStreamIndex)
                progressDialog.setMessage(
                    if (isRecognitionPcmCacheValid(cachedPcmFile)) "正在使用缓存音频..."
                    else "正在准备音频..."
                )
                val pcmFile = withContext(Dispatchers.IO) {
                    convertAudioToRecognitionPcm(inputFile, audioStreamIndex, sourceMd5)
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
                val result = withContext(Dispatchers.IO) {
                    recognizer.recognizeRanges(
                        audioFile = pcmFile,
                        ranges = ranges,
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
    private fun recognitionPcmCacheFile(sourceMd5: String, audioStreamIndex: Int?): File {
        val mediaCacheDir = File(cacheDir, "quick_transcribe/$sourceMd5").apply { mkdirs() }
        val streamKey = audioStreamIndex?.toString() ?: "default"
        return File(mediaCacheDir, "quick_transcribe_${streamKey}_16k.wav")
    }

    private fun isRecognitionPcmCacheValid(file: File): Boolean =
        file.exists() && file.length() > 44L

    private fun convertAudioToRecognitionPcm(
        inputFile: File,
        audioStreamIndex: Int?,
        sourceMd5: String
    ): File? {
        return try {
            val outputFile = recognitionPcmCacheFile(sourceMd5, audioStreamIndex)
            if (isRecognitionPcmCacheValid(outputFile)) return outputFile
            if (outputFile.exists()) outputFile.delete()
            val mapOption = audioStreamIndex?.let { "-map 0:$it " }.orEmpty()
            val command = "-y -i \"${inputFile.absolutePath}\" $mapOption" +
                "-ar 16000 -ac 1 -c:a pcm_s16le \"${outputFile.absolutePath}\""
            val session = FFmpegKit.execute(command)
            outputFile.takeIf {
                session.getReturnCode()?.isValueSuccess() == true && isRecognitionPcmCacheValid(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "快速转录音频转换失败", e)
            null
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
    }
}
