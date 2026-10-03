package com.maapocket.core.maa;


import com.maapocket.core.bridge.NativeBridgeLib;
import com.maapocket.core.remote.internal.ActivityUtils;
import com.maapocket.core.remote.internal.GameFpsMonitor;
import com.maapocket.core.remote.internal.PrimaryDisplayManager;
import com.maapocket.core.third.Ln;

/**
 * upcall driver
 */
public final class DriverClass {

    private static final String TAG = "DriverClass";
    private static final int FRAME_WAIT_TIMEOUT_MS = 5000;
    private static final int FRAME_WAIT_INTERVAL_MS = 50;

    private DriverClass() {
    }

    /**
     * 见 `ActivityUtils.normalisePackage`：`package/activity` 只取包名。
     * 带 `/` 的字符串喂给 `getLaunchIntentForPackage` 只会拿到 null。
     */
    private static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        int slash = raw.indexOf('/');
        String pkg = (slash >= 0 ? raw.substring(0, slash) : raw).trim();
        return pkg.isEmpty() ? null : pkg;
    }

    public static boolean startApp(String packageName, int displayId, boolean forceStop) {
        String pkg = normalise(packageName);
        if (pkg == null) {
            Ln.w(TAG + ": startApp with empty package name");
            return false;
        }
        if (displayId == PrimaryDisplayManager.DISPLAY_ID) {
            return ActivityUtils.startApp(pkg, displayId, forceStop);
        }
        boolean ret = ActivityUtils.startApp(pkg, displayId, forceStop, true);
        if (ret) {
            // 部分 ROM（如 One UI）会把游戏从虚拟屏挪回主屏，启动后校验并尝试拉回；
            // 拉不回则快速失败，避免识别对着虚拟屏空转
            ret = ActivityUtils.ensureAppOnDisplay(pkg, displayId);
            if (!ret) {
                Ln.e(TAG + ": " + pkg + " could not be pinned on display " + displayId);
            }
        }
        if (ret) {
            awaitFirstFrame();
            GameFpsMonitor.start(pkg);
        }
        return ret;
    }

    /**
     * MaaFramework 的 `StopApp` 动作。参数名在 C ABI 里叫 `client_type`，但 AndroidNative
     * ControlUnitMgr 是把 pipeline 里写的字符串**原样**透传的（`AndroidNativeControlUnitMgr.cpp:86-100`），
     * PI-V2 的 `StopApp{package}` 填的就是包名。
     *
     * 以前 `DispatchInputMessage` 没有 `STOP_GAME` 分支，落进 `default: return 0` 静默 no-op，
     * 于是所有 `CloseGame` 任务都关不掉游戏（迁移包的迁移报告把它记为 KNOWN_LIMITATION）。
     */
    public static boolean stopApp(String packageName, int displayId) {
        String pkg = normalise(packageName);
        if (pkg == null) {
            Ln.w(TAG + ": stopApp with empty package name");
            return false;
        }
        return ActivityUtils.stopApp(pkg, displayId);
    }

    private static void awaitFirstFrame() {
        long baseline = NativeBridgeLib.getFrameCount();
        int elapsed = 0;
        while (NativeBridgeLib.getFrameCount() <= baseline && elapsed < FRAME_WAIT_TIMEOUT_MS) {
            try {
                Thread.sleep(FRAME_WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            elapsed += FRAME_WAIT_INTERVAL_MS;
        }
        if (elapsed >= FRAME_WAIT_TIMEOUT_MS) {
            Ln.w(TAG + ": awaitFirstFrame timed out after " + FRAME_WAIT_TIMEOUT_MS + "ms");
        }
    }

    /* 触控是热路径（一次滑动几十次 MOVE），坐标由 MaaCore 记，这里只记失败 */
    public static boolean touchDown(int x, int y, int contact, int displayId) {
        boolean result = InputControlUtils.down(x, y, contact, displayId);
        if (!result) {
            Ln.w(TAG + ": touchDown failed (" + x + ", " + y + ", contact=" + contact + ", displayId=" + displayId + ")");
        }
        return result;
    }

    public static boolean touchMove(int x, int y, int contact, int displayId) {
        boolean result = InputControlUtils.move(x, y, contact, displayId);
        if (!result) {
            Ln.w(TAG + ": touchMove failed (" + x + ", " + y + ", contact=" + contact + ", displayId=" + displayId + ")");
        }
        return result;
    }

    public static boolean touchUp(int x, int y, int contact, int displayId) {
        boolean result = InputControlUtils.up(x, y, contact, displayId);
        if (!result) {
            Ln.w(TAG + ": touchUp failed (" + x + ", " + y + ", contact=" + contact + ", displayId=" + displayId + ")");
        }
        return result;
    }

    public static boolean keyDown(int keyCode, int displayId) {
        Ln.i(TAG + ": keyDown(keyCode=" + keyCode + ", displayId=" + displayId + ")");
        boolean result = InputControlUtils.keyDown(keyCode, displayId);
        Ln.i(TAG + ": keyDown result=" + result);
        return result;
    }

    public static boolean keyUp(int keyCode, int displayId) {
        Ln.i(TAG + ": keyUp(keyCode=" + keyCode + ", displayId=" + displayId + ")");
        boolean result = InputControlUtils.keyUp(keyCode, displayId);
        Ln.i(TAG + ": keyUp result=" + result);
        return result;
    }
}
