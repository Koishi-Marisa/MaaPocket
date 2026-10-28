package com.maafw.naruto.remote.internal

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Display
import com.maafw.naruto.third.FakeContext
import com.maafw.naruto.third.Ln
import com.maafw.naruto.third.wrappers.ServiceManager
import java.lang.reflect.Field
import java.lang.reflect.Method

@SuppressLint("BlockedPrivateApi")
object ActivityUtils {

    // android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
    private const val WINDOWING_MODE_FULLSCREEN = 1

    // getAppDisplayId：包名无任何运行中任务
    private const val DISPLAY_NO_TASK = -2

    // 启动后等待任务出现在目标屏的窗口
    private const val APP_PIN_TIMEOUT_MS = 10_000L
    private const val APP_PIN_POLL_INTERVAL_MS = 500L

    // 拉回操作后等系统完成 reparent 的时间
    private const val REPIN_SETTLE_MS = 1_000L

    @Volatile
    var forceFullscreenOnVirtualDisplay: Boolean = false

    private val setLaunchWindowingMode by lazy {
        runCatching {
            ActivityOptions::class.java
                .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }
        }.onFailure { Ln.w("ActivityUtils: setLaunchWindowingMode reflection failed: ${it.message}") }.getOrNull()
    }

    /**
     * 以 shell 身份启动指定 Intent 的 Activity，绕过 BAL 限制。
     * [forceFullscreen] 为 true 时无视用户设置强制 FULLSCREEN 窗口模式（用于漂移拉回重试）。
     */
    @JvmStatic
    @JvmOverloads
    fun startActivity(intent: Intent, displayId: Int = 0, forceFullscreen: Boolean = false): Boolean {
        val am = ServiceManager.getActivityManager() ?: run {
            Ln.e("ActivityUtils.startActivity: ActivityManager is null")
            return false
        }
        return try {
            val launchOptions = ActivityOptions.makeBasic()
            if (displayId != Display.DEFAULT_DISPLAY) {
                launchOptions.launchDisplayId = displayId
                if (forceFullscreenOnVirtualDisplay || forceFullscreen) {
                    runCatching {
                        setLaunchWindowingMode?.invoke(launchOptions, WINDOWING_MODE_FULLSCREEN)
                    }.onFailure {
                        Ln.e("ActivityUtils.startActivity: invoke setLaunchWindowingMode failed: ${it.message}")
                    }
                }
            }
            val ret = try {
                am.startActivity(intent, launchOptions.toBundle())
            } catch (e: Exception) {
                Ln.w("ActivityUtils.startActivity: am.startActivity failed: ${e.message}")
                -1
            }
            if (ret < 0) {
                Ln.w("ActivityUtils.startActivity: returned $ret, fallback to am command")
                startViaAmCommand(intent, displayId)
            } else {
                true
            }
        } catch (e: Exception) {
            Ln.w("ActivityUtils.startActivity: failed, fallback to am command: ${e.message}")
            startViaAmCommand(intent, displayId)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun startApp(
        packageName: String,
        displayId: Int,
        forceStop: Boolean = true,
        excludeFromRecents: Boolean = true
    ): Boolean {
        val pm = FakeContext.get().packageManager

        val intent = pm.getLaunchIntentForPackage(packageName) ?: run {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                pm.getLeanbackLaunchIntentForPackage(packageName)
            } else {
                null
            }
        }

        if (intent == null) {
            Ln.w("ActivityUtils.startApp: cannot create launch intent for $packageName")
            return false
        }

        var flag = Intent.FLAG_ACTIVITY_NEW_TASK
        if (excludeFromRecents) {
            flag = flag or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        intent.addFlags(flag)

        if (forceStop) {
            ServiceManager.getActivityManager().forceStopPackage(packageName)
        }
        Ln.i("ActivityUtils.startApp: ${intent.component?.flattenToShortString()}")

        return startActivity(intent, displayId)
    }

    /**
     * 检查指定包名的应用最近的活动 task 是否运行在给定 displayId 上。
     * API 28 无 TaskInfo.displayId 字段（@hide），宽松返回 true（不拦截）。
     * 任何异常也宽松返回 true，避免误伤。
     */
    fun isAppOnDisplay(packageName: String, targetDisplayId: Int): Boolean {
        return when (getAppDisplayId(packageName)) {
            null -> true // 无法判断，宽松放行
            targetDisplayId -> true
            else -> false
        }
    }

    /**
     * 启动后校验：等待 [packageName] 的任务出现在 [displayId] 上；若发现任务落在其它
     * display（如 One UI / 部分 ROM 会把游戏从虚拟屏挪回主屏，B 服 U8 SDK 二段跳也可能
     * 丢失 launchDisplayId），立即尝试拉回。仅在确认漂移且拉回失败时返回 false。
     */
    @JvmStatic
    @JvmOverloads
    fun ensureAppOnDisplay(
        packageName: String,
        displayId: Int,
        timeoutMs: Long = APP_PIN_TIMEOUT_MS
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            when (val current = getAppDisplayId(packageName)) {
                null -> return true // 无法判断，宽松放行
                displayId -> return true
                DISPLAY_NO_TASK -> Unit // 任务尚未出现，继续等待
                else -> {
                    Ln.w("ActivityUtils.ensureAppOnDisplay: $packageName drifted to display $current (expected $displayId), trying to move it back")
                    return repinAppToDisplay(packageName, displayId)
                }
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                Ln.w("ActivityUtils.ensureAppOnDisplay: no task of $packageName observed within ${timeoutMs}ms, passing leniently")
                return true
            }
            SystemClock.sleep(APP_PIN_POLL_INTERVAL_MS)
        }
    }

    /**
     * 把 [packageName] 的任务拉回 [displayId]：
     * 1. moveRootTaskToDisplay / moveStackToDisplay（hidden API，shell 有 MANAGE_ACTIVITY_TASKS）
     * 2. am display move-stack 命令兜底
     * 3. 重新投放 launch intent（强制 FULLSCREEN），让系统 reparent 现有任务
     */
    @JvmStatic
    fun repinAppToDisplay(packageName: String, displayId: Int): Boolean {
        if (moveAppTaskToDisplay(packageName, displayId)) {
            SystemClock.sleep(REPIN_SETTLE_MS)
            if (isAppOnDisplay(packageName, displayId)) {
                Ln.i("ActivityUtils.repinAppToDisplay: $packageName moved back to display $displayId")
                return true
            }
        }
        val pm = FakeContext.get().packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            if (startActivity(intent, displayId, forceFullscreen = true)) {
                SystemClock.sleep(REPIN_SETTLE_MS)
                if (isAppOnDisplay(packageName, displayId)) {
                    Ln.i("ActivityUtils.repinAppToDisplay: $packageName relaunched onto display $displayId")
                    return true
                }
            }
        }
        Ln.e("ActivityUtils.repinAppToDisplay: failed to pin $packageName on display $displayId")
        return false
    }

    /**
     * 返回 [packageName] 最近任务所在的 displayId。
     * null = 无法判断（API < Q / 反射失败 / 异常）；[DISPLAY_NO_TASK] = 无运行中任务。
     * 取最近任务而非任意任务：漂移时新任务落在主屏，虚拟屏上可能残留旧任务。
     */
    private fun getAppDisplayId(packageName: String): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (taskDisplayIdField == null) return null
        return runCatching {
            val task = findRecentTask(packageName) ?: return@runCatching DISPLAY_NO_TASK
            getTaskDisplayId(task).takeIf { it >= 0 }
        }.getOrNull()
    }

    private fun findRecentTask(packageName: String): ActivityManager.RunningTaskInfo? {
        val am = FakeContext.get().getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        @Suppress("DEPRECATION")
        val tasks = am.getRunningTasks(100)
        return tasks.firstOrNull { task ->
            task.topActivity?.packageName == packageName
                    || task.baseActivity?.packageName == packageName
        }
    }

    @JvmStatic
    fun moveAppTaskToDisplay(packageName: String, displayId: Int): Boolean {
        val task = runCatching { findRecentTask(packageName) }.getOrNull() ?: run {
            Ln.w("ActivityUtils.moveAppTaskToDisplay: no running task of $packageName")
            return false
        }
        @Suppress("DEPRECATION")
        val taskId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) task.taskId else task.id
        val method = moveTaskToDisplayMethod
        if (method != null) {
            runCatching {
                method.invoke(activityTaskManager, taskId, displayId)
                Ln.i("ActivityUtils.moveAppTaskToDisplay: ${method.name}($taskId, $displayId) invoked")
                return true
            }.onFailure {
                Ln.w("ActivityUtils.moveAppTaskToDisplay: ${method.name} failed: ${it.message}")
            }
        }
        return moveTaskViaAmCommand(taskId, displayId)
    }

    private val activityTaskManager: Any? by lazy {
        runCatching {
            val binder = ServiceManager.getService("activity_task")
            Class.forName("android.app.IActivityTaskManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.onFailure {
            Ln.w("ActivityUtils.activityTaskManager: reflection failed: ${it.message}")
        }.getOrNull()
    }

    // API 31+: moveRootTaskToDisplay；API 29/30: moveStackToDisplay
    private val moveTaskToDisplayMethod: Method? by lazy {
        val atm = activityTaskManager ?: return@lazy null
        val candidates = arrayOf("moveRootTaskToDisplay", "moveStackToDisplay")
        for (name in candidates) {
            val method = runCatching {
                atm.javaClass.getMethod(name, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            }.getOrNull()
            if (method != null) return@lazy method
        }
        Ln.w("ActivityUtils.moveTaskToDisplayMethod: no candidate method found")
        null
    }

    private fun moveTaskViaAmCommand(taskId: Int, displayId: Int): Boolean {
        return try {
            val args = arrayOf("am", "display", "move-stack", taskId.toString(), displayId.toString())
            Ln.i("ActivityUtils.moveTaskViaAmCommand: exec: ${args.joinToString(" ")}")
            val process = Runtime.getRuntime().exec(args)
            val exitCode = process.waitFor()
            val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()
            if (stderr.isNotEmpty()) Ln.w("ActivityUtils.moveTaskViaAmCommand: stderr: $stderr")
            if (exitCode != 0) {
                Ln.w("ActivityUtils.moveTaskViaAmCommand: exitCode=$exitCode")
                return false
            }
            true
        } catch (e: Exception) {
            Ln.e("ActivityUtils.moveTaskViaAmCommand: failed: ${e.message}")
            false
        }
    }

    private val taskDisplayIdField: Field? by lazy {
        runCatching {
            var cls: Class<*>? = ActivityManager.RunningTaskInfo::class.java
            var field: Field? = null
            while (true) {
                val c = cls ?: break
                field = runCatching { c.getDeclaredField("displayId") }.getOrNull()
                if (field != null) break
                cls = c.superclass
            }
            field?.also { it.isAccessible = true }
        }.onFailure {
            Ln.w("ActivityUtils.taskDisplayIdField: reflection failed: ${it.message}")
        }.getOrNull()
    }

    private fun getTaskDisplayId(task: ActivityManager.RunningTaskInfo): Int =
        runCatching { taskDisplayIdField?.getInt(task) ?: -1 }.getOrDefault(-1)

    private fun startViaAmCommand(intent: Intent, displayId: Int): Boolean {
        return try {
            val intentUri = intent.toUri(Intent.URI_INTENT_SCHEME)
            val args = if (displayId == Display.DEFAULT_DISPLAY) {
                arrayOf("am", "start", intentUri)
            } else {
                arrayOf("am", "start", "--display", displayId.toString(), intentUri)
            }
            Ln.i("ActivityUtils.startViaAmCommand: exec: ${args.joinToString(" ")}")
            val process = Runtime.getRuntime().exec(args)
            val exitCode = process.waitFor()
            // am start 输出量极小（远小于管道缓冲），先 waitFor 再读不会死锁
            val stdout = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()
            if (stdout.isNotEmpty()) Ln.i("ActivityUtils.startViaAmCommand: stdout: $stdout")
            if (stderr.isNotEmpty()) Ln.w("ActivityUtils.startViaAmCommand: stderr: $stderr")
            if (exitCode != 0) {
                Ln.w("ActivityUtils.startViaAmCommand: exitCode=$exitCode")
                return false
            }
            Ln.i("ActivityUtils.startViaAmCommand: success")
            true
        } catch (e: Exception) {
            Ln.e("ActivityUtils.startViaAmCommand: failed: ${e.message}")
            false
        }
    }

    /**
     * 用 am start --display 把应用直接启动到指定虚拟屏喵（ ）。
     * 部分 ROM 会丢失 ActivityOptions.launchDisplayId（腾讯 MSDK 二段跳尤其明显），
     * 而 am start --display 在真机上验证可用，作为预启动/校准的兜底手段。
     */
    @JvmStatic
    fun startAppViaAmCommand(packageName: String, displayId: Int): Boolean {
        if (displayId <= 0 || packageName.isBlank()) return false
        val pm = FakeContext.get().packageManager
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return false
        val component = intent.component ?: return false
        return try {
            val args = arrayOf(
                "/system/bin/am", "start",
                "--display", displayId.toString(),
                "--user", "0",
                "-f", "0x10000000", // FLAG_ACTIVITY_NEW_TASK
                "-n", component.flattenToShortString()
            )
            Ln.i("ActivityUtils.startAppViaAmCommand: exec: ${args.joinToString(" ")}")
            val process = Runtime.getRuntime().exec(args)
            val exitCode = process.waitFor()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()
            if (stdout.isNotEmpty()) Ln.i("ActivityUtils.startAppViaAmCommand: stdout: $stdout")
            if (stderr.isNotEmpty()) Ln.w("ActivityUtils.startAppViaAmCommand: stderr: $stderr")
            if (exitCode != 0) {
                Ln.w("ActivityUtils.startAppViaAmCommand: exitCode=$exitCode")
                return false
            }
            Ln.i("ActivityUtils.startAppViaAmCommand: success")
            true
        } catch (e: Exception) {
            Ln.e("ActivityUtils.startAppViaAmCommand: failed: ${e.message}")
            false
        }
    }
}