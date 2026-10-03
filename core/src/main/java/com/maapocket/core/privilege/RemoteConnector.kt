package com.maapocket.core.privilege

import com.maapocket.core.third.Ln
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 管道迟迟没送回来/建立失败。携带最后一次的真实原因，便于区分「app 没接住」和「特权进程没起来」。 */
class RemoteConnectException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 特权通道的**客户端**（跑在 app 进程）。
 *
 * ## 连接是怎么建立的
 *
 * 不再是自己去 `connect()` 某个地址，而是**等特权进程把管道送回来**：
 *
 * 1. `PrivilegedSession.start()` 先 `BootstrapRegistry.register(token)` 挂号，再 spawn；
 * 2. 特权进程起来后用 `getContentProviderExternal` 调 app 的 `BootstrapProvider`，
 *    在 `Bundle` 里塞两个 `ParcelFileDescriptor`；
 * 3. [connect] 阻塞等那个槽位（`CompletableDeferred`），拿到后把自己的
 *    [InputStream]/[OutputStream] 指过去，剩下的读循环与多路复用一行没变。
 *
 * 为什么不用 LocalSocket 见 `BootstrapProtocol` 的类注释（SELinux `connectto` 拒绝）。
 *
 * ## 多路复用（需求 7）
 *
 * app 完全可以在一段长任务跑着的时候再发请求，这条连接不会串行化它们：
 *
 * - 每个请求自带单调递增的 `id`（[AtomicLong]），响应按 `id` 派发到各自的
 *   [CompletableDeferred]；**没有全局锁，没有“一次一个请求”的窗口**。
 * - 读循环独立线程，长任务的响应等多久都不会挡住别的响应。
 * - 服务端也是每请求一线程（见 `RemoteServer`），所以 `display.start` 卡住时 `ping` 照样秒回。
 * - 事件帧走 [events]，与请求/响应共用一条连接但不占用 id 空间。
 *
 * ## 写路径
 *
 * 这里**不加**独立写线程：app 侧的写入量由 app 自己控制（`capture.preview` 的帧率
 * 是 app 定义的），背压应该如实反馈给调用者，而不是悄悄堆在内存里。写操作用一把锁串行化，
 * 保证一帧不会被两条线程写交叉。服务端不同：它面向的是可能卡住的 app，所以用独立写线程 +
 * 有界队列丢事件（见 [RemoteServer]）。
 *
 * ## FD 所有权
 *
 * [connect] 一旦从槽位拿到值就**立刻** `BootstrapRegistry.consume(token)` 声明接管，
 * 此后两个 FD 的生命周期完全归本类：无论是 [connect] 中途失败、读循环结束还是本地
 * [close]，都由这里负责关掉。注册表那边的兜底关闭因此不会重复关（见 `BootstrapRegistry`）。
 *
 * ## 断开
 *
 * 断开后 [onClosed] 会被回调一次（本地主动 [close] 不算），由 [PrivilegedSession] 决定
 * 是重连还是报错。
 */
