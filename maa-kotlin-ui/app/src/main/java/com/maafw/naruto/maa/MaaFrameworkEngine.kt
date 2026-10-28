package com.maafw.naruto.maa

import android.content.Context
import android.util.Log
import com.sun.jna.Pointer
import java.io.File

/**
 * MaaFramework 原生引擎包装
 * 用 JNA 调用 libMaaFramework.so，驱动 MaaAndroidNativeController + bridge.so
 */
class MaaFrameworkEngine(context: Context) {

    companion object {
        private const val TAG = "MaaFrameworkEngine"
        private const val STATUS_SUCCEEDED = 3000
        /** O-2：控制器连接超时（同步 MaaControllerWait 可能永久挂起 -> 引擎卡死，必须限时） */
        private const val CONTROLLER_CONNECT_TIMEOUT_MS = 15_000L
    }

    private val lib = MaaFrameworkLib.INSTANCE
    // UserService context.applicationContext 可能为 null（Shizuku shell 进程），兜底用原 context
    private val appContext = context.applicationContext ?: context

    private var resource: Pointer? = null
    private var controller: Pointer? = null
    private var tasker: Pointer? = null
    private var currentTaskId: Long = -1

    /** resource 句柄（Agent 绑定用） */
    val resourceHandle: Pointer? get() = resource
    /** 上次任务结束状态（3000=成功；非 3000 时下次任务前需重建引擎，保证稳定性） */
    private var lastTaskStatus: Int = STATUS_SUCCEEDED

    val version: String get() = lib.MaaVersion() ?: "unknown"

    /** 任务是否可安全复用（上次任务正常结束 -> 跳过资源/模型重载） */
    fun needRebuild(): Boolean = lastTaskStatus != STATUS_SUCCEEDED

    /** tasker 是否已存在（引擎复用时跳过重建，避免旧 tasker 泄漏且与 controller 竞争） */
    fun hasTasker(): Boolean = tasker != null && Pointer.nativeValue(tasker) != 0L

    /** 记录任务结束状态（供复用判定） */
    fun markTaskStatus(status: Int) {
        lastTaskStatus = status
    }

    /** 清空识别缓存（任务间复用 tasker 时调用，避免旧识别结果影响下次任务） */
    fun clearCache() {
        val t = tasker ?: return
        runCatching { lib.MaaTaskerClearCache(t) }
    }

    /**
     * 强制控制器重连（复用引擎时调用，毫秒级）。
     * 重置 controller 内部截屏状态，确保新任务每一轮识别拿到的都是最新帧，
     * 杜绝任何潜在旧帧/旧截屏缓存导致的识别滞后。
     */
    fun reConnectController(): Boolean {
        val c = controller ?: return false
        return runCatching {
            val connId = lib.MaaControllerPostConnection(c)
            val status = waitController(c, connId)
            Log.i(TAG, "控制器重连 status=$status（3000=成功）")
            status == STATUS_SUCCEEDED
        }.getOrDefault(false)
    }

    /**
     * O-2：等待控制器连接结果，带超时（同步 MaaControllerWait 可能永久挂起 -> 引擎卡死）。
     * 用独立线程 + Future.get(timeout)，超时返回 -1（失败），主流程继续，不会卡死引擎。
     */
    private fun waitController(ctrl: Pointer, connId: Long): Int {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit(java.util.concurrent.Callable<Int> { lib.MaaControllerWait(ctrl, connId) })
            future.get(CONTROLLER_CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.e(TAG, "控制器连接超时（${CONTROLLER_CONNECT_TIMEOUT_MS}ms），返回失败（避免引擎卡死）")
            -1
        } catch (e: Exception) {
            Log.e(TAG, "控制器等待异常: ${e.message}")
            -1
        } finally {
            executor.shutdownNow()
        }
    }

    /** 当前控制器截图帧号（验证识别是否使用新帧） */
    fun currentFrameCount(): Long {
        return runCatching { com.maafw.naruto.bridge.BridgeNativeLib.getFrameCount() }.getOrDefault(-1L)
    }

    /**
     * 初始化 MaaFramework 全局选项
     */
    fun init(logDir: File? = null) {
        // 日志目录
        val dir = (logDir ?: File(appContext.getExternalFilesDir(null), "maa_logs")).apply {
            mkdirs()
        }.absolutePath
        val dirBytes = dir.toByteArray(Charsets.UTF_8)
        val dirPtr = MemoryUtil.bytesToPointer(dirBytes)
        lib.MaaGlobalSetOption(1, dirPtr, dirBytes.size.toLong())

        // 日志等级 Info
        val level = intArrayOf(4)
        val levelPtr = MemoryUtil.intArrayToPointer(level)
        lib.MaaGlobalSetOption(4, levelPtr, 4)

        Log.i(TAG, "MaaFramework 全局初始化完成，版本：$version")
    }

