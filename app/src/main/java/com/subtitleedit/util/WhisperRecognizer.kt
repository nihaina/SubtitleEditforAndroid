package com.subtitleedit.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Whisper 语音识别器
 * 使用 sherpa-onnx 进行离线语音识别，支持长音频分段处理
 * 支持 VAD (Voice Activity Detection) 进行精确的语音段检测
 */
class WhisperRecognizer(
    private val encoderPath: String,
    private val decoderPath: String,
    private val tokensPath: String,
    private val vadModelPath: String = "",
    private val useVad: Boolean = true,
    private val language: String = "auto",
    private val contentResolver: ContentResolver,
    private val context: Context
) {

    companion object {
        private const val TAG = "WhisperRecognizer"
        private const val SEGMENT_DURATION_MS = 30000L // 30秒一段
        private const val SAMPLE_RATE = 16000 // Whisper 需要 16kHz
    }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null

    /**
     * 字幕片段
     */
    data class SubtitleSegment(
        val startTime: Long,  // 毫秒
        val endTime: Long,    // 毫秒
        val text: String
    )

    /**
     * VAD 检测到的语音段
     */
    data class VadSegment(
        val startSample: Int,  // 起始采样点（相对于原始音频）
        val samples: FloatArray,  // 语音段的音频数据
        val startTime: Long,   // 起始时间（毫秒）
        val endTime: Long      // 结束时间（毫秒）
    )

    /**
     * 初始化识别器
     */
    private fun initRecognizer(): Result<Unit> {
        return try {
            // 将 URI 转换为本地文件路径
            val encoderFile = copyUriToCache(Uri.parse(encoderPath), "encoder.onnx")
            val decoderFile = copyUriToCache(Uri.parse(decoderPath), "decoder.onnx")
            val tokensFile = copyUriToCache(Uri.parse(tokensPath), "tokens.txt")

            if (encoderFile == null) {
                return Result.failure(Exception("无法读取 encoder 文件"))
            }
            if (decoderFile == null) {
                return Result.failure(Exception("无法读取 decoder 文件"))
            }
            if (tokensFile == null) {
                return Result.failure(Exception("无法读取 tokens 文件"))
            }

            Log.d(TAG, "模型文件准备完成:")
            Log.d(TAG, "  encoder: ${encoderFile.absolutePath}")
            Log.d(TAG, "  decoder: ${decoderFile.absolutePath}")
            Log.d(TAG, "  tokens: ${tokensFile.absolutePath}")

            if (useVad) {
                // 初始化 VAD（如果提供了模型路径，使用外部模型；否则使用内置模型）
                if (vadModelPath.isNotEmpty()) {
                    val vadFile = copyUriToCache(Uri.parse(vadModelPath), "vad.onnx")
                    if (vadFile != null) {
                        Log.d(TAG, "  vad: ${vadFile.absolutePath}")
                        val vadConfig = VadModelConfig(
                            sileroVadModelConfig = SileroVadModelConfig(
                                model = vadFile.absolutePath,
                                threshold = 0.3F,  // 适中的阈值
                                minSilenceDuration = 0.3F,  // 0.3秒静音就分段
                                minSpeechDuration = 0.25F,  // 最短语音 0.25 秒
                                windowSize = 512,
                                maxSpeechDuration = 10.0F  // 单段最长 10 秒，超过就强制分段
                            ),
                            sampleRate = SAMPLE_RATE,
                            numThreads = 2,
                            provider = "cpu",
                            debug = true
                        )
                        vad = Vad(assetManager = null, config = vadConfig)
                        Log.d(TAG, "VAD 初始化成功（外部模型）")
                    } else {
                        Log.w(TAG, "VAD 外部模型文件读取失败")
                    }
                } else {
                    // 使用内置的 VAD 模型
                    try {
                        Log.d(TAG, "使用内置 VAD 模型")
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
                        vad = Vad(assetManager = context.assets, config = vadConfig)
                        Log.d(TAG, "VAD 初始化成功（内置模型）")
                    } catch (e: Exception) {
                        Log.w(TAG, "VAD 内置模型初始化失败: ${e.message}")
                    }
                }
            } else {
                vad = null
                Log.d(TAG, "已禁用 VAD 分段，将使用固定分段识别")
            }

            val modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoderFile.absolutePath,
                    decoder = decoderFile.absolutePath,
                    language = if (language == "自动检测") "" else mapLanguage(language),
                    task = "transcribe",
                    tailPaddings = 1000,
                    enableTokenTimestamps = true,
                    enableSegmentTimestamps = false
                ),
                tokens = tokensFile.absolutePath,
                numThreads = 4,
                debug = true,
                provider = "cpu"
            )

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80
                ),
                modelConfig = modelConfig
            )

            Log.d(TAG, "开始初始化 OfflineRecognizer...")
            recognizer = OfflineRecognizer(assetManager = null, config = config)

            if (recognizer == null) {
                return Result.failure(Exception("OfflineRecognizer 初始化返回 null"))
            }

            Log.d(TAG, "Whisper 识别器初始化成功")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "初始化识别器失败", e)
            recognizer = null
            Result.failure(e)
        }
    }

    /**
     * 复制 URI 到缓存目录
     */
    private fun copyUriToCache(uri: Uri, fileName: String): File? {
        return try {
            val cacheFile = File(context.cacheDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (cacheFile.exists()) {
                Log.d(TAG, "文件复制成功: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
                cacheFile
            } else {
                Log.e(TAG, "文件复制失败: 文件不存在")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "复制文件失败: $fileName", e)
            null
        }
    }

    /**
     * 识别音频文件
     */
    fun recognize(
        audioFile: File,
        progressCallback: (progress: Int, status: String, segmentResult: SubtitleSegment?) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): Result<List<SubtitleSegment>> {
        return try {
            // 初始化识别器
            val initResult = initRecognizer()
            if (initResult.isFailure) {
                return Result.failure(initResult.exceptionOrNull()!!)
            }

            if (isCancelled()) {
                return Result.failure(Exception("用户取消"))
            }

            progressCallback(0, "正在加载音频...", null)

            // 读取音频数据
            val audioData = readAudioFile(audioFile)
            val totalSamples = audioData.size
            val totalDurationMs = (totalSamples * 1000L) / SAMPLE_RATE

            Log.d(TAG, "音频总时长: ${totalDurationMs}ms, 总采样点: $totalSamples")

            val allSegments = mutableListOf<SubtitleSegment>()

            // 如果启用了 VAD，先进行语音段检测
            val vadSegments = if (vad != null) {
                Log.d(TAG, "使用 VAD 进行语音段检测...")
                Log.d(TAG, "音频数据统计: min=${audioData.minOrNull()}, max=${audioData.maxOrNull()}, avg=${audioData.average()}")
                progressCallback(5, "正在检测语音段...", null)

                val segments = detectSpeechSegments(audioData)
                Log.d(TAG, "VAD 检测到 ${segments.size} 个语音段")

                if (segments.isEmpty()) {
                    Log.w(TAG, "VAD 未检测到任何语音段，将使用固定分段方式")
                    null
                } else {
                    segments
                }
            } else {
                null
            }

            if (vadSegments != null) {
                // 对每个语音段进行识别
                for ((index, vadSegment) in vadSegments.withIndex()) {
                    if (isCancelled()) {
                        return Result.failure(Exception("用户取消"))
                    }

                    progressCallback(
                        5 + ((index * 95) / vadSegments.size),
                        "正在识别第 ${index + 1}/${vadSegments.size} 个语音段...",
                        null
                    )

                    Log.d(TAG, "识别语音段 ${index + 1}/${vadSegments.size} (${vadSegment.startTime}ms - ${vadSegment.endTime}ms)")

                    // 使用 VAD 提取的语音段数据
                    val segmentData = vadSegment.samples

                    // 识别当前语音段
                    val segments = recognizeSegment(segmentData, vadSegment.startTime)
                    allSegments.addAll(segments)

                    // 实时返回识别结果
                    if (segments.isNotEmpty()) {
                        for (segment in segments) {
                            progressCallback(
                                5 + ((index * 95) / vadSegments.size),
                                "正在识别第 ${index + 1}/${vadSegments.size} 个语音段...",
                                segment
                            )
                        }
                    }
                }
            } else {
                // 没有 VAD，使用原来的固定分段方式
                Log.d(TAG, "未使用 VAD，采用固定时长分段")

                // 计算分段数量
                val segmentCount = ((totalDurationMs + SEGMENT_DURATION_MS - 1) / SEGMENT_DURATION_MS).toInt()
                val samplesPerSegment = (SEGMENT_DURATION_MS * SAMPLE_RATE / 1000).toInt()

                Log.d(TAG, "将分为 $segmentCount 段处理")

                // 逐段识别
                for (i in 0 until segmentCount) {
                    if (isCancelled()) {
                        return Result.failure(Exception("用户取消"))
                    }

                    val startSample = i * samplesPerSegment
                    val endSample = minOf((i + 1) * samplesPerSegment, totalSamples)
                    val segmentData = audioData.copyOfRange(startSample, endSample)

                    val startTimeMs = (startSample * 1000L) / SAMPLE_RATE

                    progressCallback(
                        (i * 100) / segmentCount,
                        "正在识别第 ${i + 1}/$segmentCount 段...",
                        null
                    )

                    Log.d(TAG, "识别第 ${i + 1}/$segmentCount 段 (${startTimeMs}ms - ${(endSample * 1000L) / SAMPLE_RATE}ms)")

                    // 识别当前段
                    val segments = recognizeSegment(segmentData, startTimeMs)
                    allSegments.addAll(segments)

                    // 如果识别到内容，立即通过回调返回
                    if (segments.isNotEmpty()) {
                        for (segment in segments) {
                            progressCallback(
                                (i * 100) / segmentCount,
                                "正在识别第 ${i + 1}/$segmentCount 段...",
                                segment
                            )
                        }
                    }
                }
            }

            progressCallback(100, "识别完成", null)

            Log.d(TAG, "识别完成，共生成 ${allSegments.size} 个字幕片段")
            Result.success(allSegments)

        } catch (e: Exception) {
            Log.e(TAG, "识别失败", e)
            Result.failure(e)
        } finally {
            release()
        }
    }

    /**
     * 识别单个音频段
     */
    private fun recognizeSegment(
        audioData: FloatArray,
        startTimeMs: Long
    ): List<SubtitleSegment> {
        val segments = mutableListOf<SubtitleSegment>()

        try {
            // 检查 recognizer 是否已初始化
            val rec = recognizer
            if (rec == null) {
                Log.e(TAG, "recognizer 为 null，无法创建 stream")
                return segments
            }

            Log.d(TAG, "创建 stream...")
            val stream = try {
                rec.createStream()
            } catch (e: Exception) {
                Log.e(TAG, "创建 stream 失败", e)
                return segments
            }

            Log.d(TAG, "输入音频数据: ${audioData.size} 个采样点")
            // 输入音频数据
            stream.acceptWaveform(audioData, SAMPLE_RATE)

            Log.d(TAG, "执行识别...")
            // 执行识别
            rec.decode(stream)

            Log.d(TAG, "获取识别结果...")
            // 获取结果
            val result = rec.getResult(stream)
            val text = result.text.trim()

            Log.d(TAG, "识别结果: $text")

            if (text.isNotEmpty()) {
                // Whisper 返回的时间戳是相对于当前段的
                val tokens = result.tokens
                val timestamps = result.timestamps

                Log.d(TAG, "Token 数量: ${tokens.size}, 时间戳数量: ${timestamps.size}")

                if (tokens.isNotEmpty() && timestamps.isNotEmpty() && tokens.size == timestamps.size) {
                    // 如果有详细的 token 时间戳，使用它们
                    Log.d(TAG, "使用 token 时间戳进行分段")
                    var currentText = StringBuilder()
                    var segmentStart = startTimeMs

                    for (j in tokens.indices) {
                        val token = tokens[j]
                        currentText.append(token)

                        // 检查是否是句子结束
                        if (token.endsWith(".") || token.endsWith("。") ||
                            token.endsWith("?") || token.endsWith("？") ||
                            token.endsWith("!") || token.endsWith("！") ||
                            j == tokens.size - 1) {

                            // timestamps 是秒为单位，转换为毫秒
                            val segmentEnd = startTimeMs + (timestamps[j] * 1000).toLong()

                            val segmentText = currentText.toString().trim()
                            if (segmentText.isNotEmpty()) {
                                segments.add(SubtitleSegment(
                                    startTime = segmentStart,
                                    endTime = segmentEnd,
                                    text = segmentText
                                ))
                                Log.d(TAG, "添加字幕段: ${segmentStart}ms - ${segmentEnd}ms, 文本: ${segmentText.take(50)}...")
                            }

                            currentText = StringBuilder()
                            segmentStart = segmentEnd
                        }
                    }
                } else {
                    // 没有详细时间戳，按句子手动分割
                    Log.d(TAG, "没有 token 时间戳，按句子手动分割")
                    val sentences = splitIntoSentences(text)
                    val totalDuration = (audioData.size * 1000L) / SAMPLE_RATE
                    val avgDurationPerChar = totalDuration.toFloat() / text.length

                    var currentTime = startTimeMs
                    for (sentence in sentences) {
                        if (sentence.isNotEmpty()) {
                            val duration = (sentence.length * avgDurationPerChar).toLong()
                            val endTime = currentTime + duration

                            segments.add(SubtitleSegment(
                                startTime = currentTime,
                                endTime = endTime,
                                text = sentence
                            ))
                            Log.d(TAG, "添加字幕段: ${currentTime}ms - ${endTime}ms, 文本: ${sentence.take(50)}...")

                            currentTime = endTime
                        }
                    }
                }
            }

            stream.release()

        } catch (e: Exception) {
            Log.e(TAG, "识别段失败", e)
        }

        return segments
    }

    /**
     * 读取 WAV 文件为 Float 数组
     */
    private fun readAudioFile(file: File): FloatArray {
        FileInputStream(file).use { fis ->
            // 跳过 WAV 文件头（44 字节）
            fis.skip(44)

            // 读取 PCM 数据
            val buffer = ByteArray(fis.available())
            fis.read(buffer)

            // 转换为 Float 数组（16-bit PCM -> Float）
            val samples = FloatArray(buffer.size / 2)
            val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

            for (i in samples.indices) {
                val sample = byteBuffer.short.toFloat() / 32768.0f
                samples[i] = sample
            }

            return samples
        }
    }

    /**
     * 将文本按句子分割
     */
    private fun splitIntoSentences(text: String): List<String> {
        // 按句子结束符分割
        val sentences = mutableListOf<String>()
        val regex = Regex("([^.!?。！？]+[.!?。！？]+)")
        val matches = regex.findAll(text)

        for (match in matches) {
            sentences.add(match.value.trim())
        }

        // 如果没有匹配到任何句子（可能没有标点符号），返回原文本
        if (sentences.isEmpty() && text.isNotEmpty()) {
            sentences.add(text)
        }

        return sentences
    }

    /**
     * 映射语言代码
     */
    private fun mapLanguage(language: String): String {
        return when (language) {
            "中文" -> "zh"
            "英语" -> "en"
            "日语" -> "ja"
            "韩语" -> "ko"
            "法语" -> "fr"
            "德语" -> "de"
            "西班牙语" -> "es"
            "俄语" -> "ru"
            "葡萄牙语" -> "pt"
            "意大利语" -> "it"
            "土耳其语" -> "tr"
            else -> ""
        }
    }

    /**
     * 释放资源
     */
    private fun release() {
        try {
            recognizer?.release()
            recognizer = null
            vad?.release()
            vad = null
            Log.d(TAG, "识别器资源已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放资源失败", e)
        }
    }

    /**
     * 使用 VAD 检测语音段（流式处理）
     */
    private fun detectSpeechSegments(audioData: FloatArray): List<VadSegment> {
        val segments = mutableListOf<VadSegment>()
        val vadInstance = vad ?: return segments

        try {
            // 流式输入：每次输入一小块，立即检查是否有语音段产生
            val windowSize = 512  // 每次输入 512 个采样点（32ms）
            var offset = 0
            var totalProcessed = 0

            Log.d(TAG, "开始流式输入音频到 VAD，总长度: ${audioData.size} 采样点")

            while (offset < audioData.size) {
                val end = minOf(offset + windowSize, audioData.size)
                val chunk = audioData.copyOfRange(offset, end)

                // 输入音频块
                vadInstance.acceptWaveform(chunk)
                totalProcessed += chunk.size

                // 立即检查是否有语音段产生
                while (!vadInstance.empty()) {
                    val speechSegment = vadInstance.front()
                    vadInstance.pop()

                    // 计算时间（毫秒）
                    val startSample = speechSegment.start
                    val endSample = startSample + speechSegment.samples.size
                    val startTime = (startSample * 1000L) / SAMPLE_RATE
                    val endTime = (endSample * 1000L) / SAMPLE_RATE

                    segments.add(VadSegment(
                        startSample = startSample,
                        samples = speechSegment.samples,
                        startTime = startTime,
                        endTime = endTime
                    ))

                    Log.d(TAG, "VAD 检测到语音段: ${startTime}ms - ${endTime}ms (${speechSegment.samples.size} 采样点)")
                }

                offset = end
            }

            // 刷新 VAD 缓冲区，获取剩余的语音段
            vadInstance.flush()
            Log.d(TAG, "VAD flush 完成，已处理 $totalProcessed 采样点")

            // 提取 flush 后产生的语音段
            while (!vadInstance.empty()) {
                val speechSegment = vadInstance.front()
                vadInstance.pop()

                val startSample = speechSegment.start
                val endSample = startSample + speechSegment.samples.size
                val startTime = (startSample * 1000L) / SAMPLE_RATE
                val endTime = (endSample * 1000L) / SAMPLE_RATE

                segments.add(VadSegment(
                    startSample = startSample,
                    samples = speechSegment.samples,
                    startTime = startTime,
                    endTime = endTime
                ))

                Log.d(TAG, "VAD flush 后检测到语音段: ${startTime}ms - ${endTime}ms (${speechSegment.samples.size} 采样点)")
            }

            vadInstance.reset()
            Log.d(TAG, "VAD 检测完成，共 ${segments.size} 个语音段")
        } catch (e: Exception) {
            Log.e(TAG, "VAD 检测失败", e)
        }

        return segments
    }
}
