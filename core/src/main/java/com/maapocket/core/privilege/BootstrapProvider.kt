package com.maapocket.core.privilege

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.os.BundleCompat
import com.maapocket.core.third.Ln

/**
 * 特权进程 → app 的**引导入口**：把两条管道 FD 送回 app 进程。
 *
 * ## 为什么要有这么一个 provider（而不是直接用 LocalSocket）
 *
 * 见 [BootstrapProtocol] 的类注释：SELinux 禁止 `untrusted_app` 连 `shell` 域建的抽象
 * unix socket（真机 Enforcing 下 `avc: denied { connectto } ... tclass=unix_stream_socket`）。
 * 方向反过来就通了：**app 提供 provider，特权进程用 `getContentProviderExternal` 主动调进来**。
 * ContentProvider 是四大组件里唯一能被跨 uid 用 authority 找到、又允许在 `call()` 里
 * 收发 `ParcelFileDescriptor` 的东西，正好适合干这一件事。
 *
 * ## 与 MAA-Meow 的差异
 *
 * MAA-Meow 的 `RootServiceBootstrapProvider` 只把**一个 binder**（AIDL 服务）运回去，
 * 之后全部走 binder 调用。我们运的是**两条单向管道**，运完之后命令/响应/事件仍然走
 * 原来那套自描述的 JSON 行协议（`RemoteProtocol` / `RemoteServer` / `RemoteConnector`），
 * 好处是协议层一行没改，坏处是这里多了一次 FD 传递。
 *
 * ## 安全
 *
 * `authorities` 是 `${applicationId}.maapocket.bootstrap`，别的 app 理论上也能调进来，
 * 所以 [call] 第一件事就是校验 `Binder.getCallingUid()` 必须是 `shell(2000)` 或 `root(0)`。
 * 再加一层：`Bundle` 里必须带一个**当前正挂着**的一次性 token（[BootstrapRegistry] 里
 * 没有对应槽位就拒绝），而 token 只在 app 进程内存里存在。两层都过才认。
 *
 * ## 线程
 *
 * [call] 跑在 app 进程的 binder 线程池里，**不能做重活**：这里只做一次 map 查找 +
 * `CompletableDeferred.complete`，真正的连接建立由 `PrivilegedSession` 那边的协程继续。
 */
class BootstrapProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != BootstrapProtocol.METHOD_ATTACH || extras == null) {
            // 其它方法名不归我们管，交回默认实现（默认会抛 UnsupportedOperationException）。
            return super.call(method, arg, extras)
        }

        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.SHELL_UID && callingUid != 0) {
            // 不是 shell 也不是 root：不是我们的特权进程，直接拒。
            Ln.w("BootstrapProvider: 拒绝来自 uid=$callingUid 的 attach（只接受 shell(2000) / root(0)）")
            return null
        }

        val token = extras.getString(BootstrapProtocol.KEY_TOKEN)
        if (token.isNullOrEmpty()) {
            Ln.w("BootstrapProvider: attach 缺少 token")
            return null
        }

        // 用 BundleCompat 而不是 Bundle.getParcelable(String)：后者在 API 33 起被标记废弃，
        // 且新旧重载的返回类型不一致，androidx 的封装把这段兼容做掉了。
        val fromRemote = BundleCompat.getParcelable(
            extras, BootstrapProtocol.KEY_FROM_REMOTE, ParcelFileDescriptor::class.java,
        )
        val toRemote = BundleCompat.getParcelable(
            extras, BootstrapProtocol.KEY_TO_REMOTE, ParcelFileDescriptor::class.java,
        )
        if (fromRemote == null || toRemote == null) {
            Ln.w("BootstrapProvider: attach 的 Bundle 里缺管道 FD（fromRemote=$fromRemote toRemote=$toRemote）")
            return null
        }

        val remotePid = extras.getInt(BootstrapProtocol.KEY_REMOTE_PID, -1)
        val remoteUid = extras.getInt(BootstrapProtocol.KEY_REMOTE_UID, -1)
        Ln.i("BootstrapProvider: 收到 uid=$callingUid pid=$remotePid 的 attach（token=${BootstrapRegistry.short(token)}）")

        val accepted = BootstrapRegistry.attach(token, fromRemote, toRemote, remotePid, remoteUid)
        if (!accepted) {
            // 没人等这个 token：把 FD 关掉（attach 里对「重复 attach」已经关过了，
            // 走到这里的是「未知 token」那条分支，FD 还开着）。
            runCatching { fromRemote.close() }
            runCatching { toRemote.close() }
            return null
        }

        // 回包告诉特权进程「app 是谁」，它拿 appPid 起看门狗（原来这个信息来自
        // app 发的 hello，现在管道一通就能先拿到，hello 仍然保留作为业务级握手）。
        return Bundle().apply {
            putInt(BootstrapProtocol.KEY_APP_PID, Process.myPid())
            putInt(BootstrapProtocol.KEY_APP_UID, Process.myUid())
        }
    }

    // ------------------------------------------------------------------ 用不到的部分
    // 这个 provider 只借道 ContentProvider 的 call() 通道，不承载任何数据。
    // 照 MAA-Meow 的做法一律返回空，不调 super（super 会抛 UnsupportedOperationException）。

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
