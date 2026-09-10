package com.subtitleedit.demix

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import kotlin.math.min

/**
 * Standalone HTDemucs ONNX inference engine.
 *
 * The model contract follows demixr-app:
 * input `mix` [1, 2, 343980], output `stems` [1, 4, 2, 343980].
 * Audio is read from an interleaved stereo f32le file one segment at a time,
 * and finalized overlap regions are streamed directly to WAV files.
 */
class VocalSeparationEngine(
    private val modelPath: String,
    private val modelDisplayName: String,
    private val modelSize: Long? = null,
    private val threadCount: Int = 2,
    private val graphOptimizationEnabled: Boolean = false,
    private val cpuArenaEnabled: Boolean = false,
    private val log: (String) -> Unit = {}
) : VocalSeparationRunner {
    enum class Stem(val fileSuffix: String, val displayName: String, val modelIndex: Int) {
        VOCALS("vocals", "Vocals", 3),
        DRUMS("drums", "Drums", 0),
        BASS("bass", "Bass", 1),
        OTHER("other", "Other", 2)
    }

    data class Result(
        val outputFiles: Map<Stem, File>,
        val totalFrames: Long,
        val chunkCount: Int,
        val elapsedMs: Long
    )

    override fun separate(
        pcmFile: File,
        outputDir: File,
        outputBaseName: String,
        stems: Set<Stem>,
        isCancelled: () -> Boolean,
        onProgress: (completedChunks: Int, totalChunks: Int) -> Unit
    ): Result {
        require(modelPath.isNotBlank()) { "ONNX 模型路径为空" }
        require(pcmFile.isFile) { "PCM 文件不存在：${pcmFile.absolutePath}" }
        require(stems.isNotEmpty()) { "至少选择一个输出音轨" }
        outputDir.mkdirs()

        val totalFrames = pcmFile.length() / BYTES_PER_FRAME
        require(totalFrames > 0) { "FFmpeg 输出的 PCM 音频为空" }
        val chunkCount = ((totalFrames + STRIDE - 1L) / STRIDE).toInt().coerceAtLeast(1)
        val startedAt = System.currentTimeMillis()

        log("ONNX Runtime：创建独立 HTDemucs 会话")
        log("模型：$modelDisplayName${modelSize?.let { "，大小 ${formatBytes(it)}" }.orEmpty()}")
        log("输入张量：$INPUT_NAME [1, 2, $SEGMENT]")
        log("输出张量：$OUTPUT_NAME [1, 4, 2, $SEGMENT]")
        log("分块参数：44.1kHz / 双声道 / ${"%.2f".format(Locale.getDefault(), SEGMENT / SAMPLE_RATE.toDouble())}秒 / 25% 重叠")
        log(
            "ORT 线程：$threadCount；图优化：${enabledText(graphOptimizationEnabled)}；" +
                "CPU arena：${enabledText(cpuArenaEnabled)}"
        )

        val environment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "subtitleedit-demix")
        OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(
                if (graphOptimizationEnabled) {
                    OrtSession.SessionOptions.OptLevel.ALL_OPT
                } else {
                    OrtSession.SessionOptions.OptLevel.NO_OPT
                }
            )
            options.setMemoryPatternOptimization(false)
            options.setCPUArenaAllocator(cpuArenaEnabled)
            options.setIntraOpNumThreads(threadCount.coerceIn(1, 8))
            options.setInterOpNumThreads(1)

            log("正在加载 ONNX 模型，会话初始化可能需要较长时间")
            environment.createSession(modelPath, options).use { session ->
                validateModel(session)
                log("模型会话初始化完成，开始处理 $chunkCount 个分块")
                return runChunks(
                    environment,
                    session,
                    pcmFile,
                    outputDir,
                    outputBaseName,
                    stems,
                    totalFrames,
                    chunkCount,
                    isCancelled,
                    onProgress,
                    startedAt
                )
            }
        }
    }

    private fun runChunks(
        environment: OrtEnvironment,
        session: OrtSession,
        pcmFile: File,
        outputDir: File,
        outputBaseName: String,
        stems: Set<Stem>,
        totalFrames: Long,
        chunkCount: Int,
        isCancelled: () -> Boolean,
        onProgress: (Int, Int) -> Unit,
        startedAt: Long
    ): Result {
        val transitionWindow = buildTransitionWindow()
        val accumulators = stems.associateWith {
            arrayOf(FloatArray(SEGMENT), FloatArray(SEGMENT))
        }
        val weights = FloatArray(SEGMENT)
        val inputBuffer = ByteBuffer.allocateDirect(2 * SEGMENT * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val interleavedBytes = ByteArray(SEGMENT * BYTES_PER_FRAME)
        val outputFiles = stems.associateWith { stem ->
            File(outputDir, "${outputBaseName}_${stem.fileSuffix}.wav")
        }
        val writers = outputFiles.mapValues { (_, file) ->
            StreamingWavWriter(file, SAMPLE_RATE, CHANNELS, totalFrames)
        }

        try {
            RandomAccessFile(pcmFile, "r").use { pcm ->
                var baseFrame = 0L
                for (chunkIndex in 0 until chunkCount) {
                    checkNotCancelled(isCancelled)
                    val chunkStart = chunkIndex.toLong() * STRIDE
                    val chunkLength = min(SEGMENT.toLong(), totalFrames - chunkStart).toInt()
                    if (chunkLength <= 0) break

                    val chunkStartedAt = System.currentTimeMillis()
                    readChunk(pcm, chunkStart, chunkLength, interleavedBytes, inputBuffer)
                    inputBuffer.position(0)

                    OnnxTensor.createTensor(
                        environment,
                        inputBuffer,
                        longArrayOf(1, CHANNELS.toLong(), SEGMENT.toLong())
                    ).use { inputTensor ->
                        session.run(mapOf(INPUT_NAME to inputTensor)).use { inferenceResult ->
                            val outputTensor = inferenceResult.get(OUTPUT_NAME).orElseThrow {
                                IllegalStateException("模型没有输出张量 $OUTPUT_NAME")
                            } as? OnnxTensor ?: throw IllegalStateException("模型输出不是张量")
                            accumulateSelectedStems(
                                outputTensor.floatBuffer,
                                stems,
                                accumulators,
                                transitionWindow,
                                weights,
                                chunkLength
                            )
                        }
                    }

                    val isLast = chunkIndex == chunkCount - 1
                    val flushEnd = if (isLast) totalFrames else (chunkIndex + 1L) * STRIDE
                    val flushCount = (flushEnd - baseFrame).toInt().coerceAtMost(SEGMENT)
                    normalizeAndWrite(stems, accumulators, weights, writers, flushCount)

                    if (!isLast) {
                        val keep = SEGMENT - flushCount
                        shiftAccumulators(stems, accumulators, weights, flushCount, keep)
                        baseFrame += flushCount
                    }

                    val elapsed = System.currentTimeMillis() - chunkStartedAt
                    log("分块 ${chunkIndex + 1}/$chunkCount 完成：输入 ${"%.2f".format(Locale.getDefault(), chunkLength / SAMPLE_RATE.toDouble())} 秒，耗时 ${formatDuration(elapsed)}")
                    onProgress(chunkIndex + 1, chunkCount)
                }
            }
        } catch (e: Throwable) {
            writers.values.forEach { runCatching { it.close() } }
            outputFiles.values.forEach { it.delete() }
            throw e
        }

        writers.values.forEach { it.close() }
        return Result(outputFiles, totalFrames, chunkCount, System.currentTimeMillis() - startedAt)
    }

    private fun validateModel(session: OrtSession) {
        require(session.inputNames.contains(INPUT_NAME)) {
            "模型输入不兼容：需要名为 $INPUT_NAME 的输入，实际为 ${session.inputNames}"
        }
        require(session.outputNames.contains(OUTPUT_NAME)) {
            "模型输出不兼容：需要名为 $OUTPUT_NAME 的输出，实际为 ${session.outputNames}"
        }
    }

    private fun readChunk(
        pcm: RandomAccessFile,
        startFrame: Long,
        frameCount: Int,
        bytes: ByteArray,
        input: FloatBuffer
    ) {
        val byteCount = frameCount * BYTES_PER_FRAME
        pcm.seek(startFrame * BYTES_PER_FRAME)
        pcm.readFully(bytes, 0, byteCount)
        val interleaved = ByteBuffer.wrap(bytes, 0, byteCount).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            input.put(i, interleaved.float)
            input.put(SEGMENT + i, interleaved.float)
        }
        for (i in frameCount until SEGMENT) {
            input.put(i, 0f)
            input.put(SEGMENT + i, 0f)
        }
    }

    private fun accumulateSelectedStems(
        output: FloatBuffer,
        stems: Set<Stem>,
        accumulators: Map<Stem, Array<FloatArray>>,
        window: FloatArray,
        weights: FloatArray,
        chunkLength: Int
    ) {
        for (stem in stems) {
            val stemAcc = accumulators.getValue(stem)
            for (channel in 0 until CHANNELS) {
                val outputBase = (stem.modelIndex * CHANNELS + channel) * SEGMENT
                val destination = stemAcc[channel]
                for (sample in 0 until chunkLength) {
                    destination[sample] += output.get(outputBase + sample) * window[sample]
                }
            }
        }
        for (sample in 0 until chunkLength) weights[sample] += window[sample]
    }

    private fun normalizeAndWrite(
        stems: Set<Stem>,
        accumulators: Map<Stem, Array<FloatArray>>,
        weights: FloatArray,
        writers: Map<Stem, StreamingWavWriter>,
        count: Int
    ) {
        for (sample in 0 until count) {
            val weight = weights[sample].coerceAtLeast(1e-8f)
            for (stem in stems) {
                val channels = accumulators.getValue(stem)
                channels[0][sample] /= weight
                channels[1][sample] /= weight
            }
        }
        for (stem in stems) writers.getValue(stem).write(accumulators.getValue(stem), count)
    }

    private fun shiftAccumulators(
        stems: Set<Stem>,
        accumulators: Map<Stem, Array<FloatArray>>,
        weights: FloatArray,
        from: Int,
        keep: Int
    ) {
        for (stem in stems) {
            for (channel in accumulators.getValue(stem)) {
                System.arraycopy(channel, from, channel, 0, keep)
                java.util.Arrays.fill(channel, keep, SEGMENT, 0f)
            }
        }
        System.arraycopy(weights, from, weights, 0, keep)
        java.util.Arrays.fill(weights, keep, SEGMENT, 0f)
    }

    private fun checkNotCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw InterruptedException("人声分离已取消")
    }

    private fun buildTransitionWindow(): FloatArray {
        val window = FloatArray(SEGMENT) { 1f }
        for (i in 0 until OVERLAP) {
            val value = i.toFloat() / (OVERLAP - 1).toFloat()
            window[i] = value
            window[SEGMENT - 1 - i] = value
        }
        return window
    }

    private class StreamingWavWriter(
        file: File,
        sampleRate: Int,
        channels: Int,
        totalFrames: Long
    ) : Closeable {
        private val output = BufferedOutputStream(FileOutputStream(file), 256 * 1024)
        private val channelCount = channels

        init {
            output.write(wavHeader(sampleRate, channels, totalFrames))
        }

        fun write(channels: Array<FloatArray>, frameCount: Int) {
            val bytes = ByteArray(frameCount * channelCount * 2)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (frame in 0 until frameCount) {
                for (channel in 0 until channelCount) {
                    val sample = channels[channel][frame].coerceIn(-1f, 1f)
                    buffer.putShort((sample * 32767f).toInt().toShort())
                }
            }
            output.write(bytes)
        }

        override fun close() = output.close()

        companion object {
            private fun wavHeader(sampleRate: Int, channels: Int, totalFrames: Long): ByteArray {
                val bitsPerSample = 16
                val blockAlign = channels * bitsPerSample / 8
                val dataSize = totalFrames * blockAlign
                require(dataSize <= 0xffffffffL - 36L) { "输出 WAV 超过标准 RIFF 4GB 限制" }
                return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray(Charsets.US_ASCII))
                    putInt((36L + dataSize).toInt())
                    put("WAVE".toByteArray(Charsets.US_ASCII))
                    put("fmt ".toByteArray(Charsets.US_ASCII))
                    putInt(16)
                    putShort(1.toShort())
                    putShort(channels.toShort())
                    putInt(sampleRate)
                    putInt(sampleRate * blockAlign)
                    putShort(blockAlign.toShort())
                    putShort(bitsPerSample.toShort())
                    put("data".toByteArray(Charsets.US_ASCII))
                    putInt(dataSize.toInt())
                }.array()
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
        const val SEGMENT = 343980
        const val OVERLAP = SEGMENT / 4
        const val STRIDE = SEGMENT - OVERLAP
        const val INPUT_NAME = "mix"
        const val OUTPUT_NAME = "stems"
        private const val BYTES_PER_FRAME = CHANNELS * Float.SIZE_BYTES

        private fun formatDuration(ms: Long): String = if (ms < 1000) {
            "${ms}ms"
        } else {
            "${"%.1f".format(Locale.getDefault(), ms / 1000.0)}s"
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> "${"%.2f".format(Locale.getDefault(), bytes / (1024.0 * 1024.0 * 1024.0))} GB"
            bytes >= 1024L * 1024L -> "${"%.2f".format(Locale.getDefault(), bytes / (1024.0 * 1024.0))} MB"
            bytes >= 1024L -> "${"%.2f".format(Locale.getDefault(), bytes / 1024.0)} KB"
            else -> "$bytes B"
        }

        private fun enabledText(enabled: Boolean): String = if (enabled) "开启" else "关闭"
    }
}
