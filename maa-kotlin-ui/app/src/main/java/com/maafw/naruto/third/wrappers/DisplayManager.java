package com.maafw.naruto.third.wrappers;

import android.annotation.SuppressLint;
import android.hardware.display.VirtualDisplay;
import android.view.Display;
import android.view.Surface;

import com.maafw.naruto.third.Command;
import com.maafw.naruto.third.DisplayInfo;
import com.maafw.naruto.third.ShellContext;
import com.maafw.naruto.third.Ln;
import com.maafw.naruto.third.Size;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DisplayManager 隐藏 API 包装。
 */
@SuppressLint("PrivateApi,DiscouragedPrivateApi")
public final class DisplayManager {

    private final Object manager;
    private Method getDisplayInfoMethod;
    private Method createVirtualDisplayMethod;
    private Method requestDisplayPowerMethod;

    static DisplayManager create() {
        try {
            Class<?> clazz = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Method getInstanceMethod = clazz.getDeclaredMethod("getInstance");
            Object dmg = getInstanceMethod.invoke(null);
            return new DisplayManager(dmg);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Ln.e("DisplayManager.create() failed: " + cause.getClass().getName() + ": " + cause.getMessage());
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private DisplayManager(Object manager) {
        this.manager = manager;
    }

    public VirtualDisplay createNewVirtualDisplay(String name, int width, int height, int dpi, Surface surface, int flags) throws Exception {
        try {
            Constructor<android.hardware.display.DisplayManager> ctor = android.hardware.display.DisplayManager.class.getDeclaredConstructor(
                    android.content.Context.class);
            ctor.setAccessible(true);
            android.hardware.display.DisplayManager dm = ctor.newInstance(ShellContext.get());
            return dm.createVirtualDisplay(name, width, height, dpi, surface, flags);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Ln.e("createNewVirtualDisplay underlying exception: " + cause.getClass().getName() + ": " + cause.getMessage());
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        } catch (Exception e) {
            Ln.e("createNewVirtualDisplay failed: " + e.getClass().getName() + ": " + e.getMessage());
            throw e;
        }
    }

    public int[] getDisplayIds() {
        try {
            return (int[]) manager.getClass().getMethod("getDisplayIds").invoke(manager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    public DisplayInfo getDisplayInfo(int displayId) {
        try {
            Method method = getGetDisplayInfoMethod();
            Object displayInfo = method.invoke(manager, displayId);
            if (displayInfo == null) {
                return getDisplayInfoFromDumpsysDisplay(displayId);
            }
            Class<?> cls = displayInfo.getClass();
            int width = cls.getDeclaredField("logicalWidth").getInt(displayInfo);
            int height = cls.getDeclaredField("logicalHeight").getInt(displayInfo);
            int rotation = cls.getDeclaredField("rotation").getInt(displayInfo);
            int layerStack = cls.getDeclaredField("layerStack").getInt(displayInfo);
            int flags = cls.getDeclaredField("flags").getInt(displayInfo);
            int dpi = cls.getDeclaredField("logicalDensityDpi").getInt(displayInfo);
            String uniqueId = (String) cls.getDeclaredField("uniqueId").get(displayInfo);
            return new DisplayInfo(displayId, new Size(width, height), rotation, layerStack, flags, dpi, uniqueId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private synchronized Method getGetDisplayInfoMethod() throws NoSuchMethodException {
        if (getDisplayInfoMethod == null) {
            getDisplayInfoMethod = manager.getClass().getMethod("getDisplayInfo", int.class);
        }
        return getDisplayInfoMethod;
    }

    private static DisplayInfo getDisplayInfoFromDumpsysDisplay(int displayId) {
        try {
            String output = Command.execReadOutput("dumpsys", "display");
            return parseDisplayInfo(output, displayId);
        } catch (Exception e) {
            Ln.e("Could not get display info from dumpsys display: " + e.getMessage());
            return null;
        }
    }

    private static DisplayInfo parseDisplayInfo(String dumpsysDisplayOutput, int displayId) {
        Pattern regex = Pattern.compile(
                "^    mOverrideDisplayInfo=DisplayInfo\\{\".*?, displayId " + displayId + ".*?(, FLAG_.*)?, real ([0-9]+) x ([0-9]+).*?, "
                        + "rotation ([0-9]+).*?, density ([0-9]+).*?, layerStack ([0-9]+)",
                Pattern.MULTILINE);
        Matcher m = regex.matcher(dumpsysDisplayOutput);
        if (!m.find()) {
            return null;
        }
        int flags = parseDisplayFlags(m.group(1));
        int width = Integer.parseInt(m.group(2));
        int height = Integer.parseInt(m.group(3));
        int rotation = Integer.parseInt(m.group(4));
        int density = Integer.parseInt(m.group(5));
        int layerStack = Integer.parseInt(m.group(6));
        return new DisplayInfo(displayId, new Size(width, height), rotation, layerStack, flags, density, null);
    }

    private static int parseDisplayFlags(String text) {
        if (text == null) return 0;
        int flags = 0;
        Pattern regex = Pattern.compile("FLAG_[A-Z_]+");
        Matcher m = regex.matcher(text);
        while (m.find()) {
            String flagString = m.group();
            try {
                Field field = Display.class.getDeclaredField(flagString);
                flags |= field.getInt(null);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return flags;
    }

    private synchronized Method getRequestDisplayPowerMethod() throws NoSuchMethodException {
        if (requestDisplayPowerMethod == null) {
            requestDisplayPowerMethod = manager.getClass().getMethod("requestDisplayPower", int.class, boolean.class);
        }
        return requestDisplayPowerMethod;
    }

    public boolean requestDisplayPower(int displayId, boolean on) {
        try {
            Method method = getRequestDisplayPowerMethod();
            return (boolean) method.invoke(manager, displayId, on);
        } catch (ReflectiveOperationException e) {
            Ln.e("requestDisplayPower failed: " + e.getMessage());
            return false;
        }
    }
}