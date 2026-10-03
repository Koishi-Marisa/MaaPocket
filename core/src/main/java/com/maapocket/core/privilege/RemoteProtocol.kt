package com.maapocket.core.privilege

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * 特权通道协议：**一行一帧 UTF-8 JSON**，`\n` 结尾。
 *
 * ## 为什么不是 AIDL
 *
 * MAA-Meow 用「AIDL `RemoteService` + ContentProvider 引导 + binder 回传」把特权进程接回 app。
 * 那条链路里 ContentProvider 只负责把 binder 运回去（Shizuku 的 binder 只能 app→Shizuku，
 * 不能反过来），代价是 `RootServiceBootstrapProvider/Client/Registry` +
 * `RootIContentProviderCompat` 四个文件加一段 `enableContentProviderExternal` 前的
 * `getContentProviderExternal` 舞步。改成 LocalSocket 后这些全部消失，协议本身变成自描述的
 * JSON 行，调试时 `nc` 就能手工敲。
 *
 * ## 帧类型
 *
 * - **请求**：`{"v":1,"id":7,"cmd":"ping","params":{...}}` — [RemoteFrame.cmd] 非空。
 * - **响应**：`{"v":1,"id":7,"ok":true,"result":{...}}` 或 `{"v":1,"id":7,"ok":false,"error":{...}}`
 *   — [RemoteFrame.ok] 非空，`id` 与请求一致。**每个请求必须有且只有一个响应**。
 * - **事件**：`{"v":1,"event":"display.changed","data":{...}}` — 主动推送，[RemoteFrame.id]
 *   通常为空；带 `id` 时表示“该事件属于某个长任务”（例如 `job.progress`）。
 *
 * ## 健壮性约束（需求 6）
 *
 * 1. 单行硬上限 [MAX_LINE_BYTES]。超长行**只丢这一帧**：读端继续丢弃字节直到下一个 `\n`，
 *    回一帧 `E_TOO_LONG`，连接不断、服务不死。
 * 2. UTF-8 严格解码，解码失败同样只丢一帧。
 * 3. JSON 解析失败 / 缺少 `cmd` / 未知 `cmd` / 参数不合法 → 一律回 `ok:false` + [RemoteError]，
 *    **绝不抛到读循环外面**。
 * 4. 未知字段一律忽略（[Json.ignoreUnknownKeys]），协议可以向前加字段。
 */
object RemoteProtocol {

    /** 协议版本。改动不兼容语义时 +1；对端比本端低时至少要能回 `E_PROTOCOL`。 */
    const val VERSION = 1

    /** 抽象命名空间里 socket 名的前缀，完整名 = `maapocket.<token>`；见 [socketNameFor]。 */
    const val SOCKET_PREFIX = "maapocket."

    /** 单行上限 1 MiB。`capture.frame` 的 base64 图片可能到几百 KB，留够余量。 */
    const val MAX_LINE_BYTES = 1 shl 20

    /** 连接重试节奏：特权进程从 fork 到 `app_process` 起 ART 通常 200ms~2s。 */
    const val CONNECT_RETRY_INTERVAL_MS = 200L

    /** 连不上就放弃的总时长。MAA-Meow 是 spawnTimeout 15s + 3s 余量。 */
    const val CONNECT_TIMEOUT_MS = 18_000L

    /** 默认请求超时；长任务（`display.start`、`engine.setup`）自己传更大的值。 */
    const val REQUEST_TIMEOUT_MS = 20_000L

    /** 事件流缓冲；满了丢最旧的（[kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST]），
     *  保证读线程永远不被慢消费者阻塞。 */
    const val EVENT_BUFFER = 256

