package com.subtitleedit.localllm;

import android.os.ParcelFileDescriptor;
import com.subtitleedit.localllm.ILocalLlmCallback;
import com.subtitleedit.localllm.ILocalLlmLoadCallback;

/** Minimal text-only llama.cpp service contract. */
interface ILocalLlmService {
    /** Starts loading a model under explicit user control. */
    int loadModel(
        String modelKey,
        String modelPath,
        in ParcelFileDescriptor modelFd,
        boolean repackEnabled,
        ILocalLlmLoadCallback callback
    );

    /** Returns a stable positive request id, or 0 if the request was rejected. */
    int generate(
        String modelKey,
        String modelPath,
        in ParcelFileDescriptor modelFd,
        boolean repackEnabled,
        int contextSize,
        float temperature,
        in String[] roles,
        in String[] contents,
        boolean thinkingEnabled,
        ILocalLlmCallback callback
    );

    /** Idempotently cancels a running request. */
    void cancel(int requestId);

    /** Returns whether the requested model is currently resident. */
    boolean isModelLoaded(String modelKey, boolean repackEnabled);

    /** Returns the current process PSS estimate in bytes. */
    long memoryBytes();

    /** Releases the currently loaded model. */
    void unload();
}
