package com.maapocket.core.privilege

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.maapocket.core.third.Ln
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

/** socket 连不上/握手失败。携带最后一次的真实原因，便于区分 EACCES 和“还没起来”。 */
class RemoteConnectException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 特权通道的**客户端**（跑在 app 进程）。
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
 * ## 连接与重连
 *
 * [connect] 会在 [RemoteProtocol.CONNECT_TIMEOUT_MS] 内按
 * [RemoteProtocol.CONNECT_RETRY_INTERVAL_MS] 重试：特权进程从 fork 到 ART 起来通常要
 * 几百毫秒到两秒，第一次必然连不上。断开后 [onClosed] 会被回调一次，由
 * [PrivilegedSession] 决定是重连还是报错。
 */
class RemoteConnector(
    val socketName: String,
    /** 与特权进程共享的 token，用于 `hello` 鉴权。 */
    private val token: String,
    private val namespace: LocalSocketAddress.Namespace = LocalSocketAddress.Namespace.ABSTRACT,
    private val maxLineBytes: Int = RemoteProtocol.MAX_LINE_BYTES,
) : Closeable {

    private val socket = AtomicReferenceHolder<LocalSocket>()

    private var input: InputStream? = null
    private var output: OutputStream? = null

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

    /** 连接断开（EOF、socket 异常、本地 [close]）。参数为 null 表示是本地主动关的。 */
    var onClosed: ((Throwable?) -> Unit)? = null

    val isConnected: Boolean get() = socket.get() != null && !closed.get()

    private var reader: Thread? = null

    // ------------------------------------------------------------------ 连接

    /**
     * 阻塞式连接 + 重试，直到 [timeoutMs] 用完。
     * 成功返回后读循环已经在跑；`hello` 由 [PrivilegedSession] 负责发。
     */
    fun connect(timeoutMs: Long = RemoteProtocol.CONNECT_TIMEOUT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            try {
                val s = LocalSocket()
                s.connect(LocalSocketAddress(socketName, namespace))
                socket.set(s)
                input = BufferedInputStream(s.inputStream, 16 * 1024)
                output = BufferedOutputStream(s.outputStream, 16 * 1024)
                closed.set(false)
                Ln.i("RemoteConnector: connected to $socketName (namespace=$namespace, attempt=$attempt)")
                startReader()
                return
            } catch (e: IOException) {
                lastError = e
                Ln.d("RemoteConnector: connect attempt $attempt failed: ${e.message}")
                runCatching { socket.getAndSet(null)?.close() }
                try {
                    Thread.sleep(RemoteProtocol.CONNECT_RETRY_INTERVAL_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw RemoteConnectException(
            "could not connect to $socketName within ${timeoutMs}ms after $attempt attempt(s)",
            lastError,
        )
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
        socket.getAndSet(null)?.let { runCatching { it.close() } }
        input = null
        output = null

        val error = RemoteConnectException("connection to $socketName lost", cause)
        for ((_, waiter) in pending) waiter.completeExceptionally(error)
        pending.clear()

        if (cause != null) Ln.w("RemoteConnector: read loop ended: ${cause.javaClass.simpleName}: ${cause.message}")
        // 本地 close() 不再回调 onClosed，避免上层把主动关闭当成异常断开去做重连。
        if (!wasClosedByUs) runCatching { onClosed?.invoke(cause) }
    }

    override fun close() {
        // 先置 closed 再关 socket：teardown 里靠它区分主动/被动断开。
        closed.set(true)
        socket.getAndSet(null)?.let { runCatching { it.close() } }
        reader?.interrupt()
        reader = null
        input = null
        output = null
        for ((_, waiter) in pending) waiter.cancel()
        pending.clear()
    }

    /** 只需要一个可空原子引用；JDK 的 AtomicReference 也行，这里避免多一个 import 面。 */
    private class AtomicReferenceHolder<T> {
        @Volatile
        private var value: T? = null

        fun get(): T? = value

        fun set(v: T?) {
            value = v
        }

        fun getAndSet(v: T?): T? {
            val old = value
            value = v
            return old
        }
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