    /**
     * 加载资源
     * @param resourceDir pipeline/image/template 根目录
     */
    fun loadResource(resourceDir: String): Boolean {
        val res = lib.MaaResourceCreate()
        resource = res
        var loaded = true

        // 注册 custom actions（pipeline 里 action: "Custom" 的节点回调到 CustomActions ）
        CustomActions.register(res)

        // 注册 custom recognitions（pipeline 里 recognition: "Custom" 的节点回调到 CustomRecognitions ）
        // 决斗场连点器（IsCounterOverflow）等依赖它，缺失会导致任务链提前结束
        CustomRecognitions.register(res)
        CustomRecognitions.resetState()

        Log.i(TAG, "loadResource: resourceDir=$resourceDir")
        val dir = File(resourceDir)
        val pipelineCount = File(dir, "pipeline").listFiles()?.size ?: 0
        val imageCount = File(dir, "image").listFiles()?.size ?: 0
        val modelDir = File(dir, "model/ocr")
        val modelCount = modelDir.listFiles()?.size ?: 0
        Log.i(TAG, "资源目录统计：pipeline=$pipelineCount image=$imageCount ocrModel=$modelCount")

        // pipeline（MaaResourcePostPipeline 只加载 pipeline JSON，不扫描 model/ 和 image/）
        val id = lib.MaaResourcePostPipeline(res, resourceDir)
        lib.MaaResourceWait(res, id)
        loaded = loaded && lib.MaaResourceLoaded(res).toBool()
        Log.i(TAG, "加载 pipeline：id=$id loaded=$loaded")

        // 图片模板：MaaFramework 只在 PostBundle / PostImage 时才会注册 image/ 目录
        val imageDir = File(resourceDir, "image")
        if (imageDir.exists()) {
            Log.i(TAG, "加载图片模板：path=${imageDir.absolutePath} 子目录=${imageDir.listFiles()?.size ?: 0}")
            val imgId = lib.MaaResourcePostImage(res, imageDir.absolutePath)
            lib.MaaResourceWait(res, imgId)
            loaded = loaded && lib.MaaResourceLoaded(res).toBool()
            Log.i(TAG, "加载图片模板：id=$imgId loaded=$loaded")
        } else {
            Log.w(TAG, "图片目录不存在：${imageDir.absolutePath}")
        }

        // OCR 模型：只在 PostBundle / PostOcrModel 时才会扫描 model/ocr 目录
        val ocrDir = File(resourceDir, "model/ocr")
        if (ocrDir.exists()) {
            Log.i(TAG, "加载 OCR 模型：path=${ocrDir.absolutePath} 文件数=${ocrDir.listFiles()?.size ?: 0}")
            val ocrId = lib.MaaResourcePostOcrModel(res, ocrDir.absolutePath)
            lib.MaaResourceWait(res, ocrId)
            loaded = loaded && lib.MaaResourceLoaded(res).toBool()
            Log.i(TAG, "加载 OCR 模型：id=$ocrId loaded=$loaded")
        } else {
            Log.w(TAG, "OCR 模型目录不存在：${ocrDir.absolutePath}（OCR 识别将不可用）")
        }

        Log.i(TAG, "加载资源：path=$resourceDir, loaded=$loaded")
        return loaded
    }

    /**
     * 创建 AndroidNative 控制器
     */
    fun createController(libraryPath: String, width: Int, height: Int, displayId: Int = 0, forceStop: Boolean = false): Boolean {
        val config = """
            {
                "library_path": "$libraryPath",
                "screen_resolution": {
                    "width": $width,
                    "height": $height
                },
                "display_id": $displayId,
                "force_stop": $forceStop
            }
        """.trimIndent()
        val ctrl = lib.MaaAndroidNativeControllerCreate(config)
        controller = ctrl
        Log.i(TAG, "创建控制器：config=$config, handle=$ctrl")
        if (ctrl == null || Pointer.nativeValue(ctrl) == 0L) {
            Log.e(TAG, "控制器创建失败（返回空句柄）")
            return false
        }
        val connId = lib.MaaControllerPostConnection(ctrl)
        val status = waitController(ctrl, connId)
        Log.i(TAG, "控制器连接 status=$status（3000=成功，其余失败）")
        if (status != STATUS_SUCCEEDED) {
            Log.e(TAG, "控制器连接失败 status=$status，请确认 Shizuku/Root 权限与 libbridge.so 可用")
        }
        return status == STATUS_SUCCEEDED
    }

