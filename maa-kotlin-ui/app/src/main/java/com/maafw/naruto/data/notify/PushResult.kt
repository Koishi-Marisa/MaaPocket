/*
 * 火影MAA - 安卓脚本辅助框架
 * Copyright (C) 2024  火影MAA贡献者
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.maafw.naruto.data.notify

/**
 * 单次推送结果。
 */
sealed interface PushResult {
    /** 推送成功。 */
    object Delivered : PushResult

    /** 推送被拒绝/失败，通常重试也无效，需要检查配置。 */
    data class Rejected(val reason: String) : PushResult

    /** 临时失败，可稍后重试。 */
    data class Retryable(val reason: String) : PushResult
}