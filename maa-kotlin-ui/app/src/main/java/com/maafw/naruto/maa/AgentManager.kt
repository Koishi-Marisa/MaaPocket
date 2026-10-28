package com.maafw.naruto.maa

import android.content.Context
import com.maafw.naruto.third.Ln
import com.sun.jna.Pointer
import java.io.File
import java.util.zip.ZipFile

/**
 * 方案 A：主引擎侧 Agent 管理（AgentClient + agent 进程生命周期）
 *
 * 流程：
 *  1. 把需要的 so 从 APK 解压到 userDir/maa_agent/lib（shell 可写目录）
 *  2. 生成 identifier（tcp://127.0.0.1:<随机端口>，ZeroMQ 通信）
 *  3. 用 app_process 启动 AgentMain（独立进程，跑 AgentServer）
 *  4. MaaAgentClientCreateV2 + BindResource + Connect
 *  5. Connect 成功后，client 自动把 agent 注册的 custom 桥接到引擎 resource
 */
object AgentManager {

    private const val TAG = "AgentManager"
    private const val AGENT_MAIN = "com.maafw.naruto.agent.AgentMain"

    @Volatile
    private var client: Pointer? = null

    @Volatile
    private var agentProcess: Process? = null

    @Volatile
    private var identifier: String? = null

    /** agent 需要的 so（及其依赖） */
    private val NEEDED_SO = listOf(
        "libc++_shared.so",
        "libjnidispatch.so",
        "libMaaAgentServer.so",
        "libMaaAgentClient.so",
        "libMaaUtils.so",
        "libMaaFramework.so",
        "libopencv_world4.so",
        "libonnxruntime.so",
        "libfastdeploy_ppocr.so",
    )

    val isConnected: Boolean
        get() = client?.let { MaaAgentClientLib.INSTANCE.MaaAgentClientConnected(it) == 1.toByte() } ?: false

    fun start(context: Context, resource: Pointer?, userDir: String): Boolean {
        return runCatching {
            Ln.i("$TAG start...")
            if (isConnected) {
                Ln.i("$TAG already connected, reuse")
                return true
            }
            stop()

            val libDir = File("/data/local/tmp", "maa_agent_${context.packageName}/lib").apply { mkdirs() }
            extractLibs(context, libDir)
            Ln.i("$TAG extractLibs done -> ${libDir.absolutePath}")

            // identifier：tcp 回环 + 随机端口（TCP 通道，规避 ipc socket 路径/SELinux 问题）
            val port = 20000 + (System.currentTimeMillis() % 20000).toInt()
            val id = "tcp://127.0.0.1:$port"
            identifier = id

            // 复制 APK 到 data 分区（app_process 从 /data/app 读会被 SELinux 拒绝）
            val apkSrc = runCatching { context.applicationInfo.sourceDir }.getOrNull()
                ?: context.packageResourcePath
            val agentDir = File("/data/local/tmp", "maa_agent_${context.packageName}").apply { mkdirs() }
            val apkPath = File(agentDir, "base.apk")
            val srcLen = runCatching { java.io.File(apkSrc).length() }.getOrDefault(-1L)
            if (!apkPath.exists() || apkPath.length() != srcLen) {
                runCatching { java.io.File(apkSrc).copyTo(apkPath, overwrite = true) }
                    .onFailure { Ln.w("$TAG copy apk to data failed: ${it.message}") }
            }
            Ln.i("$TAG apk=$apkPath (${apkPath.length() / 1024 / 1024}MB)")

            // 1. 先创建 client（CreateTcp = bind 监听本地端口），agent server 稍后 connect 过来
            val cli = MaaAgentClientLib.INSTANCE.MaaAgentClientCreateTcp(port)
            client = cli
            if (cli == null || Pointer.nativeValue(cli) == 0L) {
                Ln.e("$TAG create client failed")
                return false
            }
            Ln.i("$TAG client created (tcp bound :$port)")
            runCatching { MaaAgentClientLib.INSTANCE.MaaAgentClientSetTimeout(cli, 3000) }
            MaaAgentClientLib.INSTANCE.MaaAgentClientBindResource(cli, resource)
            Ln.i("$TAG resource bound")

            // 关键：从 client 拿库生成的 identifier（纯端口字符串，如 "23472"），agent 进程用它 StartUp
            val idBuf = MaaFrameworkLib.INSTANCE.MaaStringBufferCreate()
            MaaAgentClientLib.INSTANCE.MaaAgentClientIdentifier(cli, idBuf)
            val agentId = MaaFrameworkLib.INSTANCE.MaaStringBufferGet(idBuf) ?: id
            MaaFrameworkLib.INSTANCE.MaaStringBufferDestroy(idBuf)
            identifier = agentId
            Ln.i("$TAG client identifier=$agentId")

            // 2. 再启动 agent 独立进程（server StartUp 用 agentId connect 到 client 端口）
            // Root 模式用 su 启动（直接 exec 在引擎进程 domain 下会失败）；Shizuku 模式无 su，直接 exec app_process。
            // 运行模式由进程自身 uid 判定（root=0 / Shizuku shell=2000），不读 App 私有 SharedPreferences（引擎侧无权访问）
            val isRoot = android.os.Process.myUid() == 0
            val proc = if (isRoot) {
                val cmdStr = "CLASSPATH=$apkPath app_process /system/bin $AGENT_MAIN $agentId ${libDir.absolutePath} $userDir"
                Ln.i("$TAG launching agent via su: $cmdStr")
                Runtime.getRuntime().exec(arrayOf("su", "-c", cmdStr))
            } else {
                val cmd = arrayOf(
                    "/system/bin/app_process",
                    "-Djava.class.path=$apkPath",
                    "/system/bin",
                    AGENT_MAIN,
                    agentId,
                    libDir.absolutePath,
                    userDir,
                )
                Ln.i("$TAG launching agent (shizuku direct exec): ${cmd.joinToString(" ")}")
                Runtime.getRuntime().exec(cmd)
            }
            agentProcess = proc
            // 异步读取 agent 进程 stdout/stderr（su 前台执行，proc 输出即 agent 输出），并写入 agent.log 便于导出
            val agentLogFile = File(File(userDir, "maa_logs"), "agent.log").apply { parentFile?.mkdirs() }
            // root 写入的文件默认 rw-rw----，App 进程读不了 -> 设置为其他用户可读
            runCatching { agentLogFile.setReadable(true, false) }
            readProcessOutput(proc, agentLogFile)

            // 3. 等待连接（agent server 需要一点时间启动并 connect；进程死了立即放弃）
            var ok = false
            for (i in 0 until 50) {
                if (!proc.isAlive) {
                    Ln.e("$TAG agent process died before connect")
                    break
                }
                val c = MaaAgentClientLib.INSTANCE.MaaAgentClientConnect(cli)
                if (c == 1.toByte()) {
                    ok = true
                    break
                }
                Thread.sleep(100)
            }
            Ln.i("$TAG connected=$ok id=$id")
            if (!ok) {
                // 失败清理：断开+销毁 client，避免脏状态污染引擎（防止停止任务时 mutex 崩溃）
                runCatching { MaaAgentClientLib.INSTANCE.MaaAgentClientDisconnect(cli) }
                runCatching { MaaAgentClientLib.INSTANCE.MaaAgentClientDestroy(cli) }
                client = null
                runCatching { proc.destroy() }
                agentProcess = null
            }
            ok
        }.getOrElse { e ->
            Ln.e("$TAG start failed: ${e.message}")
            false
        }
    }

