package com.maafw.naruto.root

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.IBinder
import android.os.Looper
import com.maafw.naruto.IRemoteEngineService
import com.maafw.naruto.data.profile.ProfileManager
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.model.AssetLoader
import com.maafw.naruto.model.OptionOverrideBuilder
import com.maafw.naruto.remote.RemoteEngineServiceImpl
import com.maafw.naruto.schedule.ScheduleHelper
import com.maafw.naruto.schedule.data.SchedulePolicyRepository
import com.maafw.naruto.schedule.model.ExecutionResult
import com.maafw.naruto.schedule.model.ScheduleStrategy
import com.maafw.naruto.third.Ln
import org.json.JSONArray
import org.json.JSONObject

/**
 * Root 守护进程（app_process main）～
 *
 * 由设置里「Root 守护进程」开关控制，以 su + CLASSPATH + app_process 启动，
 * 常驻 root 进程（uid=0 不会被系统后台清理）。职责：
 * 1. 周期性读取定时策略（App 的 SharedPreferences，root 可读）；
 * 2. 计算下一次触发时间并睡眠等待（最长 30s 醒来重读，及时响应策略变更）；
 * 3. 到点直接在当前进程内创建虚拟屏、启动 MaaFramework 执行任务——
 *    完全不依赖 App 进程存活（App 被 force-stop/后台清理也能准时执行）。
 *
 * 与 RootServiceStarter（root 引擎进程，守护 App 生命周期）不同：
 * 本 daemon 不守护 App，App 退出/被杀后依然常驻调度。
 */
object RootDaemon {

    private const val TAG = "RootDaemon"
    const val PROCESS_TAG = "com.maafw.naruto.root.RootDaemon"
    /** 唤醒周期上限：30s 内重新读取策略（及时响应 App 侧策略变更/开关变化） */
    private const val RESCAN_MS = 30_000L
    /** 无可用策略时的轮询间隔 */
    private const val IDLE_POLL_MS = 60_000L
    /** 任务执行完成后的防抖间隔（避免同一次触发被重复执行） */
    private const val POST_EXECUTE_DELAY_MS = 10_000L

