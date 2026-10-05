package com.subtitleedit.localllm;

/** Reports asynchronous local model loading progress and completion. */
interface ILocalLlmLoadCallback {
    void onProgress(float progress);
    void onComplete(int status, long memoryBytes, String error);
}
