package com.maapocket.core.privilege;

import android.content.AttributionSource;
import android.content.IContentProvider;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;

import com.maapocket.core.third.Ln;
import com.maapocket.core.third.wrappers.ServiceManager;

/**
 * 特权进程侧的引导客户端：把两条管道 FD 交给 app 的
 * {@link BootstrapProvider}（authority = {@code <packageName>.maapocket.bootstrap}）。
 *
 * <p>对应 MAA-Meow 的 {@code RootServiceBootstrapClient} + {@code RootIContentProviderCompat}，
 * 差别是运过去的东西从「一个 AIDL 服务 binder」变成了「两个 {@code ParcelFileDescriptor}」。
 *
 * <h2>为什么要重试</h2>
 *
 * <p>本类跑在裸 {@code app_process} 里，是 **app 先 spawn 我们、我们再调回去**。app 侧
 * {@code AMS.getContentProviderExternal(authority, ...)} 要求该 provider 已经被
 * {@code ActivityThread} publish 出来；app 刚起来的那一瞬间它可能还没有。
 * MAA-Meow 直接失败就退，我们在真机上观察到过偶发的 "provider is null"，所以做
 * {@link #MAX_ATTEMPTS} 次 × {@link #RETRY_INTERVAL_MS} 的短重试（合计 5s）。
 *
 * <h2>为什么用 shell 身份</h2>
 *
 * <p>{@code IContentProvider.call()} 在 SDK 29/30/31+ 上有四套不同的重载（多一个
 * {@code AttributionSource}、多个 {@code attributionTag}、多个 {@code callingPkg}）。
 * 这里的 SDK 分支逐字照抄 MAA-Meow 的 {@code RootIContentProviderCompat}：它们是在
 * 真机上验证过的组合，重载与真机签名不一致会让 binder transaction code 错位，
 * 报 "Transaction failed on small parcel" 之类看不出原因的错。
 *
 * <p>参数 {@code callingPkg} 固定用 {@code com.android.shell}——特权进程本来就以
 * shell(2000) 运行（或 root），用这个名字与真实身份一致；app 侧 provider 最终是按
 * {@code Binder.getCallingUid()} 校验的，不依赖这里填的包名。
 *
 * <h2>诊断</h2>
 *
 * <p>本类跑在裸进程里，logcat 未必可见（{@code Ln} 的 tag 可能被 selinux 挡住、
 * 也可能日志级别被调低），所以每条关键路径同时写 {@code System.err}——它已经被
 * {@code --log-file} 重定向到磁盘上的 launcher 日志里，adb 一定能读到。
 */
public final class BootstrapClient {

    /** 与 {@code Ln.TAG} 无关，这是 System.err 行的前缀。 */
    private static final String ERR_TAG = "[BootstrapClient]";

    /** 冒充的调用方包名：与真实 uid（shell/root）一致，避免 ContentProvider 侧的额外校验别扭。 */
    private static final String CALLING_PACKAGE = "com.android.shell";

    /** provider 可能还没 publish，最多试这么多次。 */
    private static final int MAX_ATTEMPTS = 10;

    private static final long RETRY_INTERVAL_MS = 500L;

    private BootstrapClient() {
        /* 纯静态工具类 */
    }

    /** app 端接受后的回包：app 的 pid / uid。 */
    public static final class Result {
        public final int appPid;
        public final int appUid;

        Result(int appPid, int appUid) {
            this.appPid = appPid;
            this.appUid = appUid;
        }
    }

