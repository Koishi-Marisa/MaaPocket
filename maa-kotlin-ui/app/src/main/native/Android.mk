# Android.mk —— AIDE/ndk-build 编译 libbridge.so（与 CMakeLists.txt 同源，含 FPS 统计）
# 注意：去掉 -flto/--strip-all（保守编译），避免 LTO 优化影响 JNI upcall/线程 attach 行为
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := bridge
LOCAL_SRC_FILES := \
    bridge.cpp \
    bridge_capture.cpp \
    bridge_frame_buffer.cpp \
    bridge_preview.cpp \
    bridge_input.cpp \
    misc.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_CPPFLAGS := -std=c++17 -O2 -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden
LOCAL_CFLAGS := -O2 -ffunction-sections -fdata-sections -fvisibility=hidden
LOCAL_LDFLAGS := -Wl,--gc-sections
LOCAL_LDLIBS := -llog -landroid -ljnigraphics -lmediandk -lEGL -lGLESv2
include $(BUILD_SHARED_LIBRARY)

# ⚠ launcher 必须用 BUILD_EXECUTABLE（可执行程序），不能用 BUILD_SHARED_LIBRARY！
# 原因：launcher 由 App 通过 `su -c liblauncher.so ...` 作为独立程序直接执行，
#      必须生成带 PT_INTERP 的 PIE 可执行文件。若用 BUILD_SHARED_LIBRARY 编译会得到
#      无 INTERP 的纯共享库，直接运行会 SIGSEGV（段错误），导致 Root 引擎永远启动不起来
#      （见 maa_logs 中"获取 Root 引擎 binder 超时"）。
# 构建后产物在 obj/local/arm64-v8a/launcher，需改名 liblauncher.so 放入 jniLibs/arm64-v8a/。
include $(CLEAR_VARS)
LOCAL_MODULE := launcher
LOCAL_SRC_FILES := launcher.c
LOCAL_CFLAGS := -O2 -ffunction-sections -fdata-sections
LOCAL_LDFLAGS := -Wl,--gc-sections
LOCAL_LDLIBS := -llog
# -fPIE + -pie 确保生成 PIE 可执行文件（Android 强制要求）
LOCAL_CFLAGS += -fPIE
include $(BUILD_EXECUTABLE)