package com.maapocket.core.privilege

import android.os.ParcelFileDescriptor
import android.view.Surface
import com.maapocket.core.third.Ln
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * app 侧收到的、由特权进程建好并经 binder 送回来的两条单向管道。
 *
 * 方向都按 **app 视角** 命名（见 `BootstrapProtocol` 的类注释）：
 * - [fromRemote] 特权进程写、**app 读**；
 * - [toRemote] **app 写**、特权进程读。
 */
data class RemoteStreams(
    val fromRemote: ParcelFileDescriptor,
    val toRemote: ParcelFileDescriptor,
    val remotePid: Int,
    val remoteUid: Int,
)

/**
 * 「等管道送回来」的挂号处（app 进程内）。
 *
 * ## 为什么需要它
 *
 * 传输层从 LocalSocket 换成「ContentProvider 引导 + binder 传管道」之后，连接不再是
 * app 主动去 `connect()` 一个地址，而是**特权进程反过来调进 app 的 provider**。所以
 * `PrivilegedSession.start()` 必须先挂号、再 spawn：
 *
 * ```
 * val slot = BootstrapRegistry.register(token)   // 先挂号
 * backend.spawn(inv)                             // 再起进程
 * val streams = slot.await()                     // 进程起来后会 attach 进来
 * ```
 *
 * **顺序不能反**：`BootstrapClient` 在 app 进程刚起、provider 还没 publish 时会重试，
 * 但 token 槽位不存在它就直接判失败并退出，那一轮会话就白白浪费了。
 *
 * ## FD 所有权（唯一规则，别的地方不要关）
 *
 * - `attach()` 成功 → 所有权在「[register] 返回的那个 `CompletableDeferred`」里；
 * - `RemoteConnector.connect()` 一旦 `await()` 到值，**立刻**调 [consume] 声明接管，
 *   之后**无论成功还是失败**都由它负责 `close()` 两个 FD；
 * - [unregister] 只在没人接管时才兜底关闭（`await()` 超时 / spawn 失败 / 会话重启）。
 *
 * 这样只有两条关闭路径，且互斥——不会出现「谁都以为对方会关」的 fd 泄漏，也不会双关。
 */
object BootstrapRegistry {

    /** 一个 token 对应一个槽位。`consumed` 记录 FD 是否已被 [RemoteConnector] 接管。 */
    private class Slot {
        val deferred = CompletableDeferred<RemoteStreams>()

        /**
         * 与 [deferred] 同步落地的 FD 引用。
         *
         * 为什么另外存一份：`Deferred.getCompleted()` 是 `@ExperimentalCoroutinesApi` 的扩展，
         * 而且它对「被取消」的 deferred 会抛 `IllegalStateException`（`isCompleted` 对取消也是
         * true）。兜底关闭那段代码在清理路径上，不能抛异常也不该依赖实验 API，所以自己留一份。
         */
        @Volatile
        var streams: RemoteStreams? = null

        val consumed = AtomicBoolean(false)
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    /**
     * 挂号并返回等待结果。**必须在 spawn 之前调用**（理由见类注释）。
     *
     * 同一 token 重复挂号（理论上是 bug）时丢弃旧槽位：旧槽位若已经收到 FD 就顺手关掉，
     * 免得泄漏；旧槽位上的 `await()` 会被取消，不会永远挂着。
     */
    fun register(token: String): CompletableDeferred<RemoteStreams> {
        val slot = Slot()
        slots.put(token, slot)?.let { previous ->
            Ln.w("BootstrapRegistry: token ${short(token)} 重复挂号，丢弃旧槽位")
            discard(previous)
        }
        Ln.d("BootstrapRegistry: 挂号 token ${short(token)}（当前 ${slots.size} 个）")
        return slot.deferred
    }

    /**
     * 特权进程调回来时走这里。返回 false 表示这个 token 没人等（会话已取消 / token 不对），
     * 调用方应当把 FD 关掉并退出。
     *
     * **跑在 app 进程的 binder 线程上**，所以这里只做一次 map 查找 + `complete`，不做任何重活。
     */
    fun attach(
        token: String,
        fromRemote: ParcelFileDescriptor,
        toRemote: ParcelFileDescriptor,
        remotePid: Int,
        remoteUid: Int,
    ): Boolean {
        val slot = slots[token]
        if (slot == null) {
            Ln.w("BootstrapRegistry: 收到未知 token ${short(token)} 的管道，拒绝")
            return false
        }
        val streams = RemoteStreams(fromRemote, toRemote, remotePid, remoteUid)
        // 先落地 FD 引用再 complete：这样「已 complete 但没人 consume」的兜底关闭
        // 一定能看到 streams，不会出现「以为没人接管、其实也没有 FD 可关」的窗口。
        slot.streams = streams
        if (!slot.deferred.complete(streams)) {
            // 同一 token 被 attach 了两次：第二次的 FD 没人会关，就地处理。
            // 注意**不要**把 slot.streams 置空 —— 它指向的是先到那一对的 FD，
            // 置空会让「已 attach 但没人 consume」的兜底关闭看不到它们。
            Ln.w("BootstrapRegistry: token ${short(token)} 已收到过管道，忽略重复 attach")
            closeQuietly(fromRemote)
            closeQuietly(toRemote)
            return false
        }
        Ln.i("BootstrapRegistry: token ${short(token)} 的管道已就位（remotePid=$remotePid remoteUid=$remoteUid）")
        return true
    }

