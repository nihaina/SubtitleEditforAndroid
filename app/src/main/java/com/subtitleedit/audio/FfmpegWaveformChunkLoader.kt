package com.subtitleedit.audio

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import kotlinx.coroutines.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * 基于 FFmpeg 缓存的波形 Chunk 加载器
 * 
 * 工作流程：
 * 1. 首次加载音频时，检查是否存在 .wave 缓存文件
 * 2. 如果缓存不存在或无效，使用 FFmpeg 生成缓存（后台线程）
 * 3. 从缓存文件快速读取请求的 chunk 数据
 * 4. 将 Short 振幅转换为归一化的 Float 数组返回给 View
 * 
 * 性能特点：
 * - 1 小时音频 ≈ 30 万帧 ≈ 1.2MB 缓存
 * - 随机读取任意 chunk 只需几毫秒
 * - 支持边生成边读取（生成完成后一次性读取）
 */
class FfmpegWaveformChunkLoader(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "FfmpegWaveformChunkLoader"
        
        /** 每帧的采样点数（与 FfmpegWaveformExtractor 保持一致）*/
        private const val SAMPLES_PER_FRAME = 512
        
        /** PCM 采样率 */
        private const val SAMPLE_RATE = 44100
        
        /** 每个 chunk 包含的帧数（根据 chunk 时长动态计算）*/
        private const val FRAMES_PER_SECOND = SAMPLE_RATE / SAMPLES_PER_FRAME  // ≈ 86 frames/sec
    }

    /** 波形帧数据结构（与 FfmpegWaveformExtractor 保持一致）*/
    data class WaveFrame(
        val min: Short,
        val max: Short
    )

    // ==================== 状态 ====================

    private var audioFilePath: String? = null
    private var cacheFile: File? = null
    private var durationMs: Long = 0
    private var totalFrames: Int = 0
    private var isCacheReady: Boolean = false
    private var isGeneratingCache: Boolean = false

    // ==================== 公共 API ====================

    /**
     * 准备加载器，设置音频文件
     * 
     * @param filePath 音频文件路径
     * @param durationMs 音频时长（毫秒）
     */
    fun prepare(filePath: String, durationMs: Long) {
        this.audioFilePath = filePath
        this.durationMs = durationMs
        this.cacheFile = File(filePath).let { audioFile ->
            File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.wave")
        }
        this.isCacheReady = false
        this.isGeneratingCache = false
        
        // 检查缓存是否有效
        if (cacheFile?.exists() == true) {
            val header = readCacheHeader(cacheFile!!)
            if (header != null && header.frameCount > 0) {
                this.totalFrames = header.frameCount
                this.isCacheReady = true
                Log.d(TAG, "使用现有缓存：${cacheFile?.absolutePath}, frames=$totalFrames")
                return
            }
        }
        
        // 缓存无效，需要生成
        Log.d(TAG, "缓存不存在或无效，需要生成新缓存")
    }

    /**
     * 请求生成波形缓存（在后台线程执行）
     * 
     * @param onComplete 完成回调（在主线程执行）
     */
    fun generateCache(onComplete: (success: Boolean) -> Unit) {
        if (isCacheReady) {
            onComplete(true)
            return
        }
        
        if (isGeneratingCache) {
            Log.w(TAG, "缓存正在生成中，忽略重复请求")
            return
        }
        
        isGeneratingCache = true
        
        scope.launch(Dispatchers.IO) {
            try {
                val audioFile = File(audioFilePath!!)
                val cache = cacheFile!!
                
                Log.d(TAG, "开始生成波形缓存：${audioFile.absolutePath}")
                
                // 1. 使用 FFmpeg 导出 PCM
                val pcmFile = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.pcm")
                val pcmSuccess = exportPcm(audioFile.absolutePath, pcmFile.absolutePath)
                
                if (!pcmSuccess || !pcmFile.exists()) {
                    Log.e(TAG, "PCM 导出失败")
                    withContext(Dispatchers.Main) {
                        onComplete(false)
                    }
                    return@launch
                }
                
                // 2. 从 PCM 构建波形数据
                val frames = buildWaveform(pcmFile)
                
                // 3. 保存波形缓存
                saveWaveformCache(frames, cache)
                
                // 4. 删除临时 PCM 文件
                pcmFile.delete()
                
                // 5. 更新状态
                totalFrames = frames.size
                isCacheReady = true
                isGeneratingCache = false
                
                Log.d(TAG, "波形缓存生成成功：frames=${frames.size}, size=${cache.length()} bytes")
                
                withContext(Dispatchers.Main) {
                    onComplete(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "生成波形缓存失败", e)
                isGeneratingCache = false
                withContext(Dispatchers.Main) {
                    onComplete(false)
                }
            }
        }
    }

    /**
     * 请求加载指定时间范围的 chunk 数据
     * 
     * @param chunkIndex chunk 索引
     * @param startMs 起始时间（毫秒）
     * @param endMs 结束时间（毫秒）
     * @param targetSamples 目标采样点数（由 View 根据缩放级别决定）
     * @param callback 加载完成回调
     */
    fun requestChunk(
        chunkIndex: Int,
        startMs: Long,
        endMs: Long,
        targetSamples: Int,
        callback: (chunkIndex: Int, data: FloatArray) -> Unit
    ) {
        if (!isCacheReady) {
            Log.w(TAG, "缓存未就绪，返回空数据")
            callback(chunkIndex, FloatArray(0))
            return
        }
        
        scope.launch(Dispatchers.IO) {
            try {
                // 计算对应的帧范围
                val startFrame = timeToFrameIndex(startMs)
                val endFrame = timeToFrameIndex(endMs) + 1
                val frameCount = endFrame - startFrame
                
                // 从缓存读取帧数据
                val frames = readWaveformFrames(cacheFile!!, startFrame, frameCount)
                
                // 转换为归一化的振幅数组（取 max 的绝对值）
                val amplitudes = FloatArray(frames.size) { i ->
                    normalizeAmplitude(frames[i].max)
                }
                
                // 降采样到目标点数
                val result = if (amplitudes.size > targetSamples) {
                    downsample(amplitudes, targetSamples)
                } else {
                    amplitudes
                }
                
                withContext(Dispatchers.Main) {
                    callback(chunkIndex, result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "读取 chunk 失败：$chunkIndex", e)
                withContext(Dispatchers.Main) {
                    callback(chunkIndex, FloatArray(0))
                }
            }
        }
    }

    /**
     * 获取总帧数
     */
    fun getTotalFrames(): Int = totalFrames

    /**
     * 检查缓存是否就绪
     */
    fun isCacheReady(): Boolean = isCacheReady

    /**
     * 检查是否正在生成缓存
     */
    fun isGeneratingCache(): Boolean = isGeneratingCache

    /**
     * 释放资源
     */
    fun release() {
        audioFilePath = null
        cacheFile = null
        durationMs = 0
        totalFrames = 0
        isCacheReady = false
        isGeneratingCache = false
    }

    // ==================== 内部实现 ====================

    /**
     * 使用 FFmpeg 将音频导出为 16bit PCM（单声道，44100Hz）
     */
    private fun exportPcm(inputPath: String, outputPath: String): Boolean {
        val cmd = "-y -i \"$inputPath\" -f s16le -ac 1 -ar $SAMPLE_RATE \"$outputPath\""
        
        Log.d(TAG, "执行 FFmpeg 命令：$cmd")
        
        val session = FFmpegKit.execute(cmd)
        val returnCode = session.returnCode
        
        // 使用 ReturnCode 的 isValueSuccess 方法判断
        return if (returnCode.isValueSuccess) {
            Log.d(TAG, "PCM 导出成功")
            true
        } else {
            Log.e(TAG, "PCM 导出失败：returnCode=$returnCode")
            false
        }
    }

    /**
     * 从 PCM 文件构建波形数据
     */
    private fun buildWaveform(pcmFile: File): List<WaveFrame> {
        val frames = ArrayList<WaveFrame>()
        val bufferSize = 8192
        val buffer = ByteArray(bufferSize)
        val samples = ShortArray(bufferSize / 2)
        
        FileInputStream(pcmFile).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                
                val shortBuffer = ByteBuffer
                    .wrap(buffer, 0, read)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                
                val sampleCount = shortBuffer.limit()
                shortBuffer.get(samples, 0, sampleCount)
                
                var i = 0
                while (i < sampleCount) {
                    val end = min(i + SAMPLES_PER_FRAME, sampleCount)
                    
                    var minVal = Short.MAX_VALUE
                    var maxVal = Short.MIN_VALUE
                    
                    for (j in i until end) {
                        val s = samples[j]
                        if (s < minVal) minVal = s
                        if (s > maxVal) maxVal = s
                    }
                    
                    frames.add(WaveFrame(minVal, maxVal))
                    i += SAMPLES_PER_FRAME
                }
            }
        }
        
        return frames
    }

    /**
     * 保存波形缓存
     */
    private fun saveWaveformCache(frames: List<WaveFrame>, outputFile: File) {
        DataOutputStream(outputFile.outputStream()).use { out ->
            out.writeInt(frames.size)
            frames.forEach { frame ->
                out.writeShort(frame.min.toInt())
                out.writeShort(frame.max.toInt())
            }
        }
    }

    /**
     * 读取缓存文件头
     */
    private fun readCacheHeader(cacheFile: File): WaveformHeader? {
        return try {
            if (!cacheFile.exists() || cacheFile.length() < 4) {
                return null
            }
            
            RandomAccessFile(cacheFile, "r").use { raf ->
                val frameCount = raf.readInt()
                WaveformHeader(frameCount)
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取缓存头失败", e)
            null
        }
    }

    /**
     * 波形缓存文件头
     */
    private data class WaveformHeader(
        val frameCount: Int
    )

    /**
     * 从缓存文件读取指定范围的波形帧
     */
    private fun readWaveformFrames(cacheFile: File, startIndex: Int, count: Int): List<WaveFrame> {
        val frames = ArrayList<WaveFrame>(count)
        
        if (!cacheFile.exists()) {
            return frames
        }
        
        RandomAccessFile(cacheFile, "r").use { raf ->
            val headerSize = 4L
            val offset = headerSize + startIndex * 4L
            raf.seek(offset)
            
            for (i in 0 until count) {
                if (raf.filePointer >= raf.length()) break
                
                val min = raf.readShort()
                val max = raf.readShort()
                frames.add(WaveFrame(min, max))
            }
        }
        
        return frames
    }

    /**
     * 将振幅值（Short）转换为归一化的 Float 值（0.0 - 1.0）
     */
    private fun normalizeAmplitude(value: Short): Float {
        return kotlin.math.abs(value.toFloat()) / 32768f
    }

    /**
     * 根据时间（毫秒）获取对应的帧索引
     */
    private fun timeToFrameIndex(timeMs: Long): Int {
        val sample = (timeMs * SAMPLE_RATE / 1000L).toInt()
        return sample / SAMPLES_PER_FRAME
    }

    /**
     * 降采样波形数据
     */
    private fun downsample(source: FloatArray, targetSize: Int): FloatArray {
        if (source.isEmpty()) return FloatArray(0)
        if (source.size <= targetSize) return source
        
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
