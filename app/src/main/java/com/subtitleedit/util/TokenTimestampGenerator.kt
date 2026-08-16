package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import java.io.File

class TokenTimestampGenerator(context: Context) {

    data class Segment(
        val startTime: Long,
        val endTime: Long,
        val text: String = ""
    )

    private val appContext = context.applicationContext
    private val settingsManager = SettingsManager.getInstance(appContext)

    fun generateSegments(
        pcmFile: File,
        language: String = "自动检测",
        progressCallback: (progress: Int, status: String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): Result<List<Segment>> {
        val modelConfig = currentModelConfig(settingsManager)
        if (!isConfigured(appContext, modelConfig)) {
            return Result.failure(
                Exception("实验打轴需要先配置当前非 Whisper ASR 模型")
            )
        }

        val recognizer = WhisperRecognizer(
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
            settingsManager.getAsrModelType() != SettingsManager.ASR_MODEL_WHISPER

        fun isConfigured(context: Context): Boolean {
            val settings = SettingsManager.getInstance(context.applicationContext)
            return isConfigured(context, currentModelConfig(settings))
        }

        fun modelPath(settingsManager: SettingsManager): String =
            currentModelConfig(settingsManager).encoderPath

        fun tokensPath(settingsManager: SettingsManager): String =
            currentModelConfig(settingsManager).tokensPath

        fun modelDisplayName(settingsManager: SettingsManager): String = when (
            settingsManager.getAsrModelType()
        ) {
            SettingsManager.ASR_MODEL_SENSEVOICE -> "SenseVoice"
            SettingsManager.ASR_MODEL_PARAKEET_TDT -> "Parakeet TDT"
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> "Parakeet CTC 日语"
            else -> "Whisper"
        }

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
            if (config.modelType == SettingsManager.ASR_MODEL_WHISPER) return false
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
