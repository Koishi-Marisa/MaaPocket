package com.maapocket.core.pi

import android.content.Context
import com.maapocket.core.maafw.MaaAgentRuntime
import java.io.File
import java.io.IOException

/**
 * 在起动 agent **之前**把它的工作目录（CWD）准备出来。
 *
 * ## 为什么 agent 需要一个专门的 CWD
 * docs/agents.md §6 列的是从 MaaEnd 的 Go agent 源码里读出来的硬性要求，不是我们的偏好：
 *
 * | 要求 | 出处 |
 * | --- | --- |
 * | `<CWD>/maafw/` 下必须有 `libMaaFramework.so` / `libMaaToolkit.so` / `libMaaAgentServer.so` / `libMaaAgentClient.so` | `agent/go-service/agent.go`：`libDir := filepath.Join(getCwd(), "maafw")` 后 `maa.Init(maa.WithLibDir(libDir), …)` |
 * | CWD 必须可写 | `logger.go`：`os.MkdirAll("debug", 0755)` 失败即 `log.Fatal()...("Failed to initialize logger")` |
 * | `<CWD>/locales/go-service/zh_cn.json` 必须存在 | `pkg/i18n/i18n.go` 的 `resolveLocaleDir()` 用它判定候选目录是否合格 |
 *
 * 而 PI 规范说 agent 的 CWD 应该是 `interface.json` 所在目录 —— 那是 pc/Windows 上的约定。
 * Android 上 pack 解在 assets 里、文件系统上没有那个目录，而且 assets 不可写，
 * 所以 MaaPocket 的做法是**另建一个工作目录**，把它需要的东西都铺进去：
 *
 * ```
 * <externalFilesDir>/agent-area/<agentName>/
 * ├── .complete                      # 幂等标记（内容=stamp，见 [prepare]）
 * ├── maafw/                         # 4 个原生库的副本（Go agent 只认 <CWD>/maafw）
 * ├── debug/                         # Go logger 要写的目录
 * └── locales/                       # 从 assets/pi-agent-bundle/<agentName>/ 解出来的数据
 * ```
 *
 * 注意：**可执行文件本身不复制**，仍在 APK 原生库目录里原地 execve，理由见下。
 *
 * ## 为什么 maafw/\*.so 必须复制，而可执行文件不能复制
 * 这两件事的规则不一样：
 *
 * - `maafw/` 里的 4 个 .so 必须**复制**到 `<CWD>/maafw/`：Go agent 里写死了
 *   `libDir := filepath.Join(getCwd(), "maafw")` 再 `maa.Init(WithLibDir(libDir))`，
 *   它不看 `LD_LIBRARY_PATH`、也不认 `nativeLibraryDir`（docs/agents.md §6）。这是上游的硬要求，
 *   没有别的选择。dlopen 一个普通文件不受"可执行位"限制，所以复制可行。
 * - agent 可执行文件**不能**复制到 app 可写目录再执行。Android 10+ 上软件包的数据目录
 *   （`app_data_file`）对外部/app 域没有 `execute` 权限，外部私有目录还额外是 `noexec`
 *   挂载 —— 从那里 `execve` 会得到 `EACCES`，而这正是 docs/agents.md §3/§4 要把可执行文件
 *   伪装成 `lib*.so` 装进 APK 的原因：**只有安装器落到 `lib/<abi>/` 的那份保证能执行**。
 *   所以 [prepare] 只准备 CWD，可执行文件始终用原路径（[AgentRuntimeCatalog] 给的那个）。
 *
 * 复制原生库只有几 MB，换来的是在所有 ROM 上都成立的行为（早先版本的注释提到"软链跨分区不可用"，
 * 那只是次要原因：主因是上游写死了 `<CWD>/maafw`）。
 *
 * ## 幂等
 * 每次 [prepare] 会算一个 stamp（可执行文件 + 4 个原生库 + APK 本体的大小与 mtime），
 * 写进 `.complete`；下次 stamp 一致且文件都还在就直接复用，不再复制/解包。
 * APK 本体进 stamp 是因为 assets 里的 bundle 只随 APK 更新，用 APK 的 mtime 当代理信号。
 */
