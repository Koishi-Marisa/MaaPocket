package com.maapocket.core.pi

import com.maapocket.core.maafw.MaaAgentClient
import com.maapocket.core.maafw.MaaAgentRuntime
import com.maapocket.core.maafw.MaaFrameworkApi
import com.maapocket.core.maafw.MaaResource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 子进程根本没起来（execve 失败 / CWD 不存在 / 文件缺 x 位）。 */
class AgentSpawnException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 子进程起来了，但没能和它完成握手（多半是 `<CWD>/maafw/` 里的 .so 不全，或 CWD 不可写）。 */
class AgentHandshakeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 自 v2.5.0 起 Client 必须注入的 8 个 `PI_*` 环境变量（docs/agents.md §2）。
 *
 * 这个类型只负责**收集**值，不负责决定值从哪来：`PI_CONTROLLER` / `PI_RESOURCE` 是
 * 已解析 i18n 的控制器/资源配置，只有真正持有它们的进程才知道内容。
 * 缺失的项直接不写进环境变量 —— 规范允许省略，而显式写空串会让 agent 以为"拿到了空配置"
 * （例如 `pienv.ControllerType()` 会读到空串而不是走"变量不存在"的分支）。
 */
data class PiAgentEnv(
    /** PI 扩展面的语义化版本。**不是** interface.json 里那个数值型 interface_version=2。 */
    val interfaceVersion: String? = null,
    val clientName: String? = null,
    val clientVersion: String? = null,
    val clientLanguage: String? = null,
    val clientMaafwVersion: String? = null,
    val version: String? = null,
    /** 控制器的单行压缩 JSON。 */
    val controllerJson: String? = null,
    /** 资源的单行压缩 JSON。 */
    val resourceJson: String? = null,
) {
    fun toMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>(8)
        put(map, "PI_INTERFACE_VERSION", interfaceVersion)
        put(map, "PI_CLIENT_NAME", clientName)
        put(map, "PI_CLIENT_VERSION", clientVersion)
        put(map, "PI_CLIENT_LANGUAGE", clientLanguage)
        put(map, "PI_CLIENT_MAAFW_VERSION", clientMaafwVersion)
        put(map, "PI_VERSION", version)
        put(map, "PI_CONTROLLER", controllerJson)
        put(map, "PI_RESOURCE", resourceJson)
        return map
    }

    private fun put(map: MutableMap<String, String>, key: String, value: String?) {
        if (!value.isNullOrEmpty()) map[key] = value
    }

    companion object {
        /** 把一个 JsonElement 编码成单行压缩 JSON（规范要求 PI_CONTROLLER / PI_RESOURCE 是单行）。 */
        fun json(element: JsonElement): String = Json.encodeToString(JsonElement.serializer(), element)
    }
}

/**
 * 起一个 agent 子进程，并完成与它的 MaaAgentClient 握手。
 *
 * ## 必须在哪个进程里跑
 * **必须是在持有 MaaFramework 句柄的那个进程（MaaPocket 里是特权 helper）里调用。**
 * 原因见 [MaaRunController][com.maapocket.core.run.MaaRunController] 的类注释：
 * App 进程刻意不加载 `libMaaFramework.so`，`MaaResource` 只存在于 helper 进程，
 * 而 `bindResource()` 必须绑定**真正在跑 pipeline 的那个资源**，在别的进程里另建一个
 * MaaResource 是错的（agent 会把自定义动作注册到另一个资源上，pipeline 依旧卡死）。
 *
 * ## 启动顺序（死规定，来自 MaaAgentClient.kt 与 docs/agents.md §3）
 * ```
 * ① MaaAgentClient.create(api, null)          // identifier 交给框架生成
 * ② client.identifier()                       // 取回标识（通常是 socket 路径）
 * ③ ProcessBuilder([executable] + args + [identifier])   // identifier 必须是最后一个参数
 *       .directory(workingDir) .environment(PI_*) .redirectErrorStream(true)
 *   后台线程泵 stdout/stderr —— 不泵的话管道满了子进程会阻塞在这里
 * ④ client.bindResource(resource)             // 必须在 connect 之前
 * ⑤ client.connect() + 轮询 client.alive      // 直到超时
 * ```
 * ③ 的"最后一个参数"是两侧源码共同约定的：Go 是 `runAgent(os.Args[1])`，
 * C++ 是 `const char* identifier = argv[argc - 1]`。
 *
 * 线程：本类所有方法都是阻塞的，调用方自己放到后台线程/协程里。
 */
