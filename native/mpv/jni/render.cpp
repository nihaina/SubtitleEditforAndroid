#include <jni.h>
#include <mpv/client.h>

#include "globals.h"
#include "jni_utils.h"
#include "log.h"

extern "C" {
jni_func(void, attachSurface, jobject surfaceObject);
jni_func(void, detachSurface);
}

static jobject surface = nullptr;

jni_func(void, attachSurface, jobject surfaceObject) {
    CHECK_MPV_INIT();
    if (surface) env->DeleteGlobalRef(surface);
    surface = env->NewGlobalRef(surfaceObject);
    if (!surface) die("invalid Android surface");
    int64_t windowId = reinterpret_cast<intptr_t>(surface);
    const int result = mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &windowId);
    if (result < 0) ALOGE("setting mpv wid failed: %s", mpv_error_string(result));
}

jni_func(void, detachSurface) {
    CHECK_MPV_INIT();
    int64_t windowId = 0;
    mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &windowId);
    if (surface) {
        env->DeleteGlobalRef(surface);
        surface = nullptr;
    }
}
