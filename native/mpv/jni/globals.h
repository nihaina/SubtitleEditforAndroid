#pragma once

#include <atomic>
#include <jni.h>
#include <mpv/client.h>

extern JavaVM *g_vm;
extern mpv_handle *g_mpv;
extern std::atomic<bool> g_event_thread_request_exit;
