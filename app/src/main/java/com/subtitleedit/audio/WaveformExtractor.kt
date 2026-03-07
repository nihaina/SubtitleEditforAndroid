package com.subtitleedit.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * 音频波形提取器
 * 从音频文件中提取 PCM 数据并生成波形振幅数组
 */
object WaveformExtractor {

    /**
     * 从音频文件提取波形数据
     * @param path 音频文件路径
     * @param samplesPerSecond 每秒采样点数（默认 100，即每 10ms 一个点）
     * @return 振幅数组，每个值在 0-1 之间
     */
    fun extractWaveform(path: String, samplesPerSecond: Int = 100): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)

        var audioTrackIndex = -1

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                break
            }
        }

        if (audioTrackIndex < 0) {
            extractor.release()
            return floatArrayOf()
        }

        extractor.selectTrack(audioTrackIndex)

        val format = extractor.getTrackFormat(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()

        // 原始 PCM 振幅列表（每个音频帧一个振幅）
        val rawAmplitudes = mutableListOf<Float>()

        var isEOS = false

        while (!isEOS) {
            val inputBufferId = codec.dequeueInputBuffer(10000)

            if (inputBufferId >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferId)!!
                val sampleSize = extractor.readSampleData(inputBuffer, 0)

                if (sampleSize < 0) {
                    codec.queueInputBuffer(
                        inputBufferId,
                        0,
                        0,
                        0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    isEOS = true
                } else {
                    val presentationTimeUs = extractor.sampleTime

                    codec.queueInputBuffer(
                        inputBufferId,
                        0,
                        sampleSize,
                        presentationTimeUs,
                        0
                    )

                    extractor.advance()
                }
            }

            val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10000)

            if (outputBufferId >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputBufferId)!!
                val chunk = ByteArray(bufferInfo.size)

                outputBuffer.get(chunk)
                outputBuffer.clear()

                var maxAmp = 0f

                // 解析 16-bit PCM 数据
                for (i in chunk.indices step 2) {
                    if (i + 1 < chunk.size) {
                        val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xff)).toShort()
                        val amp = abs(sample / 32768f)
                        maxAmp = max(maxAmp, amp)
                    }
                }

                rawAmplitudes.add(maxAmp)

                codec.releaseOutputBuffer(outputBufferId, false)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        // 降采样到目标采样率
        return downsampleWaveform(rawAmplitudes.toFloatArray(), samplesPerSecond)
    }

    /**
     * 降采样波形数据
     * @param rawAmplitudes 原始振幅数组
     * @param samplesPerSecond 目标每秒采样点数
     * @return 降采样后的振幅数组
     */
    private fun downsampleWaveform(rawAmplitudes: FloatArray, samplesPerSecond: Int): FloatArray {
        if (rawAmplitudes.isEmpty()) return floatArrayOf()

        // 假设原始采样率约为 44100Hz，计算原始数据代表的时长（秒）
        // 每个原始振幅代表约 1024 个音频样本（典型编解码器帧大小）
        val rawSampleRate = 44100f / 1024f  // 约 43 帧/秒
        val durationSeconds = rawAmplitudes.size / rawSampleRate

        // 计算目标采样点数量
        val targetSamples = (durationSeconds * samplesPerSecond).toInt().coerceAtLeast(100)

        // 计算降采样比例
        val ratio = rawAmplitudes.size.toFloat() / targetSamples

        val result = FloatArray(targetSamples)

        for (i in 0 until targetSamples) {
            val startIdx = (i * ratio).toInt()
            val endIdx = ((i + 1) * ratio).toInt().coerceAtMost(rawAmplitudes.size)

            // 计算该范围内的最大振幅
            var maxAmp = 0f
            for (j in startIdx until endIdx) {
                if (j < rawAmplitudes.size) {
                    maxAmp = maxOf(maxAmp, rawAmplitudes[j])
                }
            }
            result[i] = maxAmp
        }

        return result
    }
}
