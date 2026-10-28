package com.maafw.naruto.root;

import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import com.maafw.naruto.third.Ln;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Root 服务入口（app_process main）～
 * root 进程启动后把引擎 binder 注册到系统 ServiceManager（root 可 addService），
 * app 侧通过 ServiceManager.getService 获取——与 Shizuku 完全一致的 binder 接口。
 * 生命周期：守护 app 进程，app 退出后自动销毁引擎并退出，避免孤儿进程。
 */
public final class RootServiceStarter {

    private static final String TAG = "RootServiceStarter";
    public static final String SERVICE_NAME = "maafw_engine";
    // 对应 IRemoteEngineService.aidl 里 destroy() 的事务码
    private static final int DESTROY_TRANSACTION_CODE = 16777114;

    private static RootUserService.CreatedService activeService;

    private RootServiceStarter() {
    }

    public static void main(String[] args) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        int appPid = -1;
        for (String arg : args) {
            if (arg.startsWith("--app-pid=")) {
                try {
                    appPid = Integer.parseInt(arg.substring(10));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        RootUserService.CreatedService createdService = RootUserService.create(args);
        if (createdService == null) {
            System.err.println("[RootServiceStarter] RootUserService.create() returned null");
            System.exit(1);
            return;
        }
        activeService = createdService;

        if (!publishService(createdService)) {
            System.err.println("[RootServiceStarter] publishService() failed");
            System.exit(1);
            return;
        }

        if (appPid > 0) {
            watchAppProcess(appPid);
        }

        Looper.loop();
        System.exit(0);
    }

    /** 把引擎 binder 回传给 App（P1-5：优先 ContentProvider 握手，Android16 兼容；失败兜底 addService） */
    private static boolean publishService(RootUserService.CreatedService createdService) {
        if (attachViaContentProvider(createdService)) {
            Ln.i(TAG + ": ContentProvider attach ok");
            return true;
        }
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method add = smClass.getMethod("addService", String.class, IBinder.class);
            add.invoke(null, SERVICE_NAME, createdService.service);
            Ln.i(TAG + ": addService(" + SERVICE_NAME + ") ok");
            return true;
        } catch (Throwable tr) {
            Ln.e(TAG + ": addService failed", tr);
            return false;
        }
    }

    /**
     * P1-5：root 进程经 ContentProvider.call 把引擎 binder 回传给 App。
     * App 侧 RootServiceBootstrapProvider.call 校验后完成 Registry，App 无需轮询 ServiceManager。
     * 同时拿到 App 的 lifecycleBinder（linkToDeath 守护）+ appPid（喂引擎心跳）。
     */
    private static boolean attachViaContentProvider(RootUserService.CreatedService createdService) {
        try {
            String pkg = createdService.packageName;
            String authority = pkg + RootServiceBootstrapRegistry.AUTHORITY_SUFFIX;
            // ActivityManager.getService() → IActivityManager
            Class<?> amClass = Class.forName("android.app.ActivityManager");
            Object am = amClass.getMethod("getService").invoke(null);
            // getContentProviderExternal(authority, userId, token, callingTag)
            Method getCpe = null;
            for (Method m : am.getClass().getMethods()) {
                if (m.getName().equals("getContentProviderExternal") && m.getParameterCount() == 4) {
                    getCpe = m;
                    break;
                }
            }
            if (getCpe == null) return false;
            IBinder providerToken = new Binder();
            Object provider = getCpe.invoke(am, authority, 0, providerToken, authority);
            if (provider == null) return false;
            try {
                android.os.Bundle extras = new android.os.Bundle();
                extras.putString(RootServiceBootstrapRegistry.KEY_TOKEN, createdService.token);
                extras.putBinder(RootServiceBootstrapRegistry.KEY_SERVICE_BINDER, createdService.service);
                Object reply = callProvider(provider, authority, extras);
                if (reply instanceof android.os.Bundle) {
                    android.os.Bundle rb = (android.os.Bundle) reply;
                    IBinder appBinder = rb.getBinder(RootServiceBootstrapRegistry.KEY_APP_BINDER);
                    int appPid = rb.getInt(RootServiceBootstrapRegistry.KEY_APP_PID, 0);
                    if (appBinder != null) {
                        // App 生命周期 binder：App 死则 root 服务自杀
                        appBinder.linkToDeath(() -> {
                            Ln.i(TAG + ": app binder died, destroying root service");
                            destroyService(activeService != null ? activeService.service : createdService.service);
                            System.exit(0);
                        }, 0);
                    }
                    // 喂引擎心跳（App pid），引擎看门狗据此守护
                    if (appPid > 0 && createdService.service instanceof com.maafw.naruto.IRemoteEngineService) {
                        try {
                            ((com.maafw.naruto.IRemoteEngineService) createdService.service).heartbeat(appPid);
                        } catch (Throwable ignored) {
                        }
                    }
                    Ln.i(TAG + ": attach ok, appPid=" + appPid);
                    return true;
                }
            } finally {
                try {
                    am.getClass().getMethod("removeContentProviderExternal", String.class, IBinder.class)
                            .invoke(am, authority, providerToken);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Ln.e(TAG + ": attachViaContentProvider failed", t);
        }
        return false;
    }

    /** IContentProvider.call 多版本签名兼容（5 参 / 4 参） */
    private static Object callProvider(Object provider, String authority, android.os.Bundle extras) throws Exception {
        Method call5 = null, call4 = null;
        for (Method m : provider.getClass().getMethods()) {
            if (m.getName().equals("call")) {
                if (m.getParameterCount() == 5 && m.getParameterTypes()[4] == android.os.Bundle.class) call5 = m;
                else if (m.getParameterCount() == 4 && m.getParameterTypes()[3] == android.os.Bundle.class) call4 = m;
            }
        }
        if (call5 != null) {
            return call5.invoke(provider, null, authority, RootServiceBootstrapRegistry.METHOD_ATTACH_REMOTE_SERVICE, null, extras);
        }
        if (call4 != null) {
            return call4.invoke(provider, authority, RootServiceBootstrapRegistry.METHOD_ATTACH_REMOTE_SERVICE, null, extras);
        }
        return null;
    }

    /** 守护 app 进程：app 退出后销毁引擎并退出 root 进程（保证 app 退出后引擎不残留） */
    private static void watchAppProcess(int appPid) {
        Thread watcher = new Thread(() -> {
            Ln.i(TAG + ": watching app pid=" + appPid);
            while (true) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                if (!isProcessAlive(appPid)) {
                    Ln.i(TAG + ": app process " + appPid + " died, destroying root service");
                    destroyService(activeService.service);
                    System.exit(0);
                    return;
                }
            }
        }, "root-app-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static boolean isProcessAlive(int pid) {
        return new File("/proc/" + pid).exists();
    }

    private static void destroyService(IBinder service) {
        if (service == null || !service.pingBinder()) {
            return;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            String descriptor = service.getInterfaceDescriptor();
            if (descriptor != null) {
                data.writeInterfaceToken(descriptor);
            }
            service.transact(DESTROY_TRANSACTION_CODE, data, reply, Binder.FLAG_ONEWAY);
        } catch (Throwable tr) {
            Ln.w(TAG + ": destroy root remote service failed", tr);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}