package com.maapocket.core.pi

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.io.File

/**
 * 把用户在 UI 上的选择，解算成一次运行所需的全部输入。
 *
 * 这一层刻意保持**纯函数**（除读文件外无副作用），方便单测与排查：
 * 输入 = (pi pack, controller 名, resource 名, 勾选的 task 名, 选项取值)
 * 输出 = [PiRunPlan]
 */
object PiSelection {

    /** 选项取值：option 名 -> case 名（switch）/ 原始文本（input / hotkey）。 */
    typealias OptionValues = Map<String, String>

    data class ResolvedAgent(
        /** PI 里声明的 `child_exec`，例如 `agent/go-service`。 */
        val declared: String,
        val args: List<String>,
        /**
         * 在解包后的 pi pack 里解析出的绝对路径 —— 只有在资源包**自带**该可执行文件时才非空。
         * 为空表示应当由 App 自带的 Android agent 运行时提供（见 `AgentRuntime`）。
         */
        val inPackPath: String?,
        /** 该 agent 是否能在设备上跑起来；false 时对应任务链会失败，需要 UI 明确提示。 */
        val runnableOnDevice: Boolean,
    )

    data class PiRunPlan(
        /** 按优先级从低到高排列的资源目录（MaaFramework 后加载者覆盖先加载者）。 */
        val bundlePaths: List<String>,
        /** 依次应用到 MaaResource 的 pipeline override 列表（顺序 = 语义）。 */
        val overrides: List<JsonObject>,
        /** 需要拉起的 agent 子进程。 */
        val agents: List<ResolvedAgent>,
        /** controller 启动前要执行的外部程序。 */
        val pretasks: List<PiPretask>,
        /** 选中的 task，按声明顺序。 */
        val tasks: List<PiTask>,
        /** 最终 controller 的显示分辨率建议（PI 的 display_* 字段），未声明则为 null。 */
        val displayShortSide: Int?,
        val displayLongSide: Int?,
        val warnings: List<String>,
    )

    /**
     * @param root 已解包的 pi pack 根目录
     * @param controllerName 选中的 controller（必须是 `type == "Adb"` 的那一类，设备上跑）
     * @param resourceName 选中的 resource 项
     * @param taskNames 勾选的 task 名
     * @param optionValues 所有生效的选项取值（global_option + 各 task 的 option + 子选项）
     */
    fun resolve(
        repo: PiRepository,
        root: File,
        controllerName: String,
        resourceName: String,
        taskNames: Collection<String>,
        optionValues: OptionValues = emptyMap(),
    ): PiRunPlan {
        val warnings = mutableListOf<String>()

        val controller = repo.controllers().firstOrNull { it.name == controllerName }
            ?: repo.onDeviceControllers().firstOrNull()
            ?: error("该资源包没有声明可在设备上运行的 controller（需要 type=\"Adb\"）")
        if (controller.type != PiRepository.ON_DEVICE_PI_TYPE) {
            warnings += "controller「${controller.name}」类型为 ${controller.type}，设备上按 Android Native 处理。"
        }

        val resource = repo.resources().firstOrNull { it.name == resourceName }
            ?: repo.resources().firstOrNull()
            ?: error("该资源包没有声明任何 resource")

        // ---- 1. 资源目录顺序：resource.path 在前，controller.attach_resource_path 在后（后者覆盖前者）
        val rawPaths = PiNorm.stringList(resource.path) + controller.attachResourcePath
        val bundlePaths = rawPaths.map { it.removePrefix("./") }.distinct().map { rel ->
            File(root, rel).absolutePath
        }
        bundlePaths.forEach { p ->
            if (!File(p).isDirectory) warnings += "资源目录不存在：${root.toURI().relativize(File(p).toURI()).path}"
        }
        if (bundlePaths.isEmpty()) warnings += "解析出的资源目录为空。"

        // ---- 2. 选项 -> override 序列
        val overrides = mutableListOf<JsonObject>()
        val resolvedOptions = LinkedHashSet<String>()

        // global_option 永远参与，无论选了哪些 task
        repo.globalOptionNames().forEach { resolvedOptions += it }
        // 选中 task 自己声明的 option
        val selectedTasks = repo.tasks().filter { it.name in taskNames }
        selectedTasks.forEach { resolvedOptions += it.option }
        // 调用方直接传进来的（UI 上勾了但没被 task 引用的情况）
        resolvedOptions += optionValues.keys

        val defs = repo.optionDefs()
        val visited = HashSet<String>()
        for (name in resolvedOptions) {
            applyOption(defs, name, optionValues, overrides, visited, warnings)
        }

        // ---- 3. agent / pretask
        val agents = repo.agents().map { a ->
            val file = File(root, a.childExec.removePrefix("./"))
            ResolvedAgent(
                declared = a.childExec,
                args = a.childArgs,
                inPackPath = if (file.isFile) file.absolutePath else null,
                runnableOnDevice = file.isFile && file.canExecute(),
            )
        }
        if (agents.any { !it.runnableOnDevice }) {
            warnings += "有 ${agents.count { !it.runnableOnDevice }} 个 agent 不在资源包内，" +
                "需要 App 自带对应的 Android 可执行文件，否则依赖它的任务会失败。"
        }

        return PiRunPlan(
            bundlePaths = bundlePaths,
            overrides = overrides,
            agents = agents,
            pretasks = repo.pretasks(),
            tasks = selectedTasks,
            displayShortSide = PiNorm.int(controller.displayShortSide),
            displayLongSide = PiNorm.int(controller.displayLongSide),
            warnings = warnings,
        )
    }