    /**
     * 声明接管。由 [RemoteConnector] 在 `await()` 到值之后立刻调用。
     *
     * 返回 false 表示这个 token 已被 [unregister] 摘掉（或重复调用）——此时调用方仍然拥有
     * 从 `await()` 拿到的 FD，照常自己关就行，不算错误。
     */
    fun consume(token: String): Boolean {
        val slot = slots[token] ?: return false
        val won = slot.consumed.compareAndSet(false, true)
        if (!won) Ln.w("BootstrapRegistry: token ${short(token)} 被重复接管，忽略")
        return won
    }

    /**
     * 摘掉槽位。会话失败/结束时必须调用（`PrivilegedSession.teardownConnection` 里做）。
     *
     * 若槽位已收到 FD 但没人 [consume]，由这里兜底关闭——这是 FD 泄漏的最后一道防线。
     * 若槽位还没完成，就取消它，让还挂在 `await()` 上的连接立刻报错而不是等到超时。
     */
    fun unregister(token: String) {
        val slot = slots.remove(token) ?: return
        Ln.d("BootstrapRegistry: 注销 token ${short(token)}（剩余 ${slots.size} 个）")
        discard(slot)
    }

    private fun discard(slot: Slot) {
        if (!slot.deferred.isCompleted) {
            // 还没 attach：取消 pending 的等待者，让 connect() 立刻拿到异常。
            slot.deferred.cancel()
            return
        }
        if (slot.consumed.compareAndSet(false, true)) {
            // 已 attach 但没人接管：所有权仍在注册表，这里兜底关掉。
            val streams = slot.streams
            if (streams == null) {
                // 只可能出现在「deferred 因为 cancel 而 isCompleted」的情况，此时没有 FD。
                Ln.d("BootstrapRegistry: 槽位被丢弃，但没有待接管的 FD")
                return
            }
            Ln.w("BootstrapRegistry: 槽位被丢弃且无人接管，兜底关闭两端的 FD")
            closeQuietly(streams.fromRemote)
            closeQuietly(streams.toRemote)
        }
    }

    /** `ParcelFileDescriptor.close()` 会抛 IOException，清理路径上不该因此中断。 */
    private fun closeQuietly(fd: ParcelFileDescriptor) {
        runCatching { fd.close() }.onFailure { Ln.d("BootstrapRegistry: 关闭 FD 失败: ${it.message}") }
    }

    // ---------------------------------------------------------------- 预览 Surface（app → 特权进程）

    /**
     * 预览 Surface 的快照。`generation` 每换一次 +1，特权进程拿它判断「要不要重设」。
     *
     * @property surface 当前该画到哪块 Surface 上；null = 停止预览。
     */
    data class PreviewSurfaceSnapshot(val surface: Surface?, val generation: Int)

    private val previewLock = Any()
    private var previewSurface: Surface? = null
    private var previewGeneration: Int = 0

    /**
     * app 侧 UI（`SurfaceView` 的 `surfaceChanged` / `surfaceDestroyed`）调这里。
     *
     * 不在这里做任何 IPC：特权进程按 [BootstrapProtocol.METHOD_PREVIEW_SURFACE] 轮询
     * [previewSurfaceSnapshot]。这样 UI 线程永远不会被跨进程调用挡住。
     */
    fun setPreviewSurface(surface: Surface?) {
        val generation: Int
        synchronized(previewLock) {
            if (previewSurface === surface) return
            previewSurface = surface
            previewGeneration += 1
            generation = previewGeneration
        }
        Ln.i("BootstrapRegistry: 预览 Surface 已更新（generation=$generation surface=${surface != null}）")
    }

    /** 特权进程取用。跑在 app 进程的 binder 线程上，只做一次加锁读。 */
    fun previewSurfaceSnapshot(): PreviewSurfaceSnapshot = synchronized(previewLock) {
        PreviewSurfaceSnapshot(previewSurface, previewGeneration)
    }

    /** 日志里不打完整 token（它等同于一次性的会话凭证）。 */
    internal fun short(token: String): String =
        if (token.length <= 8) token else token.substring(0, 8) + "..."
}
