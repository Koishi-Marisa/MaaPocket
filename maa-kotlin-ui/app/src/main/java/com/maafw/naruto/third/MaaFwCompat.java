package com.maafw.naruto.third;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Android 系统兼容性处理，构造 ActivityThread 和 Looper，用于 Shizuku UserService 进程。
 *  / scrcpy，保持简洁，避免过度填充导致系统服务校验失败。
 */
@SuppressLint("PrivateApi,BlockedPrivateApi,SoonBlockedPrivateApi,DiscouragedPrivateApi")
public final class MaaFwCompat {

    private static final String TAG = "MaaFwCompat";

    private static final Class<?> ACTIVITY_THREAD_CLASS;
    private static final Object ACTIVITY_THREAD;

    static {
        try {
            prepareMainLooper();

            ACTIVITY_THREAD_CLASS = Class.forName("android.app.ActivityThread");
            Constructor<?> activityThreadConstructor = ACTIVITY_THREAD_CLASS.getDeclaredConstructor();
            activityThreadConstructor.setAccessible(true);
            ACTIVITY_THREAD = activityThreadConstructor.newInstance();

            Field sCurrentActivityThreadField = ACTIVITY_THREAD_CLASS.getDeclaredField("sCurrentActivityThread");
            sCurrentActivityThreadField.setAccessible(true);
            sCurrentActivityThreadField.set(null, ACTIVITY_THREAD);

            Field mSystemThreadField = ACTIVITY_THREAD_CLASS.getDeclaredField("mSystemThread");
            mSystemThreadField.setAccessible(true);
            mSystemThreadField.setBoolean(ACTIVITY_THREAD, true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private MaaFwCompat() {
    }

    public static void apply() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 部分三星/Android 12+ 设备在 DisplayManagerGlobal 中会访问 ActivityThread.getConfiguration()
            fillConfigurationController();
        }
        fillAppInfo();
        fillAppContext();
        try {
            boolean bypassOk = HiddenApiBypass.addHiddenApiExemptions("L");
            Log.i(TAG, "MaaFwCompat.apply() done, hiddenApiBypass=" + bypassOk);
        } catch (Throwable t) {
            Log.w(TAG, "HiddenApiBypass failed: " + t.getMessage());
        }
    }

    static Context getSystemContext() {
        try {
            Method getSystemContextMethod = ACTIVITY_THREAD_CLASS.getDeclaredMethod("getSystemContext");
            return (Context) getSystemContextMethod.invoke(ACTIVITY_THREAD);
        } catch (Throwable throwable) {
            Log.e(TAG, "Could not get system context", throwable);
            return null;
        }
    }

    public static void prepareMainLooper() {
        if (Looper.myLooper() != null) {
            return;
        }
        Looper.prepare();
        synchronized (Looper.class) {
            try {
                Field field = Looper.class.getDeclaredField("sMainLooper");
                field.setAccessible(true);
                field.set(null, Looper.myLooper());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static void fillConfigurationController() {
        try {
            Class<?> configurationControllerClass = Class.forName("android.app.ConfigurationController");
            Class<?> activityThreadInternalClass = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> ctor = configurationControllerClass.getDeclaredConstructor(activityThreadInternalClass);
            ctor.setAccessible(true);
            Object controller = ctor.newInstance(ACTIVITY_THREAD);
            Field field = ACTIVITY_THREAD_CLASS.getDeclaredField("mConfigurationController");
            field.setAccessible(true);
            field.set(ACTIVITY_THREAD, controller);
        } catch (Throwable throwable) {
            Log.d(TAG, "Could not fill configuration: " + throwable.getMessage());
        }
    }

    private static void fillAppInfo() {
        try {
            Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> appBindDataConstructor = appBindDataClass.getDeclaredConstructor();
            appBindDataConstructor.setAccessible(true);
            Object appBindData = appBindDataConstructor.newInstance();

            ApplicationInfo applicationInfo = new ApplicationInfo();
            applicationInfo.packageName = ShellContext.PACKAGE_NAME;

            Field appInfoField = appBindDataClass.getDeclaredField("appInfo");
            appInfoField.setAccessible(true);
            appInfoField.set(appBindData, applicationInfo);

            Field mBoundApplicationField = ACTIVITY_THREAD_CLASS.getDeclaredField("mBoundApplication");
            mBoundApplicationField.setAccessible(true);
            mBoundApplicationField.set(ACTIVITY_THREAD, appBindData);
        } catch (Throwable throwable) {
            Log.d(TAG, "Could not fill app info: " + throwable.getMessage());
        }
    }

    private static void fillAppContext() {
        try {
            Application app = Instrumentation.newApplication(Application.class, ShellContext.get());
            Field mInitialApplicationField = ACTIVITY_THREAD_CLASS.getDeclaredField("mInitialApplication");
            mInitialApplicationField.setAccessible(true);
            mInitialApplicationField.set(ACTIVITY_THREAD, app);
        } catch (Throwable throwable) {
            Log.d(TAG, "Could not fill app context: " + throwable.getMessage());
        }
    }
}