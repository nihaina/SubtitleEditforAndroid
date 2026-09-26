package com.subtitleedit.util

import android.os.Build
import android.os.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit

object ModelDownloader {
    const val QWEN3_ASR_MODELSCOPE_URL = "https://modelscope.cn/models/zengshuishui/Qwen3-ASR-onnx"
    const val SENSEVOICE_MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2"
    const val DEMIX_GENERAL_MODEL_URL =
        "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx"

    const val SENSEVOICE_DIRECTORY_NAME =
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
    const val SEPARATION_DIRECTORY_NAME = "separation"
    private const val DEMIX_MODEL_NAME = "htdemucs_fp16weights.onnx"
    private const val MIN_ONNX_SIZE = 1024L * 1024L
    private const val MAX_ARCHIVE_ENTRIES = 10_000
    private const val MAX_EXTRACTED_BYTES = 8L * 1024L * 1024L * 1024L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val fileDownloader = ResumableModelDownload(client)
    private val senseVoiceMutex = Mutex()
    private val whisperMutex = Mutex()
    private val parakeetMutex = Mutex()
    private val qwen3AsrMutex = Mutex()
    private val demixMutex = Mutex()

    data class Progress(
        val message: String,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L
    )

    data class SenseVoiceFiles(
        val model: File,
        val tokens: File
    )

    enum class SenseVoiceArchitecture {
        ONNX,
        QNN
    }

    data class SenseVoiceModelOption(
        val id: String,
        val displayName: String,
        val architecture: SenseVoiceArchitecture,
        val directoryName: String,
        val url: String,
        val sizeLabel: String,
        val durationSeconds: Int? = null
    )

    data class WhisperFiles(
        val encoder: File,
        val decoder: File,
        val tokens: File
    )

    data class WhisperModelOption(
        val id: String,
        val displayName: String,
        val directoryName: String,
        val url: String,
        val sizeLabel: String
    )

    enum class ParakeetArchitecture {
        TDT,
        CTC
    }

    data class ParakeetFiles(
        val model: File? = null,
        val encoder: File? = null,
        val decoder: File? = null,
        val joiner: File? = null,
        val tokens: File
    )

    data class ParakeetModelOption(
        val modelType: String,
        val displayName: String,
        val description: String,
        val architecture: ParakeetArchitecture,
        val directoryName: String,
        val url: String,
        val sizeLabel: String
    )

    data class Qwen3AsrFiles(
        val convFrontend: File,
        val encoder: File,
        val decoder: File,
        val tokenizer: File
    )

    data class Qwen3AsrModelOption(
        val id: String,
        val displayName: String,
        val directoryName: String,
        val remoteModelDirectory: String,
        val sizeLabel: String
    )

    val SENSEVOICE_CPU_MODEL = SenseVoiceModelOption(
        id = "cpu",
        displayName = "CPU",
        architecture = SenseVoiceArchitecture.ONNX,
        directoryName = SENSEVOICE_DIRECTORY_NAME,
        url = SENSEVOICE_MODEL_URL,
        sizeLabel = "约 1.09 GB"
    )

    val SENSEVOICE_NPU_5S_MODEL = SenseVoiceModelOption(
        id = "npu-5s",
        displayName = "sense-voice-2024-07-17-int8 5 秒",
        architecture = SenseVoiceArchitecture.QNN,
        directoryName =
            "sherpa-onnx-qnn-5-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8-android-aarch64",
        url =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn/sherpa-onnx-qnn-5-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8-android-aarch64.tar.bz2",
        sizeLabel = "约 228 MB",
        durationSeconds = 5
    )

    val SENSEVOICE_NPU_10S_MODEL = SenseVoiceModelOption(
        id = "npu-10s",
        displayName = "sense-voice-2024-07-17-int8 10 秒",
        architecture = SenseVoiceArchitecture.QNN,
        directoryName =
            "sherpa-onnx-qnn-10-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8-android-aarch64",
        url =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn/sherpa-onnx-qnn-10-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8-android-aarch64.tar.bz2",
        sizeLabel = "约 228 MB",
        durationSeconds = 10
    )

    val SENSEVOICE_NPU_MODELS = listOf(SENSEVOICE_NPU_5S_MODEL, SENSEVOICE_NPU_10S_MODEL)

    val WHISPER_MODELS = listOf(
        WhisperModelOption(
            id = "tiny",
            displayName = "Tiny",
            directoryName = "sherpa-onnx-whisper-tiny",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
            sizeLabel = "约 257 MB"
        ),
        WhisperModelOption(
            id = "small",
            displayName = "Small",
            directoryName = "sherpa-onnx-whisper-small",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2",
            sizeLabel = "约 1.25 GB"
        ),
        WhisperModelOption(
            id = "large-v3",
            displayName = "Large v3",
            directoryName = "sherpa-onnx-whisper-large-v3",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-large-v3.tar.bz2",
            sizeLabel = "约 1.8 GB"
        ),
        WhisperModelOption(
            id = "turbo",
            displayName = "Turbo",
            directoryName = "sherpa-onnx-whisper-turbo",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-turbo.tar.bz2",
            sizeLabel = "约 1 GB"
        )
    )

