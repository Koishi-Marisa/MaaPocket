package com.maafw.naruto.third.wrappers;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;

import com.maafw.naruto.third.Ln;

import java.lang.reflect.Method;

/**
 * 对系统 IActivityManager 的反射封装。
 * 调用方以 shell 身份执行 startActivityAsUser / forceStopPackage 等接口。
 */
public class ActivityManager {

    private final IInterface mManager;

    private ActivityManager(IInterface manager) {
        mManager = manager;
    }

    @SuppressLint("PrivateApi")
    public static ActivityManager create() {
        try {
            Class<?> cls = Class.forName("android.app.ActivityManagerNative");
            Method getDefault = cls.getDeclaredMethod("getDefault");
            IInterface manager = (IInterface) getDefault.invoke(null);
            return new ActivityManager(manager);
        } catch (Throwable e) {
            Ln.e("ActivityManager.create failed", e);
            return null;
        }
    }

    /**
     * 等价于 IActivityManager.startActivityAsUser，调用包固定为 com.android.shell。
     *
     * @param intent  待启动 Intent
     * @param options ActivityOptions 对应的 Bundle，可含 launchDisplayId
     * @return ActivityManager.startActivity 返回值语义，>=0 表示请求已被系统接收
     */
    @SuppressLint("PrivateApi")
    public int startActivity(Intent intent, Bundle options) {
        if (mManager == null) {
            Ln.e("ActivityManager.startActivity: manager is null");
            return -1;
        }
        try {
            Method method = mManager.getClass().getMethod(
                    "startActivityAsUser",
                    /* caller */          Class.forName("android.app.IApplicationThread"),
                    /* callingPackage */  String.class,
                    /* intent */          Intent.class,
                    /* resolvedType */    String.class,
                    /* resultTo */        IBinder.class,
                    /* resultWho */       String.class,
                    /* requestCode */     int.class,
                    /* startFlags */      int.class,
                    /* profilerInfo */    Class.forName("android.app.ProfilerInfo"),
                    /* bOptions */        Bundle.class,
                    /* userId */          int.class
            );
            Object result = method.invoke(
                    mManager,
                    /* caller */          null,
                    /* callingPackage */  "com.android.shell",
                    /* intent */          intent,
                    /* resolvedType */    null,
                    /* resultTo */        null,
                    /* resultWho */       null,
                    /* requestCode */     0,
                    /* startFlags */      0,
                    /* profilerInfo */    null,
                    /* bOptions */        options,
                    /* userId */          -2 /* UserHandle.USER_CURRENT */
            );
            return result == null ? -1 : (Integer) result;
        } catch (Throwable e) {
            Ln.e("ActivityManager.startActivity failed", e);
            return -1;
        }
    }

    @SuppressLint("PrivateApi")
    public void forceStopPackage(String packageName) {
        if (mManager == null || packageName == null) {
            Ln.e("ActivityManager.forceStopPackage: invalid args");
            return;
        }
        try {
            Method method = mManager.getClass().getMethod("forceStopPackage", String.class, int.class);
            method.invoke(mManager, packageName, /* UserHandle.USER_CURRENT */ -2);
        } catch (Throwable e) {
            Ln.e("ActivityManager.forceStopPackage failed", e);
        }
    }
}
