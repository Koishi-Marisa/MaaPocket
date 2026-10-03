/*
 * ============================================================================
 *  MaaPocket 特权进程入口（app_process 的 main class）
 * ============================================================================
 *
 * ## 需求 1 的结论：需不需要一个「真正的 Android Context」？
 *
 * **需要 Context 对象，但不需要（也不要）RootUserService 那套
 * `ActivityThread.systemMain()` + `Context.createPackageContextAsUser()` +
 * `LoadedApk.makeApplication()` 序列。** 我们用 scrcpy 的「假系统 Context」
 * （`Workarounds` + `FakeContext`）就够了，代价只有几十行反射。
 *
 * ### 证据
 *
 * 1. `root/RootUserService.java:32-55` 那套重活存在的**唯一**目的是
 *    `instantiateService(serviceClass, packageContext)`：它要 new 出一个绑定的
 *    AIDL `RemoteService.Stub`，而 Stub 的构造需要 package/Application Context。
 *    本设计里没有绑定服务、没有 Stub、没有 `--class=` 要实例化的东西
 *    （`liblauncher.so` 强制要求 `--class=`，我们只传个占位符，见
 *    `ProcessSpawner.build` 的 KDoc），所以这段序列没有存在理由。
 * 2. `remote/RemoteServiceImpl.kt:86-94`：特权进程真正的 Context 来源是构造器里的
 *    `Workarounds.apply()`（第 90 行，`FakeContext.get()` 的基类），**不是** package Context。
 *    也就是说 MAA-Meow 自己跑业务时用的也是假 Context；package Context 只喂给了那个 Stub。
 * 3. 到底谁真的需要 Context？只有三个 wrapper：
 *    - `third/wrappers/InputManager.java:33-37` — `FakeContext.get().getSystemService(INPUT_SERVICE)`；
 *    - `third/wrappers/DisplayManager.java:148-154` — 反射 `new android.hardware.display.DisplayManager(Context)`
 *      然后 `createVirtualDisplay(...)`；
 *    - `third/wrappers/ServiceManager.java:92-102` — `new CameraManager(Context)`（本模块用不到）。
 *    其余全是**无 Context** 的纯反射：`ServiceManager.getService(service, type)` 直接调
 *    `android.os.ServiceManager.getService` + `<type>$Stub.asInterface`
 *    （`third/wrappers/ServiceManager.java:38-46`）；`WindowManager.create()` 走
 *    `ServiceManager.getService("window","android.view.IWindowManager")`
 *    （`third/wrappers/WindowManager.java:49-52`）；`DisplayManager.create()` 走
 *    `DisplayManagerGlobal.getInstance()`（`third/wrappers/DisplayManager.java:35-44`）；
 *    `SurfaceControl` 全静态反射（`third/wrappers/SurfaceControl.java:27-33`）。
 * 4. 假 Context 怎么来的：`third/FakeContext.java:29-31`
 *    `super(Workarounds.getSystemContext())`，而 `third/Workarounds.java:25-47` 只做了
 *    「反射 new 一个 `android.app.ActivityThread` → 写静态字段 `sCurrentActivityThread`
 *    → 置 `mSystemThread = true`」，再 `Workarounds.java:152-165` 反射调
 *    `ActivityThread.getSystemContext()`。`apply()`（`Workarounds.java:72-91`）在
 *    SDK≥31 时补一个 `ConfigurationController`（三星 `getDisplayInfoLocked()` 需要非空
 *    Configuration）、非 ONYX 品牌时补 `mBoundApplication`/`mInitialApplication`。
 *
 * ### 后果（本文件做了什么 / 没做什么）
 *
 * - 调 `Workarounds.apply()`，然后用 `FakeContext.get()`；**不**建 package context、
 *   **不**建 Application、**不**实例化 `--class=`、**不**需要
 *   MIUI 的 `makeApplication` 兜底（`RootUserService.java:44-49`）——那个兜底只是因为
 *   RootUserService 要造 Application，与屏幕/输入无关。
 * - `apply()` 内部三步（fillConfigurationController / fillAppInfo / fillAppContext）都是
 *   尽力而为的 try/catch，失败只在 `Ln.d` 记一笔；`getSystemContext()` 失败会返回 **null**
 *   并打栈。所以下面把 Context 获取整段包了 try/catch：拿不到也要把服务端跑起来
 *   （没有 Context 时只有「建虚拟屏」和「输入注入」会退化，ServiceManager 反射路径仍然可用），
 *   并如实通过 `Event.FATAL`/日志告知。
 *
 * ## 需求 4：为什么主线程进 `Looper.loop()`，管道引导线程放后台？
 *
 * - `Workarounds` 的静态初始化本身要 `prepareMainLooper()`；`DisplaySession` 的
 *   `DisplayListener` 也要一个 `Handler(Looper.getMainLooper())`；框架（Binder、DisplayManager）
 *   都假设主 Looper 在跑。**主线程返回就会让 app_process 直接结束进程**，所以主线程只能
 *   待在 `Looper.loop()` 里，这既是需求也是平台约束。
 * - 引导线程要做两件会阻塞的事：`BootstrapClient.attach(...)`（最多重试 10 次 × 500ms，
 *   等 app 把 `BootstrapProvider` publish 出来）和 `RemoteServer.awaitTermination()`
 *   （等到明确停机）。这两段必须放独立线程；它们一旦返回（收到远端 `shutdown`、管道断开、
 *   或引导失败）就由**那个线程**执行清理并退出进程。
 *   注意 `Looper.prepareMainLooper()` 建出来的 looper 是 **non-quittable** 的
 *   （`quit()` 会抛 "Main thread not allowed to quit"），所以不能靠「让 loop() 返回」
 *   来收尾——这也是为什么退出动作放在引导线程里。
 * - `Looper.prepareMainLooper()` 先调，`Workarounds.prepareMainLooper()` 看到
 *   `Looper.myLooper() != null` 就早退。这与 MAA-Meow 的真实流程一致：
 *   `root/RemoteServiceStarter.java:26-30` 也是先 `Looper.prepareMainLooper()`，
 *   之后 `RemoteServiceImpl` 构造器里的 `Workarounds` 才被触发。差别只是 scrcpy 那个
 *   变体想要 `quitAllowed = true`（`Workarounds.java:57` 注释），我们在 `prepareMainLooper()`
 *   之后就享受不到了；因为特权进程本来就该活到被明确关掉，这个差别无害。
 *
 * ## 未捕获异常
 *
 * `Thread.setDefaultUncaughtExceptionHandler` 会：写日志 → 通过管道发一帧
 * `Event.FATAL`（尽力而为，对端没了就算了）→ 清理 → `System.exit(70)`。
 * 理由：`Looper.loop()` 里抛出的异常本来就会杀进程，与其带着未知状态苟活，
 * 不如明确地把死因送到 app 端。注意单个命令处理函数抛异常**不会**走到这里——
 * `RemoteServer` 已经把每个请求包在 try/catch 里翻译成错误帧了。
 *
 * ## 需求 5：Android 14+ 的 root 门槛
 *
 * `--keep-root` 这个开关**不会**传到 main：它由 `liblauncher.so` 消费（决定要不要
 * `setresuid(2000)`），命令行由 `ProcessSpawner.build` 在 `SDK_INT >= 34` 时加上。
 * 这里能做的、也必须做的是**核对效果**：启动时把真实 uid 打出来，若不是 0 而
 * SDK≥34 就明确告警——那种情况下 `input.*` 注定拿不到 `INJECT_EVENTS`。
 * ============================================================================
 */