    val PARAKEET_TDT_MODEL = ParakeetModelOption(
        modelType = SettingsManager.ASR_MODEL_PARAKEET_TDT,
        displayName = "Parakeet TDT 0.6B v3",
        description = "支持 25 种欧洲语言，自动识别语言，包含标点、大小写和时间信息。",
        architecture = ParakeetArchitecture.TDT,
        directoryName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        sizeLabel = "约 640 MB"
    )

    val PARAKEET_CTC_JA_MODEL = ParakeetModelOption(
        modelType = SettingsManager.ASR_MODEL_PARAKEET_CTC_JA,
        displayName = "Parakeet CTC 0.6B 日语",
        description = "面向日语语音转写，采用 CTC 解码，适合日语字幕生成。",
        architecture = ParakeetArchitecture.CTC,
        directoryName = "sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8.tar.bz2",
        sizeLabel = "约 628 MB"
    )

    val PARAKEET_MODELS = listOf(PARAKEET_TDT_MODEL, PARAKEET_CTC_JA_MODEL)

    val QWEN3_ASR_0_6B_MODEL = Qwen3AsrModelOption(
        id = "0.6b",
        displayName = "0.6B INT8",
        directoryName = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        remoteModelDirectory = "model_0.6B",
        sizeLabel = "约 953 MB"
    )
    val QWEN3_ASR_1_7B_MODEL = Qwen3AsrModelOption(
        id = "1.7b",
        displayName = "1.7B INT8",
        directoryName = "qwen3-asr-onnx-1.7B-int8",
        remoteModelDirectory = "model_1.7B",
        sizeLabel = "约 2.5 GB"
    )
    val QWEN3_ASR_MODELS = listOf(QWEN3_ASR_0_6B_MODEL, QWEN3_ASR_1_7B_MODEL)
    private val qwen3TokenizerFiles = setOf(
        "chat_template.json",
        "config.json",
        "merges.txt",
        "preprocessor_config.json",
        "tokenizer_config.json",
        "vocab.json"
    )