    /** 抽象命名空间不落文件系统，因此不受目录 0700 限制，也不用操心 socket 文件的属主，
     *  只受 SELinux `unix_stream_socket connectto` 约束（见 RemoteConnector 的说明）。*/
    val json: Json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
        isLenient = false
    }

    /** 抽象 socket 名由 token 推导，双方分别从 `--token=` argv 和 spawn 时的本地 token 得到，
     *  不需要任何额外握手。token 是 32 位十六进制 UUID，长度远低于 sun_path 上限。 */
    fun socketNameFor(token: String): String = SOCKET_PREFIX + token

    /**
     * `RemoteMain.java` 用的 [Event.FATAL] 载荷构造器。
     *
     * 为什么单独开一个：`jsonObjectOf` 是 `internal`（Kotlin 的 internal 顶层函数在字节码里会被
     * 改名成 `jsonObjectOf$core`），Java 调不到；而 `@JvmStatic` 打在 `object` 的成员上会生成
     * 真正的静态方法，Java 侧就是 `RemoteProtocol.fatalData(...)`，不用碰 `kotlin.Pair`。
     */
    @JvmStatic
    fun fatalData(threadName: String, error: Throwable): JsonObject = buildJsonObject {
        put("thread", threadName)
        put("throwable", error.toString())
        put("stack", error.stackTraceToString())
    }

    /** 协议级错误码。app 侧按 [RemoteError.code] 分支，不解析 message。 */
    object ErrorCode {
        const val PROTOCOL = "E_PROTOCOL"
        const val BAD_FRAME = "E_BAD_FRAME"
        const val TOO_LONG = "E_TOO_LONG"
        const val UNAUTHORIZED = "E_UNAUTHORIZED"
        const val NO_SUCH_CMD = "E_NO_SUCH_CMD"
        const val BAD_PARAMS = "E_BAD_PARAMS"
        const val NOT_READY = "E_NOT_READY"
        const val BUSY = "E_BUSY"
        const val INTERNAL = "E_INTERNAL"
        const val UNSUPPORTED = "E_UNSUPPORTED"

        /** MaaFramework 绑定类尚未接进本仓（见 `RemoteEngine.maaBootstrap`），`engine.*` 会回这个。 */
        const val MAAFRAMEWORK_NOT_WIRED = "E_MAAFRAMEWORK_NOT_WIRED"
    }

    /** app → 特权进程的请求名。 */
    object Cmd {
        /** 连接后**第一帧必须**是它，负责鉴权（token + 对端 uid）。 */
        const val HELLO = "hello"
        const val PING = "ping"

        /** app 上报自己的 pid，供特权进程的看门狗判断 app 是否还活着。 */
        const val HEARTBEAT = "heartbeat"

        const val ENGINE_SETUP = "engine.setup"
        const val ENGINE_INFO = "engine.info"
        const val SHUTDOWN = "shutdown"

        const val DISPLAY_START = "display.start"
        const val DISPLAY_STOP = "display.stop"
        const val DISPLAY_RESTART = "display.restart"
        const val DISPLAY_RESIZE = "display.resize"
        const val DISPLAY_STATE = "display.state"

        const val CAPTURE_FRAME = "capture.frame"
        const val CAPTURE_PREVIEW = "capture.preview"

        const val INPUT_TOUCH = "input.touch"
        const val INPUT_KEY = "input.key"

        /** 走 `DriverClass.startApp`（虚拟屏/主屏启动游戏并等首帧）。 */
        const val APP_START = "app.start"

        // ---------------------------------------------------------------- MaaFramework
        // 以下 5 条把 MaaFramework 真正跑起来。**必须跑在特权进程里**：MaaFramework 与外部库
        // `bridge`（`GetLockedPixels`/`UnlockPixels`/`DispatchInputMessage` 是进程内符号）
        // 必须同进程，而只有特权进程能建虚拟屏、能注输入。

        /** 建 AndroidNative 控制器并 `postConnection` 到虚拟屏。 */
        const val MAA_CONTROLLER_START = "maa.controller.start"

        /** 按顺序叠加加载 resource 目录（后者优先级高）。 */
        const val MAA_RESOURCE_LOAD = "resource.load"

        /** 跑一条 task，进度用 [Event.JOB_PROGRESS] 主动推送。 */
        const val MAA_TASK_RUN = "task.run"

        /** 请求停止正在跑的 task。 */
        const val MAA_TASK_STOP = "task.stop"

        /** 汇总状态（有没有加载、控制器/资源/任务各自的就绪情况）。 */
        const val MAA_STATE = "maa.state"
    }

    /** 特权进程 → app 的主动事件名。 */
    object Event {
        /** 服务端就绪且鉴权通过，随时可收请求。 */
        const val READY = "ready"

        /** 服务端即将退出（收到 `shutdown` 或看门狗判定 app 已死）。 */
        const val BYE = "bye"

        /** 转发 `Ln` 日志，`data = {"level":"I","msg":"..."}`。 */
        const val LOG = "log"

        /** 主动上报的致命异常，`data = {"thread":"main","throwable":"java.lang.IllegalStateException: ..."}`。 */
        const val FATAL = "fatal"

        /** 虚拟屏/主屏尺寸或 id 发生变化（`PrimaryDisplayManager` 的 DisplayListener 触发）。 */
        const val DISPLAY_CHANGED = "display.changed"

        /** `capture.preview` 开启后持续下发的帧，`data = {"width":..,"height":..,"format":"jpeg","dataBase64":".."}`。 */
        const val FRAME = "frame"

        /** `InputControlUtils` 的触控回调（原 AIDL `ITouchEventCallback`），
         *  `data = {"x":..,"y":..,"actionMasked":..,"pointerId":..}`。 */
        const val TOUCH = "touch"

        /** 长任务进度，带 `id` 指向对应请求。 */
        const val JOB_PROGRESS = "job.progress"
    }
}

