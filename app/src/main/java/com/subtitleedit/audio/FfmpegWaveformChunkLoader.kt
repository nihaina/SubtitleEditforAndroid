package com.subtitleedit.audio

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 按需生成和加载波形块。
 *
 * 每个块独立缓存。请求未命中时，通过 FFmpeg PCM 管道只解码对应时间范围，
 * 完成后立即返回给 View，不需要等待整段媒体生成完毕。
 */
class FfmpegWaveformChunkLoader(
    context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "FfmpegWaveformChunkLoader"
        private const val CHUNK_FILE_MARKER = ".chunk_"
        private const val MAX_CONCURRENT_GENERATIONS = 2
        private const val STALE_PART_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }

    private data class PendingRequest(
        val targetSamples: Int,
        val callback: (chunkIndex: Int, data: FloatArray) -> Unit
    )

    private val appContext = context.applicationContext
    private val stateLock = Any()
    private val generationSemaphore = Semaphore(MAX_CONCURRENT_GENERATIONS)
    private val pendingRequests = mutableMapOf<Int, MutableList<PendingRequest>>()
    private val generationJobs = mutableMapOf<Int, Job>()

    private var audioFile: File? = null
    private var cacheDirectory: File? = null
    private var legacyCacheFile: File? = null
    private var cacheFilePrefix: String? = null
    private var durationMs: Long = 0L
    private var audioStreamIndex: Int? = null
    private var totalFrames: Int = 0
    private var generationEnabled = false
    private var generationVersion = 0L

    /**
     * @param cacheDir 缓存根目录；null 表示媒体文件所在目录
     */
    fun prepare(
        filePath: String,
        durationMs: Long,
        cacheDir: File? = null,
        audioStreamIndex: Int? = null,
        audioCacheKey: String
    ) {
        cancelActiveJobs()

        val mediaFile = File(filePath)
        val cacheRootDir = cacheDir ?: mediaFile.parentFile
            ?: throw IllegalArgumentException("Audio file must have a parent directory")
        if (!cacheRootDir.exists() && !cacheRootDir.mkdirs()) {
            throw IllegalStateException("Unable to create waveform cache root: ${cacheRootDir.absolutePath}")
        }

        val mediaCacheDir = File(cacheRootDir, audioCacheKey).apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException("Unable to create waveform cache directory: $absolutePath")
            }
        }
        val streamSuffix = audioStreamIndex?.let { ".a$it" }.orEmpty()
        val prefix = "${mediaFile.nameWithoutExtension}$streamSuffix"

        this.audioFile = mediaFile
        this.cacheDirectory = mediaCacheDir
        this.legacyCacheFile = File(mediaCacheDir, "$prefix.wave")
        this.cacheFilePrefix = prefix
        this.durationMs = durationMs
        this.audioStreamIndex = audioStreamIndex
        this.totalFrames = estimateFrameCount(durationMs)

        cleanupStalePartFiles(mediaCacheDir, prefix)

        val hasLegacyCache = legacyCacheFile?.let(FfmpegWaveformExtractor::isWaveformCacheValid) == true
        val hasChunkCache = mediaCacheDir.listFiles()?.any { file ->
            isChunkCacheFile(file, prefix) && FfmpegWaveformExtractor.isWaveformCacheValid(file)
        } == true
        generationEnabled = hasLegacyCache || hasChunkCache

        Log.d(
            TAG,
            "波形分块加载器已准备：dir=${mediaCacheDir.absolutePath}, " +
                "legacy=$hasLegacyCache, chunks=$hasChunkCache"
        )
    }

    /** 启用按需生成。实际生成由 View 的 chunk 请求触发。 */
    fun generateCache(onComplete: (success: Boolean) -> Unit) {
        if (audioFile == null || cacheDirectory == null || cacheFilePrefix == null) {
            onComplete(false)
            return
        }
        generationEnabled = true
        onComplete(true)
    }

    fun requestChunk(
        chunkIndex: Int,
        startMs: Long,
        endMs: Long,
        targetSamples: Int,
        callback: (chunkIndex: Int, data: FloatArray) -> Unit
    ) {
        if (!generationEnabled || chunkIndex < 0 || endMs <= startMs) {
            callback(chunkIndex, FloatArray(0))
            return
        }

        val request = PendingRequest(targetSamples.coerceAtLeast(1), callback)
        synchronized(stateLock) {
            pendingRequests.getOrPut(chunkIndex) { mutableListOf() }.add(request)
            if (generationJobs.containsKey(chunkIndex)) {
                return
            }

            val requestVersion = generationVersion
            val job = scope.launch(
                context = Dispatchers.IO,
                start = CoroutineStart.LAZY
            ) {
                processChunk(chunkIndex, startMs, endMs, requestVersion)
            }
            generationJobs[chunkIndex] = job
            job.start()
        }
    }

    fun getTotalFrames(): Int = totalFrames

    fun isCacheReady(): Boolean = generationEnabled

    fun isGeneratingCache(): Boolean = synchronized(stateLock) {
        generationJobs.isNotEmpty()
    }

    fun release() {
        cancelActiveJobs()
        audioFile = null
        cacheDirectory = null
        legacyCacheFile = null
        cacheFilePrefix = null
        durationMs = 0L
        audioStreamIndex = null
        totalFrames = 0
        generationEnabled = false
    }

    private suspend fun processChunk(
        chunkIndex: Int,
        startMs: Long,
        endMs: Long,
        requestVersion: Long
    ) {
        var frames: List<FfmpegWaveformExtractor.WaveFrame> = emptyList()
        try {
            frames = readLegacyChunk(startMs, endMs).takeIf { it.isNotEmpty() }
                ?: readOrGenerateChunk(chunkIndex, startMs, endMs)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "波形块处理失败：chunk=$chunkIndex", error)
        } finally {
            val callbacks = synchronized(stateLock) {
                if (requestVersion != generationVersion) {
                    emptyList()
                } else {
                    generationJobs.remove(chunkIndex)
                    pendingRequests.remove(chunkIndex).orEmpty()
                }
            }

            if (callbacks.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    callbacks.forEach { request ->
                        val data = framesToAmplitudes(frames, request.targetSamples)
                        request.callback(chunkIndex, data)
                    }
                }
            }
        }
    }

    private fun readLegacyChunk(
        startMs: Long,
        endMs: Long
    ): List<FfmpegWaveformExtractor.WaveFrame> {
        val cache = legacyCacheFile ?: return emptyList()
        if (!FfmpegWaveformExtractor.isWaveformCacheValid(cache)) return emptyList()

        val startFrame = FfmpegWaveformExtractor.timeToFrameIndex(startMs)
        val endFrame = FfmpegWaveformExtractor.timeToFrameIndex(endMs) + 1
        return FfmpegWaveformExtractor.readWaveformFrames(
            cache,
            startFrame,
            (endFrame - startFrame).coerceAtLeast(0)
        )
    }

    private suspend fun readOrGenerateChunk(
        chunkIndex: Int,
        startMs: Long,
        endMs: Long
    ): List<FfmpegWaveformExtractor.WaveFrame> {
        val chunkFile = chunkCacheFile(chunkIndex) ?: return emptyList()
        if (!FfmpegWaveformExtractor.isWaveformCacheValid(chunkFile)) {
            val mediaFile = audioFile ?: return emptyList()
            val generated = generationSemaphore.withPermit {
                if (FfmpegWaveformExtractor.isWaveformCacheValid(chunkFile)) {
                    true
                } else {
                    FfmpegWaveformExtractor.generateWaveformChunk(
                        context = appContext,
                        audioFile = mediaFile,
                        outputFile = chunkFile,
                        startMs = startMs,
                        endMs = endMs,
                        audioStreamIndex = audioStreamIndex
                    )
                }
            }
            if (!generated) return emptyList()
        }

        val frameCount = FfmpegWaveformExtractor.getFrameCount(chunkFile)
        return FfmpegWaveformExtractor.readWaveformFrames(chunkFile, 0, frameCount)
    }

    private fun chunkCacheFile(chunkIndex: Int): File? {
        val directory = cacheDirectory ?: return null
        val prefix = cacheFilePrefix ?: return null
        return File(directory, "$prefix$CHUNK_FILE_MARKER${chunkIndex.toString().padStart(6, '0')}.wave")
    }

    private fun framesToAmplitudes(
        frames: List<FfmpegWaveformExtractor.WaveFrame>,
        targetSamples: Int
    ): FloatArray {
        if (frames.isEmpty()) return FloatArray(0)

        val amplitudes = FloatArray(frames.size * 2) { index ->
            val frame = frames[index / 2]
            if (index % 2 == 0) {
                FfmpegWaveformExtractor.normalizeAmplitude(frame.max)
            } else {
                FfmpegWaveformExtractor.normalizeAmplitude(frame.min)
            }
        }
        return if (frames.size > targetSamples) {
            downsamplePairs(amplitudes, targetSamples)
        } else {
            amplitudes
        }
    }

    private fun downsamplePairs(source: FloatArray, targetFrames: Int): FloatArray {
        val sourceFrames = source.size / 2
        if (sourceFrames <= targetFrames) return source

        val result = FloatArray(targetFrames * 2)
        val step = sourceFrames.toFloat() / targetFrames
        for (index in 0 until targetFrames) {
            val from = (index * step).toInt()
            val to = ((index + 1) * step).toInt().coerceIn(from + 1, sourceFrames)
            var peakMax = 0f
            var peakMin = 0f
            for (sourceIndex in from until to) {
                val max = source[sourceIndex * 2]
                val min = source[sourceIndex * 2 + 1]
                if (max > peakMax) peakMax = max
                if (min > peakMin) peakMin = min
            }
            result[index * 2] = peakMax
            result[index * 2 + 1] = peakMin
        }
        return result
    }

    private fun estimateFrameCount(mediaDurationMs: Long): Int {
        val sampleCount = mediaDurationMs.coerceAtLeast(0L) * FfmpegWaveformExtractor.SAMPLE_RATE / 1000L
        val frameCount = (sampleCount + FfmpegWaveformExtractor.SAMPLES_PER_FRAME - 1L) /
            FfmpegWaveformExtractor.SAMPLES_PER_FRAME
        return frameCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun isChunkCacheFile(file: File, prefix: String): Boolean {
        return file.isFile &&
            file.name.startsWith(prefix + CHUNK_FILE_MARKER) &&
            file.extension == "wave"
    }

    private fun cleanupStalePartFiles(directory: File, prefix: String) {
        val cutoff = System.currentTimeMillis() - STALE_PART_MAX_AGE_MS
        directory.listFiles()?.forEach { file ->
            if (file.isFile &&
                file.name.startsWith(prefix) &&
                file.extension == "part" &&
                file.lastModified() < cutoff &&
                !file.delete()
            ) {
                Log.w(TAG, "无法删除过期波形临时文件：${file.absolutePath}")
            }
        }
    }

    private fun cancelActiveJobs() {
        val jobs = synchronized(stateLock) {
            generationVersion++
            val activeJobs = generationJobs.values.toList()
            generationJobs.clear()
            pendingRequests.clear()
            activeJobs
        }
        jobs.forEach { it.cancel() }
    }
}
