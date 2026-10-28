package com.maafw.naruto.model

import com.google.gson.annotations.SerializedName

/**
 * MaaFramework interface.json 的顶层对象
 * 包含项目名、控制器配置、资源路径和任务列表。
 */
data class MaaInterface(
    val name: String = "",
    val description: String? = null,
    val github: String? = null,
    val license: String? = null,
    val icon: String? = null,
    @SerializedName("interface_version")
    val interfaceVersion: String = "1",
    val task: List<MaaTask> = emptyList(),
    val option: Map<String, MaaOption> = emptyMap()
)