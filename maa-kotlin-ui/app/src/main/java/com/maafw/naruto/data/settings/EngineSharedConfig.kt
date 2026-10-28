package com.maafw.naruto.data.settings

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 引擎进程 <-> App 进程共享配置（存于 userDir/engine_config.json）。
 *
 * 背景：Shizuku UserService（shell uid=2000，无 root）无权访问 App 私有 SharedPreferences：
 * 1) /data/user/0/<pkg>/shared_prefs/ 目录 shell uid 无权限（mkdir EACCES）；
 * 2) UserService 的 context.applicationContext == null，
 *    SettingsRepository.prefs() 的 `context.applicationContext.getSharedPreferences()` 会直接 NPE。
 * 因此引擎侧需要读取的一切运行设置，统一由 App 侧在启动任务前写入本文件
 * （位于 userDir = /storage/emulated/0/Android/data/<pkg>/files，shell uid 可读写），
 * 引擎侧在 setup() / startTasksJson() 时读取。读不到一律回退默认值，绝不抛异常。
 */
object EngineSharedConfig {

    private const val FILE_NAME = "engine_config.json"

    data class Config(
        /** 引擎复用开关（默认开，对应设置页「引擎复用」） */
        val engineReuse: Boolean = true,
        /** 任务结束后关闭游戏（默认关，对应设置页「任务结束关闭游戏」） */
        val closeGameAfterTask: Boolean = false,
        /** 游戏包名（默认火影忍者官方包；渠道包/改名在设置页可改） */
        val gamePackage: String = DEFAULT_GAME_PACKAGE,
        /** 详细日志（L-6：开启后引擎输出更详细的识别/动作日志） */
        val verboseLogging: Boolean = false,
        /** 强制重启游戏（P2：任务启动时 force-stop 游戏再启动；默认关闭） */
        val forceStop: Boolean = false,
        /** 按 entry 的任务选项（供单任务 startTask 路径） */
        val taskOptions: Map<String, Map<String, String>> = emptyMap(),
    ) {
        fun optionsOf(entry: String): Map<String, String> = taskOptions[entry] ?: emptyMap()
    }

    /** 火影忍者官方包名（默认值） */
    const val DEFAULT_GAME_PACKAGE = "com.tencent.KiHan"

    private fun configFile(userDir: String?): File? {
        if (userDir.isNullOrBlank()) return null
        return File(userDir, FILE_NAME)
    }

    /** App 侧：启动任务前写入（覆盖写，幂等）。失败不影响任务流程。 */
    fun write(userDir: String?, config: Config): Boolean {
        val f = configFile(userDir) ?: return false
        return runCatching {
            f.parentFile?.mkdirs()
            val json = JSONObject().apply {
                put("engine_reuse", config.engineReuse)
                put("close_game_after_task", config.closeGameAfterTask)
                put("game_package", config.gamePackage)
                put("verbose_logging", config.verboseLogging)
                put("force_stop", config.forceStop)
                put("task_options", JSONObject().apply {
                    config.taskOptions.forEach { (entry, opts) ->
                        put(entry, JSONObject(opts))
                    }
                })
            }
            f.writeText(json.toString(), Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    /** 引擎侧：读取（文件缺失/损坏均回退默认值，绝不抛异常） */
    fun read(userDir: String?): Config {
        val f = configFile(userDir) ?: return Config()
        return runCatching {
            if (!f.exists()) return@runCatching Config()
            val json = JSONObject(f.readText(Charsets.UTF_8))
            val taskOpts = mutableMapOf<String, Map<String, String>>()
            json.optJSONObject("task_options")?.let { to ->
                to.keys().forEach { entry ->
                    val opts = to.optJSONObject(entry) ?: return@forEach
                    val m = mutableMapOf<String, String>()
                    opts.keys().forEach { k -> m[k] = opts.optString(k, "") }
                    taskOpts[entry] = m
                }
            }
            Config(
                engineReuse = json.optBoolean("engine_reuse", true),
                closeGameAfterTask = json.optBoolean("close_game_after_task", false),
                gamePackage = json.optString("game_package", DEFAULT_GAME_PACKAGE),
                verboseLogging = json.optBoolean("verbose_logging", false),
                forceStop = json.optBoolean("force_stop", false),
                taskOptions = taskOpts,
            )
        }.getOrElse { Config() }
    }

    /** 从已构建的 items JSON 提取「entry -> options」映射（App 侧组装 Config 用） */
    fun taskOptionsFrom(items: JSONArray): Map<String, Map<String, String>> {
        val taskOpts = mutableMapOf<String, Map<String, String>>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val entry = item.optString("entry", "")
            val opts = item.optJSONObject("options") ?: continue
            if (entry.isBlank()) continue
            val m = mutableMapOf<String, String>()
            opts.keys().forEach { k -> m[k] = opts.optString(k, "") }
            taskOpts[entry] = m
        }
        return taskOpts
    }
}