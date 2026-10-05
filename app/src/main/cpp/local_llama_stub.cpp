// 32-bit ABI placeholder. llama.cpp is intentionally packaged only for
// arm64-v8a and x86_64 because its mobile build requires 64-bit atomics and
// memory-map sizes. The service's JNI load call returns a zero handle here.
#include <jni.h>

extern "C" JNIEXPORT jlong JNICALL
Java_com_subtitleedit_localllm_LocalLlmNative_loadModel(JNIEnv *, jobject, jstring, jboolean) {
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_subtitleedit_localllm_LocalLlmNative_unloadModel(JNIEnv *, jobject, jlong) {}

extern "C" JNIEXPORT void JNICALL
Java_com_subtitleedit_localllm_LocalLlmNative_cancel(JNIEnv *, jobject, jlong) {}

extern "C" JNIEXPORT jint JNICALL
Java_com_subtitleedit_localllm_LocalLlmNative_generate(
    JNIEnv *, jobject, jlong, jint, jfloat, jobjectArray, jobjectArray, jboolean, jobject) {
    return 1;
}