/** 一帧。三个类型共用一个结构：请求看 [cmd]/[params]，响应看 [ok]/[result]/[error]，事件看 [event]/[data]。 */
@Serializable
data class RemoteFrame(
    val v: Int = RemoteProtocol.VERSION,
    val id: Long? = null,
    val cmd: String? = null,
    val params: JsonObject? = null,
    val event: String? = null,
    val data: JsonObject? = null,
    val ok: Boolean? = null,
    val result: JsonObject? = null,
    val error: RemoteError? = null,
    val ts: Long = System.currentTimeMillis(),
)

@Serializable
data class RemoteError(
    val code: String,
    val message: String,
    val detail: String? = null,
)

/** 一行超长 / 非法 UTF-8 / 非法 JSON。读循环捕获它、丢弃该帧、继续服务。 */
class RemoteProtocolException(
    val code: String,
    message: String,
    val detail: String? = null,
) : Exception(message)

/** 把 JSON 值拼成 `JsonObject` 的小工具，省掉到处 import `buildJsonObject`。 */
internal fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, value) in pairs) {
        when (value) {
            null -> put(k, JsonNull)
            is JsonElement -> put(k, value)
            is Boolean -> put(k, JsonPrimitive(value))
            is Number -> put(k, JsonPrimitive(value))
            else -> put(k, JsonPrimitive(value.toString()))
        }
    }
}

internal inline fun <reified T> JsonObject.decode(): T =
    RemoteProtocol.json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), this)

/**
 * 读一行，硬上限 [maxBytes]。超长时把余下字节丢掉（**必须在下一个 `\n` 之后才能重新同步**），
 * 然后抛 [RemoteProtocolException]；调用方丢帧继续。
 *
 * 返回 null 只在流正常结束（EOF）时。
 */
internal fun InputStream.readBoundedLine(maxBytes: Int): String? {
    val buf = java.io.ByteArrayOutputStream(256)
    var overflow = false
    while (true) {
        val b = read()
        if (b == -1) {
            if (overflow) throw RemoteProtocolException(
                RemoteProtocol.ErrorCode.TOO_LONG, "line exceeded $maxBytes bytes"
            )
            return if (buf.size() == 0) null
            else String(buf.toByteArray(), StandardCharsets.UTF_8)
        }
        if (b == '\n'.code) {
            if (overflow) throw RemoteProtocolException(
                RemoteProtocol.ErrorCode.TOO_LONG, "line exceeded $maxBytes bytes"
            )
            val text = String(buf.toByteArray(), StandardCharsets.UTF_8)
            // 容忍 CRLF：手工用 nc 调试时很常见
            return text.removeSuffix("\r")
        }
        if (overflow) continue
        if (buf.size() >= maxBytes) {
            overflow = true
            buf.reset()
            continue
        }
        buf.write(b)
    }
}

/** 编码一帧（不含换行）。超限就抛，调用方自己决定回错误还是断开。 */
internal fun RemoteFrame.encodeLine(): ByteArray {
    val text = RemoteProtocol.json.encodeToString(RemoteFrame.serializer(), this)
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size > RemoteProtocol.MAX_LINE_BYTES) {
        throw RemoteProtocolException(
            RemoteProtocol.ErrorCode.TOO_LONG, "outbound frame is ${bytes.size} bytes"
        )
    }
    return bytes
}

/** 写一帧并 flush。反序列化失败只影响这一帧，因此这里抛 [RemoteProtocolException] 由调用方处理。 */
internal fun OutputStream.writeFrame(frame: RemoteFrame) {
    write(frame.encodeLine())
    write('\n'.code)
    flush()
}

/**
 * 解析一帧。非法 JSON → `E_BAD_FRAME`（含原始行前 200 字符做诊断，够定位又不至于把日志灌爆）。
 * 空行直接返回 null（读循环跳过）。
 */
internal fun parseFrame(line: String): RemoteFrame? {
    if (line.isBlank()) return null
    return try {
        RemoteProtocol.json.decodeFromString(RemoteFrame.serializer(), line)
    } catch (e: Exception) {
        throw RemoteProtocolException(
            RemoteProtocol.ErrorCode.BAD_FRAME,
            "malformed JSON frame: ${e.message}",
            line.take(200),
        )
    }
}

/** 供 [RemoteMain] 用：把 EOF 统一成同一种异常，免得两端各写一遍。 */
internal class PeerClosedException(message: String) : EOFException(message)
