package com.maafw.naruto.model

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * interface.json 中 option 字段的数据类
 * 每个 option 对应一个用户可选项，case 里可以覆盖 pipeline 节点字段。
 */
data class MaaOption(
    val type: String = "select",
    val description: String = "",
    @SerializedName("default_case")
    val defaultCase: String = "",
    @SerializedName("default")
    val default: String? = null,
    val cases: List<MaaOptionCase> = emptyList(),
    val inputs: List<MaaOptionInput>? = null,
    @SerializedName("pipeline_override")
    val pipelineOverride: JsonObject? = null
)

/**
 * option 的单个 case
 * name 是显示名，pipeline_override 用来动态修改 pipeline 节点。
 * option 是该 case 选中后需要显示的嵌套子选项列表（对应 interface.json 里 case 的 "option" 字段）。
 */
data class MaaOptionCase(
    val name: String = "",
    val description: String? = null,
    val option: List<String>? = null,
    @SerializedName("pipeline_override")
    val pipelineOverride: JsonObject? = null
)

/**
 * input 类型 option 的输入项
 */
data class MaaOptionInput(
    val name: String = "",
    val label: String = "",
    val description: String = "",
    @SerializedName("pipeline_type")
    val pipelineType: String = "string",
    val default: String = "",
    val verify: String? = null
)