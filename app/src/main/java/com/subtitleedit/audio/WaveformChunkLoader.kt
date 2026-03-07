package com.subtitleedit.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue

/**
 * 波形 chunk 异步加载调度器
 *
 * 功能：
 * 1. 接收来自 WaveformTimelineView 的 chunk 加载请求
 * 2. 按优先级调度（可见区域 > 预加载区域）
 * 3. 使用 MediaExtractor + MediaCodec 解码音频 PCM
 * 4. 将振幅数组回调给 View
 *
 * 使用方式（在 Activity / ViewModel 中）：
 *
 *   private val chunkLoader = WaveformChunkLoader(lifecycleScope)
 *
 *   // 设置音频文件
 *   chunkLoader.prepare(filePath, durationMs)
 *
 *   // 连接到 View
 *   waveformView.onChunkLoadRequest = { idx, startMs, endMs, samples ->
 *       chunkLoader.requestChunk(idx, startMs, endMs, samples) { chunkIdx, data ->
 *           waveformView.post { waveformView.updateChunk(chunkIdx, data) }
 *       }
 *   }
 *
 *   // 销毁时释放
 *   chunkLoader.release()
 */
class WaveformChunkLoader(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "WaveformChunkLoader"

        /** 后台解码并发数（不宜过高，避免与 UI 线程争抢 CPU）*/
        private const val MAX_CONCURRENT_JOBS = 2
    }

    // ==================== 优先级任务队列 ====================

    private data class LoadTask(
        val chunkIndex: Int,
        val startMs: Long,
        val endMs: Long,
        val targetSamples: Int,
        val priority: Int,          // 数值越小优先级越高
        val callback: (chunkIndex: Int, data: FloatArray) -> Unit
    ) : Comparable<LoadTask> {
        override fun compareTo(other: LoadTask) = this.priority.compareTo(other.priority)
    }

    private val queue = PriorityBlockingQueue<LoadTask>()

    /** 正在处理中的 chunk 索引，避免重复解码 */
    private val inProgress = ConcurrentHashMap.newKeySet<Int>()

    private var filePath: String? = null
    private var durationMs: Long = 0

    private var workerJob: Job? = null

    // ==================== 公共 API ====================

    /**
     * 设置音频文件，重置所有状态
     */
    fun prepare(filePath: String, durationMs: Long) {
        this.filePath = filePath
        this.durationMs = durationMs
        queue.clear()
        inProgress.clear()
        startWorkers()
    }

    /**
     * 提交一个 chunk 加载请求。
     *
     * @param chunkIndex   chunk 索引
     * @param startMs      起始时间（毫秒）
     * @param endMs        结束时间（毫秒）
     * @param targetSamples 期望采样点数（由 View 根据当前缩放动态传入）
     * @param callback     加载完成回调（在主线程执行）
     */
    fun requestChunk(
        chunkIndex: Int,
        startMs: Long,
        endMs: Long,
        targetSamples: Int,
        callback: (chunkIndex: Int, data: FloatArray) -> Unit
    ) {
        if (inProgress.contains(chunkIndex)) return

        // 优先级：队列中靠前的任务先执行（由 View 保证可见区域先调用）
        val priority = queue.size
        queue.offer(LoadTask(chunkIndex, startMs, endMs, targetSamples, priority, callback))
    }

    /**
     * 释放资源，取消所有后台任务
     */
    fun release() {
        workerJob?.cancel()
        queue.clear()
        inProgress.clear()
    }

    // ==================== 内部实现 ====================

    private fun startWorkers() {
        workerJob?.cancel()
        workerJob = scope.launch(Dispatchers.IO) {
            // 启动 N 个并发 worker
            val workers = (1..MAX_CONCURRENT_JOBS).map {
                launch { workerLoop() }
            }
            workers.forEach { it.join() }
        }
    }

    private suspend fun workerLoop() {
        while (!workerJob?.isCancelled!!) {
            // 阻塞等待任务（带超时避免永久挂起）
            val task = withTimeoutOrNull(500L) {
                runInterruptible { queue.take() }
            } ?: continue

            if (inProgress.contains(task.chunkIndex)) continue
            inProgress.add(task.chunkIndex)

            try {
                val data = decodeChunk(task.startMs, task.endMs, task.targetSamples)
                // 回调切回主线程
                withContext(Dispatchers.Main) {
                    task.callback(task.chunkIndex, data)
                }
            } catch (e: Exception) {
                Log.e(TAG, "chunk ${task.chunkIndex} 解码失败", e)
                // 解码失败时提供空数据，避免 View 一直显示占位
                withContext(Dispatchers.Main) {
                    task.callback(task.chunkIndex, FloatArray(0))
                }
            } finally {
                inProgress.remove(task.chunkIndex)
            }
        }
    }

    /**
     * 使用 MediaExtractor + MediaCodec 解码指定时间段的音频，返回振幅数组。
     *
     * @param startMs       起始时间（毫秒）
     * @param endMs         结束时间（毫秒）
     * @param targetSamples 目标振幅点数（最终输出数组的长度）
     */
    private fun decodeChunk(startMs: Long, endMs: Long, targetSamples: Int): FloatArray {
        val path = filePath ?: return FloatArray(0)

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(path)

            // 找到音频轨道
            val audioTrack = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return FloatArray(0)

            extractor.selectTrack(audioTrack)
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            // Seek 到起始位置
            extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            // 初始化解码器
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // 收集 PCM 采样（短时浮点）
            val rawSamples = mutableListOf<Float>()
            val endUs = endMs * 1000L
            var inputDone = false
            var outputDone = false

            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 5000L

            while (!outputDone) {
                // 输入：喂给解码器
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0 || extractor.sampleTime > endUs + 500_000L) {
                            // 超出范围或文件结束
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                // 输出：读取解码后的 PCM
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outIdx >= 0 -> {
                        val outputBuf = codec.getOutputBuffer(outIdx)
                        val ptsUs = bufferInfo.presentationTimeUs

                        // 只收集目标时间段内的 PCM
                        if (outputBuf != null && ptsUs >= startMs * 1000L && ptsUs <= endUs) {
                            extractAmplitudes(outputBuf, bufferInfo.size, channelCount, rawSamples)
                        }

                        codec.releaseOutputBuffer(outIdx, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* 忽略 */ }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 等待 */ }
                }
            }

            // 将原始 PCM 振幅降采样到目标点数
            return downsample(rawSamples, targetSamples)

        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    /**
     * 从 PCM ByteBuffer 中提取归一化振幅（16-bit PCM，取各声道平均绝对值）
     */
    private fun extractAmplitudes(
        buffer: ByteBuffer,
        size: Int,
        channelCount: Int,
        out: MutableList<Float>
    ) {
        buffer.rewind()
        // 每帧 = channelCount × 2 字节（16-bit PCM）
        val frameSize = channelCount * 2
        val frameCount = size / frameSize

        for (i in 0 until frameCount) {
            var sum = 0f
            for (ch in 0 until channelCount) {
                val lo = buffer.get().toInt() and 0xFF
                val hi = buffer.get().toInt()
                val sample = ((hi shl 8) or lo).toShort()
                sum += Math.abs(sample.toFloat())
            }
            // 归一化到 0..1
            out.add((sum / channelCount) / 32768f)
        }
    }

    /**
     * 将原始振幅列表降采样到目标点数，每点取区间最大值（保留峰值）
     */
    private fun downsample(source: List<Float>, targetSize: Int): FloatArray {
        if (source.isEmpty()) return FloatArray(0)
        if (source.size <= targetSize) return source.toFloatArray()

        val result = FloatArray(targetSize)
        val step = source.size.toFloat() / targetSize

        for (i in 0 until targetSize) {
            val from = (i * step).toInt()
            val to = ((i + 1) * step).toInt().coerceAtMost(source.size)
            var max = 0f
            for (j in from until to) {
                if (source[j] > max) max = source[j]
            }
            result[i] = max
        }
        return result
    }
}
