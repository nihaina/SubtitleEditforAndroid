package com.subtitleedit.audio

import android.util.Log
import kotlinx.coroutines.*

/**
 * 基于 FFmpeg 缓存的波形 Chunk 加载器
 * 
 * 工作流程：
 * 1. 首次加载音频时，检查是否存在 .wave 缓存文件
 * 2. 如果缓存不存在或无效，使用 FfmpegWaveformExtractor 生成缓存（后台线程）
 * 3. 从缓存文件快速读取请求的 chunk 数据
 * 4. 将 Short 振幅转换为归一化的 Float 数组返回给 View
 * 
 * 性能特点：
 * - 1 小时音频 ≈ 30 万帧 ≈ 1.2MB 缓存
 * - 随机读取任意 chunk 只需几毫秒
 * - 支持边生成边读取（生成完成后一次性读取）
 * 
 * 职责说明：
 * - 本类仅负责：调度、分块请求和协程管理
 * - 缓存生成和读取委托给 FfmpegWaveformExtractor 处理
 */
class FfmpegWaveformChunkLoader(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "FfmpegWaveformChunkLoader"
    }

    // ==================== 状态 ====================

    private var audioFilePath: String? = null
    private var cacheFile: java.io.File? = null
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
     * @param cacheDir 缓存目录；null 表示与音频文件同目录（旧行为）
     */
    fun prepare(filePath: String, durationMs: Long, cacheDir: java.io.File? = null) {
        this.audioFilePath = filePath
        this.durationMs = durationMs
        
        val audioFile = java.io.File(filePath)
        val targetDir = if (cacheDir != null) {
            cacheDir.mkdirs()
            cacheDir
        } else {
            audioFile.parentFile ?: cacheDir
        }
        this.cacheFile = java.io.File(targetDir, "${audioFile.nameWithoutExtension}.wave")
        
        this.isCacheReady = false
        this.isGeneratingCache = false
        
        // 检查缓存是否有效
        if (cacheFile?.exists() == true) {
            val header = FfmpegWaveformExtractor.readCacheHeader(cacheFile!!)
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
                val audioFile = java.io.File(audioFilePath!!)
                val cache = cacheFile!!
                
                Log.d(TAG, "开始生成波形缓存：${audioFile.absolutePath}")
                
                // 委托给 FfmpegWaveformExtractor 生成缓存
                val success = FfmpegWaveformExtractor.generateWaveformCache(audioFile, cache)
                
                if (!success) {
                    Log.e(TAG, "缓存生成失败")
                    withContext(Dispatchers.Main) {
                        onComplete(false)
                    }
                    return@launch
                }
                
                // 更新状态
                totalFrames = FfmpegWaveformExtractor.getFrameCount(cache)
                isCacheReady = true
                isGeneratingCache = false
                
                Log.d(TAG, "波形缓存生成成功：frames=$totalFrames, size=${cache.length()} bytes")
                
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
                val startFrame = FfmpegWaveformExtractor.timeToFrameIndex(startMs)
                val endFrame = FfmpegWaveformExtractor.timeToFrameIndex(endMs) + 1
                val frameCount = endFrame - startFrame
                
                // 委托给 FfmpegWaveformExtractor 读取帧数据
                val frames = FfmpegWaveformExtractor.readWaveformFrames(cacheFile!!, startFrame, frameCount)
                
                // 转换为归一化的振幅数组（取 max 的绝对值）
                val amplitudes = FloatArray(frames.size) { i ->
                    FfmpegWaveformExtractor.normalizeAmplitude(frames[i].max)
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
