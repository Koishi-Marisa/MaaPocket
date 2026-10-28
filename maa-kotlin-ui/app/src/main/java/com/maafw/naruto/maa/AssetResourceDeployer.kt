package com.maafw.naruto.maa

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 把 assets/resource 部署到 /data/data/<pkg>/files/resource/ 
 * MaaFramework 加载 pipeline 和 image 都需要真实文件路径。
 */
object AssetResourceDeployer {

    private const val TAG = "AssetResourceDeployer"

    /**
     * 同步复制 assets/resource 下所有文件到资源目录。
     * 返回 base 目录的绝对路径。
     *
     * 目录选择shell uid 对 App 外部目录可写，内部 filesDir 不可写：
     * 1. App 外部目录 userDir/resource（App 传参，/storage/emulated/0/Android/data/{pkg}/files，shell 可写）首选
     * 2. App 内部 filesDir/resource（仅 root / 自身 uid 可写）
     * 3. /data/local/tmp/maafw_res_<pkg>（兜底）
     */
    fun deploy(context: Context, userDir: String? = null, force: Boolean = false): String {
        val start = System.currentTimeMillis()

        val extRoot = userDir?.let { File(it, "resource") }
        val appRoot = File(context.filesDir, "resource")
        val tmpRoot = File("/data/local/tmp", "maafw_res_${context.packageName}")

        // 选第一个「已有资源可读 或 目录可写」的根目录
        val root: File = listOf(extRoot, appRoot, tmpRoot).firstNotNullOfOrNull { r ->
            if (r == null) return@firstNotNullOfOrNull null
            val base = File(r, "base")
            val ready = (base.exists() && base.list()?.isNotEmpty() == true) || r.canWrite() || r.mkdirs()
            if (ready) r else null
        } ?: tmpRoot
        val baseDir = File(root, "base")
        Log.i(TAG, "deploy 开始：filesDir=${context.filesDir} userDir=$userDir -> root=$root")

        // 统计 assets/resource/base 下的文件数（判断是否完整）
        val assetBaseCount = runCatching {
            countAssets(context.assets, "resource/base")
        }.getOrDefault(-1)

        if (!force && baseDir.exists() && baseDir.list()?.isNotEmpty() == true) {
            val onDisk = countFiles(baseDir)
            // pipeline 文件数变化说明资源有更新（如 MultiSwipe 转换），强制重新部署
            val diskPipelineCount = File(baseDir, "pipeline").listFiles()?.size ?: -1
            val assetPipelineCount = runCatching {
                context.assets.list("resource/base/pipeline")?.size ?: -1
            }.getOrDefault(-1)
            // 部署版本号：App versionCode 变化（资源可能已改内容）-> 强制重新部署
            val versionFile = File(baseDir, ".maafw_version")
            val diskVersion = runCatching { versionFile.readText().trim() }.getOrDefault("")
            val curVersion = com.maafw.naruto.BuildConfig.VERSION_CODE.toString()
            // 完整文件对比：assets 与磁盘每个文件（文件名+大小）是否一致——不一致说明资源不是最新，必须覆盖更新
            val filesMatch = runCatching { assetsMatchDisk(context.assets, "resource/base", baseDir) }.getOrDefault(false)
            if (diskPipelineCount == assetPipelineCount && diskVersion == curVersion && filesMatch) {
                Log.i(TAG, "资源已是最新，跳过部署：$baseDir 磁盘文件=$onDisk assets文件=$assetBaseCount")
                return baseDir.absolutePath
            }
            Log.w(TAG, "资源需要重新部署（pipeline: 磁盘=$diskPipelineCount assets=$assetPipelineCount；版本: 磁盘=$diskVersion 当前=$curVersion；文件全匹配=$filesMatch）")
            runCatching { root.deleteRecursively() }
        } else if (force) {
            // 强制更新：删除现有部署，从安装包 assets 完整重拷（确保释放的是安装包最新版本资源）
            Log.i(TAG, "强制重新部署资源（force=true）：删除现有 $root")
            runCatching { root.deleteRecursively() }
        }

        Log.i(TAG, "开始部署资源到 $root（assets/resource/base 文件数=$assetBaseCount）")
        copyAssets(context.assets, "resource", root)
        val onDisk = countFiles(baseDir)
        val cost = System.currentTimeMillis() - start
        // 记录部署版本号（下次启动据此判断是否需要重新部署）
        runCatching { File(baseDir, ".maafw_version").writeText(com.maafw.naruto.BuildConfig.VERSION_CODE.toString()) }
        Log.i(TAG, "资源部署完成：$baseDir 文件数=$onDisk 耗时=${cost}ms")
        if (onDisk <= 0) {
            throw IllegalStateException("资源部署后文件数为 0，assets 可能缺失或复制失败")
        }
        return baseDir.absolutePath
    }

    private fun countAssets(am: android.content.res.AssetManager, path: String): Int {
        val files = am.list(path) ?: return 0
        var count = 0
        for (name in files) {
            val sub = "$path/$name"
            val children = am.list(sub) ?: continue
            count += if (children.isEmpty()) 1 else countAssets(am, sub)
        }
        return count
    }

    private fun countFiles(dir: File): Int {
        return dir.walkTopDown().count { it.isFile }
    }

    /** 递归对比 assets 与磁盘：每个文件（相对路径+大小）一致才返回 true */
    private fun assetsMatchDisk(am: android.content.res.AssetManager, assetPath: String, diskDir: File): Boolean {
        val entries = am.list(assetPath) ?: return false
        for (name in entries) {
            val aPath = "$assetPath/$name"
            val dFile = File(diskDir, name)
            val children = am.list(aPath) ?: continue
            if (children.isEmpty()) {
                // 文件：磁盘必须存在且大小一致
                if (!dFile.isFile) return false
                val aSize = runCatching { am.open(aPath).use { it.available().toLong() } }.getOrDefault(-1L)
                if (aSize != dFile.length()) return false
            } else {
                if (!dFile.isDirectory) return false
                if (!assetsMatchDisk(am, aPath, dFile)) return false
            }
        }
        return true
    }

    private fun copyAssets(assetManager: android.content.res.AssetManager, path: String, destDir: File) {
        val files = assetManager.list(path) ?: return
        if (files.isEmpty()) {
            // 是文件
            destDir.parentFile?.mkdirs()
            assetManager.open(path).use { input ->
                File(destDir.parentFile, destDir.name).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }
        destDir.mkdirs()
        for (name in files) {
            copyAssets(assetManager, "$path/$name", File(destDir, name))
        }
    }
}