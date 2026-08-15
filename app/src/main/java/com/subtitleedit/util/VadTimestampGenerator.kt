package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import java.io.File
import java.util.Locale

/**
 * VAD 时间轴生成器 - 使用 VAD 检测语音段并生成字幕时间轴
 */
class VadTimestampGenerator(private val context: Context) {

    companion object {
        private const val TAG = "VadTimestampGenerator"
        private const val SAMPLE_RATE = 16000
    }

    private var secondaryVad: Vad? = null

    private data class SampleRange(
        val startSample: Long,
        val endSample: Long
    )

    private enum class VadSegmentSource {
        PRIMARY,
        SECONDARY
    }

    private data class SourcedVadSegment(
        val segment: VadSegment,
        val tailSource: VadSegmentSource
    )

    /**
     * 生成时间轴
     */
    fun generateTimestamps(pcmFile: File): Result<String> {
        return try {
            val segments = generateSegments(pcmFile)
            if (segments.isEmpty()) {
                return Result.failure(Exception("未检测到任何语音段"))
            }
            val subtitle = generateSrtSubtitle(segments)
            Result.success(subtitle)
        } catch (e: Exception) {
            Log.e(TAG, "生成时间轴失败", e)
            Result.failure(e)
        }
    }

    /**
     * 生成语音段列表
     */
    fun generateSegments(pcmFile: File): List<VadSegment> {
        Log.d(TAG, "开始生成时间轴，音频文件: ${pcmFile.absolutePath}")

        // 初始化 VAD
        val vad = initVad()
        if (vad == null) {
            Log.e(TAG, "VAD 初始化失败")
            return emptyList()
        }

        return try {
            val segments = Pcm16WavReader(pcmFile).use { reader ->
                Log.d(TAG, "音频信息: sampleRate=${reader.sampleRate}, channels=${reader.channels}, samples=${reader.totalSamples}")
                val primarySegments = detectSpeechSegments(vad, reader)
                val settingsManager = SettingsManager.getInstance(context)
                val secondaryMode = settingsManager.getSpeechSecondaryVadMode()
                if (secondaryMode == SettingsManager.SECONDARY_VAD_MODE_NONE) {
                    primarySegments
                } else {
                    secondaryVad = initVad(secondary = true)
                    if (secondaryVad == null) {
                        Log.w(TAG, "二次 VAD 初始化失败，将仅使用第一次 VAD")
                        primarySegments
                    } else {
                        applySecondaryVad(reader, primarySegments, secondaryMode)
                    }
                }
            }
            Log.d(TAG, "检测到 ${segments.size} 个语音段")
            segments
        } finally {
            try {
                vad.release()
            } finally {
                secondaryVad?.release()
                secondaryVad = null
            }
        }
    }

