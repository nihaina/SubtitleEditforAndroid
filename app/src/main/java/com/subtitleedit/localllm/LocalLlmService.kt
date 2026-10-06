package com.subtitleedit.localllm

import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the native model and serializes generation. It is intentionally a
 * bound service in the dedicated :llama process; the JNI boundary and opaque
 * handle keep native model failures isolated from the editor UI process.
 */
class LocalLlmService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "local-llm-generation").apply { isDaemon = true }
    }
    private val requests = ConcurrentHashMap<Int, Long>()
    private val nextRequestId = AtomicInteger(1)
    private val modelLock = Any()
    @Volatile private var modelHandle = 0L
    @Volatile private var modelKey: String? = null
    @Volatile private var modelRepackEnabled = false
    private var modelFd: ParcelFileDescriptor? = null

    private val binder = object : ILocalLlmService.Stub() {
        override fun loadModel(
            requestedModelKey: String,
            modelPath: String,
            modelFd: ParcelFileDescriptor?,
            repackEnabled: Boolean,
            callback: ILocalLlmLoadCallback,
        ): Int {
            val loadId = nextRequestId.getAndIncrement().coerceAtLeast(1)
            executor.execute {
                try {
                    val backendDirectory = LocalLlmBackendStager.resolve(this@LocalLlmService)
                    synchronized(modelLock) {
                        if (
                            modelHandle != 0L &&
                            modelKey == requestedModelKey &&
                            modelRepackEnabled == repackEnabled
                        ) {
                            // The descriptor is a binder-owned copy. The resident
                            // descriptor remains held by the first successful load.
                            modelFd?.close()
                            reportLoadProgress(callback, 1f)
                            reportLoadComplete(callback, 0, memoryBytesLocked(), "")
                            return@synchronized
                        }

                        unloadModelLocked()
                        val loadPath = if (modelFd != null) {
                            "/proc/self/fd/${modelFd.fd}"
                        } else {
                            modelPath
                        }
                        if (loadPath.isBlank()) {
                            error("本地模型路径为空")
                        }

                        val handle = LocalLlmNative.loadModelWithProgress(
                            loadPath,
                            disableRepack = !repackEnabled,
                            backendDirectory = backendDirectory,
                            callback = object : LocalLlmNative.LoadCallback {
                                override fun onProgress(progress: Float) {
                                    reportLoadProgress(callback, progress)
                                }
                            },
                        )
                        if (handle == 0L) {
                            error("llama.cpp 无法加载模型")
                        }
                        modelHandle = handle
                        modelKey = requestedModelKey
                        modelRepackEnabled = repackEnabled
                        this@LocalLlmService.modelFd = modelFd
                        reportLoadProgress(callback, 1f)
                        reportLoadComplete(callback, 0, memoryBytesLocked(), "")
                    }
                } catch (t: Throwable) {
                    modelFd?.close()
                    Log.e(TAG, "Failed to load local model", t)
                    reportLoadComplete(callback, 1, 0L, t.message ?: "无法加载本地模型")
                }
            }
            return loadId
        }

        override fun generate(
            requestedModelKey: String,
            modelPath: String,
            modelFd: ParcelFileDescriptor?,
            repackEnabled: Boolean,
            contextSize: Int,
            temperature: Float,
            roles: Array<String>,
            contents: Array<String>,
            thinkingEnabled: Boolean,
            callback: ILocalLlmCallback,
        ): Int {
            if (roles.size != contents.size || roles.isEmpty()) {
                modelFd?.close()
                return 0
            }

            // Loading is explicitly controlled from AI settings. Generation only
            // uses a model that is already resident and matches its configuration.
            modelFd?.close()
            val handle = synchronized(modelLock) {
                if (
                    modelHandle != 0L &&
                    modelKey == requestedModelKey &&
                    modelRepackEnabled == repackEnabled
                ) {
                    modelHandle
                } else {
                    0L
                }
            }
            if (handle == 0L) {
                try {
                    callback.onComplete(1, "", "本地模型未加载，请先在 AI 设置中点击“加载”", 0, 0L)
                } catch (_: Throwable) {
                    // The caller may have gone away before the rejection arrived.
                }
                return 0
            }

            val requestId = nextRequestId.getAndIncrement().coerceAtLeast(1)
            requests[requestId] = handle
            executor.execute {
                try {
                    // Keep model replacement and generation on one lock so a
                    // concurrent request cannot free the backing model.
                    synchronized(modelLock) {
                        if (modelHandle != handle) {
                            callback.onComplete(1, "", "本地模型已被另一请求替换", 0, 0L)
                            return@synchronized
                        }
                        LocalLlmNative.generate(
                            handle = handle,
                            contextSize = contextSize.coerceIn(MIN_CONTEXT, MAX_CONTEXT),
                            temperature = temperature.coerceIn(0f, 2f),
                            roles = roles,
                            contents = contents,
                            thinkingEnabled = thinkingEnabled,
                            callback = object : LocalLlmNative.Callback {
                                override fun onDelta(text: String) {
                                    try {
                                        callback.onDelta(text)
                                    } catch (_: Throwable) {
                                        LocalLlmNative.cancel(handle)
                                    }
                                }

                                override fun onComplete(
                                    status: Int,
                                    response: String,
                                    error: String,
                                    outputTokens: Int,
                                    generationMs: Long,
                                ) {
                                    try {
                                        callback.onComplete(
                                            status,
                                            response,
                                            error,
                                            outputTokens,
                                            generationMs,
                                        )
                                    } catch (_: Throwable) {
                                        // The client may have been cancelled.
                                    }
                                }
                            },
                        )
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Local generation failed", t)
                    try {
                        callback.onComplete(1, "", t.message ?: "本地模型生成失败", 0, 0L)
                    } catch (_: Throwable) {
                        // The callback may have been disposed already.
                    }
                } finally {
                    requests.remove(requestId)
                }
            }
            return requestId
        }

        override fun cancel(requestId: Int) {
            requests[requestId]?.let(LocalLlmNative::cancel)
        }

        override fun isModelLoaded(requestedModelKey: String, repackEnabled: Boolean): Boolean {
            // Generation holds modelLock for the native call. This status query must stay
            // lock-free so the settings screen can observe the resident model while a
            // request is running instead of showing the initial "not loaded" state.
            return modelHandle != 0L &&
                modelKey == requestedModelKey &&
                modelRepackEnabled == repackEnabled
        }

        override fun memoryBytes(): Long = memoryBytesLocked()

        override fun unload() {
            executor.execute { unloadModel() }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        executor.shutdownNow()
        unloadModel()
        super.onDestroy()
    }

    private fun unloadModel() = synchronized(modelLock) { unloadModelLocked() }

    private fun unloadModelLocked() {
        if (modelHandle != 0L) {
            runCatching { LocalLlmNative.unloadModel(modelHandle) }
        }
        modelHandle = 0L
        modelKey = null
        modelRepackEnabled = false
        modelFd?.close()
        modelFd = null
    }

    private fun memoryBytesLocked(): Long {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        return memoryInfo.totalPss.toLong() * 1024L
    }

    private fun reportLoadProgress(callback: ILocalLlmLoadCallback, progress: Float) {
        runCatching { callback.onProgress(progress.coerceIn(0f, 1f)) }
    }

    private fun reportLoadComplete(
        callback: ILocalLlmLoadCallback,
        status: Int,
        memoryBytes: Long,
        error: String,
    ) {
        runCatching { callback.onComplete(status, memoryBytes, error) }
    }

    companion object {
        private const val TAG = "LocalLlmService"
        private const val MIN_CONTEXT = 1024
        private const val MAX_CONTEXT = 32768
    }
}
