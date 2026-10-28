#include "bridge_capture.h"
#include "bridge_frame_buffer.h"
#include "bridge_input.h"
#include "bridge_internal.h"
#include "bridge_preview.h"

#include <sys/types.h>
#include <unistd.h>

static jstring ping(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return env->NewStringUTF("LibBridge");
}

/**
 * 切换进程 UID（保留 saved uid 以便提权）喵。
 * root 引擎发广播给 App 前临时降权到 App uid（sendingUid 匹配 App 才能收到，Android16 限制），
 * 发完立即提权回 root。调用 setresuid(ruid, euid, suid)。
 */
static jint nativeSetResUid(JNIEnv *env, jclass clazz, jint ruid, jint euid, jint suid) {
    (void) env;
    (void) clazz;
    return static_cast<jint>(setresuid(
            static_cast<uid_t>(ruid),
            static_cast<uid_t>(euid),
            static_cast<uid_t>(suid)));
}

static jobject nativeGetFrameBufferBitmap(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return CreateFrameBufferBitmap(env);
}

static void nativeSetPreviewSurface(JNIEnv *env, jclass clazz, jobject jSurface) {
    (void) clazz;
    SetPreviewSurface(env, jSurface);
}

static jobject nativeSetupNativeCapturer(JNIEnv *env, jclass clazz, jint width, jint height) {
    (void) clazz;
    return SetupNativeCapturer(env, width, height);
}

static void nativeReleaseNativeCapturer(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    ReleaseNativeCapturer();
}

static jlong nativeGetFrameCount(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return static_cast<jlong>(GetFrameCount());
}

static jdouble nativeGetFps(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return static_cast<jdouble>(GetGameFps());
}

static jdouble nativeGetScriptFps(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return static_cast<jdouble>(GetScriptFps());
}

static JNINativeMethod gMethods[] = {
        {"ping",                  "()Ljava/lang/String;",        reinterpret_cast<void *>(ping)},
        {"setupNativeCapturer",   "(II)Landroid/view/Surface;",  reinterpret_cast<void *>(nativeSetupNativeCapturer)},
        {"releaseNativeCapturer", "()V",                         reinterpret_cast<void *>(nativeReleaseNativeCapturer)},
        {"setPreviewSurface",     "(Ljava/lang/Object;)V",       reinterpret_cast<void *>(nativeSetPreviewSurface)},
        {"getFrameBufferBitmap",  "()Landroid/graphics/Bitmap;", reinterpret_cast<void *>(nativeGetFrameBufferBitmap)},
        {"getFrameCount",         "()J",                         reinterpret_cast<void *>(nativeGetFrameCount)},
        {"getFps",                "()D",                         reinterpret_cast<void *>(nativeGetFps)},
        {"getScriptFps",          "()D",                         reinterpret_cast<void *>(nativeGetScriptFps)},
        {"setResUid",             "(III)I",                      reinterpret_cast<void *>(nativeSetResUid)},
};

static constexpr char kNativeBridgeClass[] = "com/maafw/naruto/bridge/NativeBridgeLib";
static constexpr char kDriverClass[] = "com/maafw/naruto/bridge/NativeBridge";

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;

    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        return JNI_ERR;
    }

    jclass nativeLibClass = env->FindClass(kNativeBridgeClass);
    if (!nativeLibClass) {
        CheckJNIException(env, "FindClass(NativeBridgeLib)");
        return JNI_ERR;
    }

    if (env->RegisterNatives(
            nativeLibClass, gMethods,
            static_cast<jint>(sizeof(gMethods) / sizeof(gMethods[0]))) < 0) {
        CheckJNIException(env, "RegisterNatives(NativeBridgeLib)");
        env->DeleteLocalRef(nativeLibClass);
        return JNI_ERR;
    }
    env->DeleteLocalRef(nativeLibClass);

    if (!InitInputBridge(vm, env, kDriverClass)) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void) reserved;

    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK && env) {
        SetPreviewSurface(env, nullptr);
        ReleaseInputBridge(env);
    }
}