package com.maafw.naruto.data.settings

import android.content.Context
import com.maafw.naruto.model.MaaInterface

/**
 * 解析本次任务要启动的游戏包名。
 *
 * 优先级：
 * 1. 用户在任务选项 `Server` 里选中的 case（从 `StartUpGame.pipeline_override.package` 读）；
 * 2. 本机实际安装情况：只装了 B服 → B服；只装了国服 → 国服；都装了 → 选项/默认；
 * 3. 都拿不到 → 国服。
 *
 * 结果会写进 `EngineSharedConfig.gamePackage`，供引擎进程的 `prelaunchGame`
 * 与任务结束关闭游戏使用；同时以 pipeline_override 覆盖 `StartUpGame` / `CloseGame`
 * 的 `package`，保证 B服 不会因为资源包写死国服包名而启动失败。
 */
object GamePackageResolver {

    const val HSR_OFFICIAL = "com.miHoYo.hkrpg"
    const val HSR_BILIBILI = "com.miHoYo.hkrpg.bilibili"

    fun isInstalled(context: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return runCatching {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        }.getOrDefault(false)
    }

    /** 从 interface 的 Server 选项 case 里读 StartUpGame.package。 */
    fun packageFromServerCase(iface: MaaInterface?, serverValue: String?): String? {
        val option = iface?.option?.get("Server") ?: return null
        val caseName = serverValue?.takeIf { it.isNotBlank() } ?: option.defaultCase
        val case = option.cases.firstOrNull { it.name == caseName } ?: return null
        val node = case.pipelineOverride?.getAsJsonObject("StartUpGame") ?: return null
        return node.get("package")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
    }

    fun detectInstalled(context: Context): String {
        val bili = isInstalled(context, HSR_BILIBILI)
        val official = isInstalled(context, HSR_OFFICIAL)
        return when {
            bili && !official -> HSR_BILIBILI
            official -> HSR_OFFICIAL
            bili -> HSR_BILIBILI
            else -> HSR_OFFICIAL
        }
    }

    fun resolveFromServerValue(context: Context, iface: MaaInterface?, serverValue: String?): String {
        val selected = packageFromServerCase(iface, serverValue)
        if (isInstalled(context, selected)) return selected!!
        return detectInstalled(context)
    }

    /** 从本次要跑的 entry 里找第一个非空 Server 选项，再回退到安装探测。 */
    fun resolve(
        context: Context,
        iface: MaaInterface?,
        entries: List<String>,
        profile: String = "default",
    ): String {
        val serverValue = entries.asSequence()
            .map { SettingsRepository.getTaskConfig(context, it, profile).options["Server"] }
            .firstOrNull { !it.isNullOrBlank() }
        return resolveFromServerValue(context, iface, serverValue)
    }
}
