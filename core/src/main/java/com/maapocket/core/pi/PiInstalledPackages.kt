package com.maapocket.core.pi

import android.content.Context
import android.content.pm.PackageManager
import timber.log.Timber

/**
 * 用 App 自己的 [PackageManager] 造一个「这个客户端包名真的装在本机吗」探针，
 * 交给 [PiSelection.resolve] 的 `installedPackages` 参数使用。
 *
 * 为什么需要：崩铁 / 绝区零 / 终末地 都有国服与 B服 两套安装包，资源包的
 * `default_case` 写死其中一个。用户只装了另一个时，`StartApp` 会指向不存在的包，
 * 于是「点了开始游戏但游戏没起来」——这正是 m03108 报的现象之一。
 *
 * 两个实现上的注意点：
 *
 * 1. **Android 11+ 包可见性**。本 App 的 `targetSdk` 是 36，未在
 *    `<queries>` 里声明的包一律查不到，`getPackageInfo` 会抛
 *    `NameNotFoundException`。所以 `core/src/main/AndroidManifest.xml` 里
 *    逐个列出了这三款游戏的已知包名。**漏列只会让某款游戏退化成「探测不出来」
 *    （进而保持旧行为），不会误判成「没装」再乱切**——这一点是刻意保证的。
 * 2. 弹窗/异常一律当成「装好了」（返回 true），宁可不切也不要因为一次
 *    `SecurityException` 就把用户显式配置的客户端换掉。
 */
object PiInstalledPackages {

    /**
     * @return 复用同一个 [PackageManager] 与一张记忆表；同一次运行里同一包名只查一次。
     */
    fun of(context: Context): PiSelection.PackageInstalled {
        val pm = context.applicationContext.packageManager
        val cache = HashMap<String, Boolean>()
        return { raw ->
            val pkg = raw.substringBefore('/').trim()
            if (pkg.isEmpty()) {
                true
            } else {
                cache.getOrPut(pkg) { probe(pm, pkg) }
            }
        }
    }

    /**
     * 单独查一个包。PI 里 `StartApp` 的 `package` 允许写成 `包名/Activity`，
     * 调用方负责先用 `substringBefore('/')` 削成纯包名。
     */
    fun probe(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (t: Throwable) {
        // NameNotFoundException = 确实没装（或不可见）；
        // SecurityException / DeadSystemException 等异常不该被理解成「没装」。
        val absent = t is PackageManager.NameNotFoundException
        if (!absent) {
            Timber.w(t, "查询包 %s 失败，按「已安装」处理以免误改用户选择", pkg)
        }
        !absent
    }
}