    @Suppress("DEPRECATION")
    fun modelsDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "SubtitleEdit/models"
    )

    suspend fun downloadSenseVoice(
        option: SenseVoiceModelOption = SENSEVOICE_CPU_MODEL,
        onProgress: (Progress) -> Unit
    ): SenseVoiceFiles = senseVoiceMutex.withLock {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val modelsDir = requireModelsDirectory()
            val targetDir = File(modelsDir, option.directoryName)
            recoverSenseVoiceBackup(targetDir, option)
            findSenseVoiceFiles(targetDir, option.architecture)?.let {
                onProgress(Progress("检测到本地 SenseVoice ${option.displayName} 模型，跳过下载并直接导入"))
                return@withContext it
            }

            val archive = File(modelsDir, "${option.directoryName}.tar.bz2")
            if (!archive.isFile || archive.length() == 0L) {
                downloadFile(
                    option.url,
                    archive,
                    "正在下载 SenseVoice ${option.displayName} 模型",
                    onProgress
                )
            }

            val stagingDir = File(modelsDir, ".sensevoice_${option.id}_extracting")
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            if (!stagingDir.mkdirs()) throw IOException("无法创建 SenseVoice 解压临时目录")

            try {
                onProgress(Progress("正在解压 SenseVoice 模型", 0L, archive.length()))
                extractTarBz2(
                    archive = archive,
                    outputDir = stagingDir,
                    progressMessage = "正在解压 SenseVoice 模型",
                    shouldWrite = { isSenseVoiceRequiredFile(it, option.architecture) },
                    onProgress = onProgress
                )
                val stagedFiles = findSenseVoiceFiles(stagingDir, option.architecture)
                    ?: throw IOException(
                        if (option.architecture == SenseVoiceArchitecture.QNN) {
                            "压缩包中未找到可用的 SenseVoice QNN libmodel.so 和 tokens.txt"
                        } else {
                            "压缩包中未找到可用的 SenseVoice ONNX 模型和 tokens.txt"
                        }
                    )

                val modelRoot = directChildContaining(stagingDir, stagedFiles.model)
                val tokensRoot = directChildContaining(stagingDir, stagedFiles.tokens)
                val sourceRoot = if (modelRoot == tokensRoot) modelRoot else stagingDir
                installSenseVoiceDirectory(sourceRoot, targetDir, option)
                if (stagingDir.exists()) stagingDir.deleteRecursively()

                val installedFiles = findSenseVoiceFiles(targetDir, option.architecture)
                    ?: throw IOException("SenseVoice 模型解压完成，但模型文件校验失败")
                archive.delete()
                onProgress(Progress("SenseVoice 模型已下载并解压"))
                installedFiles
            } catch (e: CancellationException) {
                stagingDir.deleteRecursively()
                throw e
            } catch (e: Exception) {
                stagingDir.deleteRecursively()
                archive.delete()
                throw e
            }
        }
    }

    suspend fun downloadWhisper(
        option: WhisperModelOption,
        onProgress: (Progress) -> Unit
    ): WhisperFiles = whisperMutex.withLock {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val modelsDir = requireModelsDirectory()
            val targetDir = File(modelsDir, option.directoryName)
            recoverWhisperBackup(targetDir, option.id)
            findWhisperFiles(targetDir, option.id)?.let {
                onProgress(Progress("检测到本地 ${option.displayName} 模型，跳过下载并直接导入"))
                return@withContext it
            }

            val archive = File(modelsDir, "${option.directoryName}.tar.bz2")
            if (!archive.isFile || archive.length() == 0L) {
                downloadFile(
                    option.url,
                    archive,
                    "正在下载 Whisper ${option.displayName} 模型",
                    onProgress
                )
            }

            val stagingDir = File(modelsDir, ".whisper_${option.id}_extracting")
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            if (!stagingDir.mkdirs()) throw IOException("无法创建 Whisper 解压临时目录")

            try {
                val progressMessage = "正在解压 Whisper ${option.displayName} 模型"
                onProgress(Progress(progressMessage, 0L, archive.length()))
                extractTarBz2(
                    archive = archive,
                    outputDir = stagingDir,
                    progressMessage = progressMessage,
                    shouldWrite = ::isWhisperRequiredFile,
                    onProgress = onProgress
                )
                val stagedFiles = findWhisperFiles(stagingDir, option.id)
                    ?: throw IOException("压缩包中未找到可用的 Whisper encoder、decoder 和 tokens.txt")

                val roots = listOf(
                    directChildContaining(stagingDir, stagedFiles.encoder),
                    directChildContaining(stagingDir, stagedFiles.decoder),
                    directChildContaining(stagingDir, stagedFiles.tokens)
                ).distinct()
                val sourceRoot = roots.singleOrNull() ?: stagingDir
                installWhisperDirectory(sourceRoot, targetDir, option.id)
                if (stagingDir.exists()) stagingDir.deleteRecursively()

                val installedFiles = findWhisperFiles(targetDir, option.id)
                    ?: throw IOException("Whisper 模型解压完成，但模型文件校验失败")
                archive.delete()
                onProgress(Progress("Whisper ${option.displayName} 模型已下载并解压"))
                installedFiles
            } catch (e: CancellationException) {
                stagingDir.deleteRecursively()
                throw e
            } catch (e: Exception) {
                stagingDir.deleteRecursively()
                archive.delete()
                throw e
            }
        }
    }

    suspend fun downloadParakeet(
        option: ParakeetModelOption,
        onProgress: (Progress) -> Unit
    ): ParakeetFiles = parakeetMutex.withLock {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val modelsDir = requireModelsDirectory()
            val targetDir = File(modelsDir, option.directoryName)
            recoverParakeetBackup(targetDir, option)
            findParakeetFiles(targetDir, option.architecture)?.let {
                onProgress(Progress("检测到本地 ${option.displayName} 模型，跳过下载并直接导入"))
                return@withContext it
            }

            val archive = File(modelsDir, "${option.directoryName}.tar.bz2")
            if (!archive.isFile || archive.length() == 0L) {
                downloadFile(option.url, archive, "正在下载 ${option.displayName} 模型", onProgress)
            }

            val stagingDir = File(modelsDir, ".parakeet_${option.modelType}_extracting")
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            if (!stagingDir.mkdirs()) throw IOException("无法创建 Parakeet 解压临时目录")

            try {
                val progressMessage = "正在解压 ${option.displayName} 模型"
                onProgress(Progress(progressMessage, 0L, archive.length()))
                extractTarBz2(
                    archive = archive,
                    outputDir = stagingDir,
                    progressMessage = progressMessage,
                    shouldWrite = { isParakeetRequiredFile(it, option.architecture) },
                    onProgress = onProgress
                )
                val stagedFiles = findParakeetFiles(stagingDir, option.architecture)
                    ?: throw IOException("压缩包中未找到完整的 ${option.displayName} 模型文件")

                val roots = listOfNotNull(
                    stagedFiles.model,
                    stagedFiles.encoder,
                    stagedFiles.decoder,
                    stagedFiles.joiner,
                    stagedFiles.tokens
                ).map { directChildContaining(stagingDir, it) }.distinct()
                val sourceRoot = roots.singleOrNull() ?: stagingDir
                installParakeetDirectory(sourceRoot, targetDir, option)
                if (stagingDir.exists()) stagingDir.deleteRecursively()

                val installedFiles = findParakeetFiles(targetDir, option.architecture)
                    ?: throw IOException("Parakeet 模型解压完成，但模型文件校验失败")
                archive.delete()
                onProgress(Progress("${option.displayName} 模型已下载并解压"))
                installedFiles
            } catch (e: CancellationException) {
                stagingDir.deleteRecursively()
                throw e
            } catch (e: Exception) {
                stagingDir.deleteRecursively()
                archive.delete()
                throw e
            }
        }
    }

    suspend fun downloadQwen3Asr(
        option: Qwen3AsrModelOption,
        onProgress: (Progress) -> Unit
    ): Qwen3AsrFiles = qwen3AsrMutex.withLock {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            require(option in QWEN3_ASR_MODELS) { "不支持的 Qwen3-ASR 模型规格" }
            val modelsDir = requireModelsDirectory()
            val targetDir = File(modelsDir, option.directoryName)
            val stagingDir = File(modelsDir, ".qwen3_${option.id}_modelscope_downloading")
            val legacyStagingDir = File(modelsDir, ".qwen3_${option.id}_downloading")
            val legacyArchive = File(modelsDir, "${option.directoryName}.tar.bz2")
            recoverQwen3AsrBackup(targetDir, option)
            findQwen3AsrFiles(targetDir)?.let {
                onProgress(Progress("检测到本地 Qwen3-ASR ${option.displayName} 模型，跳过下载并直接导入"))
                return@withContext it
            }
            if (!stagingDir.exists() && !stagingDir.mkdirs()) {
                throw IOException("无法创建 Qwen3-ASR 模型下载目录")
            }

            try {
                val baseUrl = "$QWEN3_ASR_MODELSCOPE_URL/resolve/master"
                val modelDir = File(stagingDir, option.remoteModelDirectory)
                val tokenizerDir = File(stagingDir, "tokenizer")
                if ((!modelDir.exists() && !modelDir.mkdirs()) ||
                    (!tokenizerDir.exists() && !tokenizerDir.mkdirs())
                ) {
                    throw IOException("无法创建 Qwen3-ASR 文件目录")
                }
                val modelPath = option.remoteModelDirectory
                val files = listOf(
                    Triple("$modelPath/conv_frontend.onnx", modelDir, "conv_frontend.onnx"),
                    Triple("$modelPath/encoder.int8.onnx", modelDir, "encoder.int8.onnx"),
                    Triple("$modelPath/decoder.int8.onnx", modelDir, "decoder.int8.onnx")
                ) + qwen3TokenizerFiles.map { name -> Triple("tokenizer/$name", tokenizerDir, name) }
                files.forEach { (path, parent, name) ->
                    val destination = File(parent, name)
                    if (!destination.isFile || destination.length() == 0L) {
                        downloadFile(
                            "$baseUrl/$path",
                            destination,
                            "正在下载 Qwen3-ASR ${option.displayName}：$name",
                            onProgress
                        )
                    }
                }

                val stagedFiles = findQwen3AsrFiles(stagingDir)
                    ?: throw IOException("Qwen3-ASR 模型文件不完整或损坏")
                installQwen3AsrDirectory(stagingDir, targetDir, option)
                val installedFiles = findQwen3AsrFiles(targetDir)
                    ?: throw IOException("Qwen3-ASR 模型安装后校验失败")
                legacyStagingDir.deleteRecursively()
                legacyArchive.delete()
                onProgress(Progress("Qwen3-ASR ${option.displayName} 模型已下载并导入"))
                installedFiles
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (stagingDir.exists() && findQwen3AsrFiles(stagingDir) == null) {
                    onProgress(Progress("Qwen3-ASR 下载未完成，可重试继续下载"))
                }
                throw e
            }
        }
    }

    suspend fun downloadDemixGeneralModel(
        onProgress: (Progress) -> Unit
    ): File = demixMutex.withLock {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val modelsDir = requireModelsDirectory()
            val separationDir = File(modelsDir, SEPARATION_DIRECTORY_NAME)
            if ((!separationDir.exists() && !separationDir.mkdirs()) || !separationDir.isDirectory) {
                throw IOException("无法创建人声分离模型目录：${separationDir.absolutePath}")
            }
            val target = File(separationDir, DEMIX_MODEL_NAME)
            if (target.isFile && target.length() >= MIN_ONNX_SIZE) {
                onProgress(Progress("检测到本地人声分离模型，跳过下载并直接导入"))
                return@withContext target
            }
            val legacyTarget = File(modelsDir, DEMIX_MODEL_NAME)
            if (legacyTarget.isFile && legacyTarget.length() >= MIN_ONNX_SIZE) {
                if (!legacyTarget.renameTo(target)) {
                    legacyTarget.copyTo(target, overwrite = true)
                    legacyTarget.delete()
                }
                onProgress(Progress("已将本地人声分离模型迁移到 separation 目录并直接导入"))
                return@withContext target
            }
            downloadFile(
                DEMIX_GENERAL_MODEL_URL,
                target,
                "正在下载人声分离模型",
                onProgress,
                minimumSize = MIN_ONNX_SIZE
            )
            if (!target.isFile || target.length() < MIN_ONNX_SIZE) {
                target.delete()
                throw IOException("下载的人声分离模型文件无效")
            }
            onProgress(Progress("人声分离模型下载完成"))
            target
        }
    }

    private fun requireModelsDirectory(): File {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            throw IOException("请先授予应用“所有文件访问权限”，再下载模型")
        }
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            throw IOException("外部存储当前不可写")
        }
        val directory = modelsDirectory()
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法创建 ${directory.absolutePath}")
        }
        if (!directory.isDirectory || !directory.canWrite()) {
            throw IOException("模型目录不可写：${directory.absolutePath}")
        }
        return directory
    }

    private suspend fun downloadFile(
        url: String,
        destination: File,
        message: String,
        onProgress: (Progress) -> Unit,
        minimumSize: Long = 1L
    ) = fileDownloader.download(url, destination, message, onProgress, minimumSize)

    private suspend fun extractTarBz2(
        archive: File,
        outputDir: File,
        progressMessage: String,
        shouldWrite: (String) -> Boolean,
        onProgress: (Progress) -> Unit
    ) {
        val callerJob = currentCoroutineContext()[kotlinx.coroutines.Job]
        val parent = outputDir.parentFile
            ?: throw IOException("模型解压临时目录没有父目录")
        val payloadStaging = try {
            Files.createTempDirectory(parent.toPath(), ".subtitleedit-model-tar-").toFile()
        } catch (error: Exception) {
            throw IOException("无法创建模型 TAR 暂存目录", error)
        }
        try {
            val entries = OfficialSevenZipArchive.withCompressedTarStream(
                file = archive,
                password = null
            ) { input, expectedTarBytes ->
                StreamingTarExtractor.extract(
                    input = input,
                    staging = payloadStaging,
                    expectedTarBytes = expectedTarBytes,
                    maxEntries = MAX_ARCHIVE_ENTRIES,
                    maxBytes = MAX_EXTRACTED_BYTES,
                    validateExpectedSize = true,
                    shouldExtract = { entry ->
                        !entry.isDirectory && shouldWrite(entry.name.substringAfterLast('/'))
                    },
                    checkCancelled = { callerJob?.ensureActive() },
                    onProgress = { tarBytes, tarTotal ->
                        callerJob?.ensureActive()
                        // BZip2 has no uncompressed-size footer.  Keep reporting TAR progress,
                        // but mark the total as unknown instead of presenting TAR bytes as archive bytes.
                        val total = tarTotal.takeIf { it > 0L } ?: -1L
                        val consumed = if (total > 0L) tarBytes.coerceIn(0L, total)
                        else tarBytes.coerceAtLeast(0L)
                        onProgress(Progress(progressMessage, consumed, total))
                    }
                )
            }
            callerJob?.ensureActive()
            installSelectedTarEntries(entries, payloadStaging, outputDir)
        } finally {
            payloadStaging.deleteRecursively()
        }
    }

    /** Moves the selected flat TAR payloads into their validated archive-relative paths. */
    private fun installSelectedTarEntries(
        entries: List<StreamingTarExtractor.Entry>,
        payloadStaging: File,
        outputDir: File
    ) {
        val outputRoot = outputDir.toPath().toAbsolutePath().normalize()
        val payloadRoot = payloadStaging.toPath().toAbsolutePath().normalize()
        if (Files.isSymbolicLink(outputRoot) ||
            !Files.isDirectory(outputRoot, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("模型解压目标目录不可用")
        }

        entries.asSequence()
            .filter { !it.isDirectory && it.stagedFile != null }
            .forEach { entry ->
                val source = entry.stagedFile!!.toPath().toAbsolutePath().normalize()
                if (!source.startsWith(payloadRoot) ||
                    Files.isSymbolicLink(source) ||
                    !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) ||
                    Files.size(source) != entry.size
                ) {
                    throw IOException("TAR 条目未正确暂存：${entry.name}")
                }

                val target = outputRoot.resolve(entry.name).normalize()
                if (!target.startsWith(outputRoot) || target == outputRoot) {
                    throw IOException("TAR 条目包含不安全路径：${entry.name}")
                }
                ensureModelParentDirectories(outputRoot, target.parent)
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("模型 TAR 包含重复文件：${entry.name}")
                }
                try {
                    Files.move(source, target)
                } catch (error: Exception) {
                    throw IOException("无法安装模型 TAR 条目：${entry.name}", error)
                }
            }
    }

    private fun ensureModelParentDirectories(root: Path, directory: Path?) {
        if (directory == null || !directory.startsWith(root)) {
            throw IOException("模型 TAR 条目父目录不安全")
        }
        var current = root
        for (component in root.relativize(directory)) {
            current = current.resolve(component.toString())
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) ||
                    !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                ) {
                    throw IOException("模型 TAR 条目父路径不是安全目录")
                }
            } else {
                try {
                    Files.createDirectory(current)
                } catch (error: Exception) {
                    if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) ||
                        Files.isSymbolicLink(current)
                    ) {
                        throw IOException("无法创建模型 TAR 条目目录", error)
                    }
                }
            }
        }
    }

    private fun isSenseVoiceRequiredFile(
        fileName: String,
        architecture: SenseVoiceArchitecture
    ): Boolean = when (architecture) {
        SenseVoiceArchitecture.ONNX ->
            fileName.equals("model.int8.onnx", ignoreCase = true) ||
                fileName.equals("model.onnx", ignoreCase = true) ||
                fileName.equals("tokens.txt", ignoreCase = true)
        SenseVoiceArchitecture.QNN ->
            fileName.equals("libmodel.so", ignoreCase = true) ||
                fileName.equals("tokens.txt", ignoreCase = true)
    }

    private fun isWhisperRequiredFile(fileName: String): Boolean =
        fileName.endsWith("tokens.txt", ignoreCase = true) ||
            fileName.endsWith(".onnx", ignoreCase = true) &&
            (fileName.contains("encoder", ignoreCase = true) ||
                fileName.contains("decoder", ignoreCase = true))

    private fun isParakeetRequiredFile(
        fileName: String,
        architecture: ParakeetArchitecture
    ): Boolean = fileName.lowercase() in parakeetRequiredFileNames(architecture)

    internal fun parakeetRequiredFileNames(architecture: ParakeetArchitecture): Set<String> =
        when (architecture) {
            ParakeetArchitecture.TDT -> setOf(
                "encoder.int8.onnx",
                "decoder.int8.onnx",
                "joiner.int8.onnx",
                "tokens.txt"
            )
            ParakeetArchitecture.CTC -> setOf("model.int8.onnx", "tokens.txt")
        }

    private fun findSenseVoiceFiles(
        root: File,
        architecture: SenseVoiceArchitecture
    ): SenseVoiceFiles? {
        if (!root.isDirectory) return null
        val files = runCatching { root.walkTopDown().filter { it.isFile }.toList() }.getOrNull()
            ?: return null
        val model = files
            .filter {
                val validName = when (architecture) {
                    SenseVoiceArchitecture.ONNX ->
                        it.name.equals("model.int8.onnx", ignoreCase = true) ||
                            it.name.equals("model.onnx", ignoreCase = true)
                    SenseVoiceArchitecture.QNN ->
                        it.name.equals("libmodel.so", ignoreCase = true)
                }
                validName && it.length() >= MIN_ONNX_SIZE
            }
            .minWithOrNull(
                compareBy<File> {
                    if (architecture == SenseVoiceArchitecture.ONNX) {
                        senseVoiceModelPriority(it.name)
                    } else {
                        0
                    }
                }
                    .thenBy { it.absolutePath.length }
            ) ?: return null
        val tokens = files
            .filter { it.name.equals("tokens.txt", ignoreCase = true) && it.length() > 0L }
            .minByOrNull { tokenDistance(model, it) }
            ?: return null
        return SenseVoiceFiles(model, tokens)
    }

    internal fun findQwen3AsrFiles(root: File): Qwen3AsrFiles? {
        if (!root.isDirectory) return null
        val files = runCatching { root.walkTopDown().filter { it.isFile }.toList() }.getOrNull()
            ?: return null
        fun modelFile(name: String): File? = files.firstOrNull {
            it.name.equals(name, ignoreCase = true) && it.length() >= MIN_ONNX_SIZE
        }
        val convFrontend = modelFile("conv_frontend.onnx") ?: return null
        val encoder = modelFile("encoder.int8.onnx") ?: return null
        val decoder = modelFile("decoder.int8.onnx") ?: return null
        if (setOf(convFrontend.parentFile, encoder.parentFile, decoder.parentFile).size != 1) return null
        val tokenizer = files.asSequence()
            .filter { it.name.lowercase() in qwen3TokenizerFiles && it.length() > 0L }
            .groupBy { it.parentFile }
            .entries
            .firstOrNull { (_, children) -> children.map { it.name.lowercase() }.toSet().containsAll(qwen3TokenizerFiles) }
            ?.key ?: return null
        return Qwen3AsrFiles(convFrontend, encoder, decoder, tokenizer)
    }

    private fun installQwen3AsrDirectory(
        source: File,
        destination: File,
        option: Qwen3AsrModelOption
    ) {
        val backup = File(destination.parentFile, ".qwen3_${option.id}_backup")
        backup.deleteRecursively()
        if (destination.exists() && !destination.renameTo(backup)) {
            throw IOException("无法备份旧的 Qwen3-ASR 模型目录")
        }
        try {
            moveDirectory(source, destination)
            if (findQwen3AsrFiles(destination) == null) throw IOException("安装后的 Qwen3-ASR 文件校验失败")
            backup.deleteRecursively()
        } catch (e: Exception) {
            destination.deleteRecursively()
            if (backup.exists()) backup.renameTo(destination)
            throw e
        }
    }

    private fun recoverQwen3AsrBackup(destination: File, option: Qwen3AsrModelOption) {
        val backup = File(destination.parentFile, ".qwen3_${option.id}_backup")
        if (!backup.exists()) return
        if (findQwen3AsrFiles(destination) != null) {
            backup.deleteRecursively()
            return
        }
        if (findQwen3AsrFiles(backup) != null) {
            destination.deleteRecursively()
            if (!backup.renameTo(destination)) moveDirectory(backup, destination)
        } else {
            backup.deleteRecursively()
        }
    }

    private fun senseVoiceModelPriority(fileName: String): Int = when {
        fileName.equals("model.int8.onnx", ignoreCase = true) -> 0
        fileName.equals("model.onnx", ignoreCase = true) -> 1
        else -> 2
    }

    private fun findWhisperFiles(root: File, expectedStem: String): WhisperFiles? {
        if (!root.isDirectory) return null
        val files = runCatching { root.walkTopDown().filter { it.isFile }.toList() }.getOrNull()
            ?: return null
        data class OnnxCandidate(val file: File, val stem: String, val int8: Boolean)
        data class TokenCandidate(val file: File, val stem: String)
        data class ModelPair(val encoder: OnnxCandidate, val decoder: OnnxCandidate)

        fun parseOnnx(file: File, kind: String): OnnxCandidate? {
            if (!file.extension.equals("onnx", ignoreCase = true) || file.length() < MIN_ONNX_SIZE) {
                return null
            }
            val name = file.name.lowercase()
            val markers = listOf("-$kind", "_$kind")
            val marker = markers.firstOrNull { name.contains(it) }
            val stem = when {
                marker != null -> name.substringBefore(marker)
                name.startsWith(kind) -> ""
                else -> return null
            }
            return OnnxCandidate(file, stem, name.contains("int8"))
        }

        fun parseTokens(file: File): TokenCandidate? {
            if (!file.name.endsWith("tokens.txt", ignoreCase = true) || file.length() <= 0L) return null
            val name = file.name.lowercase()
            val stem = when {
                name == "tokens.txt" -> ""
                name.endsWith("-tokens.txt") -> name.removeSuffix("-tokens.txt")
                name.endsWith("_tokens.txt") -> name.removeSuffix("_tokens.txt")
                else -> return null
            }
            return TokenCandidate(file, stem)
        }

        val encoders = files.mapNotNull { parseOnnx(it, "encoder") }
        val decoders = files.mapNotNull { parseOnnx(it, "decoder") }
        val tokens = files.mapNotNull(::parseTokens)
        val samePrecisionPairs = encoders.flatMap { encoder ->
            decoders.filter { decoder ->
                decoder.file.parentFile == encoder.file.parentFile &&
                    decoder.stem == encoder.stem && decoder.int8 == encoder.int8
            }.map { ModelPair(encoder, it) }
        }
        val pairs = if (samePrecisionPairs.isNotEmpty()) {
            samePrecisionPairs
        } else {
            encoders.flatMap { encoder ->
                decoders.filter { decoder ->
                    decoder.file.parentFile == encoder.file.parentFile && decoder.stem == encoder.stem
                }.map { ModelPair(encoder, it) }
            }
        }
        val expected = expectedStem.lowercase()
        val sortedPairs = pairs.sortedWith(
            compareBy<ModelPair> {
                when {
                    it.encoder.stem == expected && it.encoder.int8 && it.decoder.int8 -> 0
                    it.encoder.int8 && it.decoder.int8 -> 1
                    it.encoder.stem == expected -> 2
                    else -> 3
                }
            }.thenBy { it.encoder.file.absolutePath }
        )
        for (pair in sortedPairs) {
            val token = tokens
                .filter {
                    it.file.parentFile == pair.encoder.file.parentFile &&
                        (it.stem == pair.encoder.stem || it.stem.isBlank())
                }
                .minWithOrNull(
                    compareBy<TokenCandidate> { if (it.stem == pair.encoder.stem) 0 else 1 }
                        .thenBy { it.file.absolutePath }
                ) ?: continue
            return WhisperFiles(pair.encoder.file, pair.decoder.file, token.file)
        }
        return null
    }

    private fun findParakeetFiles(
        root: File,
        architecture: ParakeetArchitecture
    ): ParakeetFiles? {
        if (!root.isDirectory) return null
        val files = runCatching { root.walkTopDown().filter { it.isFile }.toList() }.getOrNull()
            ?: return null

        fun requiredOnnx(name: String): File? = files.firstOrNull {
            it.name.equals(name, ignoreCase = true) && it.length() >= MIN_ONNX_SIZE
        }

        val tokens = files.firstOrNull {
            it.name.equals("tokens.txt", ignoreCase = true) && it.length() > 0L
        } ?: return null

        return when (architecture) {
            ParakeetArchitecture.TDT -> {
                val encoder = requiredOnnx("encoder.int8.onnx") ?: return null
                val decoder = requiredOnnx("decoder.int8.onnx") ?: return null
                val joiner = requiredOnnx("joiner.int8.onnx") ?: return null
                if (setOf(encoder.parentFile, decoder.parentFile, joiner.parentFile, tokens.parentFile).size != 1) {
                    return null
                }
                ParakeetFiles(encoder = encoder, decoder = decoder, joiner = joiner, tokens = tokens)
            }
            ParakeetArchitecture.CTC -> {
                val model = requiredOnnx("model.int8.onnx") ?: return null
                if (model.parentFile != tokens.parentFile) return null
                ParakeetFiles(model = model, tokens = tokens)
            }
        }
    }

    private fun tokenDistance(model: File, tokens: File): Int {
        if (model.parentFile == tokens.parentFile) return 0
        return kotlin.math.abs(model.absolutePath.length - tokens.absolutePath.length) + 1
    }

    private fun directChildContaining(root: File, file: File): File {
        var current = file.parentFile ?: return root
        if (current == root) return root
        while (true) {
            val parent = current.parentFile ?: break
            if (parent == root) break
            current = parent
        }
        return if (current.parentFile == root) current else root
    }

    private fun installSenseVoiceDirectory(
        source: File,
        destination: File,
        option: SenseVoiceModelOption
    ) {
        val backup = File(destination.parentFile, ".sensevoice_${option.id}_backup")
        backup.deleteRecursively()
        if (destination.exists() && !destination.renameTo(backup)) {
            throw IOException("无法备份旧的 SenseVoice 模型目录")
        }
        try {
            moveDirectory(source, destination)
            if (findSenseVoiceFiles(destination, option.architecture) == null) {
                throw IOException("安装后的 SenseVoice 模型校验失败")
            }
            backup.deleteRecursively()
        } catch (e: Exception) {
            destination.deleteRecursively()
            if (backup.exists()) backup.renameTo(destination)
            throw e
        }
    }

    private fun recoverSenseVoiceBackup(destination: File, option: SenseVoiceModelOption) {
        val backup = File(destination.parentFile, ".sensevoice_${option.id}_backup")
        if (!backup.exists()) return
        if (findSenseVoiceFiles(destination, option.architecture) != null) {
            backup.deleteRecursively()
            return
        }
        if (findSenseVoiceFiles(backup, option.architecture) != null) {
            destination.deleteRecursively()
            if (!backup.renameTo(destination)) {
                moveDirectory(backup, destination)
            }
        } else {
            backup.deleteRecursively()
        }
    }

    private fun installWhisperDirectory(source: File, destination: File, modelId: String) {
        val backup = File(destination.parentFile, ".whisper_${modelId}_backup")
        backup.deleteRecursively()
        if (destination.exists() && !destination.renameTo(backup)) {
            throw IOException("无法备份旧的 Whisper 模型目录")
        }
        try {
            moveDirectory(source, destination)
            if (findWhisperFiles(destination, modelId) == null) {
                throw IOException("安装后的 Whisper 模型校验失败")
            }
            backup.deleteRecursively()
        } catch (e: Exception) {
            destination.deleteRecursively()
            if (backup.exists()) backup.renameTo(destination)
            throw e
        }
    }

    private fun recoverWhisperBackup(destination: File, modelId: String) {
        val backup = File(destination.parentFile, ".whisper_${modelId}_backup")
        if (!backup.exists()) return
        if (findWhisperFiles(destination, modelId) != null) {
            backup.deleteRecursively()
            return
        }
        if (findWhisperFiles(backup, modelId) != null) {
            destination.deleteRecursively()
            if (!backup.renameTo(destination)) {
                moveDirectory(backup, destination)
            }
        } else {
            backup.deleteRecursively()
        }
    }

    private fun installParakeetDirectory(
        source: File,
        destination: File,
        option: ParakeetModelOption
    ) {
        val backup = File(destination.parentFile, ".parakeet_${option.modelType}_backup")
        backup.deleteRecursively()
        if (destination.exists() && !destination.renameTo(backup)) {
            throw IOException("无法备份旧的 Parakeet 模型目录")
        }
        try {
            moveDirectory(source, destination)
            if (findParakeetFiles(destination, option.architecture) == null) {
                throw IOException("安装后的 Parakeet 模型校验失败")
            }
            backup.deleteRecursively()
        } catch (e: Exception) {
            destination.deleteRecursively()
            if (backup.exists()) backup.renameTo(destination)
            throw e
        }
    }

    private fun recoverParakeetBackup(destination: File, option: ParakeetModelOption) {
        val backup = File(destination.parentFile, ".parakeet_${option.modelType}_backup")
        if (!backup.exists()) return
        if (findParakeetFiles(destination, option.architecture) != null) {
            backup.deleteRecursively()
            return
        }
        if (findParakeetFiles(backup, option.architecture) != null) {
            destination.deleteRecursively()
            if (!backup.renameTo(destination)) moveDirectory(backup, destination)
        } else {
            backup.deleteRecursively()
        }
    }

    private fun moveDirectory(source: File, destination: File) {
        if (source.renameTo(destination)) return
        if (!source.copyRecursively(destination, overwrite = true)) {
            destination.deleteRecursively()
            throw IOException("无法安装解压后的模型")
        }
        source.deleteRecursively()
    }
}
