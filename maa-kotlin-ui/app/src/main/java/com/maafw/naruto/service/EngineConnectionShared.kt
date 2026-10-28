package com.maafw.naruto.service

import com.maafw.naruto.IRemoteEngineService

/**
 * App 进程内共享引擎连接（P0-A 双引擎收敛）。
 * 手动任务（MainActivity）与定时任务（MaaEngineService）共用同一份绑定，
 * 避免各自 bindUserService 启动两个引擎进程抢虚拟屏/识别/触摸（黑屏/识别错乱/任务互覆盖）。
 *
 * 规则：
 * - MainActivity 绑定成功后写入（owner="main"），清理（onDestroy/cleanupStaleShizukuBinding）时 clear；
 * - MaaEngineService 优先复用共享连接（不重复 bind）；共享为空/失效时才自己 bind（owner="schedule"）；
 * - 谁真正持有 connection 谁负责 unbind；复用方只借 binder，不 unbind。
 */
object EngineConnectionShared {
    /** 当前共享的引擎服务 */
    @Volatile
    var service: IRemoteEngineService? = null

    @Volatile
    var bound = false

    /** 当前绑定持有者："main"（手动任务）/ "schedule"（定时任务） */
    @Volatile
    var owner: String? = null

    /** 共享引擎的运行模式："shizuku" / "root"（复用前校验模式匹配，避免定时任务复用到错误模式的引擎） */
    @Volatile
    var engineMode: String = "shizuku"

    /** 获取仍存活的共享引擎（binder 失效返回 null） */
    fun aliveService(): IRemoteEngineService? {
        val s = service ?: return null
        if (!bound) return null
        return if (runCatching { s.asBinder()?.pingBinder() == true }.getOrDefault(false)) s else {
            clear()
            null
        }
    }

    fun clear() {
        service = null
        bound = false
        owner = null
        engineMode = "shizuku"
    }
}