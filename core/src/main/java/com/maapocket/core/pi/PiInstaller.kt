package com.maapocket.core.pi

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * 把 APK 里烘焙好的 pi pack（`assets/pi/**`）解包到 App 私有外部目录。
 *
 * 为什么必须解包：MaaFramework 的 `MaaResourcePostBundle` 只接受**真实目录路径**，
 * 没法直接读 APK 内的 assets。
 *
 * 目录布局：`<externalFilesDir>/pi/<stamp>/`，`stamp` = `<packVersion>-vc<versionCode>`。
 * 换版本会落到新目录，因此升级后不会出现新旧资源混用；老目录在解包成功后清理。
 * 完成标记 `<stamp>/.complete` 存在即认为解包完整（不需要逐文件校验，一次解包代价可接受）。
 */
class PiInstaller(private val context: Context) {

    companion object {
        private const val ASSET_ROOT = "pi"
        private const val MARKER = ".complete"

        fun piRoot(context: Context): File = File(context.getExternalFilesDir(null), "pi")
    }

    data class Result(val root: File, val fileCount: Int, val bytes: Long, val extracted: Boolean)

    /**
     * @param stamp 资源包身份戳；通常用 `pi.version ?: "dev"` + `versionCode`
     * @param force 忽略完成标记强制重解（开发者选项 / 调试用）
     */
    suspend fun install(stamp: String, force: Boolean = false): Result = withContext(Dispatchers.IO) {
        val parent = piRoot(context)
        val dest = File(parent, sanitize(stamp))
        val marker = File(dest, MARKER)

        if (!force && marker.isFile && marker.length() > 0) {
            val (count, bytes) = measure(dest)
            Timber.i("pi pack 已解包，跳过: %s (%d 文件 / %d 字节)", dest.absolutePath, count, bytes)
            return@withContext Result(dest, count, bytes, extracted = false)
        }

        if (dest.exists()) dest.deleteRecursively()
        check(dest.mkdirs() || dest.isDirectory) { "无法创建 pi 目录: ${dest.absolutePath}" }

        val stats = longArrayOf(0L, 0L)
        copyAssetTree(context.assets, ASSET_ROOT, dest, stats)

        marker.writeText(stamp)
        // 解包成功后再清理老版本目录，避免中途失败导致没有可用资源。
        parent.listFiles()?.forEach { sibling ->
            if (sibling.isDirectory && sibling.name != dest.name) {
                Timber.i("清理过期 pi 目录: %s", sibling.name)
                sibling.deleteRecursively()
            }
        }
        Result(dest, stats[0].toInt(), stats[1], extracted = true)
    }

    private fun measure(dir: File): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                count++
                bytes += f.length()
            }
        }
        return count to bytes
    }

    private fun copyAssetTree(am: AssetManager, assetPath: String, destDir: File, stats: LongArray) {
        val children = try {
            am.list(assetPath)
        } catch (e: IOException) {
            Timber.w(e, "list assets 失败: %s", assetPath)
            return
        } ?: return

        if (children.isEmpty()) {
            // 叶子：AssetManager.list 对文件返回空数组。
            val target = File(destDir, assetPath.substringAfterLast('/'))
            try {
                am.open(assetPath).use { input ->
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> stats[1] += input.copyTo(output) }
                    stats[0]++
                }
            } catch (e: IOException) {
                // 目录在部分 ROM 上也会返回空数组，这里吞掉即可。
                Timber.v("跳过非文件 asset: %s (%s)", assetPath, e.message)
            }
            return
        }

        for (child in children) {
            val childAsset = "$assetPath/$child"
            val childDir = File(destDir, child)
            val grandChildren = am.list(childAsset)
            if (grandChildren != null && grandChildren.isNotEmpty()) {
                childDir.mkdirs()
                copyAssetTree(am, childAsset, childDir, stats)
            } else {
                childDir.parentFile?.mkdirs()
                try {
                    am.open(childAsset).use { input ->
                        childDir.outputStream().use { output -> stats[1] += input.copyTo(output) }
                        stats[0]++
                    }
                } catch (e: IOException) {
                    Timber.v("跳过非文件 asset: %s (%s)", childAsset, e.message)
                }
            }
        }
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "default" }
}
