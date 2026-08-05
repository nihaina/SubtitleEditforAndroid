#include <jni.h>
#include <mpv/client.h>

#include "globals.h"
#include "jni_utils.h"
#include "log.h"

extern "C" {
jni_func(jint, setOptionString, jstring option, jstring value);
jni_func(jobject, getPropertyInt, jstring property);
jni_func(void, setPropertyInt, jstring property, jint value);
jni_func(jobject, getPropertyDouble, jstring property);
jni_func(void, setPropertyDouble, jstring property, jdouble value);
jni_func(jobject, getPropertyBoolean, jstring property);
jni_func(void, setPropertyBoolean, jstring property, jboolean value);
jni_func(jstring, getPropertyString, jstring property);
jni_func(void, setPropertyString, jstring property, jstring value);
jni_func(void, observeProperty, jstring property, jint format);
}

jni_func(jint, setOptionString, jstring optionObject, jstring valueObject) {
    CHECK_MPV_INIT();
    const char *option = env->GetStringUTFChars(optionObject, nullptr);
    const char *value = env->GetStringUTFChars(valueObject, nullptr);
    const int result = mpv_set_option_string(g_mpv, option, value);
    env->ReleaseStringUTFChars(optionObject, option);
    env->ReleaseStringUTFChars(valueObject, value);
    return result;
}

static int get_property(JNIEnv *env, jstring propertyObject, mpv_format format, void *output) {
    CHECK_MPV_INIT();
    const char *property = env->GetStringUTFChars(propertyObject, nullptr);
    const int result = mpv_get_property(g_mpv, property, format, output);
    env->ReleaseStringUTFChars(propertyObject, property);
    return result;
}

static void set_property(JNIEnv *env, jstring propertyObject, mpv_format format, void *value) {
    CHECK_MPV_INIT();
    const char *property = env->GetStringUTFChars(propertyObject, nullptr);
    const int result = mpv_set_property(g_mpv, property, format, value);
    if (result < 0) ALOGE("setting mpv property failed: %s", mpv_error_string(result));
    env->ReleaseStringUTFChars(propertyObject, property);
}

jni_func(jobject, getPropertyInt, jstring property) {
    int64_t value = 0;
    if (get_property(env, property, MPV_FORMAT_INT64, &value) < 0) return nullptr;
    return env->NewObject(java_Integer, java_Integer_init, static_cast<jint>(value));
}

jni_func(void, setPropertyInt, jstring property, jint input) {
    int64_t value = input;
    set_property(env, property, MPV_FORMAT_INT64, &value);
}

jni_func(jobject, getPropertyDouble, jstring property) {
    double value = 0;
    if (get_property(env, property, MPV_FORMAT_DOUBLE, &value) < 0) return nullptr;
    return env->NewObject(java_Double, java_Double_init, value);
}

jni_func(void, setPropertyDouble, jstring property, jdouble input) {
    double value = input;
    set_property(env, property, MPV_FORMAT_DOUBLE, &value);
}

jni_func(jobject, getPropertyBoolean, jstring property) {
    int value = 0;
    if (get_property(env, property, MPV_FORMAT_FLAG, &value) < 0) return nullptr;
    return env->NewObject(java_Boolean, java_Boolean_init, value != 0);
}

jni_func(void, setPropertyBoolean, jstring property, jboolean input) {
    int value = input == JNI_TRUE ? 1 : 0;
    set_property(env, property, MPV_FORMAT_FLAG, &value);
}

jni_func(jstring, getPropertyString, jstring property) {
    char *value = nullptr;
    if (get_property(env, property, MPV_FORMAT_STRING, &value) < 0) return nullptr;
    jstring output = env->NewStringUTF(value);
    mpv_free(value);
    return output;
}

jni_func(void, setPropertyString, jstring property, jstring input) {
    const char *value = env->GetStringUTFChars(input, nullptr);
    set_property(env, property, MPV_FORMAT_STRING, &value);
    env->ReleaseStringUTFChars(input, value);
}

jni_func(void, observeProperty, jstring propertyObject, jint format) {
    CHECK_MPV_INIT();
    const char *property = env->GetStringUTFChars(propertyObject, nullptr);
    const int result = mpv_observe_property(g_mpv, 0, property, static_cast<mpv_format>(format));
    if (result < 0) ALOGE("observing mpv property failed: %s", mpv_error_string(result));
    env->ReleaseStringUTFChars(propertyObject, property);
}
