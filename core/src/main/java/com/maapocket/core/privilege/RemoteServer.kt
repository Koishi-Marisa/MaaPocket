package com.maapocket.core.privilege

import android.net.LocalServerSocket
import android.net.LocalSocket
import com.maapocket.core.privilege.RemoteProtocol.Cmd
import com.maapocket.core.privilege.RemoteProtocol.ErrorCode
import com.maapocket.core.privilege.RemoteProtocol.Event
import com.maapocket.core.third.Ln
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 特权进程里的 **LocalSocket 服务端**。
 *
 * 生命周期：`RemoteMain` 先在后台线程调 [serve]（阻塞在 `accept()`），主线程再进
 * `Looper.loop()`。这样框架回调（DisplayListener、Binder 死亡通知）仍有主 Looper 可投递。
 *
 * ## 多路复用（需求 7）
 *
 * 读循环**只做解析和鉴权，从不执行命令**：每个已鉴权的请求帧立刻丢给 [workers]
 * （cached pool，daemon 线程）执行，所以
 * - `display.start`（可能几秒）跑着的时候 `ping` 依然立即返回；
 * - 响应允许乱序，靠 `id` 关联；
 * - 一条连接上可以有任意多个在途请求，没有任何“单飞”窗口。
 *
 * 代价：如果 app 端连发几百个同步命令，worker 会膨胀。协议本身是 app 定义的，
 * 这里只保证“一个慢命令不拖住其它命令”，不替 app 做流控。
 *
 * ## 健壮性（需求 6）
 *
 * 每个请求都在 `try/catch(Throwable)` 里跑，异常被翻译成 `ok=false` 的错误帧；
 * 单帧过大、JSON 非法、缺 `cmd`、版本不符都只丢/回错那一帧，读循环继续；
 * 连 `hello` 都爆掉的连接直接关掉，accept 循环继续等下一个。
 */