class AgentWorkspace(private val context: Context) {

    /** 一个已经准备好工作目录的 agent。 */
    data class Prepared(
        val declared: String,
        val agentName: String,
        /** 直接喂给 `AgentLauncher.launch(...)` 的运行时描述。 */
        val runtime: MaaAgentRuntime,
        /** 工作目录本身（= runtime.workingDir）。 */
        val workspace: File,
        /** 是否从 assets 解出了 bundle（没有 bundle 时 agent 会因为找不到 zh_cn.json 而退出）。 */
        val bundleInstalled: Boolean,
        /** 从 assets 解出的文件数；幂等命中时为 0（表示本次没重新解包，不是"没有文件"）。 */
        val bundleFileCount: Int,
    )

    data class Failure(val declared: String, val reason: String)

    data class Batch(
        val prepared: List<Prepared>,
        /** 解析不到可执行文件的（含 [AgentRuntimeCatalog.Status.MISSING] / `NOT_EXECUTABLE`）。 */
        val skipped: List<AgentRuntimeCatalog.Resolution>,
        val failed: List<Failure>,
    )

    /** agent 区的根目录。 */
    val root: File
        get() = File(
            context.getExternalFilesDir(null) ?: throw IOException("外部私有目录不可用（getExternalFilesDir 返回 null）"),
            AREA_DIRNAME,
        )

    fun dirFor(declared: String): File = File(root, AgentRuntimeCatalog.sanitize(AgentRuntimeCatalog.agentName(declared)))

    /**
     * 准备一个 agent 的工作目录。可以重复调用（幂等）。
     *
     * @throws IOException 目录不可写、原生库缺失、复制失败等 —— 调用方应该把它当成
     *   「这个 agent 起不来」并降级，而不是让整轮运行失败（docs/agents.md §7.3 的精神）。
     */
    fun prepare(declared: String, executable: File, args: List<String> = emptyList()): Prepared {
        if (!executable.isFile) throw IOException("agent 可执行文件不存在：${executable.absolutePath}")

        val agentName = AgentRuntimeCatalog.agentName(declared)
        val dir = dirFor(declared)
        val stampFile = File(dir, STAMP_FILE)
        val stamp = computeStamp(executable)

        if (stampFile.isFile && runCatching { stampFile.readText().trim() }.getOrNull() == stamp &&
            executable.canExecute() && frameworkLibsPresent(dir) && File(dir, DEBUG_DIRNAME).isDirectory
        ) {
            // 幂等命中：源文件和 APK 都没变，直接用上次铺好的目录。
            return Prepared(
                declared, agentName,
                MaaAgentRuntime(executable, args, File(dir, MAAFW_DIRNAME), dir),
                dir,
                bundleInstalled = File(dir, LOCALES_DIRNAME).isDirectory,
                // 0 表示「本次没有重新解包」（幂等命中），不是「一个文件都没有」。
                bundleFileCount = 0,
            )
        }

        ensureDir(dir, "agent 工作目录")
        ensureWritable(dir)

        // ① 先解 assets bundle，再铺我们自己的文件：让宿主的文件在撞名时赢。
        val bundleCount = extractBundle(agentName, dir)
        if (bundleCount > 0 && !File(dir, LOCALES_MARKER).isFile) {
            // 不致命（agent 自己会 log.Fatal 并给出更明确的错误），但必须留痕：
            // 这种情况几乎一定是 assets 里的 bundle 不完整/路径放错了。
            throw IOException(
                "agent bundle 解包后仍缺少 $LOCALES_MARKER（解出 $bundleCount 个文件）：" +
                    "assets/${AgentRuntimeCatalog.ASSET_ROOT}/$agentName/ 的内容不符合预期",
            )
        }

        // ② 可执行文件不复制：原地用 nativeLibraryDir（或 pack 自带路径）里的那份 execve。
        //    见类注释「为什么 maafw/\*.so 必须复制，而可执行文件不能复制」。
        if (!executable.canExecute()) throw IOException("agent 可执行文件没有 x 权限：${executable.absolutePath}")

        // ③ maafw/：Go agent 只认 <CWD>/maafw，不读 LD_LIBRARY_PATH（docs/agents.md §6）
        val maafw = File(dir, MAAFW_DIRNAME)
        ensureDir(maafw, "maafw 目录")
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        for (lib in REQUIRED_NATIVE_LIBS) {
            val source = File(nativeDir, lib)
            if (!source.isFile) {
                throw IOException(
                    "缺少原生库 $lib（找的是 ${source.absolutePath}）。" +
                        "docs/agents.md §4.2：android:extractNativeLibs 与 jniLibs.useLegacyPackaging 必须都为 true，" +
                        "否则 .so 不会从 APK 解包落到 nativeLibraryDir。",
                )
            }
            copyFile(source, File(maafw, lib))
        }

        // ④ debug/：go-service 的 logger 会 MkdirAll("debug")，失败即 log.Fatal
        ensureDir(File(dir, DEBUG_DIRNAME), "debug 目录")

        stampFile.writeText(stamp)

        return Prepared(
            declared, agentName,
            MaaAgentRuntime(executable, args, maafw, dir),
            dir,
            bundleInstalled = File(dir, LOCALES_DIRNAME).isDirectory,
            bundleFileCount = bundleCount,
        )
    }

