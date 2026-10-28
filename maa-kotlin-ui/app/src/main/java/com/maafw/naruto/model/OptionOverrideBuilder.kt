package com.maafw.naruto.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 把用户选中的 option 值合并成 pipeline_override JSON 
 * 每个 option 的 case 可能带 pipeline_override，多个 option 要深度合并。
 */
object OptionOverrideBuilder {

    /**
     * 根据 task.option 顺序和当前选项值，合并出最终 pipeline_override JSON 字符串。
     *
     * 用户未配置的 option 会用 interface.json 的 default_case 填充（保持与 Maa 语义一致）：
     * - select 类型：default_case 对应的 pipeline_override
     * - input 类型：inputs[0].default 作为输入值
     */
    fun build(
        task: MaaTask,
        options: Map<String, String>,
        interfaceData: MaaInterface?
    ): String? {
        if (task.option.isNullOrEmpty()) return null

        val merged = JsonObject()
        for (optName in task.option) {
            val opt = interfaceData?.option?.get(optName) ?: continue
            collectOption(optName, opt, options, merged, interfaceData)
        }

        // 全局占位符替换：select/switch 的 case override 里引用其他选项值的 {选项名}/{输入项名} 占位符
        // （如 {挂机局数}、{刷分忍者预设}、{掉分忍者预设}），input 已在 resolveInputOverride 替换，这里兜底幂等
        return resolveAllPlaceholders(merged, task, options, interfaceData)?.let { resolved ->
            if (resolved.size() == 0) null else resolved.toString()
        }
    }

    /**
     * 全局占位符替换：把合并结果里残留的 {选项名} / {输入项名} 占位符替换成对应选项的当前值。
     */
    private fun resolveAllPlaceholders(
        merged: JsonObject,
        task: MaaTask,
        options: Map<String, String>,
        interfaceData: MaaInterface?
    ): JsonObject? {
        if (merged.size() == 0) return merged
        val optionNames = task.option ?: return merged
        var text = merged.toString()
        for (optName in optionNames) {
            val opt = interfaceData?.option?.get(optName) ?: continue
            val selected = options[optName]
                ?: when (opt.type) {
                    "input" -> opt.inputs?.firstOrNull()?.default
                    "checkbox" -> opt.defaultCase
                    else -> opt.defaultCase.ifBlank { opt.default ?: opt.cases.firstOrNull()?.name }
                }
            if (selected.isNullOrBlank()) continue
            val escaped = selected.replace("\\", "\\\\").replace("\"", "\\\"")
            // 用选项名替换（select/switch/checkbox 的值）
            text = text.replace("{${optName}}", escaped)
            // 用输入项名替换（input 的值，与 resolveInputOverride 一致）
            opt.inputs?.firstOrNull()?.name?.let { text = text.replace("{${it}}", escaped) }
        }
        return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: merged
    }

    /**
     * 递归收集单个 option 的 pipeline_override（含 checkbox 多选合并与嵌套子选项联动）。
     * select/switch 只有选中 case 才生效；checkbox 把逗号分隔的多个选中值全部合并；
     * 选中 case 的 option 子列表会继续递归收集（子选项未配置时用其自身默认值）。
     */
    private fun collectOption(
        optName: String,
        opt: MaaOption,
        options: Map<String, String>,
        merged: JsonObject,
        interfaceData: MaaInterface?
    ) {
        // 用户未配置 -> input 用 inputs[0].default；checkbox 默认空 = 都不选；其余用 default_case（兼容顶层 default 写法）
        val selected = options[optName]
            ?: when (opt.type) {
                "input" -> opt.inputs?.firstOrNull()?.default
                "checkbox" -> opt.defaultCase
                else -> opt.defaultCase.ifBlank { opt.default ?: opt.cases.firstOrNull()?.name }
            }
        if (selected.isNullOrBlank()) return

        when (opt.type) {
            "input" -> resolveInputOverride(opt, selected)?.let { deepMerge(merged, it) }
            "checkbox" -> {
                val selectedCases = selected.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                opt.cases.forEach { case ->
                    if (case.name in selectedCases) {
                        case.pipelineOverride?.let { deepMerge(merged, it) }
                        // checkbox 的 case 若带嵌套子选项，同样联动生效
                        case.option?.forEach { subName ->
                            val subOpt = interfaceData?.option?.get(subName) ?: return@forEach
                            collectOption(subName, subOpt, options, merged, interfaceData)
                        }
                    }
                }
            }
            else -> {
                val case = opt.cases.find { it.name == selected } ?: return
                case.pipelineOverride?.let { deepMerge(merged, it) }
                // 嵌套子选项：父 case 选中时才生效（如「玉石商店」Yes -> 玉石商店兑换 等）
                case.option?.forEach { subName ->
                    val subOpt = interfaceData?.option?.get(subName) ?: return@forEach
                    collectOption(subName, subOpt, options, merged, interfaceData)
                }
            }
        }
    }

    /**
     * input 类型选项：把 pipeline_override 里的 {输入项名} 占位符替换成用户输入的实际值。
     * ：input 选项的 pipeline_override 用 {inputs[0].name} 引用输入值，
     * 例如 "何时退出" 的 {"max_hit": "{周胜胜利次数}"} 会变成 {"max_hit": "5"}。
     */
    private fun resolveInputOverride(opt: MaaOption, userInput: String): JsonObject? {
        val override = opt.pipelineOverride ?: return null
        val inputs = opt.inputs.orEmpty()
        if (inputs.isEmpty()) return override

        var text = override.toString()
        inputs.forEachIndexed { idx, input ->
            // 第一个输入项使用用户输入值，其余用默认值（当前 interface.json 的 input 选项基本都只有 1 个输入项）
            val value = if (idx == 0) userInput.ifBlank { input.default } else input.default
            // 对值做 JSON 转义，避免含引号/反斜杠时破坏 JSON 结构
            val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
            text = text.replace("{${input.name}}", escaped)
        }
        return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
    }

    private fun deepMerge(target: JsonObject, source: JsonObject) {
        for ((key, value) in source.entrySet()) {
            if (!target.has(key)) {
                target.add(key, value.deepCopy())
                continue
            }
            val existing = target.get(key)
            if (existing.isJsonObject && value.isJsonObject) {
                deepMerge(existing.asJsonObject, value.asJsonObject)
            } else {
                target.add(key, value.deepCopy())
            }
        }
    }
}