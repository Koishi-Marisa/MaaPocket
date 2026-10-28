package com.maafw.naruto.third.wrappers;

import android.annotation.SuppressLint;
import android.os.IInterface;

import java.lang.reflect.Method;

/**
 * WindowManager 隐藏 API 包装。
 */
@SuppressLint("PrivateApi,DiscouragedPrivateApi")
public final class WindowManager {

    private final IInterface manager;
    private Method getRotationMethod;
    private Method isKeyguardLockedMethod;
    private Method isKeyguardSecureMethod;
    private Method dismissKeyguardMethod;
    private Method lockNowMethod;
    private Method freezeRotationMethod;
    private Method setForcedDisplaySizeMethod;
    private Method clearForcedDisplaySizeMethod;

    static WindowManager create() {
        try {
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String.class);
            getServiceMethod.setAccessible(true);
            Object binder = getServiceMethod.invoke(null, "window");

            Class<?> stubClass = Class.forName("android.view.IWindowManager$Stub");
            Method asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder.class);
            IInterface manager = (IInterface) asInterface.invoke(null, binder);
            return new WindowManager(manager);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private WindowManager(IInterface manager) {
        this.manager = manager;
    }

    public int getRotation() {
        try {
            if (getRotationMethod == null) {
                getRotationMethod = manager.getClass().getMethod("getRotation");
            }
            return (int) getRotationMethod.invoke(manager);
        } catch (Exception e) {
            return -1;
        }
    }

    // ───────────── 锁屏状态查询与解锁 ─────────────

    /** 当前是否处于锁屏状态（null=API 不支持） */
    public Boolean isKeyguardLocked() {
        try {
            if (isKeyguardLockedMethod == null) {
                isKeyguardLockedMethod = manager.getClass().getMethod("isKeyguardLocked");
            }
            return (Boolean) isKeyguardLockedMethod.invoke(manager);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** 锁屏是否安全（需要凭据；null=API 不支持） */
    public Boolean isKeyguardSecure(int displayId) {
        try {
            if (isKeyguardSecureMethod == null) {
                isKeyguardSecureMethod = manager.getClass().getMethod("isKeyguardSecure", int.class);
            }
            return (Boolean) isKeyguardSecureMethod.invoke(manager, displayId);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** 尝试解除锁屏（无凭据锁屏可直接解除） */
    public boolean dismissKeyguard() {
        try {
            if (dismissKeyguardMethod == null) {
                dismissKeyguardMethod = manager.getClass().getMethod("dismissKeyguard", IInterface.class, IInterface.class);
            }
            dismissKeyguardMethod.invoke(manager, null, null);
            return true;
        } catch (ReflectiveOperationException e) {
            try {
                // 部分 ROM 签名不同：dismissKeyguard(IKeyguardDismissCallback)
                if (dismissKeyguardMethod == null || !dismissKeyguardMethod.getName().equals("dismissKeyguard")) {
                    dismissKeyguardMethod = manager.getClass().getMethod("dismissKeyguard", IInterface.class);
                }
                dismissKeyguardMethod.invoke(manager, new Object[]{null});
                return true;
            } catch (ReflectiveOperationException e2) {
                return false;
            }
        }
    }

    /** 立即上锁（设置页自测用） */
    public boolean lockNow() {
        try {
            if (lockNowMethod == null) {
                lockNowMethod = manager.getClass().getMethod("lockNow", int.class);
            }
            lockNowMethod.invoke(manager, 0);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    // ───────────── 旋转/分辨率（横屏原生设备兼容） ─────────────

    /** 冻结指定 display 旋转（横屏原生设备虚拟屏旋转修正） */
    public boolean freezeRotation(int displayId, int rotation) {
        try {
            if (freezeRotationMethod == null) {
                freezeRotationMethod = manager.getClass().getMethod("freezeRotation", int.class, int.class);
            }
            freezeRotationMethod.invoke(manager, displayId, rotation);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** 强制 display 上报指定分辨率（横屏原生设备用） */
    public boolean setForcedDisplaySize(int displayId, int width, int height) {
        try {
            if (setForcedDisplaySizeMethod == null) {
                setForcedDisplaySizeMethod = manager.getClass().getMethod(
                        "setForcedDisplaySize", int.class, int.class, int.class);
            }
            setForcedDisplaySizeMethod.invoke(manager, displayId, width, height);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public boolean clearForcedDisplaySize(int displayId) {
        try {
            if (clearForcedDisplaySizeMethod == null) {
                clearForcedDisplaySizeMethod = manager.getClass().getMethod("clearForcedDisplaySize", int.class);
            }
            clearForcedDisplaySizeMethod.invoke(manager, displayId);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}