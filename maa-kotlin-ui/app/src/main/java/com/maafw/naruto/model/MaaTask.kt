package com.maafw.naruto.model

import com.google.gson.annotations.SerializedName

/**
 * MaaFramework 前端任务项
 * 对应 assets/interface.json 里的 task 对象。
 */
data class MaaTask(
    val name: String = "",
    val entry: String = "",
    val option: List<String>? = null,
    val description: String? = null,
    @SerializedName("default_check")
    val defaultCheck: Boolean = false
)
