LOCAL_PATH := $(call my-dir)

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
PREFIX := $(PREFIX_ARMV7)
endif
ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
PREFIX := $(PREFIX_ARM64)
endif
ifeq ($(TARGET_ARCH_ABI),x86)
PREFIX := $(PREFIX_X86)
endif
ifeq ($(TARGET_ARCH_ABI),x86_64)
PREFIX := $(PREFIX_X86_64)
endif

include $(CLEAR_VARS)
LOCAL_MODULE := avcodec
LOCAL_SRC_FILES := $(PREFIX)/lib/libavcodec.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := mpv
LOCAL_SRC_FILES := $(PREFIX)/lib/libmpv.so
LOCAL_EXPORT_C_INCLUDES := $(PREFIX)/include
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := subtitleedit_mpv
LOCAL_CFLAGS := -Werror
LOCAL_CPPFLAGS := -std=c++11
LOCAL_SRC_FILES := main.cpp render.cpp log.cpp jni_utils.cpp property.cpp event.cpp
LOCAL_LDLIBS := -llog -latomic
LOCAL_SHARED_LIBRARIES := avcodec mpv
include $(BUILD_SHARED_LIBRARY)