    /**
     * 批量准备：解析 -> 准备，把「跳过」和「失败」分开返回。
     *
     * 之所以放在这里而不是让 [com.maapocket.core.run.MaaRunController] 自己循环：
     * 「解析不到就跳过、准备失败就记账」这套规则是 agent 宿主的策略，
     * 集中在一处，UI 层只负责把结果翻译成日志。
     */
    fun prepareAll(agents: List<PiSelection.ResolvedAgent>, catalog: AgentRuntimeCatalog): Batch {
        val prepared = ArrayList<Prepared>(agents.size)
        val skipped = ArrayList<AgentRuntimeCatalog.Resolution>()
        val failed = ArrayList<Failure>()
        for (agent in agents) {
            val resolution = catalog.resolve(agent)
            val executable = resolution.executable
            if (!resolution.ready || executable == null) {
                skipped += resolution
                continue
            }
            try {
                prepared += prepare(agent.declared, executable, agent.args)
            } catch (t: Throwable) {
                failed += Failure(agent.declared, t.message ?: t.javaClass.simpleName)
            }
        }
        return Batch(prepared, skipped, failed)
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 计算工作目录内容戳。
     *
     * 进戳的东西 = 所有会被复制/解包进工作目录的输入：agent 可执行文件、4 个原生库
     * （都在 nativeLibraryDir）、以及 APK 本体（assets 里的 bundle 只随 APK 变，
     * 而 AssetManager 不提供 mtime，所以用 APK 文件的 mtime 当代理信号）。
     */
    private fun computeStamp(executable: File): String {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val out = StringBuilder(LAYOUT_VERSION).append('|')
        out.append(describe(executable))
        for (lib in REQUIRED_NATIVE_LIBS) out.append(describe(File(nativeDir, lib)))
        out.append(describe(File(context.applicationInfo.sourceDir)))
        return out.toString()
    }

    private fun describe(file: File): String = "${file.name}:${file.length()}:${file.lastModified()};"

    private fun frameworkLibsPresent(dir: File): Boolean {
        val maafw = File(dir, MAAFW_DIRNAME)
        return REQUIRED_NATIVE_LIBS.all { File(maafw, it).isFile }
    }

    private fun ensureDir(dir: File, what: String) {
        if (dir.isDirectory) return
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("无法创建$what：${dir.absolutePath}")
    }

    /**
     * 探一下目录是否真的可写。
     *
     * 必须提前探：Go agent 的 logger 一旦写不了 `debug/go-service.log` 就 `log.Fatal()`，
     * 那时子进程已经起来又立刻退出，父进程只能看到"握手超时"，排障成本高得多。
     */
    private fun ensureWritable(dir: File) {
        val probe = File(dir, ".writable-probe")
        try {
            probe.writeText("ok")
        } catch (t: Throwable) {
            throw IOException("agent 工作目录不可写：${dir.absolutePath}", t)
        } finally {
            runCatching { probe.delete() }
        }
    }

    /**
     * 复制文件；源与目标的大小+mtime 一致就跳过（省掉每次启动几 MB 的写入）。
     *
     * 注意这里不能只比 size：上游换了构建但大小恰好相同的概率不低。
     */
    private fun copyFile(source: File, target: File) {
        if (target.isFile && target.length() == source.length() && target.lastModified() == source.lastModified()) return
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        // 对齐 mtime 让上面那个跳过判断在下次调用时成立
        target.setLastModified(source.lastModified())
    }

    /** 把 `assets/pi-agent-bundle/<agentName>/` 解到工作目录；返回解出的文件数（没有 bundle 时 0）。 */
    private fun extractBundle(agentName: String, destDir: File): Int {
        val assetRoot = "${AgentRuntimeCatalog.ASSET_ROOT}/$agentName"
        val entries = runCatching { context.assets.list(assetRoot) }.getOrNull() ?: return 0
        if (entries.isEmpty()) return 0
        return extractAssetDir(assetRoot, destDir, 0)
    }

    /**
     * 递归解包。
     *
     * 判断"叶子是文件"的手法与 [PiInstaller] 一致：`AssetManager.list()` 返回空数组
     * 就说明它是文件（Android 没有 stat assets 的 API）。代价是 assets 里的**空目录**
     * 会被误判成文件，从而在 open() 时抛错 —— 我们的 bundle 里没有空目录，
     * 真出现了也应该快速失败而不是静默少文件。
     */
    private fun extractAssetDir(assetPath: String, destDir: File, depth: Int): Int {
        if (depth > MAX_ASSET_DEPTH) throw IOException("assets 层级过深，疑似环：$assetPath")
        val entries = runCatching { context.assets.list(assetPath) }.getOrNull() ?: return 0
        var count = 0
        for (entry in entries) {
            val childPath = "$assetPath/$entry"
            val grandchildren = runCatching { context.assets.list(childPath) }.getOrNull() ?: emptyArray()
            if (grandchildren.isEmpty()) {
                context.assets.open(childPath).use { input ->
                    File(destDir, entry).outputStream().use { output -> input.copyTo(output) }
                }
                count++
            } else {
                val childDir = File(destDir, entry)
                ensureDir(childDir, "assets 目录 $childPath")
                count += extractAssetDir(childPath, childDir, depth + 1)
            }
        }
        return count
    }

    companion object {
        const val AREA_DIRNAME = "agent-area"
        const val MAAFW_DIRNAME = "maafw"
        const val DEBUG_DIRNAME = "debug"
        const val LOCALES_DIRNAME = "locales"

        /** 幂等标记文件（内容 = stamp）。名字沿用 [PiInstaller] 的约定。 */
        const val STAMP_FILE = ".complete"

        /** bundle 合格的判定文件：Go agent 的 i18n 就是用它筛候选目录的。 */
        const val LOCALES_MARKER = "locales/go-service/zh_cn.json"

        /** assets 递归深度上限，纯防御。 */
        const val MAX_ASSET_DEPTH = 16

        /** 改这个数字强制所有设备重建工作目录（铺法变了就必须抬）。 */
        const val LAYOUT_VERSION = "v1"

        /**
         * `<CWD>/maafw/` 里必须有的 4 个文件，来源 docs/agents.md §6。
         * 少任何一个，agent 都会在 `maa.Init()` 阶段失败。
         */
        val REQUIRED_NATIVE_LIBS = listOf(
            "libMaaFramework.so",
            "libMaaToolkit.so",
            "libMaaAgentServer.so",
            "libMaaAgentClient.so",
        )
    }
}
