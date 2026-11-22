package com.subtitleedit.work

import android.net.Uri
import com.subtitleedit.R
import com.subtitleedit.SubtitleEditApplication
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.nativebridge.NativeMediaOperation
import com.subtitleedit.nativebridge.PcmFormat
import com.subtitleedit.util.ByteSizeFormat
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.PickedUriAccess
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.TokenTimestampGenerator
import com.subtitleedit.util.WhisperRecognizer
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal class SpeechToSubtitleRunner(
    private val app: SubtitleEditApplication,
    private val request: SpeechToSubtitleRequest,
    private val mediaOperation: NativeMediaOperation,
    private val onProgress: (String, Int) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val settings = SettingsManager.getInstance(app)
    private val modelType = request.modelType
    private val speechRecognitionService = app.dependencies.speechRecognitionService

    fun logConfiguration() {
        onLog(string(R.string.speech_to_subtitle_log_start))
        onLog(string(R.string.task_pending_files, request.files.size))
        onLog(string(R.string.task_output_format, request.format))
        onLog(string(R.string.speech_to_subtitle_log_source_language, request.language))
        onLog(
            string(
                R.string.task_output_directory,
                if (request.saveToSourceDirectory) string(R.string.save_to_source_folder_active)
                else DirectoryDisplayPath.fromUri(app, request.outputDirectory)
            )
        )
        logModelConfiguration()
    }

    suspend fun convertFile(
        file: SpeechToSubtitleFile,
        fileIndex: Int,
        taskCacheDir: File,
    ): SpeechToSubtitleContent = withContext(Dispatchers.IO) {
        val taskContext = currentCoroutineContext()
        val progressPrefix = string(R.string.task_file_progress_prefix, fileIndex, request.files.size)
        val cachedFile = File(taskCacheDir, "input_${File(file.fileName).name}")
        val pcmFile = File(taskCacheDir, "${cachedFile.nameWithoutExtension}_16k.wav")
        try {
            onProgress(string(R.string.speech_to_subtitle_progress_preparing, progressPrefix), 0)
            onLog(string(R.string.speech_to_subtitle_log_file_start, progressPrefix, file.fileName))
            onLog(string(R.string.speech_to_subtitle_log_copy_to_cache))
            if (!taskCacheDir.isDirectory && !taskCacheDir.mkdirs()) {
                error(string(R.string.speech_to_subtitle_error_copy_failed))
            }
            try {
                copyToCache(file.uri, cachedFile)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw IllegalStateException(string(R.string.speech_to_subtitle_error_copy_failed), error)
            }
            onLog(
                string(R.string.speech_to_subtitle_log_cache_file, cachedFile.name, ByteSizeFormat.format(cachedFile.length()))
            )

            onProgress(string(R.string.speech_to_subtitle_progress_extract, progressPrefix), 5)
            onLog(string(R.string.speech_to_subtitle_log_extract_pcm))
            val converted = mediaOperation.convertToPcm(cachedFile, pcmFile, PcmFormat.SPEECH_WAV_16K_MONO)
            taskContext.ensureActive()
            check(converted && pcmFile.isFile) { string(R.string.speech_to_subtitle_error_audio_convert_failed) }
            onLog(
                string(R.string.speech_to_subtitle_log_pcm_file, pcmFile.name, ByteSizeFormat.format(pcmFile.length()))
            )

            onProgress(string(R.string.speech_to_subtitle_progress_recognize, progressPrefix), 10)
            val recognition = if (request.useTokenTimeline) {
                recognizeWithTokenTimeline(pcmFile, progressPrefix) { !taskContext.isActive }
            } else {
                recognize(pcmFile, progressPrefix) { !taskContext.isActive }
            }
            taskContext.ensureActive()
            val segments = recognition.getOrThrow()
            check(segments.isNotEmpty()) { string(R.string.speech_to_subtitle_error_no_speech) }

            onProgress(string(R.string.speech_to_subtitle_progress_generate, progressPrefix), 95)
            onLog(string(R.string.speech_to_subtitle_log_recognized, segments.size))
            SpeechToSubtitleContent(generateSubtitle(segments), segments.size)
        } finally {
            cachedFile.delete()
            pcmFile.delete()
        }
    }

    private suspend fun copyToCache(uri: Uri, target: File) {
        val taskContext = currentCoroutineContext()
        val input = openInput(uri) ?: error(string(R.string.speech_to_subtitle_error_copy_failed))
        input.use { source ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    taskContext.ensureActive()
                    val count = source.read(buffer)
                    if (count == -1) break
                    output.write(buffer, 0, count)
                }
            }
        }
        taskContext.ensureActive()
    }

    private fun openInput(uri: Uri): InputStream? =
        PickedUriAccess.file(uri)?.inputStream() ?: app.contentResolver.openInputStream(uri)

    private fun recognize(
        pcmFile: File,
        progressPrefix: String,
        isCancelled: () -> Boolean,
    ): Result<List<WhisperRecognizer.SubtitleSegment>> {
        onLog(string(R.string.speech_to_subtitle_log_init_recognizer, currentAsrModelDisplayName()))
        logSenseVoiceLanguage()
        val recognizer = speechRecognitionService.createRecognizer(
            encoderPath = request.encoderPath,
            decoderPath = request.decoderPath,
            joinerPath = request.joinerPath,
            tokensPath = request.tokensPath,
            vadModelPath = request.vadModelPath,
            useVad = request.useVad,
            language = request.language,
            contentResolver = app.contentResolver,
            context = app,
            modelType = modelType,
        )
        return recognizer.recognize(
            audioFile = pcmFile,
            progressCallback = { progress, status, segment ->
                onProgress(
                    string(R.string.speech_to_subtitle_progress_status, progressPrefix, status),
                    10 + (progress * 0.8).toInt()
                )
                segment?.let(::logRecognizedSegment)
            },
            isCancelled = isCancelled,
        )
    }

    private fun recognizeWithTokenTimeline(
        pcmFile: File,
        progressPrefix: String,
        isCancelled: () -> Boolean,
    ): Result<List<WhisperRecognizer.SubtitleSegment>> {
        val timelineName = string(
            if (modelType == SettingsManager.ASR_MODEL_QWEN3_ASR) R.string.speech_to_subtitle_timeline_forced_alignment
            else R.string.speech_to_subtitle_timeline_experiment
        )
        onLog(string(R.string.speech_to_subtitle_log_timeline_init, timelineName, currentAsrModelDisplayName()))
        logSenseVoiceLanguage()
        val result = TokenTimestampGenerator(app).generateSegments(
            pcmFile = pcmFile,
            language = request.language,
            progressCallback = { progress, status ->
                onProgress(
                    string(R.string.speech_to_subtitle_progress_timeline, progressPrefix, timelineName, status),
                    10 + (progress * 0.4).toInt()
                )
            },
            isCancelled = isCancelled,
            modelConfig = TokenTimestampGenerator.ModelConfig(
                modelType = modelType,
                encoderPath = request.encoderPath,
                decoderPath = request.decoderPath,
                joinerPath = request.joinerPath,
                tokensPath = request.tokensPath,
                forcedAlignerPath = request.forcedAlignerPath,
            ),
        )
        return result.map { segments ->
            onLog(string(R.string.speech_to_subtitle_log_timeline_done, timelineName, segments.size))
            segments.map { segment ->
                WhisperRecognizer.SubtitleSegment(segment.startTime, segment.endTime, segment.text)
                    .also(::logRecognizedSegment)
            }
        }
    }

    private fun generateSubtitle(segments: List<WhisperRecognizer.SubtitleSegment>): String {
        val entries = segments.mapIndexed { index, segment ->
            SubtitleEntry(
                index = index + 1,
                startTime = segment.startTime,
                endTime = segment.endTime,
                text = segment.text,
            )
        }
        return when (request.format) {
            "LRC" -> SubtitleParser.toLRC(entries)
            "TXT" -> SubtitleParser.toTXT(entries)
            else -> SubtitleParser.toSRT(entries)
        }
    }

    private fun logSenseVoiceLanguage() {
        if (modelType != SettingsManager.ASR_MODEL_SENSEVOICE) return
        val code = when (request.language) {
            "中文" -> "zh"
            "英语" -> "en"
            "日语" -> "ja"
            "韩语" -> "ko"
            else -> "auto"
        }
        onLog(string(R.string.speech_to_subtitle_log_sensevoice_language, request.language, code))
    }

    private fun logRecognizedSegment(segment: WhisperRecognizer.SubtitleSegment) {
        onLog("\n[${formatSubtitleTime(segment.startTime)} --> ${formatSubtitleTime(segment.endTime)}]\n${segment.text}")
    }

    private fun formatSubtitleTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millis = timeMs % 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    private fun appendConfigLog(message: String) = onLog("  $message")

    private fun appendConfigLog(name: String, value: String) =
        appendConfigLog(string(R.string.speech_to_subtitle_config_named_path, name, value))

    private fun enabledText(enabled: Boolean, detail: () -> String): String =
        if (enabled) detail() else string(R.string.speech_to_subtitle_disabled)

    private fun dynamicPaddingText(): String =
        enabledText(settings.isSpeechVadDynamicPaddingEnabled(modelType)) {
            string(R.string.speech_to_subtitle_config_dynamic_padding_enabled)
        }

    private fun hotwordsText(): String =
        if (settings.isSpeechHotwordsEnabled()) {
            string(R.string.speech_to_subtitle_config_hotwords_enabled, settings.getSpeechHotwordsScore().toString())
        } else {
            string(R.string.speech_to_subtitle_not_enabled)
        }

    private fun logModelConfiguration() {
        onLog(string(R.string.speech_to_subtitle_config_header))
        appendConfigLog(string(R.string.speech_to_subtitle_config_type, currentAsrModelDisplayName()))
        val singleFileModel = modelType == SettingsManager.ASR_MODEL_SENSEVOICE ||
            modelType == SettingsManager.ASR_MODEL_PARAKEET_CTC_JA
        if (singleFileModel) {
            appendConfigLog(string(R.string.speech_to_subtitle_config_model, displayModelPath(request.encoderPath)))
        } else {
            appendConfigLog("Encoder", displayModelPath(request.encoderPath))
            appendConfigLog("Decoder", displayModelPath(request.decoderPath))
        }
        if (modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT) {
            appendConfigLog("Joiner", displayModelPath(request.joinerPath))
        }
        if (modelType == SettingsManager.ASR_MODEL_QWEN3_ASR) {
            appendConfigLog("Conv Frontend", displayModelPath(request.joinerPath))
            appendConfigLog(string(R.string.speech_to_subtitle_config_qwen_chunking))
        }
        appendConfigLog(
            if (modelType == SettingsManager.ASR_MODEL_QWEN3_ASR) "Tokenizer" else "Tokens",
            displayModelPath(request.tokensPath)
        )
        if (modelType != SettingsManager.ASR_MODEL_SENSEVOICE) {
            appendConfigLog(string(R.string.speech_to_subtitle_config_threads, settings.getSpeechWhisperThreads()))
        }
        if (request.useTokenTimeline) {
            val qwenForcedAlignment = modelType == SettingsManager.ASR_MODEL_QWEN3_ASR
            val timelineName = string(
                if (qwenForcedAlignment) R.string.speech_to_subtitle_forced_alignment
                else R.string.speech_to_subtitle_timeline_experiment
            )
            appendConfigLog(string(R.string.speech_to_subtitle_config_vad_disabled_by_timeline, timelineName))
            if (settings.isSpeechFixedVadSegmentationEnabled(modelType)) {
                appendConfigLog(string(R.string.speech_to_subtitle_config_fixed_vad_cut))
            }
            appendConfigLog(
                string(
                    R.string.speech_to_subtitle_config_timeline_model,
                    timelineName,
                    TokenTimestampGenerator.modelDisplayName(modelType),
                    displayModelPath(if (qwenForcedAlignment) request.forcedAlignerPath else request.encoderPath)
                )
            )
            val tokensText = displayModelPath(request.tokensPath)
            appendConfigLog(
                if (qwenForcedAlignment) string(R.string.speech_to_subtitle_config_named_path, "Tokenizer", tokensText)
                else string(R.string.speech_to_subtitle_config_experiment_tokens, tokensText)
            )
            appendConfigLog(
                string(
                    R.string.speech_to_subtitle_config_merge_segments,
                    enabledText(settings.isSpeechTokenTimestampMergeEnabled(modelType)) {
                        string(R.string.speech_to_subtitle_enabled_with, settings.speechTokenTimestampMergeSummary(modelType))
                    }
                )
            )
            appendConfigLog(string(R.string.speech_to_subtitle_config_dynamic_padding, dynamicPaddingText()))
            if (
                modelType == SettingsManager.ASR_MODEL_WHISPER ||
                modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT
            ) {
                appendConfigLog(string(R.string.speech_to_subtitle_config_hotwords, hotwordsText()))
            }
            return
        }
        val useVad = request.useVad
        val fixedSegmentText = if (
            modelType == SettingsManager.ASR_MODEL_SENSEVOICE &&
            settings.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU
        ) {
            string(R.string.speech_to_subtitle_config_fixed_segment_npu, settings.getSenseVoiceNpuDurationSeconds())
        } else if (modelType == SettingsManager.ASR_MODEL_QWEN3_ASR) {
            string(R.string.speech_to_subtitle_config_fixed_segment_qwen)
        } else {
            string(R.string.speech_to_subtitle_config_fixed_segment, settings.getSpeechFixedSegmentSeconds(modelType))
        }
        appendConfigLog(
            string(
                R.string.speech_to_subtitle_config_vad,
                if (useVad) string(R.string.speech_to_subtitle_enabled)
                else string(R.string.speech_to_subtitle_config_vad_disabled_with, fixedSegmentText)
            )
        )
        if (!useVad && settings.isSpeechFixedVadSegmentationEnabled(modelType)) {
            appendConfigLog(string(R.string.speech_to_subtitle_config_fixed_vad_cut))
        }
        if (useVad) {
            appendConfigLog(
                string(
                    R.string.speech_to_subtitle_config_vad_model,
                    if (request.vadModelPath.isBlank()) string(R.string.speech_to_subtitle_config_vad_builtin)
                    else displayModelPath(request.vadModelPath)
                )
            )
            appendConfigLog(
                string(
                    R.string.speech_to_subtitle_config_vad_params,
                    settings.getVadThreshold().toString(),
                    settings.getVadMinSilenceDuration().toString(),
                    settings.getVadMinSpeechDuration().toString(),
                    settings.getVadMaxSpeechDuration().toString()
                )
            )
            appendConfigLog(string(R.string.speech_to_subtitle_config_dynamic_padding, dynamicPaddingText()))
            appendConfigLog(
                string(
                    R.string.speech_to_subtitle_config_vad_merge,
                    enabledText(settings.isSpeechVadMergeEnabled()) {
                        string(R.string.speech_to_subtitle_config_merge_gap, settings.getSpeechVadMergeGapMs())
                    }
                )
            )
            val secondaryVadMode = settings.getSpeechSecondaryVadMode()
            val secondaryVadText = when (secondaryVadMode) {
                SettingsManager.SECONDARY_VAD_MODE_UNCOVERED -> string(R.string.speech_to_subtitle_config_secondary_vad_uncovered)
                SettingsManager.SECONDARY_VAD_MODE_WITHIN_SEGMENTS -> string(R.string.speech_to_subtitle_config_secondary_vad_within)
                else -> string(R.string.speech_to_subtitle_disabled)
            }
            appendConfigLog(string(R.string.speech_to_subtitle_config_secondary_vad, secondaryVadText))
            if (secondaryVadMode != SettingsManager.SECONDARY_VAD_MODE_NONE) {
                appendConfigLog(
                    string(
                        R.string.speech_to_subtitle_config_secondary_vad_params,
                        settings.getSpeechSecondaryVadThreshold().toString(),
                        settings.getSpeechSecondaryVadMinSilenceDuration().toString(),
                        settings.getSpeechSecondaryVadMinSpeechDuration().toString(),
                        settings.getSpeechSecondaryVadMaxSpeechDuration().toString()
                    )
                )
                appendConfigLog(
                    string(
                        R.string.speech_to_subtitle_config_secondary_vad_merge,
                        enabledText(settings.isSpeechSecondaryVadMergeEnabled()) {
                            string(R.string.speech_to_subtitle_config_merge_gap, settings.getSpeechSecondaryVadMergeGapMs())
                        }
                    )
                )
            }
        }
        if (modelType == SettingsManager.ASR_MODEL_WHISPER || modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT) {
            appendConfigLog(string(R.string.speech_to_subtitle_config_hotwords, hotwordsText()))
        }
    }

    private fun currentAsrModelDisplayName(): String = when (modelType) {
        SettingsManager.ASR_MODEL_SENSEVOICE -> "SenseVoice"
        SettingsManager.ASR_MODEL_PARAKEET_TDT -> "Parakeet TDT 0.6B v3"
        SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> string(R.string.speech_to_subtitle_model_parakeet_ctc_ja)
        SettingsManager.ASR_MODEL_QWEN3_ASR -> "Qwen3-ASR"
        else -> "Whisper"
    }

    private fun displayModelPath(path: String): String {
        return if (path.isBlank()) string(R.string.speech_to_subtitle_path_not_set)
        else Uri.parse(path).lastPathSegment ?: path
    }

    private fun string(id: Int, vararg args: Any): String =
        if (args.isEmpty()) app.getString(id) else app.getString(id, *args)
}
