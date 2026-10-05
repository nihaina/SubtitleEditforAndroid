package com.subtitleedit.localllm;

/** Receives streamed text and the terminal status of one generation. */
interface ILocalLlmCallback {
    void onDelta(String text);
    void onComplete(int status, String response, String error);
}
