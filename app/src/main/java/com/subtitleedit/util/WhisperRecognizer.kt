package com.subtitleedit.util

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * Whisper 语音识别器
 * 使用 sherpa-onnx 进行离线语音识别，支持长音频分段处理
 * 支持 VAD (Voice Activity Detection) 进行精确的语音段检测
 */
class WhisperRecognizer(
    private val encoderPath: String,
    private val decoderPath: String,
    private val joinerPath: String = "",
    private val tokensPath: String,
    private val vadModelPath: String = "",
    private val useVad: Boolean = true,
    private val language: String = "auto",
    private val contentResolver: ContentResolver,
    private val context: Context,
    private val modelType: String = SettingsManager.ASR_MODEL_WHISPER,
    private val senseVoiceTimestampExperiment: Boolean = false,
    private val senseVoiceTimestampGapMs: Int = 500
) {

    companion object {
        private const val TAG = "WhisperRecognizer"
        private const val SAMPLE_RATE = 16000 // Whisper 需要 16kHz
        private const val VAD_CONTEXT_PADDING_MS = 500L
    }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var secondaryVad: Vad? = null
    private var qnnExecutableModelCopy: File? = null
    // 原生识别器只接受文件路径。对 SAF URI 保持描述符存活，避免复制大型模型文件。
    private val modelFileDescriptors = mutableListOf<ParcelFileDescriptor>()

    /**
     * 字幕片段
     */
    data class SubtitleSegment(
        val startTime: Long,  // 毫秒
        val endTime: Long,    // 毫秒
        val text: String,
        val timestampGapBoundaryBefore: Boolean = false
    )

    /**
     * VAD 检测到的语音段
     */
    data class VadSegment(
        val startSample: Int,  // 起始采样点（相对于原始音频）
        val sampleCount: Int,  // 语音段采样点数量
        val startTime: Long,   // 起始时间（毫秒）
        val endTime: Long      // 结束时间（毫秒）
    )

    private data class RecognitionWindow(
        val startSample: Long,
        val sampleCount: Int
    )

    private data class SegmentTimeRange(
        val startTimeMs: Long,
        val endTimeMs: Long
    )

    private data class SenseVoiceNpuRuntimeFiles(
        val modelPath: String,
        val contextBinaryPath: String
    )

    private data class SampleRange(
        val startSample: Long,
        val endSample: Long
    )

    private enum class VadSegmentSource {
        PRIMARY,
        SECONDARY
    }

    private data class SourcedVadSegment(
        val segment: VadSegment,
        val tailSource: VadSegmentSource
    )

    data class RangeContext(
        val previousEndTimeMs: Long?,
        val nextStartTimeMs: Long?
    )

    /**
     * 初始化识别器
     */
    private fun initRecognizer(): Result<Unit> {
        return try {
            if (isSenseVoiceNpu() && "arm64-v8a" !in Build.SUPPORTED_ABIS) {
                return Result.failure(Exception("SenseVoice NPU 仅支持 arm64-v8a 骁龙设备"))
            }
            val primaryFileName = when {
                isSenseVoiceNpu() -> "libmodel.so"
                isSenseVoice() -> "sensevoice.onnx"
                isParakeetCtc() -> "model.int8.onnx"
                else -> "encoder.int8.onnx"
            }
            val resolvedEncoderFile = resolveModelPath(
                encoderPath,
                primaryFileName
            )
            val npuRuntimeFiles = if (isSenseVoiceNpu() && resolvedEncoderFile != null) {
                prepareSenseVoiceNpuRuntimeFiles(resolvedEncoderFile, encoderPath)
            } else {
                null
            }
            val encoderFile = npuRuntimeFiles?.modelPath ?: resolvedEncoderFile
            val decoderFile = if (requiresDecoder()) {
                resolveModelPath(decoderPath, "decoder.int8.onnx")
            } else {
                null
            }
            val joinerFile = if (isParakeetTdt()) {
                resolveModelPath(joinerPath, "joiner.int8.onnx")
            } else {
                null
            }
            val tokensFile = resolveModelPath(tokensPath, "tokens.txt")

            if (encoderFile == null) {
                return Result.failure(Exception("无法读取${if (isSingleFileModel()) "模型" else " encoder"}文件"))
            }
            if (requiresDecoder() && decoderFile == null) {
                return Result.failure(Exception("无法读取 decoder 文件"))
            }
            if (isParakeetTdt() && joinerFile == null) {
                return Result.failure(Exception("无法读取 joiner 文件"))
            }
            if (tokensFile == null) {
                return Result.failure(Exception("无法读取 tokens 文件"))
            }
            Log.d(TAG, "模型文件准备完成:")
            if (npuRuntimeFiles != null && encoderFile != resolvedEncoderFile) {
                Log.d(TAG, "  model source: $resolvedEncoderFile")
                Log.d(TAG, "  model runtime: $encoderFile")
            } else {
                Log.d(TAG, "  ${if (isSingleFileModel()) "model" else "encoder"}: $encoderFile")
            }
            decoderFile?.let { Log.d(TAG, "  decoder: $it") }
            joinerFile?.let { Log.d(TAG, "  joiner: $it") }
            Log.d(TAG, "  tokens: $tokensFile")

            if (useVad) {
                initializeVadInstances()
            } else {
                vad = null
                secondaryVad = null
                val fixedSegmentSeconds = fixedSegmentDurationSeconds()
                Log.d(
                    TAG,
                    if (isSenseVoiceNpu()) {
                        "已禁用 VAD 分段，将使用 SenseVoice NPU 模型固定时长 ${fixedSegmentSeconds}s"
                    } else {
                        "已禁用 VAD 分段，将使用固定时长 ${fixedSegmentSeconds}s"
                    }
                )
            }

            val modelConfig = when {
                isSenseVoice() -> {
                    val senseVoiceLanguage = mapSenseVoiceLanguage(language)
                    Log.d(TAG, "SenseVoice language=$senseVoiceLanguage (selected=$language)")
                    val qnn = isSenseVoiceNpu()
                    if (qnn) {
                        OfflineRecognizer.prependAdspLibraryPath(
                            context.applicationInfo.nativeLibraryDir
                        )
                    }
                    OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = encoderFile,
                            language = senseVoiceLanguage,
                            useInverseTextNormalization = true,
                            qnnConfig = if (qnn) {
                                QnnConfig(
                                    backendLib = "libQnnHtp.so",
                                    systemLib = "libQnnSystem.so",
                                    contextBinary = npuRuntimeFiles!!.contextBinaryPath
                                )
                            } else {
                                QnnConfig()
                            }
                        ),
                        tokens = tokensFile,
                        numThreads = if (qnn) 1 else 4,
                        debug = true,
                        provider = if (qnn) "qnn" else "cpu"
                    )
                }
                isParakeetTdt() -> OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = encoderFile,
                        decoder = decoderFile!!,
                        joiner = joinerFile!!
                    ),
                    tokens = tokensFile,
                    numThreads = settingsManager().getSpeechWhisperThreads(),
                    debug = true,
                    provider = "cpu",
                    modelType = "nemo_transducer"
                )
                isParakeetCtc() -> OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = encoderFile),
                    tokens = tokensFile,
                    numThreads = settingsManager().getSpeechWhisperThreads(),
                    debug = true,
                    provider = "cpu"
                )
                else -> OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = encoderFile,
                        decoder = decoderFile!!,
                        language = if (language == "自动检测") "" else mapLanguage(language),
                        task = "transcribe",
                        tailPaddings = 1000,
                        enableTokenTimestamps = true,
                        enableSegmentTimestamps = false
                    ),
                    tokens = tokensFile,
                    numThreads = settingsManager().getSpeechWhisperThreads(),
                    debug = true,
                    provider = "cpu"
                )
            }

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80
                ),
                modelConfig = modelConfig,
                hotwordsScore = if (supportsHotwords()) settingsManager().getSpeechHotwordsScore() else 1.0f
            )

            Log.d(TAG, "开始初始化 OfflineRecognizer...")
            recognizer = OfflineRecognizer(assetManager = null, config = config)

            if (recognizer == null) {
                return Result.failure(Exception("OfflineRecognizer 初始化返回 null"))
            }

            Log.d(TAG, "${modelDisplayName()} 识别器初始化成功")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "初始化识别器失败", e)
            recognizer = null
            deleteQnnExecutableModelCopy()
            Result.failure(e)
        }
    }

    /**
     * QNN 首次初始化会 dlopen libmodel.so。Android 共享存储不可执行，因此模型仍保存在
     * 公共目录，但首次生成 model.bin 时使用 codeCacheDir 中的临时可执行副本。
     */
    private fun prepareSenseVoiceNpuRuntimeFiles(
        modelPath: String,
        modelIdentity: String
    ): SenseVoiceNpuRuntimeFiles {
        val sourceModel = File(modelPath)
        if (!sourceModel.isFile || sourceModel.length() <= 0L) {
            throw IllegalStateException("SenseVoice NPU 模型文件无效：$modelPath")
        }

        val runtimeDir = File(context.codeCacheDir, "sensevoice-qnn")
        if (!runtimeDir.exists() && !runtimeDir.mkdirs()) {
            throw IllegalStateException("无法创建 SenseVoice NPU 运行时目录")
        }
        val durationSeconds = settingsManager().getSenseVoiceNpuDurationSeconds()
        val modelKey = Integer.toHexString(modelIdentity.hashCode())
        val contextBinary = if (modelPath.startsWith("/proc/self/fd/")) {
            File(runtimeDir, "model-${durationSeconds}s-$modelKey.bin")
        } else {
            sourceModel.resolveSibling("model.bin")
        }
        if (contextBinary.isFile && contextBinary.length() > 0L) {
            deleteQnnExecutableModelCopy()
            Log.d(TAG, "使用 QNN context binary: ${contextBinary.absolutePath}")
            return SenseVoiceNpuRuntimeFiles(sourceModel.absolutePath, contextBinary.absolutePath)
        }
        if (contextBinary.exists() && !contextBinary.delete()) {
            throw IllegalStateException("无法清理无效的 QNN context binary：${contextBinary.absolutePath}")
        }

        val runtimeModel = File(
            runtimeDir,
            "libmodel-${durationSeconds}s-$modelKey.so"
        )
        val copyRequired = !runtimeModel.isFile ||
            runtimeModel.length() != sourceModel.length() ||
            runtimeModel.lastModified() != sourceModel.lastModified()
        if (copyRequired) {
            val temporaryModel = File(runtimeDir, "${runtimeModel.name}.part")
            temporaryModel.delete()
            try {
                sourceModel.inputStream().buffered().use { input ->
                    temporaryModel.outputStream().buffered().use { output ->
                        input.copyTo(output, 1024 * 1024)
                    }
                }
                if (temporaryModel.length() != sourceModel.length()) {
                    throw IllegalStateException("SenseVoice NPU 临时模型复制不完整")
                }
                if (runtimeModel.exists() && !runtimeModel.delete()) {
                    throw IllegalStateException("无法替换 SenseVoice NPU 临时模型")
                }
                if (!temporaryModel.renameTo(runtimeModel)) {
                    temporaryModel.copyTo(runtimeModel, overwrite = true)
                    temporaryModel.delete()
                }
                runtimeModel.setLastModified(sourceModel.lastModified())
            } finally {
                temporaryModel.delete()
            }
        }

        qnnExecutableModelCopy = runtimeModel
        Log.d(TAG, "已准备 QNN 临时可执行模型: ${runtimeModel.absolutePath}")
        return SenseVoiceNpuRuntimeFiles(runtimeModel.absolutePath, contextBinary.absolutePath)
    }

    private fun deleteQnnExecutableModelCopy() {
        qnnExecutableModelCopy?.let { model ->
            if (model.exists() && !model.delete()) {
                Log.w(TAG, "无法删除 QNN 临时模型: ${model.absolutePath}")
            }
        }
        qnnExecutableModelCopy = null
    }

    private fun initializeVadInstances() {
        val settings = settingsManager()
        val modelPath: String
        val assetManager: AssetManager?
        val modelSource: String

        if (vadModelPath.isNotEmpty()) {
            val resolvedModel = resolveModelPath(vadModelPath, "vad.onnx")
            if (resolvedModel == null) {
                Log.w(TAG, "VAD 外部模型文件读取失败")
                vad = null
                secondaryVad = null
                return
            }
            modelPath = resolvedModel
            assetManager = null
            modelSource = "外部模型"
            Log.d(TAG, "  vad: $resolvedModel")
        } else {
            modelPath = "silero_vad.onnx"
            assetManager = context.assets
            modelSource = "内置模型"
            Log.d(TAG, "使用内置 VAD 模型")
        }

        try {
            vad = Vad(
                assetManager = assetManager,
                config = createVadConfig(
                    modelPath = modelPath,
                    threshold = settings.getVadThreshold(),
                    minSilenceDuration = settings.getVadMinSilenceDuration(),
                    minSpeechDuration = settings.getVadMinSpeechDuration(),
                    maxSpeechDuration = settings.getVadMaxSpeechDuration()
                )
            )
            Log.d(TAG, "VAD 初始化成功（$modelSource）")
        } catch (e: Exception) {
            vad = null
            Log.w(TAG, "VAD 初始化失败（$modelSource）: ${e.message}")
            return
        }

        if (settings.getSpeechSecondaryVadMode() == SettingsManager.SECONDARY_VAD_MODE_NONE) {
            secondaryVad = null
            return
        }

        try {
            secondaryVad = Vad(
                assetManager = assetManager,
                config = createVadConfig(
                    modelPath = modelPath,
                    threshold = settings.getSpeechSecondaryVadThreshold(),
                    minSilenceDuration = settings.getSpeechSecondaryVadMinSilenceDuration(),
                    minSpeechDuration = settings.getSpeechSecondaryVadMinSpeechDuration(),
                    maxSpeechDuration = settings.getSpeechSecondaryVadMaxSpeechDuration()
                )
            )
            Log.d(TAG, "二次 VAD 初始化成功（$modelSource）")
        } catch (e: Exception) {
            secondaryVad = null
            Log.w(TAG, "二次 VAD 初始化失败，将仅使用第一次 VAD: ${e.message}")
        }
    }

    private fun createVadConfig(
        modelPath: String,
        threshold: Float,
        minSilenceDuration: Float,
        minSpeechDuration: Float,
        maxSpeechDuration: Float
    ): VadModelConfig {
        return VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelPath,
                threshold = threshold,
                minSilenceDuration = minSilenceDuration,
                minSpeechDuration = minSpeechDuration,
                windowSize = 512,
                maxSpeechDuration = maxSpeechDuration
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 2,
            provider = "cpu",
            debug = true
        )
    }

    /**
     * 优先将 URI 作为 Linux 文件描述符路径交给原生引擎，避免复制模型。
     * 个别内容提供方不支持文件描述符时，才退回到原有缓存方式。
     */
    private fun resolveModelPath(uriString: String, fileName: String): String? {
        val uri = Uri.parse(uriString)
        if (uri.scheme.isNullOrEmpty() || uri.scheme == "file") {
            val file = File(uri.path ?: uriString)
            return file.takeIf { it.exists() && it.isFile }?.absolutePath
        }

        try {
            val descriptor = contentResolver.openFileDescriptor(uri, "r")
            if (descriptor != null) {
                modelFileDescriptors.add(descriptor)
                val directPath = "/proc/self/fd/${descriptor.fd}"
                Log.d(TAG, "直接使用模型文件描述符: $fileName")
                return directPath
            }
        } catch (e: Exception) {
            Log.w(TAG, "无法直接打开模型 $fileName，将使用缓存", e)
        }

        return copyUriToCache(uri, fileName)?.absolutePath
    }

    /** 仅用于不支持文件描述符的内容提供方。 */
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

            val allSegments = mutableListOf<SubtitleSegment>()

            Pcm16WavReader(audioFile).use { reader ->
                val totalSamples = reader.totalSamples
                val totalDurationMs = (totalSamples * 1000L) / SAMPLE_RATE

                Log.d(
                    TAG,
                    "音频总时长: ${totalDurationMs}ms, 总采样点: $totalSamples, sampleRate=${reader.sampleRate}, channels=${reader.channels}"
                )

                // 如果启用了 VAD，先进行语音段检测
                val vadSegments = if (vad != null) {
                    Log.d(TAG, "使用 VAD 进行语音段检测...")
                    progressCallback(5, "正在检测语音段...", null)

                    val primarySegments = detectSpeechSegments(reader)
                    Log.d(TAG, "VAD 检测到 ${primarySegments.size} 个语音段")

                    val segments = if (
                        secondaryVad != null &&
                        settingsManager().getSpeechSecondaryVadMode() !=
                        SettingsManager.SECONDARY_VAD_MODE_NONE
                    ) {
                        progressCallback(5, "正在进行二次 VAD 检测...", null)
                        applySecondaryVad(reader, primarySegments)
                    } else {
                        applyRecognitionFlowVadMerge(
                            primarySegments.map {
                                SourcedVadSegment(it, VadSegmentSource.PRIMARY)
                            }
                        ).map { it.segment }
                    }

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
                    val dynamicPaddingEnabled = shouldUseDynamicPadding()
                    Log.d(TAG, "VAD 动态 padding：${if (dynamicPaddingEnabled) "启用" else "关闭"}")
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

                        val recognitionWindow = createRecognitionWindow(
                            segments = vadSegments,
                            index = index,
                            totalSamples = totalSamples,
                            dynamicPaddingEnabled = dynamicPaddingEnabled
                        )
                        val recognitionStartTimeMs =
                            (recognitionWindow.startSample * 1000L) / SAMPLE_RATE
                        val recognitionEndTimeMs =
                            ((recognitionWindow.startSample + recognitionWindow.sampleCount) * 1000L) / SAMPLE_RATE

                        Log.d(
                            TAG,
                            "识别语音段 ${index + 1}/${vadSegments.size}: " +
                                "原始 ${vadSegment.startTime}ms - ${vadSegment.endTime}ms, " +
                                "识别窗口 ${recognitionStartTimeMs}ms - ${recognitionEndTimeMs}ms"
                        )

                        val segmentData = reader.readRange(
                            startSample = recognitionWindow.startSample,
                            sampleCount = recognitionWindow.sampleCount
                        )

                        val segmentResultTimeRange = if (usesSegmentLevelResult()) {
                            SegmentTimeRange(vadSegment.startTime, vadSegment.endTime)
                        } else {
                            null
                        }
                        val recognizedSegments = recognizeSegment(
                            audioData = segmentData,
                            startTimeMs = recognitionStartTimeMs,
                            segmentResultTimeRange = segmentResultTimeRange
                        )
                        val segments = if (segmentResultTimeRange == null) {
                            // 先以 padding 后窗口为原点换算 token 时间，再回收至原始 VAD 时间轴。
                            constrainToVadRange(recognizedSegments, vadSegment)
                        } else {
                            recognizedSegments
                        }
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
                    // 没有 VAD 时逐段读取，SenseVoice NPU 使用模型自身的固定输入时长。
                    val segmentDurationSeconds = fixedSegmentDurationSeconds()
                    Log.d(
                        TAG,
                        if (isSenseVoiceNpu()) {
                            "未使用 VAD，按 SenseVoice NPU 模型固定时长 ${segmentDurationSeconds}s 分段"
                        } else {
                            "未使用 VAD，按设置的固定时长 ${segmentDurationSeconds}s 分段"
                        }
                    )

                    // 计算分段数量
                    val segmentDurationMs = segmentDurationSeconds * 1000L
                    val segmentCount = ((totalDurationMs + segmentDurationMs - 1) / segmentDurationMs).toInt()
                    val samplesPerSegment = (segmentDurationMs * SAMPLE_RATE / 1000).toInt()

                    Log.d(TAG, "将分为 $segmentCount 段处理")

                    // 逐段识别
                    for (i in 0 until segmentCount) {
                        if (isCancelled()) {
                            return Result.failure(Exception("用户取消"))
                        }

                        val startSample = i.toLong() * samplesPerSegment
                        val endSample = minOf((i + 1).toLong() * samplesPerSegment, totalSamples)
                        val sampleCount = (endSample - startSample).toInt()
                        val segmentData = reader.readRange(startSample, sampleCount)

                        val startTimeMs = (startSample * 1000L) / SAMPLE_RATE
                        val endTimeMs = (endSample * 1000L) / SAMPLE_RATE

                        progressCallback(
                            (i * 100) / segmentCount,
                            "正在识别第 ${i + 1}/$segmentCount 段...",
                            null
                        )

                        Log.d(TAG, "识别第 ${i + 1}/$segmentCount 段 (${startTimeMs}ms - ${(endSample * 1000L) / SAMPLE_RATE}ms)")

                        // 识别当前段
                        val segments = recognizeSegment(
                            audioData = segmentData,
                            startTimeMs = startTimeMs,
                            segmentResultTimeRange = if (usesSegmentLevelResult()) {
                                SegmentTimeRange(startTimeMs, endTimeMs)
                            } else {
                                null
                            }
                        )
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
            }

            val sortedSegments = allSegments.sortedBy { it.startTime }
            val finalSegments = if (senseVoiceTimestampExperiment) {
                SenseVoiceTimestampSegmenter.mergeShortGaps(
                    segments = sortedSegments.map {
                        SenseVoiceTimestampSegmenter.Segment(
                            startTimeMs = it.startTime,
                            endTimeMs = it.endTime,
                            text = it.text,
                            hardBoundaryBefore = it.timestampGapBoundaryBefore
                        )
                    },
                    splitGapMs = senseVoiceTimestampGapMs
                ).map {
                    SubtitleSegment(
                        startTime = it.startTimeMs,
                        endTime = it.endTimeMs,
                        text = it.text,
                        timestampGapBoundaryBefore = it.hardBoundaryBefore
                    )
                }
            } else {
                sortedSegments
            }

            progressCallback(100, "识别完成", null)

            Log.d(TAG, "识别完成，共生成 ${finalSegments.size} 个字幕片段")
            Result.success(finalSegments)

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
        startTimeMs: Long,
        segmentResultTimeRange: SegmentTimeRange? = null
    ): List<SubtitleSegment> {
        val maxSamples = senseVoiceNpuMaxSamples()
        if (maxSamples != null && audioData.size > maxSamples) {
            return audioData
                .asList()
                .chunked(maxSamples)
                .flatMapIndexed { index, samples ->
                    val chunkStartTimeMs = startTimeMs +
                        index.toLong() * maxSamples * 1000L / SAMPLE_RATE
                    val chunkEndTimeMs = chunkStartTimeMs +
                        samples.size.toLong() * 1000L / SAMPLE_RATE
                    val chunkResultRange = segmentResultTimeRange?.let { range ->
                        val rangeStart = maxOf(range.startTimeMs, chunkStartTimeMs)
                        val rangeEnd = minOf(range.endTimeMs, chunkEndTimeMs)
                        if (rangeEnd > rangeStart) SegmentTimeRange(rangeStart, rangeEnd) else null
                    }
                    if (segmentResultTimeRange != null && chunkResultRange == null) {
                        emptyList()
                    } else {
                        recognizeSegment(
                            audioData = samples.toFloatArray(),
                            startTimeMs = chunkStartTimeMs,
                            segmentResultTimeRange = chunkResultRange
                        )
                    }
                }
        }
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
                val hotwords = if (supportsHotwords()) buildSpeechHotwords() else ""
                if (hotwords.isEmpty()) {
                    rec.createStream()
                } else {
                    rec.createStream(hotwords)
                }
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
                if (senseVoiceTimestampExperiment) {
                    val tokenSegments = SenseVoiceTimestampSegmenter.split(
                        tokens = result.tokens,
                        timestamps = result.timestamps,
                        durations = result.durations,
                        audioStartTimeMs = startTimeMs,
                        audioEndTimeMs = startTimeMs + audioData.size.toLong() * 1000L / SAMPLE_RATE,
                        splitGapMs = senseVoiceTimestampGapMs
                    )
                    if (tokenSegments.isEmpty()) {
                        Log.w(TAG, "SenseVoice 未返回有效 token 时间戳和持续时间，跳过当前输入窗口")
                    } else {
                        segments += tokenSegments.map {
                            SubtitleSegment(
                                startTime = it.startTimeMs,
                                endTime = it.endTimeMs,
                                text = it.text,
                                timestampGapBoundaryBefore = it.hardBoundaryBefore
                            )
                        }
                        Log.d(
                            TAG,
                            "SenseVoice token 时间戳生成 ${tokenSegments.size} 个字幕段，" +
                                "切分间隔 ${senseVoiceTimestampGapMs}ms"
                        )
                    }
                } else if (usesSegmentLevelResult() && segmentResultTimeRange != null) {
                    segments.add(
                        SubtitleSegment(
                            startTime = segmentResultTimeRange.startTimeMs,
                            endTime = segmentResultTimeRange.endTimeMs,
                            text = text
                        )
                    )
                    Log.d(
                        TAG,
                        "段级识别结果: ${segmentResultTimeRange.startTimeMs}ms - " +
                            "${segmentResultTimeRange.endTimeMs}ms, 文本: ${text.take(50)}..."
                    )
                } else {
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
            }

            stream.release()

        } catch (e: Exception) {
            Log.e(TAG, "识别段失败", e)
        }

        return segments
    }

    /**
     * 按既有字幕时间范围识别音频。每个范围对应一条返回文本，适用于编辑时补录字幕。
     */
    fun recognizeRanges(
        audioFile: File,
        ranges: List<LongRange>,
        rangeContexts: List<RangeContext>? = null,
        progressCallback: (current: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): Result<List<String>> {
        return try {
            val initResult = initRecognizer()
            if (initResult.isFailure) return Result.failure(initResult.exceptionOrNull()!!)

            val texts = mutableListOf<String>()
            Pcm16WavReader(audioFile).use { reader ->
                val dynamicPaddingEnabled = shouldUseDynamicPadding()
                val sampleRanges = ranges.map { range ->
                    val startMs = range.first.coerceAtLeast(0L)
                    val endMs = range.last.coerceAtLeast(startMs)
                    val startSample = (startMs * SAMPLE_RATE / 1000L)
                        .coerceAtMost(reader.totalSamples)
                    val endSample = (endMs * SAMPLE_RATE / 1000L)
                        .coerceIn(startSample, reader.totalSamples)
                    startSample to endSample
                }

                ranges.forEachIndexed { index, range ->
                    if (isCancelled()) return Result.failure(Exception("用户取消"))

                    progressCallback(index + 1, ranges.size)
                    val startMs = range.first.coerceAtLeast(0L)
                    val endMs = range.last.coerceAtLeast(startMs)
                    val (startSample, endSample) = sampleRanges[index]
                    val rangeContext = rangeContexts?.getOrNull(index)
                    val previousEnd = if (rangeContexts != null) {
                        rangeContext?.previousEndTimeMs
                            ?.let { timeMsToSample(it, reader.totalSamples) }
                            ?.coerceIn(0L, startSample)
                            ?: 0L
                    } else if (index > 0) {
                        sampleRanges[index - 1].second.coerceIn(0L, startSample)
                    } else {
                        0L
                    }
                    val nextStart = if (rangeContexts != null) {
                        rangeContext?.nextStartTimeMs
                            ?.let { timeMsToSample(it, reader.totalSamples) }
                            ?.coerceIn(endSample, reader.totalSamples)
                            ?: reader.totalSamples
                    } else if (index < sampleRanges.lastIndex) {
                        sampleRanges[index + 1].first.coerceIn(endSample, reader.totalSamples)
                    } else {
                        reader.totalSamples
                    }
                    val recognitionWindow = createRecognitionWindow(
                        currentStart = startSample,
                        currentEnd = endSample,
                        previousEnd = previousEnd,
                        nextStart = nextStart,
                        dynamicPaddingEnabled = dynamicPaddingEnabled
                    )
                    Log.d(
                        TAG,
                        "范围识别 ${index + 1}/${ranges.size}: " +
                            "目标=${startMs}..${endMs}ms, " +
                            "窗口=${recognitionWindow.startSample * 1000L / SAMPLE_RATE}.." +
                            "${(recognitionWindow.startSample + recognitionWindow.sampleCount) * 1000L / SAMPLE_RATE}ms"
                    )
                    val recognitionStartMs = recognitionWindow.startSample * 1000L / SAMPLE_RATE
                    val text = if (recognitionWindow.sampleCount > 0) {
                        constrainToTimeRange(
                            recognizedSegments = recognizeSegment(
                                reader.readRange(
                                    recognitionWindow.startSample,
                                    recognitionWindow.sampleCount
                                ),
                                recognitionStartMs,
                                segmentResultTimeRange = if (usesSegmentLevelResult()) {
                                    SegmentTimeRange(startMs, endMs)
                                } else {
                                    null
                                }
                            ),
                            rangeStartTime = startMs,
                            rangeEndTime = endMs
                        )
                            .joinToString(separator = "") { it.text }
                            .trim()
                    } else {
                        ""
                    }
                    texts.add(text)
                }
            }
            Result.success(texts)
        } catch (e: Exception) {
            Log.e(TAG, "按时间范围识别失败", e)
            Result.failure(e)
        } finally {
            release()
        }
    }

    private fun buildSpeechHotwords(): String {
        val settings = settingsManager()
        if (!settings.isSpeechHotwordsEnabled()) return ""

        return settings.getSpeechHotwords()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
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
            secondaryVad?.release()
            secondaryVad = null
            modelFileDescriptors.forEach { descriptor ->
                try {
                    descriptor.close()
                } catch (e: Exception) {
                    Log.w(TAG, "关闭模型文件描述符失败", e)
                }
            }
            modelFileDescriptors.clear()
            Log.d(TAG, "识别器资源已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放资源失败", e)
        } finally {
            deleteQnnExecutableModelCopy()
        }
    }

    private fun mapSenseVoiceLanguage(language: String): String = when (language) {
        "中文" -> "zh"
        "英语" -> "en"
        "日语" -> "ja"
        "韩语" -> "ko"
        else -> "auto"
    }

    private fun isSenseVoice(): Boolean = modelType == SettingsManager.ASR_MODEL_SENSEVOICE

    private fun isSenseVoiceNpu(): Boolean =
        isSenseVoice() &&
            settingsManager().getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU

    private fun fixedSegmentDurationSeconds(): Int = if (isSenseVoiceNpu()) {
        settingsManager().getSenseVoiceNpuDurationSeconds()
    } else {
        settingsManager().getSpeechFixedSegmentSeconds()
    }

    private fun senseVoiceNpuMaxSamples(): Int? = if (isSenseVoiceNpu()) {
        settingsManager().getSenseVoiceNpuDurationSeconds() * SAMPLE_RATE
    } else {
        null
    }

    private fun isParakeetTdt(): Boolean = modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT

    private fun isParakeetCtc(): Boolean = modelType == SettingsManager.ASR_MODEL_PARAKEET_CTC_JA

    private fun isParakeet(): Boolean = isParakeetTdt() || isParakeetCtc()

    private fun usesSegmentLevelResult(): Boolean =
        (isSenseVoice() && !senseVoiceTimestampExperiment) || isParakeet()

    private fun shouldUseDynamicPadding(): Boolean =
        settingsManager().isSpeechVadDynamicPaddingEnabled()

    private fun isSingleFileModel(): Boolean = isSenseVoice() || isParakeetCtc()

    private fun requiresDecoder(): Boolean = !isSingleFileModel()

    private fun supportsHotwords(): Boolean =
        modelType == SettingsManager.ASR_MODEL_WHISPER || isParakeetTdt()

    private fun modelDisplayName(): String = when (modelType) {
        SettingsManager.ASR_MODEL_SENSEVOICE -> "SenseVoice"
        SettingsManager.ASR_MODEL_PARAKEET_TDT -> "Parakeet TDT"
        SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> "Parakeet CTC 日语"
        else -> "Whisper"
    }

    /**
     * 为 VAD 原始段构建识别窗口。相邻段共享的静音间隔最多各使用一半，
     * 因此 padding 不会覆盖相邻语音，避免重复识别同一段内容。
     */
    private fun createRecognitionWindow(
        segments: List<VadSegment>,
        index: Int,
        totalSamples: Long,
        dynamicPaddingEnabled: Boolean
    ): RecognitionWindow {
        val current = segments[index]
        val currentStart = current.startSample.toLong().coerceIn(0L, totalSamples)
        val currentEnd = (current.startSample.toLong() + current.sampleCount)
            .coerceIn(currentStart, totalSamples)

        val previousEnd = if (index > 0) {
            val previous = segments[index - 1]
            (previous.startSample.toLong() + previous.sampleCount)
                .coerceIn(0L, currentStart)
        } else {
            0L
        }
        val nextStart = if (index < segments.lastIndex) {
            segments[index + 1].startSample.toLong().coerceIn(currentEnd, totalSamples)
        } else {
            totalSamples
        }

        return createRecognitionWindow(
            currentStart = currentStart,
            currentEnd = currentEnd,
            previousEnd = previousEnd,
            nextStart = nextStart,
            dynamicPaddingEnabled = dynamicPaddingEnabled
        )
    }

    private fun createRecognitionWindow(
        currentStart: Long,
        currentEnd: Long,
        previousEnd: Long,
        nextStart: Long,
        dynamicPaddingEnabled: Boolean
    ): RecognitionWindow {
        val safeStart = currentStart.coerceAtLeast(0L)
        val safeEnd = currentEnd.coerceAtLeast(safeStart)
        if (!dynamicPaddingEnabled) {
            return RecognitionWindow(
                startSample = safeStart,
                sampleCount = safeSampleCount(safeEnd - safeStart)
            )
        }

        val targetPaddingSamples = (VAD_CONTEXT_PADDING_MS * SAMPLE_RATE) / 1000L
        val boundedPreviousEnd = previousEnd.coerceIn(0L, safeStart)
        val boundedNextStart = nextStart.coerceAtLeast(safeEnd)
        val leftPadding = minOf(targetPaddingSamples, (safeStart - boundedPreviousEnd) / 2)
        val rightPadding = minOf(targetPaddingSamples, (boundedNextStart - safeEnd) / 2)
        val startSample = safeStart - leftPadding
        val endSample = safeEnd + rightPadding

        return RecognitionWindow(
            startSample = startSample,
            sampleCount = safeSampleCount(endSample - startSample)
        )
    }

    private fun timeMsToSample(timeMs: Long, totalSamples: Long): Long {
        val safeTimeMs = timeMs.coerceAtLeast(0L)
        val samples = safeTimeMs / 1000L * SAMPLE_RATE +
            safeTimeMs % 1000L * SAMPLE_RATE / 1000L
        return samples.coerceAtMost(totalSamples)
    }

    private fun safeSampleCount(sampleCount: Long): Int =
        sampleCount.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    /**
     * Padding 只服务于识别上下文，字幕时间轴仍以原始 VAD 段为准。
     */
    private fun constrainToVadRange(
        recognizedSegments: List<SubtitleSegment>,
        vadSegment: VadSegment
    ): List<SubtitleSegment> = constrainToTimeRange(
        recognizedSegments = recognizedSegments,
        rangeStartTime = vadSegment.startTime,
        rangeEndTime = vadSegment.endTime
    )

    private fun constrainToTimeRange(
        recognizedSegments: List<SubtitleSegment>,
        rangeStartTime: Long,
        rangeEndTime: Long
    ): List<SubtitleSegment> {
        val constrained = recognizedSegments.mapNotNull { segment ->
            val startTime = segment.startTime.coerceIn(rangeStartTime, rangeEndTime)
            val endTime = segment.endTime.coerceIn(rangeStartTime, rangeEndTime)
            if (endTime > startTime) {
                segment.copy(startTime = startTime, endTime = endTime)
            } else {
                null
            }
        }

        if (constrained.isEmpty()) return emptyList()

        return constrained.mapIndexed { index, segment ->
            segment.copy(
                startTime = if (index == 0) rangeStartTime else segment.startTime,
                endTime = if (index == constrained.lastIndex) rangeEndTime else segment.endTime
            )
        }
    }

    private fun applySecondaryVad(
        reader: Pcm16WavReader,
        primarySegments: List<VadSegment>
    ): List<VadSegment> {
        val settings = settingsManager()
        val processedSegments = when (settings.getSpeechSecondaryVadMode()) {
            SettingsManager.SECONDARY_VAD_MODE_UNCOVERED -> {
                val uncoveredRanges = findUncoveredRanges(reader.totalSamples, primarySegments)
                Log.d(TAG, "二次 VAD 方案一：处理 ${uncoveredRanges.size} 个未划分区间")
                val secondarySegments = uncoveredRanges.flatMap { range ->
                    detectSecondarySpeechSegments(reader, range)
                }
                Log.d(TAG, "二次 VAD 方案一：新增 ${secondarySegments.size} 个语音段")
                (
                    primarySegments.map { SourcedVadSegment(it, VadSegmentSource.PRIMARY) } +
                        secondarySegments.map { SourcedVadSegment(it, VadSegmentSource.SECONDARY) }
                    ).sortedBy { it.segment.startSample }
            }

            SettingsManager.SECONDARY_VAD_MODE_WITHIN_SEGMENTS -> {
                Log.d(TAG, "二次 VAD 方案二：处理 ${primarySegments.size} 个第一次 VAD 语音段")
                val refinedSegments = primarySegments.sortedBy { it.startSample }.flatMap { segment ->
                    val range = SampleRange(
                        startSample = segment.startSample.toLong(),
                        endSample = segment.startSample.toLong() + segment.sampleCount.toLong()
                    )
                    val secondarySegments = detectSecondarySpeechSegments(reader, range)
                    if (secondarySegments.isEmpty()) {
                        listOf(SourcedVadSegment(segment, VadSegmentSource.PRIMARY))
                    } else {
                        secondarySegments.map { SourcedVadSegment(it, VadSegmentSource.SECONDARY) }
                    }
                }
                Log.d(TAG, "二次 VAD 方案二：生成 ${refinedSegments.size} 个语音段")
                refinedSegments.sortedBy { it.segment.startSample }
            }

            else -> primarySegments.map { SourcedVadSegment(it, VadSegmentSource.PRIMARY) }
        }
        val recognitionMergedSegments = applyRecognitionFlowVadMerge(processedSegments)
        if (!settings.isSpeechSecondaryVadMergeEnabled()) {
            return recognitionMergedSegments.map { it.segment }
        }

        val mergedSegments = mergeCrossSourceVadSegments(
            recognitionMergedSegments,
            settings.getSpeechSecondaryVadMergeGapMs()
        )
        Log.d(
            TAG,
            "二次 VAD 异源合并语音段：${recognitionMergedSegments.size} -> ${mergedSegments.size}，" +
                "最大间隔 ${settings.getSpeechSecondaryVadMergeGapMs()}ms"
        )
        return mergedSegments.map { it.segment }
    }

    private fun applyRecognitionFlowVadMerge(
        segments: List<SourcedVadSegment>
    ): List<SourcedVadSegment> {
        val settings = settingsManager()
        if (!settings.isSpeechVadMergeEnabled()) return segments

        val mergedSegments = mergeSameSourceVadSegments(
            segments,
            settings.getSpeechVadMergeGapMs()
        )
        Log.d(
            TAG,
            "识别流程同源合并语音段：${segments.size} -> ${mergedSegments.size}，" +
                "最大间隔 ${settings.getSpeechVadMergeGapMs()}ms"
        )
        return mergedSegments
    }

    private fun mergeSameSourceVadSegments(
        segments: List<SourcedVadSegment>,
        maxGapMs: Int
    ): List<SourcedVadSegment> = mergeVadSegments(
        segments = segments,
        maxGapMs = maxGapMs,
        canMergeSources = { current, next -> current == next }
    )

    private fun mergeCrossSourceVadSegments(
        segments: List<SourcedVadSegment>,
        maxGapMs: Int
    ): List<SourcedVadSegment> = mergeVadSegments(
        segments = segments,
        maxGapMs = maxGapMs,
        canMergeSources = { current, next -> current != next }
    )

    private fun mergeVadSegments(
        segments: List<SourcedVadSegment>,
        maxGapMs: Int,
        canMergeSources: (VadSegmentSource, VadSegmentSource) -> Boolean
    ): List<SourcedVadSegment> {
        if (segments.size < 2) return segments.sortedBy { it.segment.startSample }

        val maxGapSamples = (maxGapMs.toLong() * SAMPLE_RATE) / 1000L
        val sorted = segments.sortedBy { it.segment.startSample }
        val merged = mutableListOf<SourcedVadSegment>()
        var current = sorted.first()

        for (next in sorted.drop(1)) {
            val currentEnd = current.segment.startSample.toLong() + current.segment.sampleCount.toLong()
            val nextStart = next.segment.startSample.toLong()
            if (
                canMergeSources(current.tailSource, next.tailSource) &&
                nextStart - currentEnd <= maxGapSamples
            ) {
                val mergedStart = current.segment.startSample.toLong()
                val mergedEnd = maxOf(
                    currentEnd,
                    next.segment.startSample.toLong() + next.segment.sampleCount.toLong()
                )
                current = SourcedVadSegment(
                    segment = VadSegment(
                        startSample = mergedStart.toInt(),
                        sampleCount = (mergedEnd - mergedStart).toInt(),
                        startTime = (mergedStart * 1000L) / SAMPLE_RATE,
                        endTime = (mergedEnd * 1000L) / SAMPLE_RATE
                    ),
                    tailSource = next.tailSource
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private fun findUncoveredRanges(
        totalSamples: Long,
        segments: List<VadSegment>
    ): List<SampleRange> {
        val ranges = mutableListOf<SampleRange>()
        var coveredUntil = 0L

        for (segment in segments.sortedBy { it.startSample }) {
            val segmentStart = segment.startSample.toLong().coerceIn(0L, totalSamples)
            val segmentEnd = (segment.startSample.toLong() + segment.sampleCount.toLong())
                .coerceIn(segmentStart, totalSamples)
            if (segmentStart > coveredUntil) {
                ranges.add(SampleRange(coveredUntil, segmentStart))
            }
            coveredUntil = maxOf(coveredUntil, segmentEnd)
        }

        if (coveredUntil < totalSamples) {
            ranges.add(SampleRange(coveredUntil, totalSamples))
        }
        return ranges
    }

    private fun detectSecondarySpeechSegments(
        reader: Pcm16WavReader,
        range: SampleRange
    ): List<VadSegment> {
        val vadInstance = secondaryVad ?: return emptyList()
        val rangeStart = range.startSample.coerceIn(0L, reader.totalSamples)
        val rangeEnd = range.endSample.coerceIn(rangeStart, reader.totalSamples)
        if (rangeEnd <= rangeStart) return emptyList()

        val segments = mutableListOf<VadSegment>()
        return try {
            vadInstance.reset()
            var cursor = rangeStart
            while (cursor < rangeEnd) {
                val chunkSize = minOf(512L, rangeEnd - cursor).toInt()
                vadInstance.acceptWaveform(reader.readRange(cursor, chunkSize))
                cursor += chunkSize
                drainSecondaryVadSegments(vadInstance, rangeStart, rangeEnd, segments)
            }

            vadInstance.flush()
            drainSecondaryVadSegments(vadInstance, rangeStart, rangeEnd, segments)
            segments
        } catch (e: Exception) {
            Log.e(
                TAG,
                "二次 VAD 检测失败: $rangeStart - $rangeEnd 采样点",
                e
            )
            emptyList()
        } finally {
            runCatching { vadInstance.reset() }
        }
    }

    private fun drainSecondaryVadSegments(
        vadInstance: Vad,
        rangeStart: Long,
        rangeEnd: Long,
        output: MutableList<VadSegment>
    ) {
        while (!vadInstance.empty()) {
            val speechSegment = vadInstance.front()
            vadInstance.pop()

            val startSample = (rangeStart + speechSegment.start.toLong())
                .coerceIn(rangeStart, rangeEnd)
            val endSample = (rangeStart + speechSegment.start.toLong() + speechSegment.samples.size)
                .coerceIn(startSample, rangeEnd)
            if (endSample <= startSample || startSample > Int.MAX_VALUE) continue

            val sampleCount = (endSample - startSample).toInt()
            output.add(
                VadSegment(
                    startSample = startSample.toInt(),
                    sampleCount = sampleCount,
                    startTime = (startSample * 1000L) / SAMPLE_RATE,
                    endTime = (endSample * 1000L) / SAMPLE_RATE
                )
            )
        }
    }

    /**
     * 使用 VAD 检测语音段（流式处理）
     */
    private fun detectSpeechSegments(reader: Pcm16WavReader): List<VadSegment> {
        val segments = mutableListOf<VadSegment>()
        val vadInstance = vad ?: return segments

        try {
            var totalProcessed = 0

            Log.d(TAG, "开始流式输入音频到 VAD，总长度: ${reader.totalSamples} 采样点")

            reader.forEachChunk(chunkSamples = 512) { chunk, _ ->
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
                        sampleCount = speechSegment.samples.size,
                        startTime = startTime,
                        endTime = endTime
                    ))

                    Log.d(TAG, "VAD 检测到语音段: ${startTime}ms - ${endTime}ms (${speechSegment.samples.size} 采样点)")
                }
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
                    sampleCount = speechSegment.samples.size,
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

    private fun settingsManager(): SettingsManager {
        return SettingsManager.getInstance(context)
    }
}
