package com.maafw.naruto.third.wrappers;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.IInterface;
import android.os.SystemClock;

import java.lang.reflect.Method;

/**
 * PowerManager 系统服务隐藏 API 反射封装。
 * 提供：isScreenOn / userActivity（防系统休眠保活）/ wakeUp（亮屏）/ goToSleep（息屏）。
 * 基于 Android 公开 AIDL 接口（android.os.IPowerManager）反射调用，
 * 运行在 Shizuku shell / Root 提权进程。
 */
@SuppressLint("PrivateApi,DiscouragedPrivateApi")
public final class PowerManager {

    private final IInterface manager;

    private static final int USER_ACTIVITY_EVENT_OTHER = 0;

    private Method isScreenOnMethod;
    private Method userActivityMethod;
    private Method wakeUpMethod;
    private Method goToSleepMethod;

    static PowerManager create() {
        return new PowerManager(ServiceManager.getService("power", "android.os.IPowerManager"));
    }

    private PowerManager(IInterface manager) {
        this.manager = manager;
    }

    // ───────────── isScreenOn ─────────────
    private Method getIsScreenOnMethod() throws NoSuchMethodException {
        if (isScreenOnMethod == null) {
            if (Build.VERSION.SDK_INT >= 34) {
                isScreenOnMethod = manager.getClass().getMethod("isDisplayInteractive", int.class);
            } else {
                isScreenOnMethod = manager.getClass().getMethod("isInteractive");
            }
        }
        return isScreenOnMethod;
    }

    public boolean isScreenOn(int displayId) {
        try {
            Method method = getIsScreenOnMethod();
            if (Build.VERSION.SDK_INT >= 34) {
                return (boolean) method.invoke(manager, displayId);
            }
            return (boolean) method.invoke(manager);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    // ───────────── userActivity（防系统休眠：告诉系统用户活跃） ─────────────
    private Method getUserActivityMethod() throws NoSuchMethodException {
        if (userActivityMethod == null) {
            if (Build.VERSION.SDK_INT >= 31) {
                // userActivity(int displayId, long time, int event, int flags)
                userActivityMethod = manager.getClass().getMethod(
                        "userActivity", int.class, long.class, int.class, int.class);
            } else {
                // userActivity(long time, int event, int flags)
                userActivityMethod = manager.getClass().getMethod(
                        "userActivity", long.class, int.class, int.class);
            }
        }
        return userActivityMethod;
    }

    /** 向指定 display 上报用户活动（虚拟屏/主屏均可），防止系统因「无活动」进入休眠/熄屏 */
    public void userActivity(int displayId) {
        try {
            Method method = getUserActivityMethod();
            long time = SystemClock.uptimeMillis();
            if (Build.VERSION.SDK_INT >= 31) {
                method.invoke(manager, displayId, time, USER_ACTIVITY_EVENT_OTHER, 0);
            } else {
                method.invoke(manager, time, USER_ACTIVITY_EVENT_OTHER, 0);
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    // ───────────── wakeUp（亮屏；比注入 KEYCODE_WAKEUP 可靠，不经过按键策略） ─────────────
    private static final int WAKE_REASON_APPLICATION = 2;

    private Method getWakeUpMethod() throws NoSuchMethodException {
        if (wakeUpMethod == null) {
            Class<?> cls = manager.getClass();
            try {
                // API 29+: wakeUp(long time, int reason, String details, String opPackageName)
                wakeUpMethod = cls.getMethod("wakeUp", long.class, int.class, String.class, String.class);
            } catch (NoSuchMethodException e1) {
                // API 28: wakeUp(long time, String reason, String opPackageName)
                wakeUpMethod = cls.getMethod("wakeUp", long.class, String.class, String.class);
            }
        }
        return wakeUpMethod;
    }

    /** 亮屏（默认 display 0，主屏） */
    public boolean wakeUp() {
        try {
            Method method = getWakeUpMethod();
            long time = SystemClock.uptimeMillis();
            if (method.getParameterTypes().length == 4) {
                method.invoke(manager, time, WAKE_REASON_APPLICATION, "maafw:wake", "com.maafw.naruto");
            } else {
                method.invoke(manager, time, "maafw:wake", "com.maafw.naruto");
            }
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    // ───────────── goToSleep（息屏） ─────────────
    private static final int GO_TO_SLEEP_REASON_APPLICATION = 0;

    private Method getGoToSleepMethod() throws NoSuchMethodException {
        if (goToSleepMethod == null) {
            Class<?> cls = manager.getClass();
            try {
                // API 29+: goToSleep(long time, int reason, int flags)
                goToSleepMethod = cls.getMethod("goToSleep", long.class, int.class, int.class);
            } catch (NoSuchMethodException e1) {
                // API 28: goToSleep(long time, int reason)
                goToSleepMethod = cls.getMethod("goToSleep", long.class, int.class);
            }
        }
        return goToSleepMethod;
    }

    public boolean goToSleep() {
        try {
            Method method = getGoToSleepMethod();
            long time = SystemClock.uptimeMillis();
            if (method.getParameterTypes().length == 3) {
                method.invoke(manager, time, GO_TO_SLEEP_REASON_APPLICATION, 0);
            } else {
                method.invoke(manager, time, GO_TO_SLEEP_REASON_APPLICATION);
            }
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}