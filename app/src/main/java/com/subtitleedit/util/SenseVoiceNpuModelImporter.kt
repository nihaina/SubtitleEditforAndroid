package com.subtitleedit.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class SenseVoiceNpuModelImporter(
    private val context: Context,
    private val contentResolver: ContentResolver
) {

    data class InstalledModel(
        val durationSeconds: Int,
        val contextBinary: File,
        val tokens: File
    )

    data class ImportResult(
        val contextBinary: File,
        val tokens: File,
        val reusedExistingBinary: Boolean
    )

    fun findInstalledModel(durationSeconds: Int): InstalledModel? {
        val (contextBinary, tokens) = SenseVoiceNpuModelPathPolicy.findLatestCompleteModel(
            managedModelsRoot(),
            durationSeconds
        ) ?: return null
        return InstalledModel(durationSeconds, contextBinary, tokens)
    }

    fun importFromUris(
        modelUri: Uri,
        tokensUri: Uri,
        durationSeconds: Int,
        onStatus: (String) -> Unit = {}
    ): ImportResult = importModel(
        openModel = {
            contentResolver.openInputStream(modelUri)
                ?: throw IOException("无法读取 SenseVoice NPU libmodel.so")
        },
        openTokens = {
            contentResolver.openInputStream(tokensUri)
                ?: throw IOException("无法读取 SenseVoice tokens.txt")
        },
        durationSeconds = durationSeconds,
        onStatus = onStatus
    )

    fun importFromFiles(
        modelFile: File,
        tokensFile: File,
        durationSeconds: Int,
        onStatus: (String) -> Unit = {}
    ): ImportResult = importModel(
        openModel = { modelFile.inputStream() },
        openTokens = { tokensFile.inputStream() },
        durationSeconds = durationSeconds,
        onStatus = onStatus
    )

    fun deleteManagedContextBinary(uriString: String, except: File? = null) {
        val selectedFile = fileFromUri(uriString) ?: return
        val managedRoot = managedModelsRoot().canonicalFile
        val candidate = runCatching { selectedFile.canonicalFile }.getOrNull() ?: return
        val keep = except?.let { runCatching { it.canonicalFile }.getOrNull() }
        if (candidate == keep || !SenseVoiceNpuModelPathPolicy.isInside(managedRoot, candidate)) return
        candidate.parentFile?.deleteRecursively()
    }

    private fun importModel(
        openModel: () -> InputStream,
        openTokens: () -> InputStream,
        durationSeconds: Int,
        onStatus: (String) -> Unit
    ): ImportResult {
        if ("arm64-v8a" !in Build.SUPPORTED_ABIS) {
            throw IllegalStateException("SenseVoice NPU 仅支持 arm64-v8a 骁龙设备")
        }
        require(durationSeconds == 5 || durationSeconds == 10) {
            "SenseVoice NPU 模型时长必须为 5 秒或 10 秒"
        }

        val importRoot = File(context.codeCacheDir, IMPORT_DIRECTORY)
        if (!importRoot.exists() && !importRoot.mkdirs()) {
            throw IOException("无法创建 SenseVoice NPU 导入缓存目录")
        }
        val sessionDir = File(
            importRoot,
            "${System.nanoTime()}-${Thread.currentThread().id}"
        )
        if (!sessionDir.mkdirs()) {
            throw IOException("无法创建 SenseVoice NPU 临时导入目录")
        }

        try {
            onStatus("正在复制 SenseVoice NPU 模型")
            val runtimeModel = File(sessionDir, MODEL_LIBRARY_NAME)
            val modelDigest = copyModelAndCalculateDigest(openModel, runtimeModel)
            if (runtimeModel.length() < MIN_MODEL_SIZE_BYTES) {
                throw IOException("SenseVoice NPU libmodel.so 文件无效")
            }

            val installedBinary = installedBinary(durationSeconds, modelDigest)
            val runtimeTokens = File(sessionDir, TOKENS_FILE_NAME)
            openTokens().buffered().use { input ->
                runtimeTokens.outputStream().buffered().use(input::copyTo)
            }
            if (runtimeTokens.length() <= 0L) {
                throw IOException("SenseVoice tokens.txt 文件无效")
            }
            val installedTokens = File(installedBinary.parentFile, TOKENS_FILE_NAME)
            if (installedBinary.isFile && installedBinary.length() > 0L) {
                installFile(runtimeTokens, installedTokens, "SenseVoice tokens.txt")
                onStatus("检测到已生成的 SenseVoice NPU BIN 模型")
                return ImportResult(
                    contextBinary = installedBinary,
                    tokens = installedTokens,
                    reusedExistingBinary = true
                )
            }

            onStatus("正在初始化 NPU 并生成 BIN 模型，首次处理可能需要较长时间")
            val generatedBinary = File(sessionDir, CONTEXT_BINARY_NAME)
            OfflineRecognizer.prependAdspLibraryPath(context.applicationInfo.nativeLibraryDir)
            val recognizer = OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = runtimeModel.absolutePath,
                            qnnConfig = QnnConfig(
                                backendLib = QNN_BACKEND_LIBRARY,
                                systemLib = QNN_SYSTEM_LIBRARY,
                                contextBinary = generatedBinary.absolutePath
                            )
                        ),
                        tokens = runtimeTokens.absolutePath,
                        numThreads = 1,
                        debug = true,
                        provider = "qnn"
                    )
                )
            )
            recognizer.release()

            if (!generatedBinary.isFile || generatedBinary.length() <= 0L) {
                throw IOException("QNN 初始化完成，但未生成有效的 model.bin")
            }

            onStatus("正在保存 SenseVoice NPU BIN 模型")
            installFile(generatedBinary, installedBinary, "SenseVoice NPU BIN 模型")
            installFile(runtimeTokens, installedTokens, "SenseVoice tokens.txt")
            return ImportResult(
                contextBinary = installedBinary,
                tokens = installedTokens,
                reusedExistingBinary = false
            )
        } catch (error: Exception) {
            throw IOException("生成 SenseVoice NPU BIN 模型失败：${error.message}", error)
        } finally {
            sessionDir.deleteRecursively()
        }
    }

    private fun copyModelAndCalculateDigest(
        openModel: () -> InputStream,
        destination: File
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        openModel().buffered().use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun installedBinary(durationSeconds: Int, modelDigest: String): File {
        val modelDir = File(
            managedModelsRoot(),
            "${durationSeconds}s-${modelDigest.take(MODEL_KEY_LENGTH)}"
        )
        return File(modelDir, CONTEXT_BINARY_NAME)
    }

    private fun installFile(source: File, destination: File, displayName: String) {
        val destinationDir = destination.parentFile
            ?: throw IOException("$displayName 目录无效")
        if (!destinationDir.exists() && !destinationDir.mkdirs()) {
            throw IOException("无法创建 $displayName 目录")
        }
        val temporary = File(destinationDir, "${destination.name}.part")
        temporary.delete()
        try {
            source.copyTo(temporary, overwrite = true)
            if (temporary.length() != source.length()) {
                throw IOException("$displayName 复制不完整")
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("无法替换旧的 $displayName")
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        } finally {
            temporary.delete()
        }
    }

    private fun managedModelsRoot(): File =
        File(context.filesDir, "$MANAGED_MODELS_DIRECTORY/$QNN_RUNTIME_VERSION")

    private fun fileFromUri(uriString: String): File? {
        if (uriString.isBlank()) return null
        val uri = Uri.parse(uriString)
        return when {
            uri.scheme == "file" -> uri.path?.let(::File)
            uri.scheme.isNullOrEmpty() -> File(uriString)
            else -> null
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 80
        const val COPY_BUFFER_SIZE = 1024 * 1024
        const val MIN_MODEL_SIZE_BYTES = 1024L * 1024L
        const val MODEL_KEY_LENGTH = 16
        const val IMPORT_DIRECTORY = "sensevoice-qnn-import"
        const val MANAGED_MODELS_DIRECTORY = "models/sensevoice-qnn"
        const val QNN_RUNTIME_VERSION = "2.40.0.251030"
        const val MODEL_LIBRARY_NAME = "libmodel.so"
        const val TOKENS_FILE_NAME = "tokens.txt"
        const val CONTEXT_BINARY_NAME = "model.bin"
        const val QNN_BACKEND_LIBRARY = "libQnnHtp.so"
        const val QNN_SYSTEM_LIBRARY = "libQnnSystem.so"
    }
}