class RemoteConnector(
    /** 与特权进程共享的 token：既用于 `hello` 鉴权，也用于从注册表认领管道。 */
    private val token: String,
    /** `BootstrapRegistry.register(token)` 返回的槽位；特权进程 attach 进来后才会完成。 */
    private val slot: CompletableDeferred<RemoteStreams>,
    private val maxLineBytes: Int = RemoteProtocol.MAX_LINE_BYTES,
) : Closeable {

    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** 是否已经拿到 FD。读循环/写入都要求它为 true。 */
    private val attached = AtomicBoolean(false)

    /** 写锁：保证一帧的字节不会被两条线程交叉写入。 */
    private val writeLock = Any()

    private val nextId = AtomicLong(1L)

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<RemoteFrame>>()

    private val closed = AtomicBoolean(false)

    private val _events = MutableSharedFlow<RemoteFrame>(
        replay = 0,
        extraBufferCapacity = RemoteProtocol.EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 服务端推来的所有**非响应**帧（含 `ready` / `log` / `frame` / `display.changed`）。 */
    val events: SharedFlow<RemoteFrame> = _events.asSharedFlow()

    /** 连接断开（EOF、管道异常、本地 [close]）。参数为 null 表示是本地主动关的。 */
    var onClosed: ((Throwable?) -> Unit)? = null

    val isConnected: Boolean get() = attached.get() && !closed.get()

    private var reader: Thread? = null

    // ------------------------------------------------------------------ 连接

    /**
     * 阻塞等待管道送回来，最多 [timeoutMs]。
     * 成功返回后读循环已经在跑；`hello` 由 [PrivilegedSession] 负责发。
     *
     * 为什么是 `runBlocking`：槽位由 binder 线程完成，这里必须**同步**等——调用方
     * （`PrivilegedSession.start`）之后的每一步都假设连接已就绪。原来的 LocalSocket 版本
     * 也是一个带 `Thread.sleep` 的阻塞重试循环，阻塞语义没有变。
     */
    fun connect(timeoutMs: Long = RemoteProtocol.CONNECT_TIMEOUT_MS) {
        val streams = try {
            runBlocking { withTimeout(timeoutMs) { slot.await() } }
        } catch (e: Throwable) {
            // 超时（TimeoutCancellationException）/ 槽位被 unregister 取消 / 其它异常
            // 都归一到同一个类型，上层只管报「连不上」。
            throw RemoteConnectException(
                "privileged pipe not delivered within ${timeoutMs}ms (token=${BootstrapRegistry.short(token)})",
                e,
            )
        }

        // 拿到就立刻声明接管：从这一行起，两个 FD 由本类负责关闭。
        BootstrapRegistry.consume(token)

        if (closed.get()) {
            // 等待期间上层已经 close() 了：直接归还资源，别把连接建起来。
            closeStreams(streams)
            throw RemoteConnectException("connector already closed (token=${BootstrapRegistry.short(token)})")
        }

        input = BufferedInputStream(android.os.ParcelFileDescriptor.AutoCloseInputStream(streams.fromRemote), 16 * 1024)
        output = BufferedOutputStream(android.os.ParcelFileDescriptor.AutoCloseOutputStream(streams.toRemote), 16 * 1024)
        attached.set(true)
        Ln.i(
            "RemoteConnector: pipe attached (remotePid=${streams.remotePid}, " +
                "remoteUid=${streams.remoteUid}, token=${BootstrapRegistry.short(token)})",
        )
        startReader()
    }

    private fun startReader() {
        reader = Thread({ readLoop() }, "maapocket-connector-reader").apply {
            isDaemon = true
            start()
        }
    }

    // ------------------------------------------------------------------ 读

    private fun readLoop() {
        var cause: Throwable? = null
        try {
            val src = input ?: throw IOException("not connected")
            while (!closed.get()) {
                var line: String? = null
                try {
                    line = src.readBoundedLine(maxLineBytes)
                } catch (e: RemoteProtocolException) {
                    // 只丢这一帧：readBoundedLine 已经把游标挪到下一个 '\n' 之后
                    Ln.w("RemoteConnector: dropped oversize frame from server: ${e.message}")
                    continue
                }
                if (line == null) break // EOF：服务端退了
                val frame = try {
                    parseFrame(line)
                } catch (e: RemoteProtocolException) {
                    Ln.w("RemoteConnector: dropped malformed frame: ${e.message} detail=${e.detail}")
                    continue
                }
                if (frame == null) continue
                if (frame.cmd != null) {
                    // 协议是单向的：只有 app → 服务端的请求。真收到就记一笔，绝不静默丢。
                    Ln.w("RemoteConnector: unexpected request frame from server cmd=${frame.cmd}")
                    continue
                }
                val id = frame.id
                if (id != null) {
                    val waiter = pending.remove(id)
                    if (waiter != null) {
                        waiter.complete(frame)
                        continue
                    }
                    // 迟到的响应（调用方已经超时放弃）：丢掉即可，连接继续用。
                    Ln.d("RemoteConnector: late response for id=$id dropped")
                    continue
                }
                _events.tryEmit(frame)
            }
        } catch (e: Throwable) {
            cause = e
        } finally {
            teardown(cause)
        }
    }

    // ------------------------------------------------------------------ 写

    /** 发一帧；失败抛 [IOException]。 */
    private fun writeFrame(frame: RemoteFrame) {
        val out = output ?: throw IOException("RemoteConnector is not connected")
        val bytes = frame.encodeLine()
        synchronized(writeLock) {
            out.write(bytes)
            out.write('\n'.code)
            out.flush()
        }
    }

    /** 发请求并等响应。超时抛 [kotlinx.coroutines.TimeoutCancellationException]，调用方自行兜。 */
    suspend fun request(
        cmd: String,
        params: JsonObject? = null,
        timeoutMs: Long = RemoteProtocol.REQUEST_TIMEOUT_MS,
    ): RemoteFrame {
        val id = nextId.getAndIncrement()
        val waiter = CompletableDeferred<RemoteFrame>()
        pending[id] = waiter
        try {
            writeFrame(RemoteFrame(id = id, cmd = cmd, params = params))
            return withTimeout(timeoutMs) { waiter.await() }
        } catch (e: Throwable) {
            throw e
        } finally {
            pending.remove(id)
        }
    }

    /**
     * 发一帧但不关心响应（`heartbeat` / `capture.preview` 这类单向通知）。
     * 响应仍然会由服务端发出，只是会被当作“迟到响应”丢掉。
     */
    fun notify(cmd: String, params: JsonObject? = null) {
        val id = nextId.getAndIncrement()
        writeFrame(RemoteFrame(id = id, cmd = cmd, params = params))
    }

    /** 读循环结束/本地关闭的统一出口。幂等。 */
    private fun teardown(cause: Throwable?) {
        val wasClosedByUs = closed.getAndSet(true)
        attached.set(false)
        closeQuietly(input)
        closeQuietly(output)
        input = null
        output = null

        val error = RemoteConnectException("privileged pipe to ${BootstrapRegistry.short(token)} lost", cause)
        for ((_, waiter) in pending) waiter.completeExceptionally(error)
        pending.clear()

        if (cause != null) Ln.w("RemoteConnector: read loop ended: ${cause.javaClass.simpleName}: ${cause.message}")
        // 本地 close() 不再回调 onClosed，避免上层把主动关闭当成异常断开去做重连。
        if (!wasClosedByUs) runCatching { onClosed?.invoke(cause) }
    }

    override fun close() {
        // 先置 closed 再关流：teardown 里靠它区分主动/被动断开。
        closed.set(true)
        attached.set(false)
        closeQuietly(input)
        closeQuietly(output)
        input = null
        output = null
        reader?.interrupt()
        reader = null
        for ((_, waiter) in pending) waiter.cancel()
        pending.clear()
    }

    /** 拿完槽位但决定不建连接时（上层已 close）把 FD 还回去。 */
    private fun closeStreams(streams: RemoteStreams) {
        runCatching { streams.fromRemote.close() }
        runCatching { streams.toRemote.close() }
    }

    private fun closeQuietly(stream: Closeable?) {
        if (stream == null) return
        runCatching { stream.close() }.onFailure { Ln.d("RemoteConnector: close failed: ${it.message}") }
    }

    companion object {
        /** `hello` 用的参数。见 `RemoteServer` 的鉴权。 */
        fun helloParams(token: String, appPid: Int, version: Int): JsonObject = jsonObjectOf(
            "token" to token,
            "appPid" to appPid,
            "version" to version,
        )
    }
}