class AgentLauncher(
    private val api: MaaFrameworkApi,
    private val resource: MaaResource,
    /** 日志回调。agent 的 stdout/stderr 会带 `[agent:<label>]` 前缀从这里出去。 */
    private val log: (String) -> Unit,
    /** connect 与 alive 的等待上限。 */
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {

    /**
     * 启动并握手。成功返回的句柄必须被 `close()`，否则会漏一个子进程 + 一条 socket。
     *
     * @param label 日志前缀，通常用 agent 名字。
     * @param identifier 交给 MaaFramework 的 socket 标识；`null`（默认）= 让框架自己生成一个。
     *   只有在调用方需要**预先知道**标识时才传值，顺序不变。
     * @throws AgentSpawnException 进程没起来。
     * @throws AgentHandshakeException 进程起来了但 bind/connect/alive 失败。
     */
    fun launch(
        label: String,
        runtime: MaaAgentRuntime,
        env: PiAgentEnv = PiAgentEnv(),
        identifier: String? = null,
    ): AgentHandle {
        val executable = runtime.executable
        if (!executable.isFile) throw AgentSpawnException("agent 可执行文件不存在：${executable.absolutePath}")
        if (!executable.canExecute()) {
            throw AgentSpawnException(
                "agent 可执行文件没有可执行权限：${executable.absolutePath}" +
                    "（必须来自 nativeLibraryDir 的 lib*.so，见 docs/agents.md §3/§4）",
            )
        }

        // ① 建 client（identifier 默认传 null，让框架生成）
        val client = MaaAgentClient.create(api, identifier)
        var process: Process? = null
        try {
            // ② 取回标识
            val identifier = client.identifier()
            if (identifier.isEmpty()) {
                throw AgentHandshakeException("MaaAgentClientIdentifier() 返回空标识，无法启动 agent：$label")
            }

            // ③ 起子进程
            val command = ArrayList<String>(runtime.args.size + 2)
            command += executable.absolutePath
            command += runtime.args
            command += identifier // 必须是最后一个参数

            val builder = ProcessBuilder(command)
            runtime.workingDir?.let { builder.directory(it) }
            builder.redirectErrorStream(true) // stderr 并进 stdout，一个泵线程就够
            val environment = builder.environment()
            environment.putAll(env.toMap())
            runtime.nativeLibDir?.let { libDir ->
                // MaaAgentRuntime 把这两个变量定义为「宿主侧约定」。go-service 内部其实不读它们
                // （它只认 <CWD>/maafw，见 docs/agents.md §6），但 C++ agent / 其它加载器可能会用，
                // 所以照约定填上，属于防御性设置。
                environment["LD_LIBRARY_PATH"] = prependPath(environment["LD_LIBRARY_PATH"], libDir.absolutePath)
                environment["MAAFW_BINARY_PATH"] = libDir.absolutePath
            }

            val started = try {
                builder.start()
            } catch (t: IOException) {
                throw AgentSpawnException(
                    "agent 进程启动失败（execve 失败 / 工作目录不存在或不可写）：$label -> ${executable.absolutePath}",
                    t,
                )
            }
            process = started

            val pump = startStdoutPump(label, started)
            log("[agent:$label] 已启动 ${executable.absolutePath}（cwd=${runtime.workingDir?.absolutePath ?: "继承父进程"}）")

            // ④ bindResource 必须在 connect 之前，否则 agent 注册的自定义动作会丢
            if (!client.bindResource(resource)) {
                throw AgentHandshakeException("MaaAgentClientBindResource() 失败：$label")
            }
            client.setTimeout(connectTimeoutMs)

            // ⑤ connect + 轮询 alive
            if (!client.connect()) {
                throw handshakeFailure(label, started, "MaaAgentClientConnect() 返回 false")
            }
            val deadline = System.currentTimeMillis() + connectTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (client.alive) break
                if (!started.isAlive) throw handshakeFailure(label, started, "agent 进程已退出")
                try {
                    Thread.sleep(pollIntervalMs)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (!client.alive) {
                throw AgentHandshakeException(
                    "agent 握手超时（${connectTimeoutMs}ms）：$label。" +
                        "最常见原因是工作目录里 maafw/ 的 4 个 .so 不全，或 CWD 不可写",
                )
            }

            // 唯一能证明"agent 真的接上了"的证据：它注册了多少自定义识别/动作
            val actions = runCatching { client.customActionList() }.getOrDefault(emptyList())
            val recognitions = runCatching { client.customRecognitionList() }.getOrDefault(emptyList())
            log(
                "[agent:$label] 握手成功：自定义动作 ${actions.size} 个、自定义识别 ${recognitions.size} 个" +
                    brief(actions, recognitions),
            )
            if (actions.isEmpty() && recognitions.isEmpty()) {
                log("[agent:$label] 警告：已连接但没有注册任何自定义动作/识别，pipeline 里的 Custom 节点仍会失败")
            }
            return AgentHandle(label, client, started, pump)
        } catch (t: Throwable) {
            // 任何一步失败都要把自己起过的东西收干净，否则会留下僵尸进程 + 泄漏的 client
            process?.let { if (it.isAlive) it.destroy() }
            runCatching { if (client.connected) client.disconnect() }
            runCatching { client.close() }
            throw t
        }
    }

    private fun handshakeFailure(label: String, process: Process, why: String): AgentHandshakeException {
        val state = if (process.isAlive) "仍在运行" else {
            val code = runCatching { process.exitValue() }.getOrNull()
            if (code == null) "已退出" else "已退出 exitCode=$code"
        }
        return AgentHandshakeException(
            "agent 握手失败（$why），子进程$state：$label。" +
                "请检查 <CWD>/maafw/ 是否有 4 个 .so、CWD 是否可写（Go agent 写不了 debug/ 会立刻 log.Fatal）",
        )
    }

    /**
     * 把子进程的 stdout/stderr 抽干。
     *
     * 这**不是**可选的调试功能：`redirectErrorStream(true)` 之后只有一条管道，
     * 管道缓冲区满了子进程就会阻塞在 write 上，表现为"启动后毫无反应最后握手超时"。
     */
    private fun startStdoutPump(label: String, process: Process): Thread {
        val thread = Thread({
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        log("[agent:$label] $line")
                    }
                }
            } catch (t: Throwable) {
                // 进程退出时流会 EOF / 被关闭，这里静默即可；但绝不能把异常抛到默认处理器
                // （那会打一条没人看得懂的 crash 日志）。
            }
        }, "agent-stdout-$label")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /** 只把前几个名字写进日志：完整列表可能有几十个，日志会被刷爆。 */
    private fun brief(actions: List<String>, recognitions: List<String>): String {
        if (actions.isEmpty() && recognitions.isEmpty()) return ""
        val names = (actions.map { "action:$it" } + recognitions.map { "recognition:$it" })
        val head = names.take(NAME_LOG_LIMIT).joinToString(", ")
        val tail = if (names.size > NAME_LOG_LIMIT) " …(共 ${names.size} 个)" else ""
        return " [$head$tail]"
    }

    companion object {
        /** 默认 20s：MaaEnd 的 go-service 初始化（含 dlopen MaaFramework）在真机上通常 <3s。 */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 20_000L
        const val DEFAULT_POLL_INTERVAL_MS = 100L
        const val NAME_LOG_LIMIT = 8
    }
}

