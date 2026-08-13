package com.subtitleedit.audio

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.util.FileHashUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 基于 FFmpeg 的波形数据提取器。
 *
 * FFmpeg 将 16-bit 单声道 PCM 写入命名管道，应用边读取边统计 WaveFrame，
 * 不在磁盘上创建完整 PCM 中间文件。
 *
 * 缓存格式：
 * - 4 bytes: frameCount (Int)
 * - N * 4 bytes: [min: Short][max: Short]
 */
object FfmpegWaveformExtractor {

    private const val TAG = "FfmpegWaveformExtractor"

    /** 每帧的采样点数（128 samples @ 44100Hz 约 2.9ms） */
    const val SAMPLES_PER_FRAME = 128

    /** PCM 采样率 */
    const val SAMPLE_RATE = 44100

    private const val PCM_READ_BUFFER_SIZE = 32 * 1024
    private const val PIPE_READER_SHUTDOWN_TIMEOUT_MS = 5_000L

    data class WaveFrame(
        val min: Short,
        val max: Short
    )

    data class WaveformHeader(
        val frameCount: Int,
        val sampleRate: Int = SAMPLE_RATE,
        val samplesPerFrame: Int = SAMPLES_PER_FRAME
    )

    private class PipeReaderState {
        var opened = false
    }

    /**
     * 生成一个指定时间范围的波形块。
     *
     * 输出先写入同目录的 .part 文件，只有 FFmpeg 和管道读取都成功后才原子发布。
     */
    suspend fun generateWaveformChunk(
        context: Context,
        audioFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        audioStreamIndex: Int? = null
    ): Boolean = coroutineScope {
        require(startMs >= 0L) { "startMs must be non-negative" }
        require(endMs > startMs) { "endMs must be greater than startMs" }

        val outputDir = outputFile.parentFile
            ?: throw IllegalArgumentException("Waveform cache must have a parent directory")
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IllegalStateException("Unable to create waveform cache directory: ${outputDir.absolutePath}")
        }

        val partFile = File(
            outputDir,
            "${outputFile.name}.${UUID.randomUUID()}.part"
        )
        val pipePath = FFmpegKitConfig.registerNewFFmpegPipe(context.applicationContext)
        if (pipePath.isNullOrBlank()) {
            Log.e(TAG, "创建 FFmpeg PCM 管道失败")
            return@coroutineScope false
        }

        val readerState = PipeReaderState()
        val sessionRef = AtomicReference<FFmpegSession?>()
        val sessionComplete = CompletableDeferred<FFmpegSession>()
        var published = false