package com.maapocket.core.privilege;

import android.content.Context;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;

import com.maapocket.core.third.FakeContext;
import com.maapocket.core.third.Ln;
import com.maapocket.core.third.Workarounds;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RemoteMain {

    /** main() 的日志前缀；`Ln.TAG` 是 logcat 的 tag，二者不同。 */
    private static final String TAG = "MaaPocket/RemoteMain";

    /** 心跳（app → 特权进程）最大间隔：超过这个时间没收到就认为 app 死了。 */
    private static final long HEARTBEAT_STALE_MS = 20_000L;

    /**
     * 「孤儿」判定：从没连上过、或连上后又断了，持续这么久就自杀。
     * 必须大于 app 侧 `RemoteProtocol.CONNECT_TIMEOUT_MS`（18s）+ 一点余量。
     */
    private static final long ORPHAN_TIMEOUT_MS = 60_000L;

    private static final long WATCHDOG_INTERVAL_MS = 5_000L;

    private static final int EXIT_UNCAUGHT = 70;

    private static volatile RemoteServer server;

    private static volatile RemoteEngine engine;

    private static final AtomicBoolean exiting = new AtomicBoolean(false);

    private static final AtomicBoolean cleaned = new AtomicBoolean(false);

    /** app 的 pid：`hello` 或第一条 `heartbeat` 里带过来，供 /proc 存活检查。 */
    private static volatile int appPid = -1;

    private static volatile long lastHeartbeatAt = 0L;

    public static void main(String[] args) {
        // 0) 主 Looper 必须先有：Workarounds 的静态初始化会用到它，DisplayListener 也挂在它上面。
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        // 1) 异常处理器要在任何可能抛异常的东西之前装好。
        installUncaughtExceptionHandler();

        final Args a = Args.parse(args);
        Ln.initLogLevel(a.debug ? Ln.Level.DEBUG : Ln.Level.INFO);
        Ln.i(TAG + ": boot pid=" + Process.myPid() + " uid=" + Process.myUid()
                + " sdk=" + android.os.Build.VERSION.SDK_INT
                + " package=" + a.packageName + " token=" + shortToken(a.token)
                + " debug=" + a.debug);

        if (a.token == null) {
            Ln.e(TAG + ": --token= is required (see launcher.c parse_args); refusing to start");
            System.exit(2);
            return;
        }

        // 2) 需求 5：核对 --keep-root 的**效果**。
        gateRootForInputInjection();

        // 3) MAA-Meow RemoteServiceImpl.kt:86-88 的做法：非 shell uid 时清掉 umask，
        //    否则 root 建出来的 socket/文件权限会受调用方 umask 影响。
        if (Process.myUid() != Process.SHELL_UID) {
            try {
                Os.umask(0);
            } catch (Throwable t) {
                Ln.w(TAG + ": Os.umask(0) failed: " + t);
            }
        }

        // 4) 假 Context：scrcpy 路线（见文件头）。不是 RootUserService 那套。
        prepareFakeContext();

        // 5) 起服务端（不再监听任何东西：管道由引导线程交进来，见下面的引导线程）。
        final RemoteServer srv = new RemoteServer(a.token, RemoteProtocol.MAX_LINE_BYTES);
        srv.setLogFileName(a.debugName != null ? a.debugName : a.packageName);
        server = srv;

        final RemoteEngine eng = new RemoteEngine((event, data) -> srv.event(event, data));
        engine = eng;
        eng.install(srv);

        // heartbeat 由 RemoteMain 自己处理：它属于「进程存活」而不是「业务」，
        // 而且看门狗就住在这个类里，闭环最省事。
        srv.on(RemoteProtocol.Cmd.HEARTBEAT, params -> {
            int pid = -1;
            if (params != null && params.containsKey("appPid")) {
                pid = readInt(params.get("appPid"));
            }
            if (pid > 0) appPid = pid;
            lastHeartbeatAt = SystemClock.elapsedRealtime();
            return null;
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Ln.i(TAG + ": shutdown hook fired");
            cleanup();
        }, "maapocket-shutdown"));

        startWatchdog();

        // 6) 管道引导放后台线程，主线程进 Looper（理由见文件头）。
        final Thread bootstrapper = new Thread(() -> {
            ParcelFileDescriptor[] up = null;          // 特权进程 → app
            ParcelFileDescriptor[] down = null;        // app → 特权进程
            ParcelFileDescriptor fromRemote = null;    // 交给 app 的读端
            ParcelFileDescriptor toRemote = null;      // 交给 app 的写端
            try {
                up = ParcelFileDescriptor.createPipe();
                down = ParcelFileDescriptor.createPipe();
                // up[1] 我们留着写；down[0] 我们留着读。
                fromRemote = up[0];
                toRemote = down[1];

                final BootstrapClient.Result r = BootstrapClient.attach(
                        a.packageName, a.uid, a.token, fromRemote, toRemote);
                if (r == null) {
                    Ln.e(TAG + ": bootstrap attach failed; exiting");
                    closeQuietly(fromRemote);
                    closeQuietly(toRemote);
                    closeQuietly(up[1]);
                    closeQuietly(down[0]);
                    cleanupAndExit(6);
                    return;
                }

                // FD 已经交出去（binder 传的是 dup），本进程不再需要这两端。
                closeQuietly(fromRemote);
                closeQuietly(toRemote);
                fromRemote = null;
                toRemote = null;

                // 用 app 回包里的真实 pid，而不是 a.uid（--uid= 传的是 app 的 uid，不是 pid）。
                appPid = r.appPid > 0 ? r.appPid : appPid;
                Ln.i(TAG + ": bootstrap attached appPid=" + r.appPid + " appUid=" + r.appUid);

                if (!srv.attachClient(up[1], down[0])) {
                    Ln.e(TAG + ": server refused client attach; exiting");
                    closeQuietly(up[1]);
                    closeQuietly(down[0]);
                    cleanupAndExit(7);
                    return;
                }
                up[1] = null;
                down[0] = null;   // 所有权已交给 RemoteServer

                // Kotlin 的默认参数对 Java 调用方不可见（除非 @JvmOverloads），所以显式传 0：
                // 0 表示「无限等」，语义见 RemoteServer.awaitTermination。
                srv.awaitTermination(0L);
                Ln.i(TAG + ": transport closed");
            } catch (Throwable t) {
                Ln.e(TAG + ": transport setup failed", t);
            } finally {
                closeQuietly(fromRemote);
                closeQuietly(toRemote);
                if (up != null) closeQuietly(up[1]);
                if (down != null) closeQuietly(down[0]);
                // 走到这里说明传输已经结束（shutdown 命令 / 管道断开 / 引导失败）。
                // 主 Looper 是 non-quittable，不能靠 loop() 返回来收尾，所以在这里主动退出。
                cleanupAndExit(0);
            }
        }, "maapocket-bootstrap");
        bootstrapper.setDaemon(false);
        bootstrapper.start();

        Ln.i(TAG + ": entering main looper");
        Looper.loop();

        // 只有主 Looper 被 quit 才会到这里（正常路径不会）。
        Ln.i(TAG + ": main looper exited");
        cleanupAndExit(0);
    }

    // ------------------------------------------------------------------ 引导

    /**
     * 需求 1 的落地：只做 scrcpy 的假 Context，不做 package context。
     *
     * 每一步都容错：`Workarounds.getSystemContext()` 在失败时返回 null
     * （`Workarounds.java:152-165`），此时 `FakeContext` 会包着一个 null base，
     * 一调 `getSystemService` 就 NPE——所以这里**主动探一下**，探不到就记日志继续
     * （ServiceManager 反射路径不依赖 Context，display/input 之外的命令仍可用）。
     */
    private static void prepareFakeContext() {
        try {
            // 补 mBoundApplication / mInitialApplication / mConfigurationController。
            // 抄 remote/RemoteServiceImpl.kt:90（那是在构造器里调的，我们必须在任何
            // display/input 调用之前调）。
            Workarounds.apply();
        } catch (Throwable t) {
            Ln.e(TAG + ": Workarounds.apply() failed; display/input commands will likely fail", t);
        }

        Context ctx = null;
        try {
            ctx = FakeContext.get();
        } catch (Throwable t) {
            Ln.e(TAG + ": FakeContext.get() failed", t);
        }
        if (ctx == null) {
            Ln.e(TAG + ": no fake context available; display/input commands will fail");
            return;
        }
        // 探一次 packageManager：Workarounds.getSystemContext() 失败时这里就会炸，
        // 提前炸比在建虚拟屏时炸好定位。
        try {
            Ln.i(TAG + ": fake context ready, package=" + ctx.getPackageName()
                    + " attributionSource=" + ctx.getAttributionSource());
        } catch (Throwable t) {
            Ln.e(TAG + ": fake context is not usable (Workarounds.getSystemContext() likely returned null)", t);
        }
    }

    /**
     * 需求 5：`--keep-root` 由 launcher 消费，这里只核对**结果**。
     *
     * MAA-Meow 的判据是 `ProcessServiceConnectorBackend.kt:30-32`：
     * `Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE`（API 34）时
     * input 注入要求 root uid。
     */
    private static void gateRootForInputInjection() {
        final int uid = Process.myUid();
        if (!ProcessSpawner.INSTANCE.getKeepRootForInputInjection()) {
            Ln.i(TAG + ": sdk " + android.os.Build.VERSION.SDK_INT + " < 34, input injection does not require root");
            return;
        }
        if (uid == 0) {
            Ln.i(TAG + ": sdk>=34 and uid=0 -> input injection allowed (--keep-root took effect)");
        } else {
            Ln.w(TAG + ": sdk>=34 but uid=" + uid + " (expected 0): input.* will fail with"
                    + " INJECT_EVENTS permission; Shizuku must itself run as root, or use the root backend");
        }
    }

    // ------------------------------------------------------------------ 异常

    private static void installUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                Ln.e(TAG + ": uncaught exception in thread '" + thread.getName() + "'", throwable);
            } catch (Throwable ignored) {
                // 日志都炸了就别再折腾了。
            }
            reportFatalToClient(thread, throwable);
            cleanupAndExit(EXIT_UNCAUGHT);
        });
    }

    /** 尽力而为地把死因通过管道送出去；对端已经没了就直接放弃。 */
    private static void reportFatalToClient(Thread thread, Throwable throwable) {
        final RemoteServer srv = server;
        if (srv == null) return;
        try {
            srv.event(RemoteProtocol.Event.FATAL, RemoteProtocol.fatalData(thread.getName(), throwable));
        } catch (Throwable t) {
            try {
                Ln.w(TAG + ": could not report fatal error over the pipe: " + t);
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    // ------------------------------------------------------------------ 看门狗

    /**
     * 需求：app 死了，特权进程必须自己走，不然会留下一个占着虚拟屏/输入通道的孤儿。
     *
     * 三条判据（任一命中即退出）：
     * 1. `appPid > 0` 且 `/proc/<appPid>` 不存在（MAA-Meow
     *    `RemoteServiceImpl.startHeartbeatWatchdog` 的做法，简单直接）；
     * 2. 距最后一条心跳超过 [HEARTBEAT_STALE_MS]（防 pid 被复用造成误判）；
     * 3. 从来没有客户端连上、或连上后断了，超过 [ORPHAN_TIMEOUT_MS]。
     *
     * 第 3 条替代了 MAA-Meow 的 binder death recipient——它原来的载体
     * （`RootServiceBootstrapRegistry` 里那个 app 生命周期 Binder）在我们的设计里没有了。
     * 现在的引导通道虽然是 binder（`BootstrapClient` → `BootstrapProvider.call`），
     * 但那条 binder 只在 attach 那一瞬间存在，拿不到能长期用的 death recipient，
     * 所以这一轮仍然只靠看门狗。
     */
    private static void startWatchdog() {
        final long startedAt = SystemClock.elapsedRealtime();
        final Thread watchdog = new Thread(() -> {
            boolean everReady = false;
            long notReadySince = startedAt;
            while (!exiting.get()) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                final long now = SystemClock.elapsedRealtime();
                final RemoteServer srv = server;

                if (srv != null && srv.isClientReady()) {
                    everReady = true;
                    notReadySince = now;
                } else if (everReady ? (now - notReadySince > ORPHAN_TIMEOUT_MS)
                        : (now - startedAt > ORPHAN_TIMEOUT_MS)) {
                    Ln.w(TAG + ": watchdog: client gone for " + (now - notReadySince) + "ms, exiting");
                    cleanupAndExit(3);
                    return;
                }

                final int pid = appPid;
                if (pid > 0) {
                    if (!new File("/proc/" + pid).exists()) {
                        Ln.w(TAG + ": watchdog: app pid " + pid + " is gone, exiting");
                        cleanupAndExit(4);
                        return;
                    }
                    final long last = lastHeartbeatAt;
                    if (last > 0 && now - last > HEARTBEAT_STALE_MS) {
                        Ln.w(TAG + ": watchdog: no heartbeat for " + (now - last) + "ms, exiting");
                        cleanupAndExit(5);
                        return;
                    }
                }
            }
        }, "maapocket-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    // ------------------------------------------------------------------ 收尾

    private static void cleanupAndExit(int code) {
        if (!exiting.compareAndSet(false, true)) return;
        Ln.i(TAG + ": exiting code=" + code);
        cleanup();
        System.exit(code);
    }

    /**
     * 幂等清理。顺序有讲究：
     * 1. `RemoteEngine.shutdown()` 先关 native 预览并释放 Surface——MAA-Meow 的教训是
     *    Surface 不关会让**下一个**特权进程 `eglCreateWindowSurface` 报 "already connected"；
     * 2. 再关服务端（`RemoteServer.close()` 会关两端流、关 worker）；
     * 3. 最后 `Runtime.getRuntime().halt()` 不作为默认路径：`System.exit` 会跑 shutdown hook，
     *    而 hook 里又调回本方法，靠 [cleaned] 防重入。
     */
    private static void cleanup() {
        if (!cleaned.compareAndSet(false, true)) return;
        try {
            final RemoteEngine eng = engine;
            if (eng != null) eng.shutdown();
        } catch (Throwable t) {
            Ln.w(TAG + ": engine shutdown failed: " + t);
        }
        try {
            final RemoteServer srv = server;
            if (srv != null) srv.close();
        } catch (Throwable t) {
            Ln.w(TAG + ": server close failed: " + t);
        }
    }

    // ------------------------------------------------------------------ 参数

    /**
     * `liblauncher.so` 传下来的参数（`launcher.c` 的 exec argv，见 `ProcessSpawner` 的 KDoc）：
     *
     * <pre>
     * /system/bin/app_process /system/bin --nice-name=&lt;process-name&gt; &lt;starter-class&gt;
     *     --token=&lt;t&gt; --package=&lt;p&gt; --class=&lt;c&gt; --uid=&lt;u&gt; [--debug-name=&lt;d&gt;]
     * </pre>
     *
     * `--nice-name=` 被 app_process 自己消费；`--keep-root`/`--apk`/`--log-file` 由 launcher 消费，
     * 都不会到这里。`--uid=` 是**调用方 app 的 uid**，不是我们的，仅供日志。
     */
    private static final class Args {
        final String token;
        final String packageName;
        final String serviceClass;
        final int uid;
        final String debugName;
        final boolean debug;

        private Args(String token, String packageName, String serviceClass, int uid, String debugName) {
            this.token = token;
            this.packageName = packageName;
            this.serviceClass = serviceClass;
            this.uid = uid;
            this.debugName = debugName;
            this.debug = debugName != null;
        }

        static Args parse(String[] argv) {
            String token = null;
            String pkg = null;
            String cls = null;
            String debugName = null;
            int uid = -1;
            for (String arg : argv) {
                if (arg == null) continue;
                if (arg.startsWith("--token=")) token = value(arg);
                else if (arg.startsWith("--package=")) pkg = value(arg);
                else if (arg.startsWith("--class=")) cls = value(arg);
                else if (arg.startsWith("--debug-name=")) debugName = value(arg);
                else if (arg.startsWith("--uid=")) {
                    try {
                        uid = Integer.parseInt(value(arg));
                    } catch (NumberFormatException nfe) {
                        Ln.w(TAG + ": bad --uid value: " + arg);
                    }
                } else {
                    Ln.w(TAG + ": ignoring unknown argument: " + arg);
                }
            }
            if (cls != null) {
                // launcher.c 要求 --class= 必填；本设计没有绑定的 AIDL Service 要实例化。
                Ln.d(TAG + ": --class=" + cls + " is accepted but not instantiated (no bound service in this design)");
            }
            return new Args(token, pkg, cls, uid, debugName);
        }

        private static String value(String arg) {
            int eq = arg.indexOf('=');
            return eq < 0 ? "" : arg.substring(eq + 1);
        }
    }

    /** 日志里不打完整 token（socket 名 = token，泄露出去等于把服务端暴露了）。 */
    private static String shortToken(String token) {
        if (token == null) return "<null>";
        return token.length() <= 8 ? token : token.substring(0, 8) + "...";
    }

    /** `ParcelFileDescriptor.close()` 会抛 IOException；清理路径上不该因此中断退出流程。 */
    private static void closeQuietly(ParcelFileDescriptor fd) {
        if (fd == null) return;
        try {
            fd.close();
        } catch (Throwable t) {
            Ln.d(TAG + ": closeQuietly failed: " + t);
        }
    }

    private static int readInt(Object element) {
        try {
            return Integer.parseInt(element.toString().replace("\"", ""));
        } catch (Throwable t) {
            return -1;
        }
    }

    private RemoteMain() {
    }
}
