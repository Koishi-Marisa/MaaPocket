package com.maafw.naruto.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

/**
 * Root 引擎 binder 接收器～
 * root 引擎进程（uid0）无法通过隐式广播/ServiceManager 把 binder 传给 App（Android 16 限制），
 * 改用显式广播直达 manifest 静态 receiver：App 进程收到后写入 RootRemoteServiceConnector.pendingBinder，
 * 由 waitForBinder 轮询取走，绕开所有 Android 16 的通道限制。
 */
public final class RootBinderReceiver extends BroadcastReceiver {

    private static final String TAG = "RootBinderReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        // 接受 root(0) / shell(2000) / 本 App 自身 uid（降权广播时 sendingUid=App uid）
        int uid = Binder.getCallingUid();
        int myUid = android.os.Process.myUid();
        if (uid != 0 && uid != android.os.Process.SHELL_UID && uid != myUid) {
            Log.w(TAG, "拒绝非 root/shell/自身身份广播：uid=" + uid + " myUid=" + myUid);
            return;
        }

        // Intent.getIBinderExtra 在 SDK stub 里是 @hide，用反射读取（运行时真实类存在该方法）
        IBinder binder = null;
        try {
            binder = (IBinder) Intent.class.getMethod("getIBinderExtra", String.class)
                    .invoke(intent, "binder");
        } catch (Throwable t) {
            Log.e(TAG, "getIBinderExtra 反射失败: " + t.getMessage());
        }

        if (binder != null && binder.pingBinder()) {
            RootRemoteServiceConnector.pendingBinder = binder;
            Log.i(TAG, "已接收 root 引擎 binder，写入 pendingBinder ");
        } else {
            Log.w(TAG, "root 引擎 binder 无效或缺失");
        }
    }
}