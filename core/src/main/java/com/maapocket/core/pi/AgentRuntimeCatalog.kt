package com.maapocket.core.pi

import android.content.Context
import java.io.File

/**
 * 把 interface.json 里声明的 `child_exec` 映射到设备上**真正可以 execve** 的文件。
 *
 * ## 为什么不能直接用 child_exec 当路径
 * PI-V2 规范里 `child_exec` 只是"路径"（example 里写的是 `agent/go-service`，但也可以写
 * `python` 这种 PATH 上的名字）。而 Android 不允许从 APK 的 assets 里 execve：可执行文件
 * 必须以**原生库**的身份随 APK 安装到 `/data/app/.../lib/<abi>/` 才有 x 权限。
 * 所以 MaaPocket 的约定是（见 docs/agents.md §3、§4）：
 *
 * ```
 * child_exec: "agent/go-service"  ->  <nativeLibraryDir>/libMaaEnd_go_service.so
 * child_exec: "agent/foo-bar"     ->  <nativeLibraryDir>/libMaaEnd_foo_bar.so   （泛化规则）
 * ```
 *
 * 那个 `.so` 的名字是库、内容是 ELF 可执行文件（PIE），这是让 Android 把它当原生库安装
 * 的"伪装"技巧，也是 scripts/build_go_agent.py 把 `-o` 钉死成 `libMaaEnd_go_service.so` 的原因。
 *
 * ## 为什么同时要显式映射表
 * 泛化规则（`agent/foo-bar` -> `libMaaEnd_foo_bar.so`）能覆盖未知 agent，但**不能**保证
 * 与已经验证过的产物名一致。已知的两个 agent 用显式表钉死，改泛化规则时不会悄悄改错名字。
 *
 * 本类不做任何 I/O 之外的事，也不抛异常：解析不出来是**正常情况**，由调用方按
 * docs/agents.md §7.3 的降级策略处理（跳过并告警）。
 */
class AgentRuntimeCatalog(private val context: Context) {

    /** 一个 agent 的可执行文件解析结果。 */
    enum class Status {
        /** 文件存在且有 x 位，可以起子进程。 */
        READY,

        /**
         * APK 里没有这个 agent 的产物。
         *
         * MaaEnd 声明的 `agent/cpp-algo` 当前必然落在这里：C++ agent 还没有可用的 Android
         * 产物（依赖 MaaDeps/OpenCV/ONNXRuntime/Boost，且有一批桌面专用 .cpp 没有平台守卫，
         * 见 docs/agents.md §7）。这不是"出错"，是"这个 agent 还没被移植"。
         */
        MISSING,

        /**
         * 文件在，但没有 x 位。
         *
         * 正常安装路径不会出现这种状态，所以它值得单独报出来：如果命中，说明
         * `android:extractNativeLibs="true"` 或 `packaging.jniLibs.useLegacyPackaging`
         * 没配对（docs/agents.md §4.2），文件根本没落盘/没有执行位。
         */
        NOT_EXECUTABLE,
    }

    data class Resolution(
        /** 原始声明的 child_exec，例如 `agent/go-service`。 */
        val declared: String,
        /** 从 declared 里取出的 agent 名字，例如 `go-service`；它同时是 asset bundle 的目录名。 */
        val agentName: String,
        val status: Status,
        /** 候选路径。即使文件不存在也给出，方便日志一眼定位找错地方的问题。 */
        val path: String,
        /** 只有 [Status.READY] 时非 null。 */
        val executable: File?,
        /** 给用户/日志看的中文说明。 */
        val detail: String,
    ) {
        val ready: Boolean get() = status == Status.READY
    }