    /**
     * 把两条管道交给 app。
     *
     * @param packageName 宿主 app 的包名（来自 {@code --package=}）
     * @param appUid      宿主 app 的 uid（来自 {@code --uid=}），用来推导 userId
     * @param token       本次会话的一次性 token，app 用它找槽位
     * @param fromRemote  特权进程写、app 读的那一端
     * @param toRemote    app 写、特权进程读的那一端
     * @return null 表示 app 端没有接受（provider 找不到 / 被拒 / 没有对应 token 的槽位）
     */
    public static Result attach(String packageName, int appUid, String token,
                                ParcelFileDescriptor fromRemote, ParcelFileDescriptor toRemote) {
        final String authority = packageName + BootstrapProtocol.AUTHORITY_SUFFIX;
        // uid 非正说明 --uid= 没解析出来（launcher 理论上拦掉了），退到 user 0 而不是崩。
        //
        // 不用 UserHandle.getUserId(int)：它是 @UnsupportedAppUsage 的隐藏 API，公开
        // android.jar 里没有（CI 原文 `error: cannot find symbol` / `symbol: method
        // getUserId(int)` / `location: class UserHandle`）。而它的实现就是
        // uid / PER_USER_RANGE，所以这里直接写常量，行为完全一致。
        final int userId = appUid > 0 ? appUid / 100_000 : 0;
        final IBinder providerToken = new Binder();

        err("attach authority=" + authority + " userId=" + userId + " appUid=" + appUid);

        IContentProvider provider = null;
        try {
            // ---------------------------------------------------------- 1) 找到 provider（带重试）
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                provider = acquire(authority, userId, providerToken);
                if (provider != null) {
                    if (attempt > 1) {
                        Ln.i("BootstrapClient: provider acquired on attempt " + attempt);
                    }
                    break;
                }
                Ln.w("BootstrapClient: provider not ready (attempt " + attempt + "/" + MAX_ATTEMPTS + "): " + authority);
                err("provider not ready, attempt " + attempt + "/" + MAX_ATTEMPTS);
                sleep(RETRY_INTERVAL_MS);
            }
            if (provider == null) {
                Ln.e("BootstrapClient: giving up, provider never appeared: " + authority);
                err("giving up: provider never appeared");
                return null;
            }

            // ---------------------------------------------------------- 2) 组 Bundle（token + 两个 FD）
            final Bundle extras = new Bundle();
            extras.putString(BootstrapProtocol.KEY_TOKEN, token);
            extras.putParcelable(BootstrapProtocol.KEY_FROM_REMOTE, fromRemote);
            extras.putParcelable(BootstrapProtocol.KEY_TO_REMOTE, toRemote);
            extras.putInt(BootstrapProtocol.KEY_REMOTE_PID, Process.myPid());
            extras.putInt(BootstrapProtocol.KEY_REMOTE_UID, Process.myUid());

            // ---------------------------------------------------------- 3) 调过去
            final Bundle reply = callCompat(provider, authority, extras);
            if (reply == null) {
                Ln.e("BootstrapClient: provider.call() returned null (token=" + shortToken(token) + ")");
                err("provider.call() returned null");
                return null;
            }
            final int appPid = reply.getInt(BootstrapProtocol.KEY_APP_PID, -1);
            final int appUidFromApp = reply.getInt(BootstrapProtocol.KEY_APP_UID, -1);
            Ln.i("BootstrapClient: attached, appPid=" + appPid + " appUid=" + appUidFromApp);
            err("attached appPid=" + appPid + " appUid=" + appUidFromApp);
            return new Result(appPid, appUidFromApp);
        } catch (Throwable t) {
            // 这里跑在裸 app_process 里，任何未捕获异常都会直接杀进程并且死因不明——全部兜住。
            Ln.e("BootstrapClient: attach failed", t);
            err("exception: " + t);
            t.printStackTrace(System.err);
            return null;
        } finally {
            // 成败都要把外部 provider 引用还回去，否则 AMS 那边会一直记着这个 client。
            try {
                ServiceManager.getActivityManager().removeContentProviderExternal(authority, providerToken);
            } catch (Throwable t) {
                Ln.w("BootstrapClient: removeContentProviderExternal failed: " + t);
            }
        }
    }

    /** 拿到 provider 并确认 binder 还活着；任何异常都当成「还没准备好」，交给上层重试。 */
    private static IContentProvider acquire(String authority, int userId, IBinder providerToken) {
        try {
            final IContentProvider provider = ServiceManager.getActivityManager()
                    .getContentProviderExternal(authority, userId, providerToken, authority);
            if (provider == null) {
                return null;
            }
            if (!provider.asBinder().pingBinder()) {
                Ln.w("BootstrapClient: provider binder is dead: " + authority);
                err("provider binder is dead");
                return null;
            }
            return provider;
        } catch (Throwable t) {
            Ln.w("BootstrapClient: getContentProviderExternal threw: " + t);
            err("getContentProviderExternal threw: " + t);
            return null;
        }
    }

    /**
     * 按 SDK 选 {@code IContentProvider.call()} 的重载。逐字照抄 MAA-Meow 的
     * {@code RootIContentProviderCompat.call}，只把 {@code callingPkg} 与 {@code attributeTag}
     * 固定成上面的常量。
     *
     * <p>SDK31+ 优先用 {@code AttributionSource} 重载；如果因为隐藏 API 白名单/类缺失导致
     * {@code LinkageError} 或 {@code RuntimeException}，退回带 {@code attributionTag} 的那套。
     */
    private static Bundle callCompat(IContentProvider provider, String authority, Bundle extras)
            throws Exception {
        final String method = BootstrapProtocol.METHOD_ATTACH;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                final AttributionSource attributionSource = new AttributionSource.Builder(Os.getuid())
                        .setAttributionTag(authority)
                        .setPackageName(CALLING_PACKAGE)
                        .build();
                return provider.call(attributionSource, authority, method, null, extras);
            } catch (LinkageError | RuntimeException e) {
                Ln.w("BootstrapClient: AttributionSource call failed, falling back: " + e);
                return provider.call(CALLING_PACKAGE, authority, authority, method, null, extras);
            }
        } else if (Build.VERSION.SDK_INT == 30) {
            return provider.call(CALLING_PACKAGE, authority, authority, method, null, extras);
        } else if (Build.VERSION.SDK_INT == 29) {
            return provider.call(CALLING_PACKAGE, authority, method, null, extras);
        } else {
            return provider.call(CALLING_PACKAGE, method, null, extras);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void err(String message) {
        System.err.println(ERR_TAG + " " + message);
    }

    private static String shortToken(String token) {
        if (token == null) {
            return "<null>";
        }
        return token.length() <= 8 ? token : token.substring(0, 8) + "...";
    }
}
