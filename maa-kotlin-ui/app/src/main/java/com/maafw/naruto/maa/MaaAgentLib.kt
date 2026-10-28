package com.maafw.naruto.maa

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * MaaAgentClient / MaaAgentServer C API 的 JNA 绑定
 * 对应 SDK 头文件：
 *  - include/MaaAgentClient/MaaAgentClientAPI.h
 *  - include/MaaAgentServer/MaaAgentServerAPI.h
 *
 * 通信基于 ZeroMQ，identifier 为 ZMQ 地址（如 tcp://127.0.0.1:12345）。
 * AgentServer 运行在独立进程（agent），AgentClient 在主引擎进程，二者通过 identifier 通信。
 */
interface MaaAgentClientLib : Library {

    companion object {
        val INSTANCE: MaaAgentClientLib by lazy {
            Native.load("MaaAgentClient", MaaAgentClientLib::class.java)
        }
    }

    /** 创建 client，identifier 为 ZMQ 地址（MaaStringBuffer） */
    fun MaaAgentClientCreateV2(identifier: Pointer?): Pointer

    /** 创建 client，直接使用 TCP 端口（避免 ipc socket 路径/SELinux 问题） */
    fun MaaAgentClientCreateTcp(port: Int): Pointer

    /** 获取 client 的 identifier（库生成，纯端口字符串，如 "23472"；agent 进程要用它 StartUp） */
    fun MaaAgentClientIdentifier(client: Pointer?, identifier: Pointer?): Byte

    fun MaaAgentClientDestroy(client: Pointer?)

    /** 绑定引擎的 resource（agent 可访问 pipeline/图片/模型） */
    fun MaaAgentClientBindResource(client: Pointer?, res: Pointer?): Byte

    fun MaaAgentClientConnect(client: Pointer?): Byte
    fun MaaAgentClientDisconnect(client: Pointer?): Byte
    fun MaaAgentClientConnected(client: Pointer?): Byte
    fun MaaAgentClientAlive(client: Pointer?): Byte
    fun MaaAgentClientSetTimeout(client: Pointer?, milliseconds: Long): Byte

    /** 获取 agent 侧注册的 custom recognition 列表（MaaStringListBuffer） */
    fun MaaAgentClientGetCustomRecognitionList(client: Pointer?, buffer: Pointer?): Byte
    fun MaaAgentClientGetCustomActionList(client: Pointer?, buffer: Pointer?): Byte
}

interface MaaAgentServerLib : Library {

    companion object {
        val INSTANCE: MaaAgentServerLib by lazy {
            Native.load("MaaAgentServer", MaaAgentServerLib::class.java)
        }
    }

    /** agent 进程侧注册 custom recognition（回调在 agent 进程执行） */
    fun MaaAgentServerRegisterCustomRecognition(name: String?, recognition: MaaCustomRecognitionCallback?, transArg: Pointer?): Byte

    fun MaaAgentServerRegisterCustomAction(name: String?, action: MaaCustomActionCallback?, transArg: Pointer?): Byte

    /** 以 identifier（ZMQ 地址）启动 server 并监听 */
    fun MaaAgentServerStartUp(identifier: String?): Byte

    fun MaaAgentServerShutDown()
    fun MaaAgentServerJoin()
    fun MaaAgentServerDetach()
}