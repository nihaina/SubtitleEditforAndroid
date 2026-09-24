package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import com.subtitleedit.repository.DefaultSpeechRecognitionService
import com.subtitleedit.repository.SpeechRecognitionService
import java.io.File

class TokenTimestampGenerator(context: Context) {

    data class Segment(
        val startTime: Long,
        val endTime: Long,
        val text: String = ""
    )

    private val appContext = context.applicationContext
    private val settingsManager = SettingsManager.getInstance(appContext)
    private val speechRecognitionService: SpeechRecognitionService = DefaultSpeechRecognitionService()

    /**
     * Adapts an independent aligner's character/word spans to the experiment timeline format.
     * The Qwen3-ForcedAligner path uses this after its ONNX inference; ASR token timestamps use
     * [TokenTimestampSegmenter] directly.
     */
    fun segmentsFromForcedAlignment(
        units: List<ForcedAlignmentUnit>,
        audioStartTimeMs: Long,
        audioEndTimeMs: Long,
        splitGapMs: Int = settingsManager.getSpeechTokenTimestampGapMs(),
    ): List<Segment> = ForcedAlignmentSegmenter.split(
        units = units,
        audioStartTimeMs = audioStartTimeMs,
        audioEndTimeMs = audioEndTimeMs,
        splitGapMs = splitGapMs,
    ).map { segment ->
        Segment(
            startTime = segment.startTimeMs,
            endTime = segment.endTimeMs,
            text = segment.text,
        )
    }

