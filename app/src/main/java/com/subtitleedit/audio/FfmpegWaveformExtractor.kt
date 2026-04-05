package com.subtitleedit.audio

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File
import java.io.FileInputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * 基于 FFmpeg 的波形数据提取器
 * 
 * 流程：
 * 1. 使用 FFmpeg 将音频解码为 16bit PCM（单声道，44100Hz）
 * 2. 读取 PCM 数据，每 128 个采样点计算一个 WaveFrame（包含 min/max 振幅）
 * 3. 将 WaveFrame 数组保存为 .wave 缓存文件
 * 4. 提供快速读取缓存的接口
 * 
 * 缓存格式：
 * - 4 bytes: frameCount (Int)
 * - N * 4 bytes: [min: Short][max: Short]
 */
object FfmpegWaveformExtractor {

    private const val TAG = "FfmpegWaveformExtractor"
    
    /** 每帧的采样点数（128 samples @ 44100Hz ≈ 2.9ms，对应约 345 帧/秒）*/
    const val SAMPLES_PER_FRAME = 128
    
    /** PCM 采样率 */
    const val SAMPLE_RATE = 44100
    
    /** PCM 位深（16bit）*/
    private const val BITS_PER_SAMPLE = 2  // 2 bytes = 16 bits

    /**
     * 波形帧数据结构
     */
    data class WaveFrame(
        val min: Short,
        val max: Short
    )

    /**
     * 波形缓存文件头
     */
    data class WaveformHeader(
        val frameCount: Int,
        val sampleRate: Int = SAMPLE_RATE,
        val samplesPerFrame: Int = SAMPLES_PER_FRAME
    )