    /**
     * 初始化 VAD
     */
    private fun initVad(secondary: Boolean = false): Vad? {
        return try {
            val settingsManager = SettingsManager.getInstance(context)
            val useBuiltIn = settingsManager.isVadUseBuiltInModel()
            val vadModelPath = settingsManager.getVadModelPath()
            val vadFile = if (useBuiltIn) {
                null
            } else {
                if (vadModelPath.isBlank()) {
                    Log.e(TAG, "外部 VAD 模型未选择")
                    return null
                }
                copyUriToCache(
                    Uri.parse(vadModelPath),
                    if (secondary) "auto_timestamp_secondary_vad.onnx" else "auto_timestamp_vad.onnx"
                ) ?: return null
            }
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadFile?.absolutePath ?: "silero_vad.onnx",
                    threshold = if (secondary) {
                        settingsManager.getSpeechSecondaryVadThreshold()
                    } else {
                        settingsManager.getVadThreshold()
                    },
                    minSilenceDuration = if (secondary) {
                        settingsManager.getSpeechSecondaryVadMinSilenceDuration()
                    } else {
                        settingsManager.getVadMinSilenceDuration()
                    },
                    minSpeechDuration = if (secondary) {
                        settingsManager.getSpeechSecondaryVadMinSpeechDuration()
                    } else {
                        settingsManager.getVadMinSpeechDuration()
                    },
                    windowSize = 512,
                    maxSpeechDuration = if (secondary) {
                        settingsManager.getSpeechSecondaryVadMaxSpeechDuration()
                    } else {
                        settingsManager.getVadMaxSpeechDuration()
                    }
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 2,
                provider = "cpu",
                debug = true
            )
            val vad = Vad(assetManager = if (useBuiltIn) context.assets else null, config = vadConfig)
            Log.d(
                TAG,
                "${if (secondary) "二次 VAD" else "VAD"} 初始化成功（${if (useBuiltIn) "内置模型" else "外部模型：${vadFile?.absolutePath}"}）"
            )
            vad
        } catch (e: Exception) {
            Log.e(TAG, "VAD 初始化失败", e)
            null
        }
    }

    /**
     * 复制外部 VAD 模型到缓存，供 native 层按文件路径读取
     */
    private fun copyUriToCache(uri: Uri, fileName: String): File? {
        return try {
            val cacheFile = File(context.cacheDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (cacheFile.exists()) {
                Log.d(TAG, "外部 VAD 模型复制成功: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
                cacheFile
            } else {
                Log.e(TAG, "外部 VAD 模型复制失败: 文件不存在")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "复制外部 VAD 模型失败", e)
            null
        }
    }

    /**
     * 检测语音段
     */
    private fun detectSpeechSegments(vad: Vad, reader: Pcm16WavReader): List<VadSegment> {
        val segments = mutableListOf<VadSegment>()

        try {
            reader.forEachChunk(chunkSamples = 512) { chunk, _ ->
                vad.acceptWaveform(chunk)

                // 立即检查是否有语音段产生
                while (!vad.empty()) {
                    val speechSegment = vad.front()
                    vad.pop()

                    val startSample = speechSegment.start
                    val endSample = startSample + speechSegment.samples.size
                    val startTime = (startSample * 1000L) / SAMPLE_RATE
                    val endTime = (endSample * 1000L) / SAMPLE_RATE

                    segments.add(
                        VadSegment(
                            startTime = startTime,
                            endTime = endTime,
                            startSample = startSample,
                            sampleCount = speechSegment.samples.size
                        )
                    )
                    Log.d(TAG, "检测到语音段: ${startTime}ms - ${endTime}ms")
                }
            }

            // 刷新 VAD 缓冲区
            vad.flush()

            // 提取 flush 后产生的语音段
            while (!vad.empty()) {
                val speechSegment = vad.front()
                vad.pop()

                val startSample = speechSegment.start
                val endSample = startSample + speechSegment.samples.size
                val startTime = (startSample * 1000L) / SAMPLE_RATE
                val endTime = (endSample * 1000L) / SAMPLE_RATE

                segments.add(
                    VadSegment(
                        startTime = startTime,
                        endTime = endTime,
                        startSample = startSample,
                        sampleCount = speechSegment.samples.size
                    )
                )
                Log.d(TAG, "flush 后检测到语音段: ${startTime}ms - ${endTime}ms")
            }

            vad.reset()

        } catch (e: Exception) {
            Log.e(TAG, "语音段检测失败", e)
        }

        return segments
    }

    private fun applySecondaryVad(
        reader: Pcm16WavReader,
        primarySegments: List<VadSegment>,
        mode: String
    ): List<VadSegment> {
        val processedSegments = when (mode) {
            SettingsManager.SECONDARY_VAD_MODE_UNCOVERED -> {
                val uncoveredRanges = findUncoveredRanges(reader.totalSamples, primarySegments)
                Log.d(TAG, "二次 VAD 方案一：处理 ${uncoveredRanges.size} 个未划分区间")
                val secondarySegments = uncoveredRanges.flatMap { range ->
                    detectSecondarySpeechSegments(reader, range)
                }
                Log.d(TAG, "二次 VAD 方案一：新增 ${secondarySegments.size} 个语音段")
                (
                    primarySegments.map { SourcedVadSegment(it, VadSegmentSource.PRIMARY) } +
                        secondarySegments.map { SourcedVadSegment(it, VadSegmentSource.SECONDARY) }
                    ).sortedBy { it.segment.startSample }
            }

            SettingsManager.SECONDARY_VAD_MODE_WITHIN_SEGMENTS -> {
                Log.d(TAG, "二次 VAD 方案二：处理 ${primarySegments.size} 个第一次 VAD 语音段")
                val refinedSegments = primarySegments.sortedBy { it.startSample }.flatMap { segment ->
                    val range = SampleRange(
                        startSample = segment.startSample.toLong(),
                        endSample = segment.startSample.toLong() + segment.sampleCount.toLong()
                    )
                    val secondarySegments = detectSecondarySpeechSegments(reader, range)
                    if (secondarySegments.isEmpty()) {
                        listOf(SourcedVadSegment(segment, VadSegmentSource.PRIMARY))
                    } else {
                        secondarySegments.map { SourcedVadSegment(it, VadSegmentSource.SECONDARY) }
                    }
                }
                Log.d(TAG, "二次 VAD 方案二：生成 ${refinedSegments.size} 个语音段")
                refinedSegments.sortedBy { it.segment.startSample }
            }

            else -> primarySegments.map { SourcedVadSegment(it, VadSegmentSource.PRIMARY) }
        }
        val settingsManager = SettingsManager.getInstance(context)
        if (!settingsManager.isSpeechSecondaryVadMergeEnabled()) {
            return processedSegments.map { it.segment }
        }

        val mergedSegments = mergeCrossSourceVadSegments(
            processedSegments,
            settingsManager.getSpeechSecondaryVadMergeGapMs()
        )
        Log.d(
            TAG,
            "二次 VAD 合并语音段：${processedSegments.size} -> ${mergedSegments.size}，" +
                "最大间隔 ${settingsManager.getSpeechSecondaryVadMergeGapMs()}ms"
        )
        return mergedSegments.map { it.segment }
    }

    private fun mergeCrossSourceVadSegments(
        segments: List<SourcedVadSegment>,
        maxGapMs: Int
    ): List<SourcedVadSegment> {
        if (segments.size < 2) return segments.sortedBy { it.segment.startSample }

        val maxGapSamples = (maxGapMs.toLong() * SAMPLE_RATE) / 1000L
        val sorted = segments.sortedBy { it.segment.startSample }
        val merged = mutableListOf<SourcedVadSegment>()
        var current = sorted.first()

        for (next in sorted.drop(1)) {
            val currentEnd = current.segment.startSample.toLong() + current.segment.sampleCount.toLong()
            val nextStart = next.segment.startSample.toLong()
            val hasOppositeSources = current.tailSource != next.tailSource
            if (hasOppositeSources && nextStart - currentEnd <= maxGapSamples) {
                val mergedStart = current.segment.startSample.toLong()
                val mergedEnd = maxOf(
                    currentEnd,
                    next.segment.startSample.toLong() + next.segment.sampleCount.toLong()
                )
                current = SourcedVadSegment(
                    segment = VadSegment(
                        startTime = (mergedStart * 1000L) / SAMPLE_RATE,
                        endTime = (mergedEnd * 1000L) / SAMPLE_RATE,
                        startSample = mergedStart.toInt(),
                        sampleCount = (mergedEnd - mergedStart).toInt()
                    ),
                    tailSource = next.tailSource
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    /**
     * 将已有字幕时间段视为已覆盖区域，仅对所有未覆盖音频运行二次 VAD 方案一。
     * 此入口不读取语音转字幕配置中的二次 VAD 模式开关。
     */
    fun generateUncoveredSegments(
        pcmFile: File,
        occupiedTimeRangesMs: List<Pair<Long, Long>>
    ): List<VadSegment> {
        val vad = initVad(secondary = true)
        if (vad == null) {
            Log.e(TAG, "二次 VAD 初始化失败")
            return emptyList()
        }
        secondaryVad = vad

        return try {
            Pcm16WavReader(pcmFile).use { reader ->
                val occupiedRanges = occupiedTimeRangesMs.mapNotNull { (startTime, endTime) ->
                    val startSample = (startTime.coerceAtLeast(0L) * SAMPLE_RATE) / 1000L
                    val endSample = (endTime.coerceAtLeast(startTime) * SAMPLE_RATE) / 1000L
                    val clampedStart = startSample.coerceIn(0L, reader.totalSamples)
                    val clampedEnd = endSample.coerceIn(clampedStart, reader.totalSamples)
                    if (clampedEnd > clampedStart) {
                        SampleRange(clampedStart, clampedEnd)
                    } else {
                        null
                    }
                }
                val uncoveredRanges = findUncoveredSampleRanges(reader.totalSamples, occupiedRanges)
                Log.d(TAG, "自动打轴二次处理：扫描 ${uncoveredRanges.size} 个未覆盖区间")
                uncoveredRanges.flatMap { range ->
                    detectSecondarySpeechSegments(reader, range)
                }.sortedBy { it.startSample }
            }
        } finally {
            secondaryVad?.release()
            secondaryVad = null
        }
    }

    private fun findUncoveredRanges(
        totalSamples: Long,
        segments: List<VadSegment>
    ): List<SampleRange> {
        val ranges = mutableListOf<SampleRange>()
        var coveredUntil = 0L

        for (segment in segments.sortedBy { it.startSample }) {
            val segmentStart = segment.startSample.toLong().coerceIn(0L, totalSamples)
            val segmentEnd = (segment.startSample.toLong() + segment.sampleCount.toLong())
                .coerceIn(segmentStart, totalSamples)
            if (segmentStart > coveredUntil) {
                ranges.add(SampleRange(coveredUntil, segmentStart))
            }
            coveredUntil = maxOf(coveredUntil, segmentEnd)
        }

        if (coveredUntil < totalSamples) {
            ranges.add(SampleRange(coveredUntil, totalSamples))
        }
        return ranges
    }

    private fun findUncoveredSampleRanges(
        totalSamples: Long,
        coveredRanges: List<SampleRange>
    ): List<SampleRange> {
        val ranges = mutableListOf<SampleRange>()
        var coveredUntil = 0L

        for (coveredRange in coveredRanges.sortedBy { it.startSample }) {
            val coveredStart = coveredRange.startSample.coerceIn(0L, totalSamples)
            val coveredEnd = coveredRange.endSample.coerceIn(coveredStart, totalSamples)
            if (coveredStart > coveredUntil) {
                ranges.add(SampleRange(coveredUntil, coveredStart))
            }
            coveredUntil = maxOf(coveredUntil, coveredEnd)
        }

        if (coveredUntil < totalSamples) {
            ranges.add(SampleRange(coveredUntil, totalSamples))
        }
        return ranges
    }

    private fun detectSecondarySpeechSegments(
        reader: Pcm16WavReader,
        range: SampleRange
    ): List<VadSegment> {
        val vadInstance = secondaryVad ?: return emptyList()
        val rangeStart = range.startSample.coerceIn(0L, reader.totalSamples)
        val rangeEnd = range.endSample.coerceIn(rangeStart, reader.totalSamples)
        if (rangeEnd <= rangeStart) return emptyList()

        val segments = mutableListOf<VadSegment>()
        return try {
            vadInstance.reset()
            var cursor = rangeStart
            while (cursor < rangeEnd) {
                val chunkSize = minOf(512L, rangeEnd - cursor).toInt()
                vadInstance.acceptWaveform(reader.readRange(cursor, chunkSize))
                cursor += chunkSize
                drainSecondaryVadSegments(vadInstance, rangeStart, rangeEnd, segments)
            }

            vadInstance.flush()
            drainSecondaryVadSegments(vadInstance, rangeStart, rangeEnd, segments)
            segments
        } catch (e: Exception) {
            Log.e(TAG, "二次 VAD 检测失败: $rangeStart - $rangeEnd 采样点", e)
            emptyList()
        } finally {
            runCatching { vadInstance.reset() }
        }
    }

    private fun drainSecondaryVadSegments(
        vadInstance: Vad,
        rangeStart: Long,
        rangeEnd: Long,
        output: MutableList<VadSegment>
    ) {
        while (!vadInstance.empty()) {
            val speechSegment = vadInstance.front()
            vadInstance.pop()

            val startSample = (rangeStart + speechSegment.start.toLong())
                .coerceIn(rangeStart, rangeEnd)
            val endSample = (rangeStart + speechSegment.start.toLong() + speechSegment.samples.size)
                .coerceIn(startSample, rangeEnd)
            if (endSample <= startSample) continue

            output.add(
                VadSegment(
                    startTime = (startSample * 1000L) / SAMPLE_RATE,
                    endTime = (endSample * 1000L) / SAMPLE_RATE,
                    startSample = startSample.toInt(),
                    sampleCount = (endSample - startSample).toInt()
                )
            )
        }
    }

    /**
     * 生成 SRT 格式字幕
     */
    private fun generateSrtSubtitle(segments: List<VadSegment>): String {
        val builder = StringBuilder()

        for ((index, segment) in segments.withIndex()) {
            // 序号
            builder.append(index + 1).append("\n")

            // 时间轴
            val startTime = formatSrtTime(segment.startTime)
            val endTime = formatSrtTime(segment.endTime)
            builder.append("$startTime --> $endTime\n")

            // 字幕内容
            builder.append("请输入文本\n")

            // 空行分隔
            builder.append("\n")
        }

        return builder.toString()
    }

    /**
     * 格式化 SRT 时间
     */
    private fun formatSrtTime(milliseconds: Long): String {
        val hours = milliseconds / 3600000
        val minutes = (milliseconds % 3600000) / 60000
        val seconds = (milliseconds % 60000) / 1000
        val millis = milliseconds % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    /**
     * VAD 语音段
     */
    data class VadSegment(
        val startTime: Long,
        val endTime: Long,
        internal val startSample: Int = 0,
        internal val sampleCount: Int = 0
    )
}