/**
 * 一个已启动并握手成功的 agent 子进程。
 *
 * [close] 的顺序是刻意的：先断 socket 再杀进程。反过来的话 agent 会卡在 `AgentServerJoin()`
 * 里等着被 SIGKILL，socket 文件也可能留在文件系统上。
 */
class AgentHandle internal constructor(
    val label: String,
    val client: MaaAgentClient,
    val process: Process,
    private val pump: Thread,
) : AutoCloseable {

    @Volatile
    private var closed = false

    /** 进程还活着且我们没主动关过它。 */
    val alive: Boolean get() = !closed && process.isAlive

    override fun close() {
        if (closed) return
        closed = true
        runCatching { client.disconnect() }
        if (process.isAlive) {
            process.destroy()
            try {
                if (!process.waitFor(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)
                }
            } catch (t: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
        runCatching { client.close() }
        runCatching { pump.join(200) }
    }

    companion object {
        /** destroy() 之后给它的退出窗口；超时就 destroyForcibly() 兜底。 */
        const val CLOSE_GRACE_MS = 2_000L
    }
}

/** 把 [prefix] 放到 [existing] 前面，保留原值（`LD_LIBRARY_PATH` 是路径列表）。 */
private fun prependPath(existing: String?, prefix: String): String =
    if (existing.isNullOrEmpty()) prefix else "$prefix:$existing"
