package com.maafw.naruto.third;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Field;

/**
 * 伪造的 Android Context，用于在无 Activity 的进程中获取系统服务喵。
 */
public final class FakeContext extends ContextWrapper {

    public static final String PACKAGE_NAME = "com.android.shell";
    public static final int ROOT_UID = 0;

    private static final FakeContext INSTANCE = new FakeContext();

    public static FakeContext get() {
        return INSTANCE;
    }

    private FakeContext() {
        super(Workarounds.getSystemContext());
    }

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public String getOpPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public int checkCallingPermission(String permission) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Object getSystemService(String name) {
        return super.getSystemService(name);
    }

    @Override
    public PackageManager getPackageManager() {
        try {
            PackageManager pm = super.getPackageManager();
            // 强行把 mContext 指向自己，避免某些系统服务校验包名失败
            try {
                Field field = pm.getClass().getDeclaredField("mContext");
                field.setAccessible(true);
                field.set(pm, this);
            } catch (Exception ignored) {
            }
            return pm;
        } catch (Exception e) {
            Log.w("FakeContext", "getPackageManager failed", e);
            return super.getPackageManager();
        }
    }

    // Android 12+ 系统服务常通过 AttributionSource 识别调用方，
    // 必须返回 shell 身份，否则 DisplayManager 等会校验失败或 NPE
    @Override
    public AttributionSource getAttributionSource() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                AttributionSource.Builder builder =
                    new AttributionSource.Builder(Process.SHELL_UID);
                builder.setPackageName(PACKAGE_NAME);
                return builder.build();
            } catch (Exception e) {
                Log.w("FakeContext", "getAttributionSource failed", e);
            }
        }
        return super.getAttributionSource();
    }

    // Android 14+ 部分系统服务会调用 getDeviceId()
    @SuppressWarnings("unused")
    public int getDeviceId() {
        return 0;
    }
}