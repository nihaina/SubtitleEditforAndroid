package com.subtitleedit.util

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import java.io.File

/**
 * VAD 时间轴生成器 - 使用 VAD 检测语音段并生成字幕时间轴
 */
class VadTimestampGenerator(private val context: Context) {

    companion object {
        private const val TAG = "VadTimestampGenerator"
        private const val SAMPLE_RATE = 16000
    }

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

        val segments = Pcm16WavReader(pcmFile).use { reader ->
            Log.d(TAG, "音频信息: sampleRate=${reader.sampleRate}, channels=${reader.channels}, samples=${reader.totalSamples}")
            detectSpeechSegments(vad, reader)
        }
        Log.d(TAG, "检测到 ${segments.size} 个语音段")

        return segments
    }

    /**
     * 初始化 VAD
     */
    private fun initVad(): Vad? {
        return try {
            val settingsManager = SettingsManager.getInstance(context)
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = settingsManager.getVadThreshold(),
                    minSilenceDuration = settingsManager.getVadMinSilenceDuration(),
                    minSpeechDuration = settingsManager.getVadMinSpeechDuration(),
                    windowSize = 512,
                    maxSpeechDuration = settingsManager.getVadMaxSpeechDuration()
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 2,
                provider = "cpu",
                debug = true
            )
            val vad = Vad(assetManager = context.assets, config = vadConfig)
            Log.d(TAG, "VAD 初始化成功")
            vad
        } catch (e: Exception) {
            Log.e(TAG, "VAD 初始化失败", e)
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

                    segments.add(VadSegment(startTime, endTime))
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

                segments.add(VadSegment(startTime, endTime))
                Log.d(TAG, "flush 后检测到语音段: ${startTime}ms - ${endTime}ms")
            }

            vad.reset()

        } catch (e: Exception) {
            Log.e(TAG, "语音段检测失败", e)
        }

        return segments
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
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    /**
     * VAD 语音段
     */
    data class VadSegment(
        val startTime: Long,
        val endTime: Long
    )
}
