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
 * 外部/本地推送通道抽象。
 * 与任何第三方通知框架解耦，仅描述本应用内部需要的“发送标题+正文”能力。
 */
interface PushChannel {
    /** 通道唯一标识，与配置中保存的 key 一致。 */
    val channelId: String

    /**
     * 尝试推送一条消息。
     *
     * @param title 消息标题
     * @param body  消息正文
     * @return 推送结果；失败可能是终态或可调用的瞬时态
     */
    suspend fun deliver(title: String, body: String): PushResult
}