class RemoteServer(
    private val token: String,
    /** 必须与 `RemoteProtocol.socketNameFor(token)` 一致。 */
    val socketName: String = RemoteProtocol.socketNameFor(token),
    private val maxLineBytes: Int = RemoteProtocol.MAX_LINE_BYTES,
) : Closeable {

    fun interface CommandHandler {
        /** 在 worker 线程执行。返回 null 表示 `result: null`。抛异常 → `ok=false` 错误帧。 */
        fun handle(params: JsonObject?): JsonObject?
    }

    private val handlers = ConcurrentHashMap<String, CommandHandler>()

    /** 事件订阅者（`RemoteMain` / `RemoteEngine` 用它把日志、帧、生命周期事件推给 app）。 */
    private val listeners = CopyOnWriteArrayList<(String, JsonObject?) -> Unit>()

    private val serverSocket = AtomicReferenceHolder<LocalServerSocket>()

    private val closed = AtomicBoolean(false)

    private val workers: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "maapocket-worker-${workerSeq.incrementAndGet()}").apply { isDaemon = true }
    }

    /** 当前已鉴权连接；同一时刻只服务一个 app 客户端。 */
    private val active = AtomicReferenceHolder<Connection>()

    private val authenticated = AtomicBoolean(false)

    /** `hello` 通过后回调（带 app 的 pid / 协议版本），用于起“app 没了我也退”的看门狗。 */
    var onAuthenticated: ((appPid: Int, protocolVersion: Int) -> Unit)? = null

    /** 已鉴权连接断开时回调一次。 */
    var onClientGone: ((Throwable?) -> Unit)? = null

    /** 是否已有 app 连上并通过 `hello`。 */
    val isClientReady: Boolean get() = authenticated.get() && active.get() != null

    // ------------------------------------------------------------------ 注册

    /**
     * 注册命令处理函数。必须在 [serve] 之前完成注册；运行期注册也安全但不推荐。
     * `hello` / `ping` 由服务端自己处理，**不要**注册它们。
     */
    fun on(cmd: String, handler: CommandHandler) {
        handlers[cmd] = handler
    }

    /** 事件监听：用于把内部的 [event] 调用再广播出去（多播给订阅者，不回调给本对象自己）。 */
    fun addListener(listener: (event: String, data: JsonObject?) -> Unit) {
        listeners += listener
    }

    // ------------------------------------------------------------------ 发送

    /** 推一个无请求 id 的 `event` 帧给 app。返回 false 表示没有客户端或队列已满（事件可丢）。 */
    fun event(name: String, data: JsonObject? = null): Boolean {
        val conn = active.get() ?: return false
        if (!authenticated.get()) return false
        val sent = conn.sendEvent(RemoteFrame(event = name, data = data))
        if (sent) {
            for (listener in listeners) runCatching { listener(name, data) }
        } else {
            droppedEvents.incrementAndGet()
        }
        return sent
    }

    private val droppedEvents = AtomicInteger(0)

    /** 已丢弃的事件帧数量（app 卡住时的背压指标，故意不阻塞特权进程去等它）。 */
    val droppedEventCount: Int get() = droppedEvents.get()

    /**
     * 请求 accept 循环退出并关闭当前连接。幂等，可从任意线程调用。
     * 主要给 `shutdown` 命令和 `RemoteMain` 的异常处理器用。
     */
    fun requestShutdown(reason: String? = null) {
        if (!closed.getAndSet(true)) Ln.i("RemoteServer: shutdown requested (${reason ?: "no reason"})")
        runCatching { serverSocket.getAndSet(null)?.close() }
        active.getAndSet(null)?.close(null)
    }

    override fun close() {
        requestShutdown("close()")
        workers.shutdownNow()
    }

    /** 阻塞直到 accept 循环结束（正常退出或被 [requestShutdown] 打断）。 */
    fun awaitTermination(timeoutMs: Long = 0L): Boolean = terminated.await(
        if (timeoutMs <= 0) Long.MAX_VALUE else timeoutMs,
        TimeUnit.MILLISECONDS,
    )

    private val terminated = CountDownLatch(1)

    // ------------------------------------------------------------------ accept 循环

    /** 阻塞式 accept 循环。正常只在 [close]/[requestShutdown] 后返回。 */
    fun serve() {
        var listener: LocalServerSocket? = null
        try {
            listener = LocalServerSocket(socketName)
            serverSocket.set(listener)
            Ln.i("RemoteServer: listening on abstract socket '$socketName' (uid=${android.os.Process.myUid()})")
        } catch (e: IOException) {
            Ln.e("RemoteServer: cannot bind '$socketName'", e)
            terminated.countDown()
            throw e
        }

        try {
            while (!closed.get()) {
                val client = try {
                    listener.accept()
                } catch (e: IOException) {
                    if (closed.get()) break
                    // 单个 accept 失败不该终止整个服务端。
                    Ln.w("RemoteServer: accept failed: ${e.message}")
                    continue
                }
                runCatching { onNewConnection(client) }
                    .onFailure {
                        Ln.w("RemoteServer: connection setup failed: ${it.message}")
                        runCatching { client.close() }
                    }
            }
        } finally {
            runCatching { listener.close() }
            terminated.countDown()
            Ln.i("RemoteServer: accept loop exited (droppedEvents=$droppedEvents)")
        }
    }

    private fun onNewConnection(socket: LocalSocket) {
        // 只允许一个已鉴权客户端：迟到的第二条连接（比如 app 重启后的旧连接、或别的 app
        // 碰巧猜到 socket 名）直接 bye 掉，避免两个客户端抢同一个特权进程。
        if (isClientReady) {
            val data = buildJsonObject {
                put("reason", "busy")
                put("message", "another client is already connected")
            }
            runCatching {
                BufferedOutputStream(socket.outputStream).also {
                    it.write(RemoteFrame(event = Event.BYE, data = data).encodeLine())
                    it.write('\n'.code)
                    it.flush()
                }
            }
            runCatching { socket.close() }
            Ln.w("RemoteServer: rejected extra connection (already serving a client)")
            return
        }
        val conn = Connection(socket)
        active.set(conn)
        conn.start()
    }

    // ------------------------------------------------------------------ 连接

    private lateinit var logFileNameHolder: String

    /** 由 `RemoteMain` 在 [serve] 前告知，仅用于 `ready` 事件回传日志路径。 */
    fun setLogFileName(name: String) {
        logFileNameHolder = name
    }

    private val logFileName: String
        get() = if (::logFileNameHolder.isInitialized) logFileNameHolder else "unknown"

    private inner class Connection(private val socket: LocalSocket) {

        private val authed = AtomicBoolean(false)

        /** 响应帧：必须送达，用 [LinkedBlockingQueue.put] 阻塞（有界，背压交给 worker 线程）。 */
        private val responseQueue = LinkedBlockingQueue<ByteArray>(RESPONSE_QUEUE_CAPACITY)

        /** 事件帧：可丢。队列满就丢最新的，特权进程绝不为 app 的接收速度买单。 */
        private val eventQueue = LinkedBlockingQueue<ByteArray>(EVENT_QUEUE_CAPACITY)

        private val done = AtomicBoolean(false)

        private val writer = Thread({ writeLoop() }, "maapocket-writer").apply { isDaemon = true }

        private val reader = Thread({ readLoop() }, "maapocket-reader").apply { isDaemon = true }

        private val requestSeq = AtomicLong(1L)

        private val nextRequestId: Long get() = requestSeq.getAndIncrement()

        fun start() {
            writer.start()
            reader.start()
        }

        // ------------------------------ 读

        private fun readLoop() {
            var cause: Throwable? = null
            try {
                val src = BufferedInputStream(socket.inputStream, 16 * 1024)
                while (!done.get()) {
                    var line: String? = null
                    try {
                        line = src.readBoundedLine(maxLineBytes)
                    } catch (e: RemoteProtocolException) {
                        Ln.w("RemoteServer: dropped oversize frame: ${e.detail}")
                        continue
                    }
                    if (line == null) break
                    val frame = try {
                        parseFrame(line)
                    } catch (e: RemoteProtocolException) {
                        // 连 id 都解析不出来，只能发一条不带 id 的错误帧。
                        Ln.w("RemoteServer: malformed frame: ${e.message}")
                        sendErrorNow(null, e.code, e.message, e.detail)
                        continue
                    }
                    if (frame == null) continue
                    handleFrame(frame)
                }
            } catch (e: Throwable) {
                cause = e
            } finally {
                close(cause)
            }
        }

        private fun handleFrame(frame: RemoteFrame) {
            val cmd = frame.cmd
            if (cmd == null) {
                // 客户端不该发事件帧。回一条错误但不断连接。
                sendErrorNow(frame.id, ErrorCode.PROTOCOL, "event frames are server-to-client only", "event=${frame.event}")
                return
            }
            if (frame.v != RemoteProtocol.VERSION) {
                // 版本不符只警告：为了前向兼容地回一条明确错误，而不是静默按新版解析。
                Ln.w("RemoteServer: frame v=${frame.v} != ${RemoteProtocol.VERSION}")
                sendErrorNow(frame.id, ErrorCode.PROTOCOL, "unsupported protocol version ${frame.v}", "expected=${RemoteProtocol.VERSION}")
                return
            }

            if (!authed.get()) {
                if (cmd != Cmd.HELLO) {
                    Ln.w("RemoteServer: cmd '$cmd' before hello; closing connection")
                    sendErrorNow(frame.id, ErrorCode.UNAUTHORIZED, "hello required before any other command", "cmd=$cmd")
                    close(null)
                    return
                }
                val ok = runCatching { authenticate(frame) }.getOrElse {
                    Ln.e("RemoteServer: hello failed", it)
                    sendErrorNow(frame.id, ErrorCode.INTERNAL, it.message ?: "hello failed", it.javaClass.name)
                    false
                }
                if (!ok) {
                    close(null) // 鉴权失败/令牌不符：不再给第二次机会
                }
                return
            }

            if (cmd == Cmd.PING) {
                sendResponseNow(frame.id, buildJsonObject { put("pong", true) }, null)
                return
            }
            if (cmd == Cmd.SHUTDOWN) {
                sendResponseNow(frame.id, buildJsonObject { put("bye", true) }, null)
                event(Event.BYE, buildJsonObject { put("reason", "shutdown-requested") })
                close(null)
                requestShutdown("client requested shutdown")
                return
            }

            val handler = handlers[cmd]
            if (handler == null) {
                sendErrorNow(frame.id, ErrorCode.NO_SUCH_CMD, "unknown command '$cmd'", "known=${handlers.keys.sorted()}")
                return
            }
            // 关键：不在读线程里跑 handler。这样长任务不会挡住同一连接上的后续请求。
            workers.execute {
                val started = System.currentTimeMillis()
                try {
                    val result = handler.handle(frame.params)
                    sendResponseNow(frame.id, result, null)
                } catch (e: RemoteProtocolException) {
                    sendErrorNow(frame.id, e.code, e.message ?: e.code, e.detail)
                } catch (e: Throwable) {
                    Ln.e("RemoteServer: cmd '$cmd' threw", e)
                    sendErrorNow(frame.id, ErrorCode.INTERNAL, e.message ?: e.javaClass.simpleName, e.javaClass.name)
                } finally {
                    val cost = System.currentTimeMillis() - started
                    if (cost > SLOW_COMMAND_MS) Ln.w("RemoteServer: cmd '$cmd' took ${cost}ms")
                }
            }
        }

        private fun authenticate(frame: RemoteFrame): Boolean {
            val params = frame.params
            val given = params?.get("token")?.let { runCatching { it.toString().trim('"') }.getOrNull() }
            if (given == null || given != token) {
                Ln.w("RemoteServer: hello rejected (token mismatch, given=${given?.take(8) ?: "null"})")
                sendErrorNow(frame.id, ErrorCode.UNAUTHORIZED, "invalid token", null)
                return false
            }
            val appPid = params["appPid"]?.let { runCatching { it.toString().toInt() }.getOrNull() } ?: -1
            val version = params["version"]?.let { runCatching { it.toString().toInt() }.getOrNull() } ?: -1
            authed.set(true)
            authenticated.set(true)

            val result = buildJsonObject {
                put("version", RemoteProtocol.VERSION)
                put("socket", socketName)
                put("pid", android.os.Process.myPid())
                put("uid", android.os.Process.myUid())
                put("sdk", android.os.Build.VERSION.SDK_INT)
                put("appPid", appPid)
            }
            sendResponseNow(frame.id, result, null)
            event(Event.READY, buildJsonObject { put("logFile", logFileName) })
            Ln.i("RemoteServer: client authenticated (appPid=$appPid, clientVersion=$version)")
            runCatching { onAuthenticated?.invoke(appPid, version) }
            return true
        }

        // ------------------------------ 写

        fun sendEvent(frame: RemoteFrame): Boolean = eventQueue.offer(frame.encodeLine())

        private fun sendResponseNow(id: Long?, result: JsonObject?, error: RemoteError?) {
            val frame = RemoteFrame(id = id, ok = error == null, result = result, error = error)
            enqueueReliable(frame)
        }

        private fun sendErrorNow(id: Long?, code: String, message: String, detail: String?) {
            sendResponseNow(id, null, RemoteError(code, message, detail))
        }

        /** 可靠帧：队列满时**阻塞**（而不是丢）。写完之前 socket 断了也会被 catch 住。 */
        private fun enqueueReliable(frame: RemoteFrame) {
            if (done.get()) return
            try {
                responseQueue.put(frame.encodeLine())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun writeLoop() {
            var out: OutputStream? = null
            try {
                out = BufferedOutputStream(socket.outputStream, 16 * 1024)
                while (!done.get()) {
                    // 优先响应；没有响应再发事件。带超时是为了能周期性地检查 done。
                    var line: ByteArray? = responseQueue.poll(200, TimeUnit.MILLISECONDS)
                    if (line == null) line = eventQueue.poll()
                    if (line == null) continue
                    out.write(line)
                    out.write('\n'.code)
                    out.flush()
                }
            } catch (e: Throwable) {
                if (!done.get()) Ln.d("RemoteServer: writer stopped: ${e.message}")
            } finally {
                close(null)
            }
        }

        // ------------------------------ 关闭

        /** 幂等关闭。cause=null 表示本端主动关。 */
        fun close(cause: Throwable?) {
            if (!done.compareAndSet(false, true)) return
            runCatching { socket.close() }
            reader.interrupt()
            writer.interrupt()
            responseQueue.clear()
            eventQueue.clear()
            if (active.get() === this) {
                active.set(null)
                authenticated.set(false)
            }
            Ln.i("RemoteServer: connection closed (cause=${cause?.javaClass?.simpleName ?: "local"})")
            if (authed.get()) runCatching { onClientGone?.invoke(cause) }
        }
    }

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

    companion object {        /** 写线程的响应队列容量：满了就让 worker 阻塞，绝不丢响应。 */
        private const val RESPONSE_QUEUE_CAPACITY = 64

        /** 事件队列容量：满了就丢事件，保证特权进程不被 app 的接收速度拖死。 */
        private const val EVENT_QUEUE_CAPACITY = 256

        /** 超过这个耗时就打一条 warning，方便定位“谁把连接占住了”。 */
        private const val SLOW_COMMAND_MS = 2_000L

        private val workerSeq = AtomicInteger(0)
    }
}
