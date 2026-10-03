package com.maapocket.core.third;

import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Log both to Android logger (so that logs are visible in "adb logcat") and standard output/error (so that they are visible in the terminal
 * directly).
 */
public final class Ln {

    /** Public so that Kotlin call sites in other packages can build `"${Ln.TAG}: ..."` prefixes. */
    public static final String TAG = "MaaPocket";
    private static final String PREFIX = "[MP] ";

    /**
     * 真机实测：这两个流**必须开 autoFlush**。
     *
     * 不回刷时 `print(... + '\n')` 只是往 8 KiB 的缓冲里塞，普通 INFO 行要等到缓冲满
     * 或者进程正常 close 才落盘；而特权进程是被 `RemoteMain` 的看门狗 `halt` 掉的，
     * 缓冲里的东西全丢。结果是 `maapocket-shizuku-launcher.log` 里只剩得到
     * `Ln.w`/`Ln.e` 那几行（走 CONSOLE_ERR 恰好刷出来的），INFO 一行都没有 ——
     * 「日志里什么都看不到」有一部分就是这么来的。
     */
    private static final PrintStream CONSOLE_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
    private static final PrintStream CONSOLE_ERR = new PrintStream(new FileOutputStream(FileDescriptor.err), true);
    private static Level threshold = Level.DEBUG;

    private Ln() {
        // not instantiable
    }

    public static void disableSystemStreams() {
        PrintStream nullStream = new PrintStream(new NullOutputStream());
        System.setOut(nullStream);
        System.setErr(nullStream);
    }

    /**
     * Initialize the log level.
     * <p>
     * Must be called before starting any new thread.
     *
     * @param level the log level
     */
    public static void initLogLevel(Level level) {
        threshold = level;
    }

    public static boolean isEnabled(Level level) {
        return level.ordinal() >= threshold.ordinal();
    }

    public static void v(String message) {
        if (isEnabled(Level.VERBOSE)) {
            Log.v(TAG, message);
            CONSOLE_OUT.print(PREFIX + "VERBOSE: " + message + '\n');
        }
    }

    public static void d(String message) {
        if (isEnabled(Level.DEBUG)) {
            Log.d(TAG, message);
            CONSOLE_OUT.print(PREFIX + "DEBUG: " + message + '\n');
        }
    }

    public static void i(String message) {
        if (isEnabled(Level.INFO)) {
            Log.i(TAG, message);
            CONSOLE_OUT.print(PREFIX + "INFO: " + message + '\n');
        }
    }

    public static void w(String message, Throwable throwable) {
        if (isEnabled(Level.WARN)) {
            Log.w(TAG, message, throwable);
            CONSOLE_ERR.print(PREFIX + "WARN: " + message + '\n');
            if (throwable != null) {
                throwable.printStackTrace(CONSOLE_ERR);
            }
        }
    }

    public static void w(String message) {
        w(message, null);
    }

    public static void e(String message, Throwable throwable) {
        if (isEnabled(Level.ERROR)) {
            Log.e(TAG, message, throwable);
            CONSOLE_ERR.print(PREFIX + "ERROR: " + message + '\n');
            if (throwable != null) {
                throwable.printStackTrace(CONSOLE_ERR);
            }
        }
    }

    public static void e(String message) {
        e(message, null);
    }

    public enum Level {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }

    static class NullOutputStream extends OutputStream {
        @Override
        public void write(byte[] b) {
            // ignore
        }

        @Override
        public void write(byte[] b, int off, int len) {
            // ignore
        }

        @Override
        public void write(int b) {
            // ignore
        }
    }
}