    /**
     * 展开一个 switch 选项：把选中 case 的 `pipeline_override` 追加进 [out]，
     * 再递归处理该 case 的 `option`（子选项）以及**选项自身的** `option`（`optionDefinition.option`）。
     */
    private fun applyOption(
        defs: Map<String, PiOption>,
        name: String,
        values: OptionValues,
        out: MutableList<JsonObject>,
        visited: MutableSet<String>,
        warnings: MutableList<String>,
    ) {
        if (!visited.add(name)) return // 防环
        val def = defs[name] ?: run {
            warnings += "选项「$name」未在 interface.json 中定义。"
            return
        }

        // 选项自带的 override 永远生效（不分 case）
        (def as? PiOption)?.let { }

        val chosen = values[name] ?: def.defaultCase ?: def.cases.firstOrNull()?.name
        if (def.cases.isEmpty()) {
            // input / hotkey 形态：没有 case，没有 pipeline_override，交给上层处理取值本身。
            return
        }

        val case = def.cases.firstOrNull { it.name == chosen } ?: def.cases.first()
        if (chosen != null && case.name != chosen) {
            warnings += "选项「$name」的取值「$chosen」不存在，回退到「${case.name}」。"
        }
        case.pipelineOverride?.let { out += it }

        // 子选项
        (case.option + def.option).forEach { sub ->
            applyOption(defs, sub, values, out, visited, warnings)
        }
    }

    /** 便捷：把 [OptionValues] 里 input/hotkey 形态的选项拼成 MaaFramework 需要的紧凑 JSON。 */
    fun optionValueJson(repo: PiRepository, values: OptionValues): String? {
        if (values.isEmpty()) return null
        val obj = JsonObject(values.mapValues { (_, v) -> JsonPrimitive(v) as JsonElement })
        return obj.toString().takeIf { it != "{}" }
    }

    /** 默认勾选的 task（`default_check = true`）。 */
    fun defaultChecked(repo: PiRepository): List<PiTask> = repo.tasks().filter { it.defaultCheck }

    fun summarize(plan: PiRunPlan): String = buildString {
        append("bundle=").append(plan.bundlePaths.size)
        append(" overrides=").append(plan.overrides.size)
        append(" tasks=").append(plan.tasks.size)
        append(" agents=").append(plan.agents.size)
        if (plan.warnings.isNotEmpty()) append(" warnings=").append(plan.warnings.size)
    }

    /** 仅用于日志：把 task 名列表打出来。 */
    fun logPlan(plan: PiRunPlan) {
        plan.tasks.forEach { Timber.i("task: %s (%s)", it.name, it.entry) }
        plan.bundlePaths.forEach { Timber.i("bundle: %s", it) }
        plan.warnings.forEach { Timber.w("pi: %s", it) }
    }

    private fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
}
