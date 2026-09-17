package com.subtitleedit.util

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.ProtocolException
import java.util.Properties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** The caller must serialize downloads to the same destination, including cancellation cleanup. */
internal class ResumableModelDownload(private val client: OkHttpClient) {
    suspend fun download(
        url: String,
        destination: File,
        message: String,
        onProgress: (ModelDownloader.Progress) -> Unit,
        minimumSize: Long = 1L
    ) = withContext<Unit>(Dispatchers.IO) {
        val part = File(destination.parentFile, "${destination.name}.part")
        val metadataFile = File(destination.parentFile, "${destination.name}.part.properties")
        val backup = File(destination.parentFile, "${destination.name}.backup")
        if (backup.exists()) {
            if (destination.exists()) backup.delete()
            else if (!backup.renameTo(destination)) throw IOException("无法恢复旧模型文件")
        }
        val context = currentCoroutineContext()
        var allowResume = true
        while (true) {
            context.ensureActive()
            val saved = if (allowResume) Metadata.read(metadataFile)?.takeIf {
                it.url == url && it.validator != null && part.isFile && part.length() > 0L &&
                    (it.total < 0L || part.length() <= it.total)
            } else null
            val offset = if (saved != null) part.length() else 0L
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "SubtitleEdit-Android")
                // Byte ranges refer to the stored representation, so transparent gzip is unsafe.
                .header("Accept-Encoding", "identity")
                .apply {
                    if (saved != null) {
                        header("Range", "bytes=$offset-")
                        header("If-Range", requireNotNull(saved.validator))
                    }
                }
                .build()
            val call = client.newCall(request)
            val result = execute(call) { response ->
                context.ensureActive()
                if (response.code == 416 && saved != null) {
                    val total = UNSATISFIED_RANGE.matchEntire(response.header("Content-Range").orEmpty())
                        ?.groupValues?.get(1)?.toLongOrNull()
                    // A crash can leave a complete .part file. Only promote it with server confirmation.
                    if (total == offset && saved.total == total && saved.matchesValidator(response, required = true)) {
                        onProgress(ModelDownloader.Progress(message, offset, offset))
                        return@execute TransferResult.COMPLETE
                    }
                    return@execute TransferResult.RESTART
                }
                if (response.code != 200 && response.code != 206) {
                    throw ModelDownloadHttpException(response.code)
                }
                if (response.header("Content-Encoding")?.let { !it.equals("identity", ignoreCase = true) } == true) {
                    throw IOException("模型下载响应使用了不支持的内容编码")
                }
                val body = response.body ?: throw IOException("模型下载响应为空")
                val range = if (response.code == 206) ByteRange.parse(response.header("Content-Range")) else null
                if (response.code == 206 && (range == null || range.start != offset ||
                        (saved != null && (!saved.matchesValidator(response) ||
                            (saved.total >= 0L && saved.total != range.total))) ||
                        (body.contentLength() >= 0L && body.contentLength() != range.length))) {
                    if (saved != null) return@execute TransferResult.RESTART
                    throw IOException("服务器返回了无效的下载范围")
                }
                val append = response.code == 206 && saved != null
                val start = if (append) offset else 0L
                val total = range?.total ?: body.contentLength()
                val expectedBytes = range?.length ?: body.contentLength()
                val metadata = if (append) requireNotNull(saved).copy(total = total)
                    else Metadata.fromResponse(url, total, response)
                val progressMessage = if (append) "$message（断点续传）" else message
                // Truncate before replacing metadata so a process death cannot pair old bytes with a new validator.
                FileOutputStream(part, append).buffered(BUFFER_SIZE).use { output ->
                    metadata.write(metadataFile)
                    var received = 0L
                    var lastReportAt = 0L
                    onProgress(ModelDownloader.Progress(progressMessage, start, total))
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            context.ensureActive()
                            val count = try {
                                input.read(buffer)
                            } catch (error: ProtocolException) {
                                throw EOFException("模型文件下载中断").apply { initCause(error) }
                            }
                            if (count < 0) break
                            if (count == 0) continue
                            if (expectedBytes >= 0L && count > expectedBytes - received) {
                                throw IOException("模型下载响应超出了声明的文件大小")
                            }
                            output.write(buffer, 0, count)
                            received += count
                            val now = System.nanoTime()
                            if (now - lastReportAt >= PROGRESS_INTERVAL_NANOS || start + received == total) {
                                onProgress(ModelDownloader.Progress(progressMessage, start + received, total))
                                lastReportAt = now
                            }
                        }
                    }
                    if (received <= 0L || (expectedBytes >= 0L && received != expectedBytes)) {
                        throw EOFException("模型文件下载不完整")
                    }
                    onProgress(ModelDownloader.Progress(progressMessage, start + received, total))
                }
                if (total > 0L && part.length() < total) {
                    if (metadata.validator == null) throw EOFException("服务器返回了不完整的模型文件，且无法校验续传")
                    TransferResult.CONTINUE
                } else TransferResult.COMPLETE
            }
            when (result) {
                TransferResult.RESTART -> allowResume = false
                TransferResult.CONTINUE -> allowResume = true
                TransferResult.COMPLETE -> break
            }
        }
        context.ensureActive()
        if (part.length() < minimumSize) {
            part.delete()
            metadataFile.delete()
            throw IOException("下载的模型文件大小异常")
        }
        val hadDestination = destination.exists()
        if (hadDestination && !destination.renameTo(backup)) throw IOException("无法备份旧模型文件")
        if (!part.renameTo(destination)) {
            val error = IOException("无法安装下载的模型文件")
            if (hadDestination && !backup.renameTo(destination)) {
                error.addSuppressed(IOException("无法恢复旧模型文件"))
            }
            throw error
        }
        backup.delete()
        metadataFile.delete()
    }

    private suspend fun <T> execute(call: Call, block: (Response) -> T): T {
        val completion = CompletableDeferred<T>()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                completion.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use(block)
                    completion.complete(result)
                } catch (error: Exception) {
                    completion.completeExceptionally(error)
                }
            }
        })
        try {
            return completion.await()
        } catch (error: CancellationException) {
            call.cancel()
            // Keep the caller's mutex until the old writer has closed and flushed its .part file.
            withContext(NonCancellable) { runCatching { completion.await() } }
            throw error
        }
    }

    private enum class TransferResult { RESTART, CONTINUE, COMPLETE }

    private data class ByteRange(val start: Long, val end: Long, val total: Long) {
        val length get() = end - start + 1L

        companion object {
            fun parse(value: String?): ByteRange? {
                val match = CONTENT_RANGE.matchEntire(value.orEmpty()) ?: return null
                val start = match.groupValues[1].toLongOrNull() ?: return null
                val end = match.groupValues[2].toLongOrNull() ?: return null
                val total = match.groupValues[3].toLongOrNull() ?: return null
                return ByteRange(start, end, total).takeIf { start <= end && end < total }
            }
        }
    }

    private data class Metadata(
        val url: String,
        val total: Long,
        val etag: String?,
        val lastModified: String?
    ) {
        val validator get() = etag ?: lastModified

        fun matchesValidator(response: Response, required: Boolean = false): Boolean {
            val returned = response.header(if (etag != null) "ETag" else "Last-Modified")
            return if (returned == null) !required else returned == validator
        }

        fun write(file: File) {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            val properties = Properties().apply {
                setProperty("url", url)
                setProperty("total", total.toString())
                etag?.let { setProperty("etag", it) }
                lastModified?.let { setProperty("lastModified", it) }
            }
            temporary.outputStream().use { properties.store(it, null) }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        companion object {
            fun fromResponse(url: String, total: Long, response: Response) = Metadata(
                url, total, strongEtag(response.header("ETag")),
                response.header("Last-Modified")?.takeIf { it.isNotBlank() }
            )

            fun read(file: File): Metadata? = runCatching {
                val properties = Properties().apply { file.inputStream().use { load(it) } }
                Metadata(
                    requireNotNull(properties.getProperty("url")),
                    properties.getProperty("total").toLong(),
                    strongEtag(properties.getProperty("etag")),
                    properties.getProperty("lastModified")?.takeIf { it.isNotBlank() }
                )
            }.getOrNull()

            private fun strongEtag(value: String?): String? = value?.takeIf {
                it.length >= 2 && it.startsWith('"') && it.endsWith('"')
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 1024 * 1024
        const val PROGRESS_INTERVAL_NANOS = 250_000_000L
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
        val UNSATISFIED_RANGE = Regex("bytes \\*/(\\d+)", RegexOption.IGNORE_CASE)
    }
}
