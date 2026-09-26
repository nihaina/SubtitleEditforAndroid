package com.subtitleedit.util

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Downloads the pinned Release volumes, verifies them, and installs the extracted ONNX pair. */
internal object Qwen3ForcedAlignerReleaseDownloader {
    const val PROJECT_URL = "https://github.com/nihaina/Qwen3-ForcedAligner-0.6B-onnx"
    private const val RELEASE_URL = "$PROJECT_URL/releases/download/v1.0.0"
    private const val ARCHIVE = "forced_aligner-onnx-fp32.7z"
    private const val GRAPH_NAME = "forced_aligner.onnx"
    private const val DATA_NAME = "$GRAPH_NAME.data"
    private const val GRAPH_SIZE = 7_757_342L
    private const val DATA_SIZE = 3_670_915_584L
    private const val GRAPH_SHA256 = "13383cd951f5e213604cac59de5ede6f83f457176f4470f21dd3845c888f3da4"
    private const val DATA_SHA256 = "5c8dca8be24a2d7dc79c037d45e1422cd64393e1aef438a091f0a56dce4f49ad"
    private const val BUFFER_SIZE = 1024 * 1024
    private val volumes = listOf(
        Volume("$ARCHIVE.001", 1_048_576_000L, "ce8dd8e27e20496ec917a8b4e0fcc407747525948a35e03e30b9353a8954eed3"),
        Volume("$ARCHIVE.002", 334_576_448L, "c724c94232377a571b2c45948f35d4b9c2f31056617eb4445f28a9299ce47af2"),
    )
    private val mutex = Mutex()
    private val downloader = ResumableModelDownload(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    )

    private data class Volume(val name: String, val size: Long, val sha256: String)

    suspend fun downloadAndInstall(
        modelDirectory: File,
        validate: (File) -> Unit,
        publish: (File) -> Unit,
        onProgress: (ModelDownloader.Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val parent = requireNotNull(modelDirectory.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "无法创建 ForcedAligner 模型目录" }
            val cache = File(parent, ".forced-aligner-v1.0.0-download")
            check(cache.isDirectory || cache.mkdirs()) { "无法创建 ForcedAligner 下载目录" }
            val totalDownload = volumes.sumOf { it.size }
            var completed = 0L
            for ((index, volume) in volumes.withIndex()) {
                currentCoroutineContext().ensureActive()
                val file = File(cache, volume.name)
                val message = "正在下载 ForcedAligner 分卷 ${index + 1}/${volumes.size}"
                if (file.length() != volume.size || sha256(file) != volume.sha256) {
                    if (file.exists() && !file.delete()) throw IOException("无法替换损坏的分卷：${volume.name}")
                    downloader.download("$RELEASE_URL/${volume.name}", file, message, { progress ->
                        onProgress(ModelDownloader.Progress(message, completed + progress.downloadedBytes, totalDownload))
                    }, volume.size)
                }
                check(file.length() == volume.size && sha256(file) == volume.sha256) {
                    file.delete()
                    "分卷 ${volume.name} 校验失败，请重试下载"
                }
                completed += volume.size
                onProgress(ModelDownloader.Progress("ForcedAligner 分卷校验完成", completed, totalDownload))
            }

            val staging = File(parent, ".forced-aligner-release-extract")
            if (staging.exists() && !staging.deleteRecursively()) throw IOException("无法清理上次解压的临时文件")
            // OfficialSevenZipArchive expands the split archive before unpacking the two model files.
            // Account for both the temporary archive and the extracted payload.
            val requiredSpace = totalDownload + GRAPH_SIZE + DATA_SIZE + 128L * 1024 * 1024
            check(parent.usableSpace >= requiredSpace) {
                "空间不足，解压还需约 5.2 GB 可用空间（旧模型在成功安装前会保留）"
            }
            check(staging.mkdir()) { "无法创建 ForcedAligner 解压目录" }
            try {
                onProgress(ModelDownloader.Progress("正在解压 ForcedAligner 分卷"))
                OfficialSevenZipArchive.extractTo(File(cache, volumes.first().name), staging, null)
                currentCoroutineContext().ensureActive()
                val graph = File(staging, GRAPH_NAME)
                val data = File(staging, DATA_NAME)
                check(staging.listFiles()?.map { it.name }?.toSet() == setOf(GRAPH_NAME, DATA_NAME) &&
                    graph.length() == GRAPH_SIZE && data.length() == DATA_SIZE
                ) { "分卷中的 ForcedAligner 模型文件不完整" }
                onProgress(ModelDownloader.Progress("正在校验 ForcedAligner 模型"))
                check(sha256(graph) == GRAPH_SHA256 && sha256(data) == DATA_SHA256) {
                    "ForcedAligner 模型校验失败，请重试下载"
                }
                currentCoroutineContext().ensureActive()
                val installed = Qwen3ForcedAlignerImporter(modelDirectory).installPrepared(
                    staging = staging,
                    validate = validate,
                    publish = publish,
                    onProgress = { onProgress(ModelDownloader.Progress(it.message)) },
                )
                withContext(NonCancellable) { cache.deleteRecursively() }
                installed
            } finally {
                withContext(NonCancellable) { staging.deleteRecursively() }
            }
        }
    }

    private suspend fun sha256(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