    /** 异步读取 agent 进程输出到 Ln，便于诊断（agent 的 println 只进其 stdout）；同时写入 logFile */
    private fun readProcessOutput(proc: Process, logFile: File? = null) {
        Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) {
                        Ln.i("$TAG[agent] $line")
                        runCatching { logFile?.appendText("$line\n") }
                    }
                }
            }.onFailure { }
        }.start()
        Thread {
            runCatching {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) {
                        Ln.w("$TAG[agent-err] $line")
                        runCatching { logFile?.appendText("$line\n") }
                    }
                }
            }.onFailure { }
        }.start()
    }

    fun stop() {
        runCatching {
            client?.let { cli ->
                runCatching { MaaAgentClientLib.INSTANCE.MaaAgentClientDisconnect(cli) }
                runCatching { MaaAgentClientLib.INSTANCE.MaaAgentClientDestroy(cli) }
            }
            client = null
        }
        runCatching { agentProcess?.destroy() }.getOrNull()
        agentProcess = null
        identifier = null
    }

    /** 从 APK 解压需要的 so 到目录（覆盖写） */
    private fun extractLibs(context: Context, dir: File) {
        val apkPath = runCatching { context.applicationInfo.sourceDir }.getOrNull()
            ?: context.packageResourcePath
        runCatching {
            ZipFile(apkPath).use { zip ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (name.startsWith("lib/arm64-v8a/")) {
                        val libName = name.substringAfterLast('/')
                        if (libName in NEEDED_SO) {
                            val out = File(dir, libName)
                            if (!out.exists() || out.length() != entry.size) {
                                zip.getInputStream(entry).use { input ->
                                    out.outputStream().use { output -> input.copyTo(output) }
                                }
                                Ln.i("$TAG extracted $libName -> ${out.absolutePath}")
                            }
                        }
                    }
                }
            }
        }.onFailure { Ln.w("$TAG extractLibs failed: ${it.message}") }
    }
}