        val reader = async(Dispatchers.IO) {
            FileInputStream(pipePath).use { input ->
                synchronized(readerState) {
                    readerState.opened = true
                }
                writeWaveformCache(input, partFile)
            }
        }
        reader.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                sessionRef.get()?.cancel()
            }
        }

        try {
            val arguments = buildChunkArguments(
                inputPath = audioFile.absolutePath,
                outputPath = pipePath,
                startMs = startMs,
                endMs = endMs,
                audioStreamIndex = audioStreamIndex
            )
            Log.d(TAG, "开始生成波形块：${FFmpegKitConfig.argumentsToString(arguments)}")

            val session = FFmpegKit.executeWithArgumentsAsync(arguments) { completedSession ->
                if (!ReturnCode.isSuccess(completedSession.getReturnCode())) {
                    unblockPipeReaderIfNeeded(pipePath, readerState)
                }
                sessionComplete.complete(completedSession)
            }
            sessionRef.set(session)
            if (reader.isCancelled) {
                session.cancel()
            }

            val completedSession = sessionComplete.await()
            val frameCount = runCatching { reader.await() }
                .onFailure { error ->
                    Log.e(TAG, "读取 PCM 管道失败", error)
                }
                .getOrElse { return@coroutineScope false }

            if (!ReturnCode.isSuccess(completedSession.getReturnCode())) {
                Log.e(
                    TAG,
                    "波形块 FFmpeg 生成失败：returnCode=${completedSession.getReturnCode()}, " +
                        "output=${completedSession.getOutput()}"
                )
                return@coroutineScope false
            }
            if (frameCount <= 0 || !isWaveformCacheValid(partFile)) {
                Log.e(TAG, "波形块为空或格式无效：${partFile.absolutePath}")
                return@coroutineScope false
            }

            publishAtomically(partFile, outputFile)
            published = true
            Log.d(
                TAG,
                "波形块生成成功：${outputFile.absolutePath}, frames=$frameCount, " +
                    "range=${startMs}..${endMs}ms"
            )
            true
        } catch (e: CancellationException) {
            sessionRef.get()?.cancel()
            throw e
        } catch (e: Exception) {
            sessionRef.get()?.cancel()
            Log.e(TAG, "生成波形块失败", e)
            false
        } finally {
            sessionRef.get()?.let { session ->
                if (session.getReturnCode() == null) {
                    session.cancel()
                }
            }
            unblockPipeReaderIfNeeded(pipePath, readerState)
            withContext(NonCancellable) {
                withTimeoutOrNull(PIPE_READER_SHUTDOWN_TIMEOUT_MS) {
                    reader.join()
                }
            }
            runCatching { FFmpegKitConfig.closeFFmpegPipe(pipePath) }
                .onFailure { Log.w(TAG, "关闭 FFmpeg PCM 管道失败：$pipePath", it) }
            if (!published && partFile.exists() && !partFile.delete()) {
                Log.w(TAG, "无法删除未完成的波形块：${partFile.absolutePath}")
            }
        }
    }

    private fun buildChunkArguments(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long,
        audioStreamIndex: Int?
    ): Array<String> {
        val arguments = mutableListOf(
            "-hide_banner",
            "-loglevel", "error",
            "-nostdin",
            "-y",
            "-ss", formatSeconds(startMs),
            "-i", inputPath
        )
        if (audioStreamIndex != null) {
            arguments += listOf("-map", "0:$audioStreamIndex")
        }
        arguments += listOf(
            "-t", formatSeconds(endMs - startMs),
            "-vn",
            "-sn",
            "-dn",
            "-f", "s16le",
            "-ac", "1",
            "-ar", SAMPLE_RATE.toString(),
            outputPath
        )
        return arguments.toTypedArray()
    }

    private fun formatSeconds(milliseconds: Long): String {
        val seconds = milliseconds / 1000L
        val remainder = milliseconds % 1000L
        return "$seconds.${remainder.toString().padStart(3, '0')}"
    }

    /**
     * 失败发生在 FFmpeg 打开输出端之前时，FIFO 读端会阻塞在 open()。
     * 短暂打开并关闭一个写端可让读端得到 EOF 并正常退出。
     */
    private fun unblockPipeReaderIfNeeded(pipePath: String, state: PipeReaderState) {
        synchronized(state) {
            if (state.opened) return
            runCatching {
                FileOutputStream(pipePath).use { }
            }.onFailure {
                Log.w(TAG, "解除 PCM 管道读端阻塞失败：$pipePath", it)
            }
        }
    }

    /**
     * 按固定缓冲区读取小端 PCM，并直接写入波形缓存，内存占用与媒体时长无关。
     */
    private fun writeWaveformCache(input: FileInputStream, outputFile: File): Int {
        val buffer = ByteArray(PCM_READ_BUFFER_SIZE)
        var trailingByte = -1
        var samplesInFrame = 0
        var minValue = Short.MAX_VALUE
        var maxValue = Short.MIN_VALUE
        var frameCount = 0

        RandomAccessFile(outputFile, "rw").use { output ->
            output.setLength(0L)
            output.writeInt(0)

            fun acceptSample(sample: Short) {
                if (sample < minValue) minValue = sample
                if (sample > maxValue) maxValue = sample
                samplesInFrame++

                if (samplesInFrame == SAMPLES_PER_FRAME) {
                    output.writeShort(minValue.toInt())
                    output.writeShort(maxValue.toInt())
                    frameCount++
                    samplesInFrame = 0
                    minValue = Short.MAX_VALUE
                    maxValue = Short.MIN_VALUE
                }
            }

            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue

                var offset = 0
                if (trailingByte >= 0) {
                    val sample = (trailingByte or (buffer[0].toInt() shl 8)).toShort()
                    acceptSample(sample)
                    trailingByte = -1
                    offset = 1
                }

                while (offset + 1 < read) {
                    val sample = (
                        (buffer[offset].toInt() and 0xff) or
                            (buffer[offset + 1].toInt() shl 8)
                        ).toShort()
                    acceptSample(sample)
                    offset += 2
                }

                if (offset < read) {
                    trailingByte = buffer[offset].toInt() and 0xff
                }
            }

            if (trailingByte >= 0) {
                throw IllegalStateException("PCM 管道包含不完整的 16-bit sample")
            }
            if (samplesInFrame > 0) {
                output.writeShort(minValue.toInt())
                output.writeShort(maxValue.toInt())
                frameCount++
            }

            output.seek(0L)
            output.writeInt(frameCount)
            output.fd.sync()
        }

        return frameCount
    }

    private fun publishAtomically(partFile: File, outputFile: File) {
        try {
            Files.move(
                partFile.toPath(),
                outputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                partFile.toPath(),
                outputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    fun readCacheHeader(cacheFile: File): WaveformHeader? {
        return try {
            if (!cacheFile.isFile || cacheFile.length() < 4L) {
                return null
            }

            RandomAccessFile(cacheFile, "r").use { raf ->
                WaveformHeader(raf.readInt())
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取缓存头失败", e)
            null
        }
    }

    fun readWaveformFrames(cacheFile: File, startIndex: Int, count: Int): List<WaveFrame> {
        val safeStartIndex = startIndex.coerceAtLeast(0)
        val safeCount = count.coerceAtLeast(0)
        val frames = ArrayList<WaveFrame>(safeCount)

        if (!cacheFile.isFile || safeCount == 0) {
            return frames
        }

        RandomAccessFile(cacheFile, "r").use { raf ->
            val offset = 4L + safeStartIndex * 4L
            if (offset >= raf.length()) return frames
            raf.seek(offset)

            for (index in 0 until safeCount) {
                if (raf.filePointer + 4L > raf.length()) break
                frames.add(WaveFrame(raf.readShort(), raf.readShort()))
            }
        }

        return frames
    }

    fun readWaveformFrame(cacheFile: File, index: Int): WaveFrame? {
        if (!cacheFile.isFile || index < 0) {
            return null
        }

        RandomAccessFile(cacheFile, "r").use { raf ->
            val offset = 4L + index * 4L
            if (offset + 4L > raf.length()) {
                return null
            }

            raf.seek(offset)
            return WaveFrame(raf.readShort(), raf.readShort())
        }
    }

    fun getFrameCount(cacheFile: File): Int {
        return readCacheHeader(cacheFile)?.frameCount ?: 0
    }

    fun isWaveformCacheValid(cacheFile: File): Boolean {
        val header = readCacheHeader(cacheFile) ?: return false
        if (header.frameCount <= 0) return false
        val expectedLength = 4L + header.frameCount.toLong() * 4L
        return cacheFile.length() == expectedLength
    }

    fun isCacheValid(cacheFile: File, @Suppress("UNUSED_PARAMETER") audioFile: File): Boolean {
        return isWaveformCacheValid(cacheFile)
    }

    fun getCachePath(audioFile: File): File {
        val parent = audioFile.parentFile
            ?: throw IllegalArgumentException("Audio file must have a parent directory")
        val cacheDir = File(parent, FileHashUtils.md5(audioFile)).apply { mkdirs() }
        return File(cacheDir, "${audioFile.nameWithoutExtension}.wave")
    }

    fun normalizeAmplitude(value: Short): Float {
        return kotlin.math.abs(value.toFloat()) / 32768f
    }

    fun frameToTimeRange(frameIndex: Int): Pair<Long, Long> {
        val startSample = frameIndex * SAMPLES_PER_FRAME
        val endSample = (frameIndex + 1) * SAMPLES_PER_FRAME
        return Pair(
            startSample * 1000L / SAMPLE_RATE,
            endSample * 1000L / SAMPLE_RATE
        )
    }

    fun timeToFrameIndex(timeMs: Long): Int {
        val sample = timeMs * SAMPLE_RATE / 1000L
        return (sample / SAMPLES_PER_FRAME).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
