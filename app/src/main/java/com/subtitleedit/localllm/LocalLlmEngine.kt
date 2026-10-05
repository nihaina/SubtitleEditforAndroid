package com.subtitleedit.localllm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * App-facing local text generation entry point.
 *
 * The service connection and native model/session are shared so the model and
 * KV cache stay resident between turns. Native compacts the session when its
 * context window is full.
 */
object LocalLlmEngine {
    data class GenerationResult(
        val text: String,
        val outputTokens: Int = 0,
        val generationMs: Long = 0L,
    )

    data class LoadResult(
        val loaded: Boolean,
        val memoryBytes: Long = 0L,
        val error: String = "",
    )

    /** Loads the selected model under explicit user control and reports progress in [0f, 1f]. */
    suspend fun load(
        context: Context,
        modelPath: String,
        repackEnabled: Boolean,
        onProgress: (Float) -> Unit = {},
    ): LoadResult {
        require(modelPath.isNotBlank()) { "本地模型路径为空" }
        val service = service(context)
        val source = openModelSource(context, modelPath)
        return try {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                val callback = object : ILocalLlmLoadCallback.Stub() {
                    override fun onProgress(progress: Float) {
                        if (continuation.isActive && !completed.get()) {
                            onProgress(progress.coerceIn(0f, 1f))
                        }
                    }

                    override fun onComplete(status: Int, memoryBytes: Long, error: String) {
                        if (!completed.compareAndSet(false, true) || !continuation.isActive) return
                        continuation.resume(
                            LoadResult(
                                loaded = status == 0,
                                memoryBytes = memoryBytes,
                                error = error,
                            )
                        )
                    }
                }
                try {
                    source.pfd.use { pfd ->
                        val requestId = service.loadModel(
                            modelPath,
                            source.path,
                            pfd,
                            repackEnabled,
                            callback,
                        )
                        if (requestId <= 0 && completed.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(LoadResult(false, error = "本地模型加载请求未启动"))
                        }
                    }
                } catch (t: Throwable) {
                    if (completed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(LoadResult(false, error = t.message ?: "无法加载本地模型"))
                    }
                }
            }
        } finally {
            // The service owns the transferred descriptor after the binder call.
        }
    }

    suspend fun isModelLoaded(
        context: Context,
        modelPath: String,
        repackEnabled: Boolean,
    ): Boolean {
        if (modelPath.isBlank()) return false
        return runCatching { service(context).isModelLoaded(modelPath, repackEnabled) }.getOrDefault(false)
    }

    suspend fun memoryBytes(context: Context): Long =
        runCatching { service(context).memoryBytes() }.getOrDefault(0L)

    suspend fun generate(
        context: Context,
        modelPath: String,
        repackEnabled: Boolean,
        contextSize: Int,
        thinkingEnabled: Boolean,
        conversation: List<LocalChatMessage>,
        onDelta: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): GenerationResult {
        require(conversation.isNotEmpty()) { "本地模型对话不能为空" }
        check(!isCancelled()) { "本地模型生成已取消" }

        val service = service(context)
        val requestId = AtomicInteger(0)
        val cancelled = AtomicBoolean(false)

        return try {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ILocalLlmCallback.Stub() {
                    override fun onDelta(text: String) {
                        if (!continuation.isActive || cancelled.get()) return
                        if (isCancelled()) {
                            cancelled.set(true)
                            requestId.get().takeIf { it > 0 }?.let { id ->
                                runCatching { service.cancel(id) }
                            }
                            return
                        }
                        onDelta(text)
                    }

                    override fun onComplete(
                        status: Int,
                        response: String,
                        error: String,
                        outputTokens: Int,
                        generationMs: Long,
                    ) {
                        if (!continuation.isActive) return
                        if (status == 0) {
                            continuation.resume(GenerationResult(response, outputTokens, generationMs))
                        } else {
                            continuation.resumeWithException(
                                IllegalStateException(error.ifBlank { "本地模型生成失败" }),
                            )
                        }
                    }
                }

                try {
                    val id = service.generate(
                        modelPath,
                        "",
                        null,
                        repackEnabled,
                        contextSize.coerceIn(MIN_CONTEXT, MAX_CONTEXT),
                        DEFAULT_TEMPERATURE,
                        conversation.map { it.role }.toTypedArray(),
                        conversation.map { it.content }.toTypedArray(),
                        thinkingEnabled,
                        callback,
                    )
                    if (id <= 0 && continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("本地模型请求未启动"),
                        )
                    } else if (id > 0) {
                        requestId.set(id)
                        if (cancelled.get() || isCancelled()) {
                            service.cancel(id)
                        }
                    }
                } catch (t: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(t)
                }

                continuation.invokeOnCancellation {
                    cancelled.set(true)
                    requestId.get().takeIf { it > 0 }?.let { id ->
                        runCatching { service.cancel(id) }
                    }
                }
            }
        } finally {
            // A cancellation predicate can become true between callbacks. The
            // service-side worker observes this through the cancel flag above.
            cancelled.set(true)
        }
    }

    /** Releases the resident model. Primarily useful for low-memory flows. */
    suspend fun unload(context: Context) {
        runCatching { service(context).unload() }
    }

    private suspend fun service(context: Context): ILocalLlmService {
        val appContext = context.applicationContext
        val deferred: CompletableDeferred<ILocalLlmService>
        var shouldBind = false
        synchronized(connectionLock) {
            val existing = serviceDeferred
            if (existing != null) {
                deferred = existing
            } else {
                deferred = CompletableDeferred()
                serviceDeferred = deferred
                shouldBind = true
            }
        }
        if (shouldBind) {
            val bound = appContext.bindService(
                Intent(appContext, LocalLlmService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) {
                synchronized(connectionLock) {
                    if (serviceDeferred === deferred) serviceDeferred = null
                }
                deferred.completeExceptionally(IllegalStateException("无法连接本地 LLM 服务"))
            }
        }
        return deferred.await()
    }

    private data class ModelSource(
        val path: String,
        val pfd: ParcelFileDescriptor?,
    )

    private fun openModelSource(context: Context, modelPath: String): ModelSource {
        val uri = runCatching { Uri.parse(modelPath) }.getOrNull()
        return when (uri?.scheme?.lowercase()) {
            "content" -> ModelSource(
                path = "",
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: error("无法打开本地模型文件"),
            )
            "file" -> ModelSource(uri.path.orEmpty(), null)
            else -> ModelSource(modelPath, null)
        }
    }

    private val connectionLock = Any()
    private var serviceDeferred: CompletableDeferred<ILocalLlmService>? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(connectionLock) {
                serviceDeferred?.complete(ILocalLlmService.Stub.asInterface(binder))
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(connectionLock) {
                serviceDeferred?.completeExceptionally(
                    IllegalStateException("本地 LLM 服务已断开"),
                )
                serviceDeferred = null
            }
        }

        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
        }

        override fun onNullBinding(name: ComponentName) {
            onServiceDisconnected(name)
        }
    }

    private const val DEFAULT_TEMPERATURE = 0f
    private const val MIN_CONTEXT = 1024
    private const val MAX_CONTEXT = 32768
}