    fun generateSegments(
        pcmFile: File,
        language: String = "自动检测",
        progressCallback: (progress: Int, status: String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): Result<List<Segment>> {
        if (settingsManager.getAsrModelType() == SettingsManager.ASR_MODEL_QWEN3_ASR) {
            return generateQwen3Segments(pcmFile, language, progressCallback, isCancelled)
        }
        val modelConfig = currentModelConfig(settingsManager)
        if (!isConfigured(appContext, modelConfig)) {
            return Result.failure(
                Exception("实验打轴需要先配置当前非 Whisper ASR 模型")
            )
        }

        val recognizer = speechRecognitionService.createRecognizer(
            encoderPath = modelConfig.encoderPath,
            decoderPath = modelConfig.decoderPath,
            joinerPath = modelConfig.joinerPath,
            tokensPath = modelConfig.tokensPath,
            vadModelPath = "",
            useVad = false,
            language = language,
            contentResolver = appContext.contentResolver,
            context = appContext,
            modelType = modelConfig.modelType,
            tokenTimestampExperiment = true,
            tokenTimestampGapMs = settingsManager.getSpeechTokenTimestampGapMs()
        )
        return recognizer.recognize(
            audioFile = pcmFile,
            progressCallback = { progress, status, _ ->
                progressCallback(progress, status)
            },
            isCancelled = isCancelled
        ).mapCatching { recognizedSegments ->
            recognizedSegments.map { segment ->
                Segment(segment.startTime, segment.endTime, segment.text)
            }.ifEmpty {
                error("当前模型未生成有效 token 时间轴")
            }
        }
    }

    private fun generateQwen3Segments(
        pcmFile: File,
        language: String,
        progressCallback: (Int, String) -> Unit,
        isCancelled: () -> Boolean,
    ): Result<List<Segment>> {
        val tokenizerPath = settingsManager.getQwen3AsrTokenizerPath()
        val alignerPath = settingsManager.getQwen3ForcedAlignerPath()
        val tokenizerDirectory = localFile(tokenizerPath)
        val alignerFile = localFile(alignerPath)
        if (tokenizerDirectory == null || !tokenizerDirectory.isDirectory) {
            return Result.failure(IllegalStateException("无法读取 Qwen3 tokenizer 目录"))
        }
        if (alignerFile == null || !alignerFile.isFile) {
            return Result.failure(IllegalStateException("无法读取 Qwen3-ForcedAligner ONNX 文件"))
        }
        if (!QwenHuggingFaceTokenizer.isAvailable()) {
            return Result.failure(IllegalStateException("未加载 libqwen_tokenizer，请检查 APK ABI"))
        }
        return runCatching {
            progressCallback(5, "Qwen3-ASR 识别文本")
            val recognizer = speechRecognitionService.createRecognizer(
                encoderPath = settingsManager.getQwen3AsrEncoderPath(),
                decoderPath = settingsManager.getQwen3AsrDecoderPath(),
                joinerPath = settingsManager.getQwen3AsrConvFrontendPath(),
                tokensPath = tokenizerPath,
                vadModelPath = "",
                useVad = false,
                language = language,
                contentResolver = appContext.contentResolver,
                context = appContext,
                modelType = SettingsManager.ASR_MODEL_QWEN3_ASR,
            )
            val recognized = recognizer.recognize(
                audioFile = pcmFile,
                progressCallback = { progress, status, _ ->
                    progressCallback(5 + progress / 3, status)
                },
                isCancelled = isCancelled,
            ).getOrThrow()
            if (recognized.isEmpty()) error("Qwen3-ASR 未识别到文本")
            val extractor = QwenLogMelExtractor()
            val output = mutableListOf<ForcedAlignmentUnit>()
            Qwen3ForcedAlignerOnnx(alignerFile).use { aligner ->
                Qwen3ForcedAlignmentTextEncoder(tokenizerDirectory).use { encoder ->
                    Pcm16WavReader(pcmFile).use { reader ->
                        require(reader.sampleRate == 16_000) { "Qwen 对齐音频必须是 16kHz" }
                        recognized.forEachIndexed { index, segment ->
                            if (isCancelled()) error("用户取消")
                            if (segment.text.isBlank()) return@forEachIndexed
                            val startSample = (segment.startTime * 16L).coerceAtLeast(0L)
                            val sampleCount = ((segment.endTime - segment.startTime).coerceAtLeast(1L) * 16L)
                                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            val samples = reader.readRange(startSample, sampleCount)
                            if (samples.isEmpty()) return@forEachIndexed
                            val encoded = encoder.encode(segment.text, language)
                            val features = extractor.extract(samples)
                            val aligned = aligner.align(
                                Qwen3ForcedAlignerOnnx.Input(
                                    inputIds = encoded.inputIds,
                                    inputFeatures = features,
                                    attentionMask = LongArray(encoded.inputIds.size) { 1L },
                                    featureAttentionMask = LongArray(features.first().size) { 1L },
                                    timestampPositions = encoded.timestampPositions,
                                    units = encoded.units,
                                )
                            )
                            output += aligned.map { unit ->
                                unit.copy(
                                    startTimeMs = unit.startTimeMs + segment.startTime,
                                    endTimeMs = unit.endTimeMs + segment.startTime,
                                )
                            }
                            progressCallback(40 + (index + 1) * 50 / recognized.size, "对齐 ${index + 1}/${recognized.size}")
                        }
                    }
                }
            }
            val durationMs = Pcm16WavReader(pcmFile).use { it.totalSamples * 1000L / it.sampleRate }
            val segments = ForcedAlignmentSegmenter.split(
                output,
                0L,
                durationMs,
                settingsManager.getSpeechTokenTimestampGapMs()
            ).map { Segment(it.startTimeMs, it.endTimeMs, it.text) }
            if (segments.isEmpty()) error("Qwen3 ForcedAligner 未生成有效时间轴")
            progressCallback(100, "Qwen3 对齐完成")
            segments
        }
    }

    private fun localFile(path: String): File? {
        if (path.isBlank()) return null
        val uri = Uri.parse(path)
        return if (uri.scheme.isNullOrEmpty() || uri.scheme == "file") {
            File(uri.path ?: path)
        } else null
    }

    fun generateUncoveredSegments(
        pcmFile: File,
        occupiedTimeRangesMs: List<Pair<Long, Long>>,
        language: String = "自动检测",
        progressCallback: (progress: Int, status: String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): Result<List<Segment>> {
        return generateSegments(
            pcmFile = pcmFile,
            language = language,
            progressCallback = progressCallback,
            isCancelled = isCancelled
        ).map { segments ->
            subtractOccupiedRanges(segments, occupiedTimeRangesMs)
        }
    }

    private fun subtractOccupiedRanges(
        segments: List<Segment>,
        occupiedTimeRangesMs: List<Pair<Long, Long>>
    ): List<Segment> {
        if (segments.isEmpty() || occupiedTimeRangesMs.isEmpty()) return segments

        val occupiedRanges = mergeRanges(
            occupiedTimeRangesMs.mapNotNull { (startTime, endTime) ->
                val start = startTime.coerceAtLeast(0L)
                val end = endTime.coerceAtLeast(start)
                if (end > start) Segment(start, end) else null
            }
        )
        if (occupiedRanges.isEmpty()) return segments

        return segments.flatMap { segment ->
            val uncovered = mutableListOf<Segment>()
            var cursor = segment.startTime
            for (occupied in occupiedRanges) {
                if (occupied.endTime <= cursor) continue
                if (occupied.startTime >= segment.endTime) break

                val uncoveredEnd = minOf(occupied.startTime, segment.endTime)
                if (uncoveredEnd > cursor) {
                    uncovered += Segment(cursor, uncoveredEnd)
                }
                cursor = maxOf(cursor, occupied.endTime)
                if (cursor >= segment.endTime) break
            }
            if (cursor < segment.endTime) {
                uncovered += Segment(cursor, segment.endTime)
            }
            uncovered
        }
    }

    private fun mergeRanges(ranges: List<Segment>): List<Segment> {
        if (ranges.size < 2) return ranges

        val sortedRanges = ranges.sortedBy { it.startTime }
        val merged = mutableListOf<Segment>()
        var current = sortedRanges.first()
        for (next in sortedRanges.drop(1)) {
            if (next.startTime <= current.endTime) {
                current = current.copy(endTime = maxOf(current.endTime, next.endTime))
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    companion object {
        fun isSupported(settingsManager: SettingsManager): Boolean =
            settingsManager.getAsrModelType() != SettingsManager.ASR_MODEL_WHISPER &&
                (settingsManager.getAsrModelType() != SettingsManager.ASR_MODEL_QWEN3_ASR ||
                    isQwen3ForcedAlignerConfigured(settingsManager))

        fun isConfigured(context: Context): Boolean {
            val settings = SettingsManager.getInstance(context.applicationContext)
            if (settings.getAsrModelType() == SettingsManager.ASR_MODEL_QWEN3_ASR) {
                return isQwen3AsrConfigured(context, settings)
            }
            return isConfigured(context, currentModelConfig(settings))
        }

        fun modelPath(settingsManager: SettingsManager): String =
            if (settingsManager.getAsrModelType() == SettingsManager.ASR_MODEL_QWEN3_ASR) {
                settingsManager.getQwen3ForcedAlignerPath()
            } else {
                currentModelConfig(settingsManager).encoderPath
            }

        fun tokensPath(settingsManager: SettingsManager): String =
            if (settingsManager.getAsrModelType() == SettingsManager.ASR_MODEL_QWEN3_ASR) {
                settingsManager.getQwen3AsrTokenizerPath()
            } else {
                currentModelConfig(settingsManager).tokensPath
            }

        fun modelDisplayName(settingsManager: SettingsManager): String = when (
            settingsManager.getAsrModelType()
        ) {
            SettingsManager.ASR_MODEL_SENSEVOICE -> "SenseVoice"
            SettingsManager.ASR_MODEL_PARAKEET_TDT -> "Parakeet TDT"
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> "Parakeet CTC 日语"
            SettingsManager.ASR_MODEL_QWEN3_ASR -> "Qwen3-ForcedAligner"
            else -> "Whisper"
        }

        fun isQwen3ForcedAlignerConfigured(settingsManager: SettingsManager): Boolean {
            if (settingsManager.getAsrModelType() != SettingsManager.ASR_MODEL_QWEN3_ASR) return false
            val path = settingsManager.getQwen3ForcedAlignerPath()
            if (path.isBlank()) return false
            val uri = Uri.parse(path)
            val file = if (uri.scheme.isNullOrEmpty() || uri.scheme == "file") {
                File(uri.path ?: path)
            } else {
                null
            }
            return file?.let { it.isFile && it.length() > 0L } == true
        }

        private fun isQwen3AsrConfigured(
            context: Context,
            settings: SettingsManager,
        ): Boolean {
            if (!isQwen3ForcedAlignerConfigured(settings)) return false
            val variant = settings.getQwen3AsrModelVariant()
            val modelFiles = listOf(
                settings.getQwen3AsrEncoderPath(variant),
                settings.getQwen3AsrDecoderPath(variant),
                settings.getQwen3AsrConvFrontendPath(variant),
            )
            if (!modelFiles.all { canReadPath(context, it) }) return false
            val tokenizerPath = settings.getQwen3AsrTokenizerPath(variant)
            if (tokenizerPath.isBlank()) return false
            val uri = Uri.parse(tokenizerPath)
            if (uri.scheme.isNullOrEmpty() || uri.scheme == "file") {
                val directory = File(uri.path ?: tokenizerPath)
                return directory.isDirectory &&
                    QWEN_REQUIRED_TOKENIZER_FILES.all { File(directory, it).isFile }
            }
            return false
        }

        private val QWEN_REQUIRED_TOKENIZER_FILES = listOf(
            "chat_template.json",
            "config.json",
            "merges.txt",
            "preprocessor_config.json",
            "tokenizer_config.json",
            "vocab.json",
        )

        private fun currentModelConfig(settings: SettingsManager): ModelConfig =
            when (val modelType = settings.getAsrModelType()) {
                SettingsManager.ASR_MODEL_SENSEVOICE -> ModelConfig(
                    modelType = modelType,
                    encoderPath = settings.getSenseVoiceModelPath(),
                    tokensPath = settings.getSenseVoiceTokensPath()
                )
                SettingsManager.ASR_MODEL_PARAKEET_TDT -> ModelConfig(
                    modelType = modelType,
                    encoderPath = settings.getParakeetTdtEncoderPath(),
                    decoderPath = settings.getParakeetTdtDecoderPath(),
                    joinerPath = settings.getParakeetTdtJoinerPath(),
                    tokensPath = settings.getParakeetTdtTokensPath()
                )
                SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> ModelConfig(
                    modelType = modelType,
                    encoderPath = settings.getParakeetCtcModelPath(),
                    tokensPath = settings.getParakeetCtcTokensPath()
                )
                else -> ModelConfig(modelType = SettingsManager.ASR_MODEL_WHISPER)
            }

        private fun isConfigured(context: Context, config: ModelConfig): Boolean {
            if (config.modelType == SettingsManager.ASR_MODEL_WHISPER ||
                config.modelType == SettingsManager.ASR_MODEL_QWEN3_ASR
            ) return false
            val requiredPaths = buildList {
                add(config.encoderPath)
                add(config.tokensPath)
                if (config.modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT) {
                    add(config.decoderPath)
                    add(config.joinerPath)
                }
            }
            return requiredPaths.all { canReadPath(context, it) }
        }

        private fun canReadPath(context: Context, path: String): Boolean {
            if (path.isBlank()) return false
            val uri = Uri.parse(path)
            if (uri.scheme.isNullOrEmpty() || uri.scheme == "file") {
                return File(uri.path ?: path).let { it.isFile && it.length() > 0L }
            }
            return runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    it.statSize != 0L
                } == true
            }.getOrDefault(false)
        }

        private data class ModelConfig(
            val modelType: String,
            val encoderPath: String = "",
            val decoderPath: String = "",
            val joinerPath: String = "",
            val tokensPath: String = ""
        )
    }
}
