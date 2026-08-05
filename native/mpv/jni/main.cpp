#include <atomic>
#include <jni.h>
#include <locale.h>
#include <mpv/client.h>
#include <pthread.h>

extern "C" {
#include <libavcodec/jni.h>
}

#include "event.h"
#include "jni_utils.h"
#include "log.h"

#define ARRAY_LENGTH(array) (sizeof(array) / sizeof((array)[0]))

extern "C" {
jni_func(jboolean, create, jobject appContext);
jni_func(jint, init);
jni_func(void, destroy);
jni_func(void, command, jobjectArray arguments);
}

JavaVM *g_vm = nullptr;
mpv_handle *g_mpv = nullptr;
std::atomic<bool> g_event_thread_request_exit(false);
static pthread_t event_thread_id;
static bool event_thread_started = false;
static jobject global_app_context = nullptr;

jni_func(jboolean, create, jobject appContext) {
    setlocale(LC_NUMERIC, "C");
    if (!env->GetJavaVM(&g_vm) && g_vm) av_jni_set_java_vm(g_vm, nullptr);
    global_app_context = env->NewGlobalRef(appContext);
    if (global_app_context) av_jni_set_android_app_ctx(global_app_context, nullptr);
    init_methods_cache(env);

    if (g_mpv) return JNI_FALSE;
    g_mpv = mpv_create();
    if (!g_mpv) return JNI_FALSE;
    mpv_request_log_messages(g_mpv, "warn");
    return JNI_TRUE;
}

jni_func(jint, init) {
    if (!g_mpv) return MPV_ERROR_UNINITIALIZED;
    const int init_result = mpv_initialize(g_mpv);
    if (init_result < 0) return init_result;
    g_event_thread_request_exit = false;
    if (pthread_create(&event_thread_id, nullptr, event_thread, nullptr) != 0) {
        return MPV_ERROR_GENERIC;
    }
    event_thread_started = true;
    pthread_setname_np(event_thread_id, "subtitleedit-mpv");
    return 0;
}

jni_func(void, destroy) {
    if (!g_mpv) return;
    if (event_thread_started) {
        g_event_thread_request_exit = true;
        mpv_wakeup(g_mpv);
        pthread_join(event_thread_id, nullptr);
        event_thread_started = false;
    }
    mpv_terminate_destroy(g_mpv);
    g_mpv = nullptr;
    if (global_app_context) {
        env->DeleteGlobalRef(global_app_context);
        global_app_context = nullptr;
    }
}

jni_func(void, command, jobjectArray array) {
    CHECK_MPV_INIT();
    jstring strings[64] = {nullptr};
    const char *arguments[64] = {nullptr};
    const jsize length = env->GetArrayLength(array);
    if (length >= static_cast<jsize>(ARRAY_LENGTH(arguments))) die("too many mpv arguments");

    for (jsize index = 0; index < length; ++index) {
        strings[index] = static_cast<jstring>(env->GetObjectArrayElement(array, index));
        arguments[index] = env->GetStringUTFChars(strings[index], nullptr);
    }
    mpv_command(g_mpv, arguments);
    for (jsize index = 0; index < length; ++index) {
        env->ReleaseStringUTFChars(strings[index], arguments[index]);
        env->DeleteLocalRef(strings[index]);
    }
}
