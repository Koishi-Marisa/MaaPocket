package com.maapocket.core.privilege;

import android.os.Build;
import android.os.Bundle;
import android.view.Surface;

import com.maapocket.core.bridge.NativeBridgeLib;
import com.maapocket.core.third.Ln;

/**
 * 特权进程侧的「预览 Surface 轮询器」：把 app 那块 {@code SurfaceView} 的 Surface 交给原生渲染线程。
 *
 * <h2>为什么是轮询</h2>
 *
 * <p>引导协议（{@link BootstrapProtocol}）本身只有
 * <b>特权进程 → app</b> 一个方向（特权进程用 {@code getContentProviderExternal} 调
 * {@link BootstrapProvider#call}）。而预览要反过来：Surface 由 app 的 UI 产生，必须送进
 * <b>特权进程</b>。没有反向 binder，所以这里每 {@link #POLL_INTERVAL_MS} 毫秒去
 * {@link BootstrapClient#previewSurface} 问一次「现在的 Surface 是哪块、第几代」。
 *
 * <p>MAA-Meow 走的是 AIDL 双向调用（{@code service.setMonitorSurface(surface)}）；我们这台
 * 设备上 SELinux 不允许 app 主动连特权进程，所以只能让特权进程来拉。
 *
 * <h2>为什么拿 Surface 而不是像素</h2>
 *
 * <p>{@code NativeBridgeLib.setPreviewSurface(surface)} 内部用
 * {@code ANativeWindow_fromSurface} 取出 {@code IGraphicBufferProducer}，然后 EGL 直接把
 * 采集到的帧画进 app 的 {@code SurfaceView} 缓冲队列。**像素不经过 binder**，这条通道只传
 * 「哪块 Surface」这一件事，所以每秒轮询一次的代价可以忽略。
 *
 * <p>原来的做法（helper 每 200 ms 编码一张 JPEG、写进 {@code files/Maa/frames/} 或塞进
 * JSON 事件）每帧要 150 KB 且 5 fps，既费 CPU 又费闪存，而且 app 拿不到 shell 私有目录里的文件。
 *
 * <h2>生命周期</h2>
 *
 * <p>线程是守护线程，进程退出即消失。{@code RemoteEngine.shutdown()} 负责
 * {@code NativeBridgeLib.shutdownPreview()}（画一帧黑再拆窗口，否则 SurfaceView 会一直留着
 * 最后一帧，看着像还在跑）。
 */
public final class PreviewSurfaceBridge {

    /** 轮询间隔。只需跟上「用户开关预览 / 转动屏幕导致 Surface 重建」这种低频事件。 */
    private static final long POLL_INTERVAL_MS = 500L;

    private static volatile boolean started = false;

    private PreviewSurfaceBridge() {
        /* 纯静态工具类 */
    }

    /**
     * 启动轮询线程。幂等；由 {@code RemoteMain} 在引导成功后调用。
     *
     * @param packageName 宿主 app 的包名（来自 {@code --package=}）
     * @param appUid      宿主 app 的 uid（来自 {@code --uid=}），用来推导 provider 的 userId
     */
    public static void start(String packageName, int appUid) {
        if (started) {
            return;
        }
        started = true;
        Thread thread = new Thread(() -> loop(packageName, appUid), "maapocket-preview-surface");
        thread.setDaemon(true);
        thread.start();
        Ln.i("PreviewSurfaceBridge: 轮询线程已启动（每 " + POLL_INTERVAL_MS + "ms）");
    }

    private static void loop(String packageName, int appUid) {
        // 本进程内已生效的代数；-1 表示还没设过。换 SurfaceView / 换会话都会让 app 侧代数 +1。
        int lastGeneration = -1;
        // 是否已经给原生层设过一块**非空** Surface——决定收到 null 时要不要真的去清。
        boolean hasSurface = false;

        while (true) {
            try {
                Bundle reply = BootstrapClient.previewSurface(packageName, appUid);
                if (reply != null) {
                    int generation = reply.getInt(BootstrapProtocol.KEY_PREVIEW_GENERATION, -1);
                    if (generation >= 0 && generation != lastGeneration) {
                        Surface surface = parcelableSurface(reply);
                        if (surface == null && !hasSurface) {
                            // app 还没打开预览：不用去碰原生层（它可能还没 dlopen）。
                            lastGeneration = generation;
                        } else if (apply(surface)) {
                            lastGeneration = generation;
                            hasSurface = surface != null;
                        }
                    }
                }
            } catch (Throwable t) {
                // 裸进程里任何未捕获异常都会直接杀进程且死因不明；轮询更不能因为一次失败就停。
                Ln.w("PreviewSurfaceBridge: 轮询失败: " + t);
            }
            sleep();
        }
    }

    private static boolean apply(Surface surface) {
        try {
            NativeBridgeLib.setPreviewSurface(surface);
            Ln.i("PreviewSurfaceBridge: setPreviewSurface(" + (surface != null ? "surface" : "null") + ")");
            return true;
        } catch (Throwable t) {
            // 原生库还没加载（engine.setup 之前）时会抛 UnsatisfiedLinkError；
            // 不推进 lastGeneration，下一轮重试。
            Ln.w("PreviewSurfaceBridge: setPreviewSurface 失败: " + t);
            return false;
        }
    }

    /** {@code Bundle.getParcelable(String)} 在 API 33 起废弃，新重载要显式给 Class。 */
    @SuppressWarnings("deprecation")
    private static Surface parcelableSurface(Bundle bundle) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return bundle.getParcelable(BootstrapProtocol.KEY_PREVIEW_SURFACE, Surface.class);
        }
        return bundle.getParcelable(BootstrapProtocol.KEY_PREVIEW_SURFACE);
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
