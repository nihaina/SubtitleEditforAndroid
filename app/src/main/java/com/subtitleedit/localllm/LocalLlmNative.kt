package com.subtitleedit.localllm

/** JNI boundary used only by [LocalLlmService]. */
internal object LocalLlmNative {
    init {
        System.loadLibrary("subtitleedit_llama")
    }

    interface Callback {
        fun onDelta(text: String)
        fun onComplete(
            status: Int,
            response: String,
            error: String,
            outputTokens: Int,
            generationMs: Long,
        )
    }

    interface LoadCallback {
        fun onProgress(progress: Float)
    }

    external fun loadModelWithProgress(
        path: String,
        disableRepack: Boolean,
        backendDirectory: String,
        callback: LoadCallback,
    ): Long
    external fun modelSize(handle: Long): Long
    external fun unloadModel(handle: Long)
    external fun generate(
        handle: Long,
        contextSize: Int,
        temperature: Float,
        roles: Array<String>,
        contents: Array<String>,
        thinkingEnabled: Boolean,
        callback: Callback,
    ): Int

    external fun cancel(handle: Long)
}