    /**
     * 绑定并初始化 Tasker 
     */
    fun createTasker(): Boolean {
        val t = lib.MaaTaskerCreate()
        tasker = t
        // 新 tasker 就绪：清掉旧 tasker 上注册的 sink 引用（引擎复用后每次任务会重建 tasker，
        // 不清会导致 registeredSinks 无限增长——内存泄漏）
        registeredSinks.clear()
        val res = resource
        val ctrl = controller
        if (res == null || ctrl == null) {
            Log.e(TAG, "Tasker 创建失败：resource 或 controller 为空")
            return false
        }
        val bindRes = lib.MaaTaskerBindResource(t, res).toBool()
        val bindCtrl = lib.MaaTaskerBindController(t, ctrl).toBool()
        val inited = lib.MaaTaskerInited(t).toBool()
        Log.i(TAG, "Tasker 绑定结果：bindRes=$bindRes, bindCtrl=$bindCtrl, inited=$inited")
        return inited
    }

    /**
     * 注册任务事件回调（基于事件回调，无需轮询）。
     * 回调收到 Tasker.Task.Starting / Succeeded / Failed 等事件。
     */
    fun addSink(sink: MaaEventCallback) {
        registeredSinks.add(sink) // 强引用防止 JNA 回调被 GC
        val t = tasker ?: return
        lib.MaaTaskerAddSink(t, sink, null)
    }

    /**
     * 注册任务级上下文事件回调（Node.* 事件：Node.Action.Starting 等）。
     * MaaTaskerAddSink 只收 Tasker 层事件（Tasker.Task.*），
     * Node.* 事件需要 MaaTaskerAddContextSink（见 MaaMsg.h）。
     */
    fun addContextSink(sink: MaaEventCallback) {
        registeredSinks.add(sink) // 强引用防止 JNA 回调被 GC
        val t = tasker ?: return
        lib.MaaTaskerAddContextSink(t, sink, null)
    }

    private val registeredSinks = mutableListOf<MaaEventCallback>()

    /**
     * 启动任务
     */
    fun startTask(entry: String, pipelineOverride: String? = null): Boolean {
        if (entry.isBlank()) {
            Log.e(TAG, "startTask: entry is blank")
            return false
        }
        val t = tasker ?: run {
            Log.e(TAG, "Tasker 未初始化")
            return false
        }
        // MaaFramework 的 MaaTaskerPostTask：
        // - null char* 会在日志里 strlen(NULL) 崩溃（SIGSEGV）；
        // - 空串 "" 会 json::parse 失败返回无效 taskId；
        // 所以无覆盖时传 "{}"（合法空 JSON 对象）最安全。
        val override = pipelineOverride ?: "{}"
        currentTaskId = lib.MaaTaskerPostTask(t, entry, override)
        Log.i(TAG, "启动任务 entry=$entry, taskId=$currentTaskId")
        return currentTaskId != 0L
    }

    /**
     * 等待当前任务结束
     */
    fun waitTask(): Int {
        val t = tasker ?: return -1
        if (currentTaskId == -1L) return -1
        val status = lib.MaaTaskerWait(t, currentTaskId)
        lastTaskStatus = status
        Log.i(TAG, "任务结束 status=$status")
        return status
    }

    /**
     * 停止任务
     */
    fun stopTask(): Boolean {
        val t = tasker ?: return false
        val id = lib.MaaTaskerPostStop(t)
        lib.MaaTaskerWait(t, id)
        currentTaskId = -1
        return true
    }

    /**
     * 销毁所有对象
     */
    fun destroy() {
        stopTask()
        tasker?.let { lib.MaaTaskerDestroy(it) }
        controller?.let { lib.MaaControllerDestroy(it) }
        resource?.let { lib.MaaResourceDestroy(it) }
        tasker = null
        controller = null
        resource = null
    }

    fun isRunning(): Boolean {
        val t = tasker ?: return false
        return lib.MaaTaskerRunning(t).toBool()
    }
}

private fun Byte.toBool(): Boolean = this != 0.toByte()