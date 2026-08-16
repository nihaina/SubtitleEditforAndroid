package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import java.io.File

class SenseVoiceTimestampGenerator(context: Context) {

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
        if (!isConfigured(appContext)) {
            return Result.failure(
                Exception("实验打轴需要先在模型设置中配置 SenseVoice int8 模型和 tokens.txt")
            )
        }

        val recognizer = WhisperRecognizer(
            encoderPath = modelPath(settingsManager),
            decoderPath = "",
            joinerPath = "",
            tokensPath = tokensPath(settingsManager),
            vadModelPath = "",
            useVad = false,
            language = language,
            contentResolver = appContext.contentResolver,
            context = appContext,
            modelType = SettingsManager.ASR_MODEL_SENSEVOICE,
            senseVoiceTimestampExperiment = true,
            senseVoiceTimestampGapMs = settingsManager.getSpeechSenseVoiceTimestampGapMs()
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
                error("SenseVoice 未生成有效 token 时间轴")
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
        fun isConfigured(context: Context): Boolean {
            val settings = SettingsManager.getInstance(context.applicationContext)
            val modelPath = modelPath(settings)
            val tokensPath = tokensPath(settings)
            return Uri.parse(modelPath).lastPathSegment.equals(
                "model.int8.onnx",
                ignoreCase = true
            ) && Uri.parse(tokensPath).lastPathSegment.equals(
                "tokens.txt",
                ignoreCase = true
            ) && canReadPath(context, modelPath) && canReadPath(context, tokensPath)
        }

        fun modelPath(settingsManager: SettingsManager): String =
            settingsManager.getSenseVoiceModelPath(SettingsManager.SENSEVOICE_PROVIDER_CPU)

        fun tokensPath(settingsManager: SettingsManager): String =
            settingsManager.getSenseVoiceTokensPath(SettingsManager.SENSEVOICE_PROVIDER_CPU)

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
    }
}
