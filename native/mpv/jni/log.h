#pragma once

#include <android/log.h>

#define LOG_TAG "SubtitleEdit-mpv"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)

__attribute__((noreturn)) void die(const char *msg);

#define CHECK_MPV_INIT() do { \
    if (__builtin_expect(!g_mpv, 0)) \
        die("libmpv is not initialized"); \
} while (0)
