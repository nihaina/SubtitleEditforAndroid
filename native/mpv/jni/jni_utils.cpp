#define UTIL_EXTERN
#include "jni_utils.h"

bool acquire_jni_env(JavaVM *vm, JNIEnv **env) {
    const int result = vm->GetEnv(reinterpret_cast<void **>(env), JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        return vm->AttachCurrentThread(env, nullptr) == 0;
    }
    return result == JNI_OK;
}

void init_methods_cache(JNIEnv *env) {
    static bool initialized = false;
    if (initialized) return;

#define FIND_CLASS(name) reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass(name)))
    java_Integer = FIND_CLASS("java/lang/Integer");
    java_Integer_init = env->GetMethodID(java_Integer, "<init>", "(I)V");
    java_Double = FIND_CLASS("java/lang/Double");
    java_Double_init = env->GetMethodID(java_Double, "<init>", "(D)V");
    java_Boolean = FIND_CLASS("java/lang/Boolean");
    java_Boolean_init = env->GetMethodID(java_Boolean, "<init>", "(Z)V");

    mpv_MPVLib = FIND_CLASS("com/subtitleedit/mpv/MPVLib");
    mpv_MPVLib_eventProperty_S = env->GetStaticMethodID(
        mpv_MPVLib, "eventProperty", "(Ljava/lang/String;)V");
    mpv_MPVLib_eventProperty_Sb = env->GetStaticMethodID(
        mpv_MPVLib, "eventProperty", "(Ljava/lang/String;Z)V");
    mpv_MPVLib_eventProperty_Sl = env->GetStaticMethodID(
        mpv_MPVLib, "eventProperty", "(Ljava/lang/String;J)V");
    mpv_MPVLib_eventProperty_Sd = env->GetStaticMethodID(
        mpv_MPVLib, "eventProperty", "(Ljava/lang/String;D)V");
    mpv_MPVLib_eventProperty_SS = env->GetStaticMethodID(
        mpv_MPVLib, "eventProperty", "(Ljava/lang/String;Ljava/lang/String;)V");
    mpv_MPVLib_event = env->GetStaticMethodID(mpv_MPVLib, "event", "(I)V");
    mpv_MPVLib_endFileError_iS = env->GetStaticMethodID(
        mpv_MPVLib, "endFileError", "(ILjava/lang/String;)V");
    mpv_MPVLib_logMessage_SiS = env->GetStaticMethodID(
        mpv_MPVLib, "logMessage", "(Ljava/lang/String;ILjava/lang/String;)V");
#undef FIND_CLASS

    initialized = true;
}