    /**
     * 解析一个 agent。
     *
     * @param inPackPath 资源包自带的可执行文件的绝对路径（[PiSelection.ResolvedAgent.inPackPath]）。
     *   规范允许 pack 自带二进制；那种情况下优先用它，因为它才是 pack 作者指定的那一份。
     */
    fun resolve(declared: String, inPackPath: String? = null): Resolution {
        val name = agentName(declared)

        // ① 资源包自带的文件优先（如果 pack 真的带了一个可执行文件）
        if (inPackPath != null) {
            val packed = File(inPackPath)
            if (packed.isFile) {
                return if (packed.canExecute()) {
                    Resolution(declared, name, Status.READY, packed.absolutePath, packed, "使用资源包自带的可执行文件")
                } else {
                    Resolution(
                        declared, name, Status.NOT_EXECUTABLE, packed.absolutePath, null,
                        "资源包自带 ${packed.name} 但没有可执行权限（Android 上 Pack 内文件无法直接 execve）",
                    )
                }
            }
        }

        // ② 退到 APK 自带产物：<nativeLibraryDir>/libMaaEnd_<name>.so
        val candidate = File(context.applicationInfo.nativeLibraryDir, fileNameFor(declared))
        return when {
            !candidate.isFile -> Resolution(
                declared, name, Status.MISSING, candidate.absolutePath, null, missingDetail(declared, candidate.name),
            )

            !candidate.canExecute() -> Resolution(
                declared, name, Status.NOT_EXECUTABLE, candidate.absolutePath, null,
                "APK 里有 ${candidate.name} 但没有可执行权限；检查 android:extractNativeLibs 与 " +
                    "packaging.jniLibs.useLegacyPackaging 是否都为 true（docs/agents.md §4.2）",
            )

            else -> Resolution(declared, name, Status.READY, candidate.absolutePath, candidate, "使用 APK 自带产物")
        }
    }

    /** [PiSelection.ResolvedAgent] 的便捷重载。 */
    fun resolve(agent: PiSelection.ResolvedAgent): Resolution = resolve(agent.declared, agent.inPackPath)

    private fun missingDetail(declared: String, fileName: String): String =
        if (declared in KNOWN_UNAVAILABLE) {
            "APK 中没有 $fileName：$declared 还没有 Android 产物（见 docs/agents.md §7），已跳过"
        } else {
            "APK 中没有 $fileName：$declared 未随包提供也没有 APK 产物，已跳过"
        }

    companion object {
        /** APK assets 下 agent bundle 的根目录。CI 把 build_go_agent.py 的 `bundle/go-service/\**` 放这里。 */
        const val ASSET_ROOT = "pi-agent-bundle"

        /**
         * 已知 agent 的显式映射表。
         *
         * 泛化规则对这些名字算出来是同一个结果；显式钉死是为了防止将来改 [fileNameFor] 时
         * 悄悄改错已经验证过的产物名（产物名一旦变了，APK 里就找不到文件，而且是静默降级）。
         */
        val EXPLICIT_FILE_NAMES: Map<String, String> = mapOf(
            "agent/go-service" to "libMaaEnd_go_service.so",
            "agent/cpp-algo" to "libMaaEnd_cpp_algo.so",
        )

        /** 明知当前拿不到 Android 产物的 agent —— 只影响告警措辞，不影响行为。 */
        val KNOWN_UNAVAILABLE: Set<String> = setOf("agent/cpp-algo")

        const val FILE_PREFIX = "libMaaEnd_"
        const val FILE_SUFFIX = ".so"

        /**
         * `child_exec` -> 文件名。
         *
         * 先查显式表（按原始声明匹配，含 `./agent/go-service` 的写法），再退回泛化规则。
         */
        fun fileNameFor(declared: String): String =
            EXPLICIT_FILE_NAMES[declared] ?: libraryFileName(agentName(declared))

        /**
         * 从 `child_exec` 里取 agent 名字：取最后一段路径分量。
         * `agent/go-service` -> `go-service`；`./agent/go-service` -> `go-service`。
         */
        fun agentName(declared: String): String {
            val trimmed = declared.trim().trimEnd('/')
            val last = trimmed.substringAfterLast('/').trim()
            return last.ifEmpty { "unknown" }
        }

        /** 泛化命名规则：`go-service` -> `libMaaEnd_go_service.so`。 */
        fun libraryFileName(agentName: String): String = FILE_PREFIX + sanitize(agentName) + FILE_SUFFIX

        /**
         * 把任意名字变成文件名安全的片段。
         *
         * `lib*.so` 前缀/后缀是硬性要求（否则 Android 不把它当原生库），中间部分只允许
         * 文件名安全字符；`-`/`.` 统一换成 `_`，让 `agent/foo-bar` 与 `agent/foo.bar`
         * 不会撞车成两个都非法的名字。
         */
        fun sanitize(raw: String): String {
            val out = StringBuilder(raw.length)
            for (ch in raw) {
                // 只放行 ASCII：非 ASCII 字母在文件名/ELF 装载上都可能出问题，统一压成 '_'。
                val safe = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '_'
                out.append(if (safe) ch else '_')
            }
            return out.toString().ifEmpty { "unknown" }
        }
    }
}