    private var engine: IRemoteEngineService? = null

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }
        val parsed = parseArgs(args)
        if (parsed == null) {
            System.err.println("[RootDaemon] 参数不完整: ${args.joinToString(" ")}")
            System.exit(1)
            return
        }

        Ln.i("$TAG: 守护进程启动 uid=${parsed.uid} pkg=${parsed.packageName}")
        try {
            // 1) 创建 App 包上下文（读 SharedPreferences 策略/配置）
            val context = createPackageContext(parsed)
            // 2) 创建引擎实例（root 进程内直接实例化，与 RootServiceStarter 相同的运行环境）
            engine = RemoteEngineServiceImpl(context)
            Ln.i("$TAG: 引擎实例就绪")

            // 3) 常驻调度循环（守护线程）
            Thread({ scheduleLoop(context) }, "root-daemon-scheduler").apply {
                isDaemon = false
                start()
            }
            Looper.loop()
        } catch (e: Throwable) {
            Ln.e("$TAG: 守护进程启动失败", e)
            System.exit(1)
        }
    }

    private fun scheduleLoop(context: Context) {
        val repository = SchedulePolicyRepository(context)
        while (true) {
            try {
                val now = System.currentTimeMillis()
                val strategies = repository.load().filter { it.enabled }
                if (strategies.isEmpty()) {
                    Thread.sleep(IDLE_POLL_MS)
                    continue
                }

                // 找下一次触发（lastExecutedAt 之后，避免重复触发）
                var best: Pair<ScheduleStrategy, Long>? = null
                for (s in strategies) {
                    val after = s.lastExecutedAt ?: 0L
                    val t = ScheduleHelper.computeNextTriggerMs(s, after) ?: continue
                    if (best == null || t < best!!.second) best = s to t
                }
                if (best == null) {
                    Thread.sleep(IDLE_POLL_MS)
                    continue
                }
                val (strategy, triggerAt) = best!!

                if (triggerAt <= now) {
                    Ln.i("$TAG: 触发定时策略 [${strategy.name}] (${strategy.profileId})")
                    executeStrategy(context, strategy, repository)
                    Thread.sleep(POST_EXECUTE_DELAY_MS)
                    continue
                }

                val sleepMs = (triggerAt - now).coerceAtMost(RESCAN_MS)
                Thread.sleep(sleepMs)
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                Ln.e("$TAG: 调度循环异常: ${e.message}")
                Thread.sleep(5_000)
            }
        }
    }

    /** 执行单个定时策略（复用引擎 AIDL 接口，与 MaaEngineService.runProfile 等价） */
    private fun executeStrategy(
        context: Context,
        strategy: ScheduleStrategy,
        repository: SchedulePolicyRepository,
    ) {
        val eng = engine ?: return
        // 后台保障：root 守护进程自持唤醒锁——App 全被杀/锁屏深度休眠时 CPU 仍不休眠，任务照跑
        val wakeLock = runCatching {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MaaFW:root_daemon_task").apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L)
            }
        }.getOrNull()
        try {
            try {
            val userDir = runCatching { context.getExternalFilesDir(null)?.absolutePath }.getOrNull()
            eng.setup(userDir)

            if (strategy.forceStart && eng.isRunning) {
                eng.stopTask()
            }

            val displayId = eng.startVirtualDisplay()
            if (displayId < 0) {
                repository.recordExecutionResult(strategy.id, ExecutionResult.FAILED_START, "守护进程：虚拟屏创建失败")
                return
            }

            val interfaceData = AssetLoader.loadInterface(context)
            val profile = ProfileManager.load(context, strategy.profileId)
                ?: run {
                    eng.stopVirtualDisplay()
                    repository.recordExecutionResult(strategy.id, ExecutionResult.FAILED_VALIDATION, "配置不存在")
                    return
                }
            val enabled = profile.tasks.filter { it.enabled }
            if (enabled.isEmpty()) {
                eng.stopVirtualDisplay()
                repository.recordExecutionResult(strategy.id, ExecutionResult.FAILED_VALIDATION, "无启用任务")
                return
            }

            val tasks = interfaceData?.task ?: emptyList()
            val items = JSONArray()
            for (entry in enabled.map { it.entry }) {
                val task = tasks.find { it.entry == entry } ?: continue
                val config = SettingsRepository.getTaskConfig(context, entry)
                val override = OptionOverrideBuilder.build(task, config.options, interfaceData)
                items.put(JSONObject().apply {
                    put("entry", entry)
                    put("options", JSONObject().apply {
                        config.options.forEach { (k, v) -> put(k, v) }
                    })
                    override?.let { put("pipeline_override", it) }
                })
            }
            if (items.length() == 0) {
                eng.stopVirtualDisplay()
                repository.recordExecutionResult(strategy.id, ExecutionResult.FAILED_VALIDATION, "无有效任务")
                return
            }

            // 引擎侧（shell/root 进程）统一走 userDir 共享配置读取运行设置，不依赖 App 私有 SharedPreferences
            runCatching {
                com.maafw.naruto.data.settings.EngineSharedConfig.write(
                    userDir,
                    com.maafw.naruto.data.settings.EngineSharedConfig.Config(
                        engineReuse = SettingsRepository.isEngineReuseEnabled(context),
                        closeGameAfterTask = SettingsRepository.isCloseGameAfterTask(context),
                        taskOptions = com.maafw.naruto.data.settings.EngineSharedConfig.taskOptionsFrom(items)
                    )
                )
            }.onFailure { Ln.w("$TAG: 引擎共享配置写入失败: ${it.message}") }

            if (!eng.startTasksJson(items.toString())) {
                eng.stopVirtualDisplay()
                repository.recordExecutionResult(strategy.id, ExecutionResult.FAILED_START, "任务启动失败")
                return
            }

            while (eng.isRunning) {
                Thread.sleep(1_000)
            }

            if (strategy.autoSleepAfterTask) {
                eng.setDisplayPower(false)
            }
            eng.stopVirtualDisplay()
            if (strategy.closeGameAfterTask) {
                runCatching { eng.stopPackage("com.tencent.KiHan") }
            }

            repository.recordExecutionResult(strategy.id, ExecutionResult.STARTED, "守护进程执行完成")
            Ln.i("$TAG: 策略 [${strategy.name}] 执行完成")
        } catch (e: Throwable) {
            Ln.e("$TAG: 策略 [${strategy.name}] 执行异常: ${e.message}")
            repository.recordExecutionResult(strategy.id, ExecutionResult.FAILED_START, "守护进程异常：${e.message}")
            runCatching { eng.stopVirtualDisplay() }
            }
        } finally {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        }
    }

    // ---- 参数与上下文（复用 RootUserService 相同机制） ----

    private data class ParsedArgs(
        val packageName: String,
        val uid: Int,
        val debugName: String,
    )

    private fun parseArgs(args: Array<String>): ParsedArgs? {
        var pkg: String? = null
        var uid = -1
        var debugName = "root_daemon"
        for (arg in args) {
            if (arg.startsWith("--package=")) pkg = arg.substring(10)
            else if (arg.startsWith("--uid=")) uid = arg.substring(6).toIntOrNull() ?: -1
            else if (arg.startsWith("--debug-name=")) debugName = arg.substring(13)
        }
        if (pkg == null || uid < 0) return null
        return ParsedArgs(pkg, uid, debugName)
    }

    private fun createPackageContext(parsed: ParsedArgs): Context {
        val activityThread = createActivityThread()
        val systemContext = getSystemContext(activityThread)
        val userId = parsed.uid / 100000
        val pkgContext = createPackageContextAsUser(systemContext, parsed.packageName, userId)
        setAppName(parsed.debugName, userId)
        // 尝试 makeApplication（失败也不影响，仅用于个别组件初始化）
        runCatching {
            val field = pkgContext.javaClass.getDeclaredField("mPackageInfo")
            field.isAccessible = true
            val loadedApk = field.get(pkgContext)
            val make = loadedApk.javaClass.getDeclaredMethod("makeApplication", Boolean::class.java, Instrumentation::class.java)
            make.isAccessible = true
            val app = make.invoke(loadedApk, true, null) as? Application
            val initial = activityThread.javaClass.getDeclaredField("mInitialApplication")
            initial.isAccessible = true
            initial.set(activityThread, app)
        }.onFailure { Ln.w("$TAG: makeApplication failed: ${it.message}") }
        return pkgContext
    }

    private fun createActivityThread(): Any {
        val cls = Class.forName("android.app.ActivityThread")
        val method = cls.getDeclaredMethod("systemMain")
        method.isAccessible = true
        return method.invoke(null)
    }

    private fun getSystemContext(activityThread: Any): Context {
        val method = activityThread.javaClass.getDeclaredMethod("getSystemContext")
        method.isAccessible = true
        return method.invoke(activityThread) as Context
    }

    private fun createPackageContextAsUser(context: Context, packageName: String, userId: Int): Context {
        val flags = Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
        return runCatching {
            val userHandleClass = Class.forName("android.os.UserHandle")
            val ofMethod = userHandleClass.getDeclaredMethod("of", Int::class.javaPrimitiveType)
            val userHandle = ofMethod.invoke(null, userId)
            val createMethod = Context::class.java.getMethod(
                "createPackageContextAsUser", String::class.java, Int::class.javaPrimitiveType, userHandleClass
            )
            createMethod.invoke(context, packageName, flags, userHandle) as Context
        }.getOrElse {
            context.createPackageContext(packageName, flags)
        }
    }

    private fun setAppName(name: String, userId: Int) {
        runCatching {
            val cls = Class.forName("android.ddm.DdmHandleAppName")
            val method = cls.getDeclaredMethod("setAppName", String::class.java, Int::class.javaPrimitiveType)
            method.invoke(null, name, userId)
        }.onFailure { Ln.w("$TAG: setAppName failed: ${it.message}") }
    }

    /** 供 RootDaemonController 判断进程是否存活（/proc/<pid> 检查由 controller 完成） */
    @JvmStatic
    fun engineBinder(): IBinder? = engine?.asBinder()
}