    /**
     * 从音频文件生成波形缓存
     * 
     * @param audioFile 音频文件
     * @param cacheFile 缓存文件（.wave）
     * @return 是否成功
     */
    fun generateWaveformCache(audioFile: File, cacheFile: File): Boolean {
        return try {
            // 1. 使用 FFmpeg 导出 PCM
            val pcmFile = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.pcm")
            exportPcm(audioFile.absolutePath, pcmFile.absolutePath)
            
            if (!pcmFile.exists()) {
                Log.e(TAG, "PCM 文件生成失败")
                return false
            }
            
            // 2. 从 PCM 构建波形数据
            val frames = buildWaveform(pcmFile)
            
            // 3. 保存波形缓存
            saveWaveformCache(frames, cacheFile)
            
            // 4. 删除临时 PCM 文件
            pcmFile.delete()
            
            Log.d(TAG, "波形缓存生成成功：${cacheFile.absolutePath}, frames=${frames.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "生成波形缓存失败", e)
            false
        }
    }

    /**
     * 使用 FFmpeg 将音频导出为 16bit PCM（单声道，44100Hz）
     * 
     * FFmpeg 命令：
     * ffmpeg -y -i input.mp3 -f s16le -ac 1 -ar 44100 output.pcm
     * 
     * 参数说明：
     * - -y: 覆盖输出文件
     * - -f s16le: 输出格式为 16bit 小端 PCM
     * - -ac 1: 单声道
     * - -ar 44100: 采样率 44100Hz
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
     * 
     * PCM 格式：16bit 小端，单声道，44100Hz
     * 每 2 字节 = 一个 sample（Short 值，范围 -32768 ~ 32767）
     * 每 128 samples = 一个 WaveFrame
     */
    private fun buildWaveform(pcmFile: File): List<WaveFrame> {
        val frames = ArrayList<WaveFrame>()
        val bufferSize = 4096  // 每次读取 4KB
        val buffer = ByteArray(bufferSize)
        val samples = ShortArray(bufferSize / 2)
        
        FileInputStream(pcmFile).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                
                // 将字节数组转换为 Short 数组（小端序）
                val shortBuffer = ByteBuffer
                    .wrap(buffer, 0, read)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                
                val sampleCount = shortBuffer.limit()
                shortBuffer.get(samples, 0, sampleCount)
                
                // 每 SAMPLES_PER_FRAME 个采样点计算一个 frame
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
     * 保存波形缓存到文件
     * 
     * 文件格式：
     * - 4 bytes: frameCount (Int)
     * - N * 4 bytes: [min: Short][max: Short]
     */
    private fun saveWaveformCache(frames: List<WaveFrame>, outputFile: File) {
        DataOutputStream(outputFile.outputStream()).use { out ->
            // 写入帧数量
            out.writeInt(frames.size)
            
            // 写入每个帧的 min/max 值
            frames.forEach { frame ->
                out.writeShort(frame.min.toInt())
                out.writeShort(frame.max.toInt())
            }
        }
        
        Log.d(TAG, "波形缓存已保存：${outputFile.absolutePath}, size=${outputFile.length()} bytes")
    }

    /**
     * 读取波形缓存文件头
     * 
     * @return 缓存头信息，如果文件无效则返回 null
     */
    fun readCacheHeader(cacheFile: File): WaveformHeader? {
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
     * 从缓存文件读取指定范围的波形帧
     * 
     * @param cacheFile 缓存文件
     * @param startIndex 起始帧索引
     * @param count 读取帧数量
     * @return 波形帧列表
     */
    fun readWaveformFrames(cacheFile: File, startIndex: Int, count: Int): List<WaveFrame> {
        val frames = ArrayList<WaveFrame>(count)
        
        if (!cacheFile.exists()) {
            return frames
        }
        
        RandomAccessFile(cacheFile, "r").use { raf ->
            // 跳过文件头（4 bytes）
            val headerSize = 4L
            
            // 计算起始偏移：header + startIndex * 4 (每个 frame 4 bytes)
            val offset = headerSize + startIndex * 4L
            raf.seek(offset)
            
            // 读取指定数量的帧
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
     * 读取单个波形帧
     * 
     * @param cacheFile 缓存文件
     * @param index 帧索引
     * @return 波形帧，如果索引无效则返回 null
     */
    fun readWaveformFrame(cacheFile: File, index: Int): WaveFrame? {
        if (!cacheFile.exists()) {
            return null
        }
        
        RandomAccessFile(cacheFile, "r").use { raf ->
            // 跳过文件头（4 bytes）+ 前面的帧
            val offset = 4L + index * 4L
            
            if (offset >= raf.length()) {
                return null
            }
            
            raf.seek(offset)
            val min = raf.readShort()
            val max = raf.readShort()
            return WaveFrame(min, max)
        }
    }

    /**
     * 获取缓存文件中的总帧数
     */
    fun getFrameCount(cacheFile: File): Int {
        return readCacheHeader(cacheFile)?.frameCount ?: 0
    }

    /**
     * 检查缓存文件是否有效
     */
    fun isCacheValid(cacheFile: File, audioFile: File): Boolean {
        if (!cacheFile.exists()) {
            return false
        }
        
        // 缓存文件应该比音频文件小很多（通常 < 2MB）
        if (cacheFile.length() > 10 * 1024 * 1024) {  // 超过 10MB 视为异常
            return false
        }
        
        // 检查文件头
        val header = readCacheHeader(cacheFile)
        return header != null && header.frameCount > 0
    }

    /**
     * 获取缓存文件路径（与音频文件同目录，同名.wave 扩展名）
     */
    fun getCachePath(audioFile: File): File {
        return File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.wave")
    }

    /**
     * 将振幅值（Short）转换为归一化的 Float 值（0.0 - 1.0）
     */
    fun normalizeAmplitude(value: Short): Float {
        return kotlin.math.abs(value.toFloat()) / 32768f
    }

    /**
     * 获取帧对应的时间范围（毫秒）
     * 
     * @param frameIndex 帧索引
     * @return Pair(startMs, endMs)
     */
    fun frameToTimeRange(frameIndex: Int): Pair<Long, Long> {
        val startSample = frameIndex * SAMPLES_PER_FRAME
        val endSample = (frameIndex + 1) * SAMPLES_PER_FRAME
        
        val startMs = (startSample * 1000L / SAMPLE_RATE)
        val endMs = (endSample * 1000L / SAMPLE_RATE)
        
        return Pair(startMs, endMs)
    }

    /**
     * 根据时间（毫秒）获取对应的帧索引
     */
    fun timeToFrameIndex(timeMs: Long): Int {
        val sample = (timeMs * SAMPLE_RATE / 1000L).toInt()
        return sample / SAMPLES_PER_FRAME
    }
}
