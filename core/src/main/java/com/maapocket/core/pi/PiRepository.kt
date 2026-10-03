package com.maapocket.core.pi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber
import java.io.File

/**
 * 载入一个已解包到磁盘的 Project Interface V2 资源包（下称 "pi pack"）。
 *
 * 职责：
 * 1. 读 `interface.json`，**递归**处理 `import`（相对 interface.json 同目录）。
 * 2. 载入 `languages[<locale>]` 翻译文件，提供 `$key` 形式的 i18n 解析。
 * 3. 暴露合并后的 task / group / option / resource / controller 视图。
 *
 * 本类不做任何“选哪一项”的决策 —— 那是 [PiSelection] 的事。
 */
class PiRepository private constructor(
    /** pi pack 的根目录（已从 APK assets 解出）。 */
    val root: File,
    val pi: PiInterface,
) {

    /** 当前 locale 下 `languages` 里最匹配的文件所构建的翻译树。 */
    private var i18nTree: JsonElement? = null

    /** 该 pack 声明但本 App 不认识的 controller 类型（用于 UI 提示）。 */
    val unsupportedControllerTypes: List<String> by lazy {
        pi.controller.map { it.type }.distinct().filter { it !in KNOWN_CONTROLLER_TYPES }
    }

    companion object {
        /** schema 里出现过的所有 controller type。on-device 只真正支持 Adb（由 native controller 顶替）。 */
        val KNOWN_CONTROLLER_TYPES = setOf("Adb", "Win32", "MacOS", "PlayCover", "Gamepad", "Linux")

        /** 设备上真正能跑的 PI controller 类型：PI 写 `Adb`，运行时换成 MaaFramework Android Native controller。 */
        const val ON_DEVICE_PI_TYPE = "Adb"

        private const val MAX_IMPORT_DEPTH = 8

        /**
         * @param root pi pack 根目录
         * @param locale 语言代码，如 `zh_cn`；为空时取 `languages` 的第一项
         */
        fun load(root: File, locale: String? = null): PiRepository {
            val main = File(root, "interface.json")
            require(main.isFile) { "pi pack 缺少 interface.json: ${main.absolutePath}" }

            val repo = PiRepository(root, readAndMerge(root, main, 0, HashSet()))
            repo.attachLocale(locale)
            return repo
        }

        private fun readAndMerge(
            root: File,
            file: File,
            depth: Int,
            visited: MutableSet<String>,
        ): PiInterface {
            val key = file.canonicalPath
            check(depth <= MAX_IMPORT_DEPTH) { "interface.json import 嵌套超过 $MAX_IMPORT_DEPTH 层: $key" }
            check(visited.add(key)) { "interface.json import 出现环: $key" }
            check(file.isFile) { "import 指向的文件不存在: $key" }

            val self = PiJson.decodeFromString(PiInterface.serializer(), file.readText())

            // 深度优先：先合并被 import 的，再让本文件覆盖它们。
            val merged = ArrayList<PiInterface>()
            for (rel in self.import) {
                val target = resolveRelative(root, file.parentFile, rel)
                merged += readAndMerge(root, target, depth + 1, visited)
            }

            return merged.fold(self) { acc, imported -> mergeInto(acc, imported) }
        }

        /**
         * 把 [imported] 的 task/option/group/pretask/preset 合并进 [base]。
         * 同名时 **base 胜出**（本文件覆盖被 import 的），与 MaaFramework 文档「导入」的直觉一致。
         */
        private fun mergeInto(base: PiInterface, imported: PiInterface): PiInterface = base.copy(
            task = dedupeByName(imported.task + base.task) { it.name },
            group = dedupeByName(imported.group + base.group) { it.name },
            setting = dedupeByName(imported.setting + base.setting) { it.name },
            option = imported.option + base.option, // Map 合并：+ 右侧(=base)覆盖左侧
            languages = imported.languages + base.languages,
            globalOption = (imported.globalOption + base.globalOption).distinct(),
            agent = base.agent ?: imported.agent,
            pretask = base.pretask ?: imported.pretask,
            preset = imported.preset + base.preset,
        )

        private fun <T> dedupeByName(all: List<T>, name: (T) -> String): List<T> {
            val out = LinkedHashMap<String, T>()
            for (item in all) out[name(item)] = item
            return out.values.toList()
        }

        /** import 路径相对 interface.json 同目录；容忍 `./x` 与 `x/y` 两种写法。 */
        private fun resolveRelative(root: File, baseDir: File, rel: String): File {
            val cleaned = rel.removePrefix("./")
            val fromBase = File(baseDir, cleaned)
            return if (fromBase.isFile) fromBase else File(root, cleaned)
        }
    }

    // ---------------------------------------------------------------- i18n

    private fun attachLocale(locale: String?) {
        val table = pi.languages
        if (table.isEmpty()) return
        val chosen = when {
            locale != null && table.containsKey(locale) -> locale
            locale != null -> table.keys.firstOrNull { it.equals(locale, true) }
                ?: table.keys.firstOrNull { it.startsWith(locale.substringBefore('_'), true) }
                ?: table.keys.first()
            else -> table.keys.first()
        }
        val file = File(root, table.getValue(chosen).removePrefix("./"))
        i18nTree = runCatching {
            Json.parseToJsonElement(file.readText())
        }.onFailure { Timber.w(it, "读取翻译文件失败: %s", file.absolutePath) }.getOrNull()
        Timber.i("pi pack locale=%s (%d 条翻译)", chosen, countLeaves(i18nTree))
    }

    private fun countLeaves(el: JsonElement?): Int = when (el) {
        null -> 0
        is JsonObject -> el.values.sumOf { countLeaves(it) }
        else -> 1
    }

    /**
     * 解析 i18n 字符串。**以 `$` 开头**表示从翻译文件里按点号路径取值（PI-V2 约定）；
     * 其它情况原样返回。查不到时返回原文（而不是 null），这样 UI 至少能显示出 key。
     */
    fun resolve(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        if (!text.startsWith("$")) return text
        val path = text.substring(1)
        var cur: JsonElement? = i18nTree
        for (seg in path.split('.')) {
            cur = (cur as? JsonObject)?.get(seg) ?: return text
        }
        return (cur as? JsonPrimitive)?.contentOrNull ?: text
    }

    // ------------------------------------------------------------ 查询视图

    fun controllers(): List<PiController> = pi.controller

    /** 设备上可用的 controller：PI 声明了 `Adb` 才可用（运行时由 native controller 顶替）。 */
    fun onDeviceControllers(): List<PiController> =
        pi.controller.filter { it.type == ON_DEVICE_PI_TYPE }

    fun resources(): List<PiResource> = pi.resource

    fun groups(): List<PiGroup> = pi.group

    /**
     * 设备上真正能跑的任务。
     *
     * 上游 `interface.json` 的每个 task 可以带一个 `controller` 白名单（**存的是
     * controller 的 `name`，不是 `type`**）；空列表 = 对所有 controller 都可用。
     * MaaEnd 的 `tasks/AutoEcoFarm.json` 之类就把它写成了
     * `["Linux-Gamescope","Linux-ScreenCast","Linux-Wlroots","Win32-Front"]` ——
     * 这是上游自己声明的「仅桌面」。那些任务里全是 WASD / 鼠标语义的节点，
     * 在手机上要么被转成 `DoNothing`、要么进了 `KNOWN_LIMITATIONS.md`，**放出来只会让用户困惑**。
     *
     * 之前这里只按 `group` 过滤，`PiTask.controller` 解析了却从没人读
     * （MaaEnd 迁移子代理在 65ce037 的报告里点名了这件事），于是 app 会把
     * 「上游明说桌面限定」的任务也列进 UI。这里补上这道闸。
     *
     * @param activeControllers 设备上实际可用的 controller **名字**集合。默认取
     *   [onDeviceControllers]（type == `Adb` 的那些）。**空集合视为「不设限」**：
     *   资源包没声明任何 Adb controller 时（理论上不该发生）宁可全放出来，
     *   也不要因为一个空的过滤集合把整个包的任务清空。
     */
    fun tasks(
        activeControllers: Set<String> = onDeviceControllers().map { it.name }.toSet(),
    ): List<PiTask> = pi.task.filter { isAvailableOn(it, activeControllers) }

    /** 单个任务在当前 controller 下是否可用。空 `controller` 白名单 = 通用任务。 */
    fun isAvailableOn(task: PiTask, activeControllers: Set<String>): Boolean {
        if (task.controller.isEmpty()) return true
        if (activeControllers.isEmpty()) return true
        return task.controller.any { it in activeControllers }
    }

    /** 按 group 分组；无 group 的归入 null 桶。保持 `group` 声明顺序。沿用 [tasks] 的 controller 闸。 */
    fun tasksByGroup(
        activeControllers: Set<String> = onDeviceControllers().map { it.name }.toSet(),
    ): LinkedHashMap<String?, List<PiTask>> {
        val order = groups().map { it.name }
        val map = LinkedHashMap<String?, MutableList<PiTask>>()
        for (g in order) map[g] = mutableListOf()
        map[null] = mutableListOf()
        for (t in tasks(activeControllers)) {
            val g = t.group.firstOrNull { map.containsKey(it) }
            map.getOrPut(g) { mutableListOf() }.add(t)
        }
        return LinkedHashMap(map.filterValues { it.isNotEmpty() })
    }

    fun optionDefs(): Map<String, PiOption> = pi.option

    fun agents(): List<PiAgent> = PiNorm.agents(pi.agent)

    fun pretasks(): List<PiPretask> = PiNorm.pretasks(pi.pretask)

    fun globalOptionNames(): List<String> = pi.globalOption

    fun displayName(): String = resolve(pi.label ?: pi.title).ifEmpty { pi.name }
}
