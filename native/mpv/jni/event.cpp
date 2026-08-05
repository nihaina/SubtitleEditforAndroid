#include <jni.h>
#include <mpv/client.h>

#include "globals.h"
#include "jni_utils.h"
#include "log.h"

static void send_property(JNIEnv *env, mpv_event_property *property) {
    jstring name = env->NewStringUTF(property->name);
    jstring stringValue = nullptr;
    switch (property->format) {
        case MPV_FORMAT_NONE:
            env->CallStaticVoidMethod(mpv_MPVLib, mpv_MPVLib_eventProperty_S, name);
            break;
        case MPV_FORMAT_FLAG:
            env->CallStaticVoidMethod(
                mpv_MPVLib, mpv_MPVLib_eventProperty_Sb, name,
                static_cast<jboolean>(*static_cast<int *>(property->data) != 0));
            break;
        case MPV_FORMAT_INT64:
            env->CallStaticVoidMethod(
                mpv_MPVLib, mpv_MPVLib_eventProperty_Sl, name,
                static_cast<jlong>(*static_cast<int64_t *>(property->data)));
            break;
        case MPV_FORMAT_DOUBLE:
            env->CallStaticVoidMethod(
                mpv_MPVLib, mpv_MPVLib_eventProperty_Sd, name,
                static_cast<jdouble>(*static_cast<double *>(property->data)));
            break;
        case MPV_FORMAT_STRING:
            stringValue = env->NewStringUTF(*static_cast<const char **>(property->data));
            env->CallStaticVoidMethod(
                mpv_MPVLib, mpv_MPVLib_eventProperty_SS, name, stringValue);
            break;
        default:
            break;
    }
    if (name) env->DeleteLocalRef(name);
    if (stringValue) env->DeleteLocalRef(stringValue);
}

static void send_log(JNIEnv *env, mpv_event_log_message *message) {
    jstring prefix = env->NewStringUTF(message->prefix);
    jstring text = env->NewStringUTF(message->text);
    env->CallStaticVoidMethod(
        mpv_MPVLib, mpv_MPVLib_logMessage_SiS,
        prefix, static_cast<jint>(message->log_level), text);
    env->DeleteLocalRef(prefix);
    env->DeleteLocalRef(text);
}

void *event_thread(void *) {
    JNIEnv *env = nullptr;
    if (!acquire_jni_env(g_vm, &env)) die("failed to attach mpv event thread");

    while (!g_event_thread_request_exit) {
        mpv_event *event = mpv_wait_event(g_mpv, -1.0);
        if (g_event_thread_request_exit) break;
        switch (event->event_id) {
            case MPV_EVENT_NONE:
                break;
            case MPV_EVENT_LOG_MESSAGE:
                send_log(env, static_cast<mpv_event_log_message *>(event->data));
                break;
            case MPV_EVENT_PROPERTY_CHANGE:
                send_property(env, static_cast<mpv_event_property *>(event->data));
                break;
            case MPV_EVENT_END_FILE: {
                auto *end_file = static_cast<mpv_event_end_file *>(event->data);
                if (end_file && end_file->reason == MPV_END_FILE_REASON_ERROR) {
                    jstring error_text = env->NewStringUTF(mpv_error_string(end_file->error));
                    env->CallStaticVoidMethod(
                        mpv_MPVLib, mpv_MPVLib_endFileError_iS,
                        static_cast<jint>(end_file->error), error_text);
                    env->DeleteLocalRef(error_text);
                } else {
                    env->CallStaticVoidMethod(
                        mpv_MPVLib, mpv_MPVLib_event, static_cast<jint>(event->event_id));
                }
                break;
            }
            default:
                env->CallStaticVoidMethod(
                    mpv_MPVLib, mpv_MPVLib_event, static_cast<jint>(event->event_id));
                break;
        }
    }

    g_vm->DetachCurrentThread();
    return nullptr;
}
