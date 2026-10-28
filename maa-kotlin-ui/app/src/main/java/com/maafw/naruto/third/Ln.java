package com.maafw.naruto.third;

import android.util.Log;

/**
 * 日志工具，远端进程也输出到 Android Logcat 。
 */
public final class Ln {

    private static final String TAG = "MaaFWRemote";

    public static void d(String message) {
        Log.d(TAG, message);
    }

    public static void i(String message) {
        Log.i(TAG, message);
    }

    public static void w(String message) {
        Log.w(TAG, message);
    }

    public static void w(String message, Throwable throwable) {
        Log.w(TAG, message, throwable);
    }

    public static void e(String message) {
        Log.e(TAG, message);
    }

    public static void e(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
    }
}