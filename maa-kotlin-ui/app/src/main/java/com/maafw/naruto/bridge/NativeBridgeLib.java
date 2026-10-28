package com.maafw.naruto.bridge;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.view.Surface;

/**
 * libbridge.so 的 JNI 桥（类名必须匹配原生 JNI 符号）。
 *
 * ⚠ 重要：libbridge.so 不可重编译（原版 md5 6807aea6，重编译会导致滑动卡死/启动崩溃），
 * 其 JNI 导出符号基于本类名（com_maafw_naruto_bridge_NativeBridgeLib），
 * 因此**本类名不可改**。业务代码请统一使用 {@link BridgeNativeLib}（封装本类）。
 */
@SuppressLint("PrivateApi,DiscouragedPrivateApi")
public final class NativeBridgeLib {

    private NativeBridgeLib() {
    }

    /** 探活 */
    public static native String ping();

    /** 建立原生捕获器，返回 Surface 投给虚拟屏 */
    public static native Surface setupNativeCapturer(int width, int height);

    /** 释放捕获器 */
    public static native void releaseNativeCapturer();

    /** 设置预览 Surface */
    public static native void setPreviewSurface(Object surface);

    /** 取当前帧 Bitmap（测试/截图用） */
    public static native Bitmap getFrameBufferBitmap();

    /** 帧计数（等首帧/黑屏判断） */
    public static native long getFrameCount();

    /** 游戏 FPS（原生统计） */
    public static native double getFps();

    /** 脚本识别频率（原生统计） */
    public static native double getScriptFps();

    /** 切换进程 UID（root 引擎发广播前降权/发完提权） */
    public static native int setResUid(int ruid, int euid, int suid);
}