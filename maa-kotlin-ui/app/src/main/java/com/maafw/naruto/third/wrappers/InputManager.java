package com.maafw.naruto.third.wrappers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.util.Log;

import com.maafw.naruto.third.ShellContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * InputManager 隐藏 API 包装。
 */
@SuppressLint("PrivateApi,DiscouragedPrivateApi")
public final class InputManager {

    public static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1;
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2;

    private static final String TAG = "InputManager";

    private final android.hardware.input.InputManager manager;

    private static Method injectInputEventMethod;
    private static Method setDisplayIdMethod;

    static InputManager create() {
        android.hardware.input.InputManager manager = (android.hardware.input.InputManager) ShellContext.get()
                .getSystemService(Context.INPUT_SERVICE);
        return new InputManager(manager);
    }

    private InputManager(android.hardware.input.InputManager manager) {
        this.manager = manager;
    }

    private static Method getInjectInputEventMethod() throws NoSuchMethodException {
        if (injectInputEventMethod == null) {
            injectInputEventMethod = android.hardware.input.InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
        return injectInputEventMethod;
    }

    public boolean injectInputEvent(InputEvent inputEvent, int mode) {
        try {
            Method method = getInjectInputEventMethod();
            return (boolean) method.invoke(manager, inputEvent, mode);
        } catch (ReflectiveOperationException e) {
            if (e instanceof InvocationTargetException && e.getCause() instanceof SecurityException) {
                Log.e(TAG, "INJECT_EVENTS permission denied: " + e.getCause().getMessage());
            } else {
                Log.e(TAG, "Could not inject input event", e);
            }
            return false;
        }
    }

    private static Method getSetDisplayIdMethod() throws NoSuchMethodException {
        if (setDisplayIdMethod == null) {
            setDisplayIdMethod = InputEvent.class.getMethod("setDisplayId", int.class);
        }
        return setDisplayIdMethod;
    }

    public static boolean setDisplayId(InputEvent inputEvent, int displayId) {
        try {
            Method method = getSetDisplayIdMethod();
            method.invoke(inputEvent, displayId);
            return true;
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "Cannot set display id on input event", e);
            return false;
        }
    }
}