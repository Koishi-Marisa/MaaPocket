package com.maafw.naruto.maa

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.maafw.naruto.bridge.NativeBridge
import com.maafw.naruto.bridge.BridgeNativeLib
import com.maafw.naruto.third.Ln
import com.maafw.naruto.third.wrappers.InputManager
import com.maafw.naruto.third.wrappers.ServiceManager
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * MaaFramework 自定义动作实现
 *
 * 通过 MaaResourceRegisterCustomAction 注册，pipeline 里 action: "Custom" 的节点
 * 会回调到这里执行。
 */
object CustomActions {

    private const val TAG = "CustomActions"
    private val lib = MaaFrameworkLib.INSTANCE

    // 防止 JNA 回调对象被 GC 回收，必须强引用持有
    private val registeredCallbacks = mutableListOf<MaaCustomActionCallback>()

    // CounterIncrement 用的计数器（按 task_id 区分）
    private val counters = ConcurrentHashMap<Long, Int>()

    // 刷胜率（LoseCounterIncrement/Reset）用的失败计数器（按 task_id 区分）
    private val loseCounters = ConcurrentHashMap<Long, Int>()

    // 刷熟练度预设轮循计数器（按 task_id 区分）：每局结束 +1，预设编号 = (count % 4) + 1
    private val presetRotation = ConcurrentHashMap<Long, Int>()

    /**
     * 注册全部 custom action 到 resource 上。
     * 必须在 MaaResourceCreate 之后、任务运行之前调用。
     */
    fun register(res: Pointer): Boolean {
        var ok = true
        val actions = mapOf(
            "StopTaskList" to ::actionStopTaskList,
            "RetryFailed" to ::actionRetryFailed,
            "NonlinearSwipe" to ::actionNonlinearSwipe,
            "MultiSwipeCustom" to ::actionMultiSwipeCustom,
            "GoIntoEntryByGuide" to ::actionGoIntoEntryByGuide,
            "ShopSwipeBack" to ::actionShopSwipeBack,
            "CounterIncrement" to ::actionCounterIncrement,
            "LoseCounterIncrement" to ::actionLoseCounterIncrement,
            "LoseCounterReset" to ::actionLoseCounterReset,
            "PresetRotateIncrement" to ::actionPresetRotateIncrement,
            "PresetTargetWriter" to ::actionPresetTargetWriter,
            "SwitchPreset" to ::actionSwitchPreset,
            "CleanupVisionImg" to ::actionCleanupVisionImg,
            "CleanupOnErrorImg" to ::actionCleanupOnErrorImg,
            "CleanupCustomImg" to ::actionCleanupCustomImg,
            "CleanupCustomLog" to ::actionCleanupCustomLog,
            "CleanupMaafwBakLogs" to ::actionCleanupMaafwBakLogs,
            "CleanupAgentDebug" to ::actionCleanupAgentDebug,
            "ChallengeSelectedOpponent" to ::actionChallengeSelectedOpponent,
            "ClickStoredPoint" to ::actionClickStoredPoint,
            "StartGameToVirtualDisplay" to ::actionStartGameToVirtualDisplay,
        )
        for ((name, fn) in actions) {
            // JNA Callback 接口不能用 Kotlin SAM lambda，必须匿名对象实现
            val cb = object : MaaCustomActionCallback {
                override fun invoke(
                    context: Pointer?, taskId: Long, nodeName: String?, actionName: String?,
                    actionParam: String?, recoId: Long, box: Pointer?, transArg: Pointer?
                ): Byte {
                    return try {
                        fn(context, taskId, nodeName, actionName, actionParam, recoId, box)
                    } catch (e: Throwable) {
                        Ln.e("$TAG.$name 执行异常: ${e.message}")
                        e.printStackTrace()
                        0
                    }
                }
            }
            registeredCallbacks.add(cb)
            val r = lib.MaaResourceRegisterCustomAction(res, name, cb, null)
            if (r != 1.toByte()) {
                Ln.e("$TAG 注册 $name 失败")
                ok = false
            } else {
                Ln.i("$TAG 已注册 custom action: $name")
            }
        }
        return ok
    }

    fun clearCounters() {
        counters.clear()
        loseCounters.clear()
        presetRotation.clear()
    }

    /** 读取指定 task 的计数器值（供 CustomRecognition 使用） */
    fun getCounter(taskId: Long): Int = counters[taskId] ?: 0

    /** 读取指定 task 的失败计数器值（刷胜率用，供 CustomRecognition 使用） */
    fun getLoseCounter(taskId: Long): Int = loseCounters[taskId] ?: 0

    /** 读取指定 task 的预设轮循计数器值（刷熟练度用，供 CustomRecognition 使用） */
    fun getPresetRotation(taskId: Long): Int = presetRotation[taskId] ?: 0

    // ==================== 工具函数 ====================

    /** 判断任务是否正在停止 */
    private fun isStopping(context: Pointer?): Boolean {
        if (context == null || Pointer.nativeValue(context) == 0L) return false
        val tasker = lib.MaaContextGetTasker(context)
        if (tasker == null || Pointer.nativeValue(tasker) == 0L) return false
        return lib.MaaTaskerStopping(tasker) == 1.toByte()
    }

    /** 停止整个任务链 */
    private fun stopTask(context: Pointer?) {
        runCatching {
            val ctx = context ?: return@runCatching
            val tasker = lib.MaaContextGetTasker(ctx)
            if (tasker != null && Pointer.nativeValue(tasker) != 0L) {
                lib.MaaTaskerPostStop(tasker)
            }
        }
    }

    /** 把当前帧缓冲截图转成 MaaImageBuffer（PNG编码） */
    private fun captureToImageBuffer(): Pointer? {
        val bitmap = BridgeNativeLib.getFrameBufferBitmap() ?: return null
        try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val bytes = baos.toByteArray()
            if (bytes.isEmpty()) return null
            val mem = Memory(bytes.size.toLong())
            mem.write(0, bytes, 0, bytes.size)
            val img = lib.MaaImageBufferCreate()
            if (img == null || Pointer.nativeValue(img) == 0L) return null
            val set = lib.MaaImageBufferSetEncoded(img, mem, bytes.size.toLong())
            if (set != 1.toByte()) {
                lib.MaaImageBufferDestroy(img)
                return null
            }
            return img
        } catch (e: Exception) {
            Ln.e("$TAG captureToImageBuffer: ${e.message}")
            return null
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    /**
     * 用 OCR 在截图中查找文本，返回 [x, y, w, h] 或 null 。
     * MaaFramework 的 recognition roi 默认就是绝对坐标（无相对模式），
     * 因此直接传绝对 roi 即可，不需要 PC 版的 absolutely 字段。
     */
    private fun ocrFind(context: Pointer?, image: Pointer?, expected: List<String>, roi: IntArray?): IntArray? {
        if (context == null || image == null) return null
        return try {
            val param = JSONObject()
            param.put("expected", JSONArray(expected))
            roi?.let { param.put("roi", JSONArray().apply { it.forEach { v -> put(v) } }) }
            val recoId = lib.MaaContextRunRecognitionDirect(context, "OCR", param.toString(), image)
            if (recoId == 0L) return null
            val tasker = lib.MaaContextGetTasker(context)
            if (tasker == null || Pointer.nativeValue(tasker) == 0L) return null
            extractRecoBox(tasker, recoId)
        } catch (e: Exception) {
            Ln.e("$TAG ocrFind: ${e.message}")
            null
        }
    }

    /** 从 reco_id 提取命中框 [x,y,w,h]，未命中返回 null  */
    private fun extractRecoBox(tasker: Pointer, recoId: Long): IntArray? {
        return try {
            val hit = IntByReference(0)
            val nodeName = lib.MaaStringBufferCreate()
            val algorithm = lib.MaaStringBufferCreate()
            val detailJson = lib.MaaStringBufferCreate()
            val box = lib.MaaRectCreate()
            val ok = lib.MaaTaskerGetRecognitionDetail(tasker, recoId, nodeName, algorithm, hit, box, detailJson, null, null)
            if (ok != 1.toByte() || hit.value == 0) {
                lib.MaaStringBufferDestroy(nodeName)
                lib.MaaStringBufferDestroy(algorithm)
                lib.MaaStringBufferDestroy(detailJson)
                lib.MaaRectDestroy(box)
                return null
            }
            val r = intArrayOf(
                lib.MaaRectGetX(box), lib.MaaRectGetY(box),
                lib.MaaRectGetW(box), lib.MaaRectGetH(box)
            )
            lib.MaaStringBufferDestroy(nodeName)
            lib.MaaStringBufferDestroy(algorithm)
            lib.MaaStringBufferDestroy(detailJson)
            lib.MaaRectDestroy(box)
            r
        } catch (e: Exception) {
            Ln.e("$TAG extractRecoBox: ${e.message}")
            null
        }
    }

    /** 点击（带随机偏移，） */
    private fun click(context: Pointer?, x: Int, y: Int, w: Int = 1, h: Int = 1) {
        if (context == null) return
        val rx = if (w > 1) x + Random.nextInt(w) else x
        val ry = if (h > 1) y + Random.nextInt(h) else y
        val rect = lib.MaaRectCreate()
        lib.MaaRectSet(rect, 0, 0, 0, 0)
        lib.MaaContextRunActionDirect(context, "Click", "{\"x\":$rx,\"y\":$ry}", rect, "")
        lib.MaaRectDestroy(rect)
    }

    /** 线性滑动（fast_swipe） */
    private fun fastSwipe(context: Pointer?, sx: Int, sy: Int, ex: Int, ey: Int, duration: Int = 150) {
        if (context == null) return
        val rect = lib.MaaRectCreate()
        lib.MaaRectSet(rect, 0, 0, 0, 0)
        lib.MaaContextRunActionDirect(
            context, "Swipe",
            "{\"begin\":[$sx,$sy],\"end\":[$ex,$ey],\"duration\":$duration}",
            rect, ""
        )
        lib.MaaRectDestroy(rect)
    }

    /**
     * 非线性滑动（方案B：引擎层直接注入，绕开 MaaFramework Swipe action）。
     * MaaFramework v5.13.0-beta.2 执行 Swipe 的「多途径点」（end 数组 + duration 数组）会卡死引擎；
     * 本实现改为在引擎 Kotlin 层直接注入单指触摸序列：DOWN -> 沿非线性路径逐点 MOVE -> UP，
     * 保留非线性轨迹与总时长，不调用 MaaFramework Swipe action，不改 libbridge/MaaFramework。
     */
    private fun nonlinearSwipe(
        context: Pointer?,
        startX: Int, startY: Int, endX: Int, endY: Int,
        duration: Int = 150, endHold: Boolean = false,
        afterSwipeDelay: Int = 300, steps: Int = 7
    ) {
        if (context == null) return
        val displayId = com.maafw.naruto.remote.internal.MaaFwVirtualDisplay.getDisplayId()
        if (displayId < 0) return
        val sX = startX + Random.nextInt(-50, 51)
        val sY = startY + Random.nextInt(-50, 51)
        val eX = endX + Random.nextInt(-50, 51)
        val eY = endY + Random.nextInt(-50, 51)
        val totalDur = (duration + Random.nextInt(-100, 101)).coerceAtLeast(30)
        val hold = if (endHold) Random.nextInt(100, 201) else 0

        // 非线性路径点（缓出轨迹：起始慢、末端快）
        val path = mutableListOf<Pair<Int, Int>>()
        var totalProg = 0.0
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val prog = 1 - (1 - t) * (1 - t)
            totalProg = prog
            path.add((sX + (eX - sX) * prog).toInt() to (sY + (eY - sY) * prog).toInt())
        }

        // 引擎层直接注入（方案B：不经过 MaaFramework Swipe action，避免多途径点卡死）
        com.maafw.naruto.shizuku.InputInjector.touchDown(sX, sY, displayId)
        val perMs = (totalDur / steps).coerceAtLeast(1)
        path.forEach { (px, py) ->
            SystemClock.sleep(perMs.toLong())
            com.maafw.naruto.shizuku.InputInjector.touchMove(px, py, displayId)
        }
        if (hold > 0) SystemClock.sleep(hold.toLong())
        com.maafw.naruto.shizuku.InputInjector.touchUp(eX, eY, displayId)
        SystemClock.sleep(afterSwipeDelay.toLong())
    }

    /** 点击配置坐标（坐标采集面板复制粘贴，经 interface option 注入 custom_action_param.point；用于 OCR 识别不到文字的 webview 界面） */
    private fun actionClickStoredPoint(
        context: Pointer?, taskId: Long, nodeName: String?, actionName: String?,
        actionParam: String?, recoId: Long, box: Pointer?
    ): Byte {
        val p = runCatching { JSONObject(actionParam ?: "{}") }.getOrDefault(JSONObject())
        val rawPoint = p.optString("point", "")
        val pt = com.maafw.naruto.data.settings.SettingsRepository.parseClickPoint(rawPoint)
        Ln.i("$TAG ClickStoredPoint node=$nodeName rawPoint=$rawPoint parsed=${pt?.contentToString()}")
        if (pt == null) return 0
        val displayId = com.maafw.naruto.remote.internal.MaaFwVirtualDisplay.getDisplayId()
        if (displayId >= 0) {
            com.maafw.naruto.shizuku.InputInjector.touchDown(pt[0], pt[1], displayId)
            SystemClock.sleep(40)
            com.maafw.naruto.shizuku.InputInjector.touchUp(pt[0], pt[1], displayId)
        }
        return 1
    }

    /**
     * start_up：把游戏启动到虚拟屏（替代原版 StartApp）。
     * 原版 StartApp 不带 displayId，会把已在虚拟屏上的游戏 task 挪回主屏（部分 ROM 行为，
     * 见 MaaFwActivityHelper.ensureAppOnDisplay 注释）-> 虚拟屏黑屏 -> 截图识别全失败。
     * 本 action：带 displayId 启动 + 启动后校验/拉回虚拟屏，与 prelaunchGame 同一套防漂移逻辑。
     * param: {"package": "com.tencent.KiHan"}
     */
    private fun actionStartGameToVirtualDisplay(
        context: Pointer?, taskId: Long, nodeName: String?, actionName: String?,
        actionParam: String?, recoId: Long, box: Pointer?
    ): Byte {
        return try {
            val p = runCatching { JSONObject(actionParam ?: "{}") }.getOrDefault(JSONObject())
            val pkg = p.optString("package", "").ifBlank { "com.tencent.KiHan" }
            val displayId = com.maafw.naruto.remote.internal.MaaFwVirtualDisplay.getDisplayId()
            if (displayId < 0) {
                Ln.w("$TAG StartGameToVirtualDisplay: 虚拟屏未就绪，跳过启动")
                return 0
            }
            val ok = com.maafw.naruto.remote.internal.MaaFwActivityHelper.startApp(pkg, displayId, forceStop = false)
            if (!ok) {
                Ln.w("$TAG StartGameToVirtualDisplay: startApp 失败，回退 am start --display")
                val ok2 = com.maafw.naruto.remote.internal.MaaFwActivityHelper.startAppViaAmCommand(pkg, displayId)
                if (!ok2) return 0
            }
            // 启动后校验/拉回虚拟屏（防止游戏 task 漂移到主屏导致截图黑屏）
            com.maafw.naruto.remote.internal.MaaFwActivityHelper.ensureAppOnDisplay(pkg, displayId)
            Ln.i("$TAG StartGameToVirtualDisplay: $pkg -> display $displayId")
            1
        } catch (e: Throwable) {
            Ln.e("$TAG StartGameToVirtualDisplay 执行异常: ${e.message}")
            0
        }
    }

    /** 挑战指定对手（不对比战力，固定点击第 N 个挑战按钮；默认第 4 个=列表最下方） */
    private fun actionChallengeSelectedOpponent(
        context: Pointer?, taskId: Long, nodeName: String?, actionName: String?,
        actionParam: String?, recoId: Long, box: Pointer?
    ): Byte {
        val param = runCatching { JSONObject(actionParam ?: "{}") }.getOrDefault(JSONObject())
        val index = param.optInt("opponent_index", 3).coerceIn(0, 3)
        // 4 个对手的挑战按钮中心点（1280x720，原版 ROI 中心）
        val targets = listOf(
            1032 to 214, // 第 1 个
            1033 to 331, // 第 2 个
            1034 to 449, // 第 3 个
            1033 to 567, // 第 4 个
        )
        val (x, y) = targets[index]
        val displayId = com.maafw.naruto.remote.internal.MaaFwVirtualDisplay.getDisplayId()
        if (displayId >= 0) {
            com.maafw.naruto.shizuku.InputInjector.touchDown(x, y, displayId)
            SystemClock.sleep(40)
            com.maafw.naruto.shizuku.InputInjector.touchUp(x, y, displayId)
        }
        return 1
    }

    /** 等待画面稳定 */
    private fun waitFreezes(context: Pointer?, time: Long = 200) {
        if (context == null) return
        val rect = lib.MaaRectCreate()
        lib.MaaRectSet(rect, 0, 0, 0, 0)
        lib.MaaContextWaitFreezes(context, time, rect, "{}")
        lib.MaaRectDestroy(rect)
    }

    // ==================== 多指滑动（MultiSwipe 替代实现） ====================
    // MaaFramework 的 AndroidNative controller 官方只支持单点触摸（"native android controller
    // only supports single touch"），MultiSwipe 多指会被拒绝；这里在引擎进程直接用
    // InputManager.injectInputEvent 注入多指针 MotionEvent，绕过该限制。

    private data class FingerSwipe(
        val bx: Float, val by: Float, val ex: Float, val ey: Float, val dur: Long
    )

    /** 解析 swipes 数组为每根手指的滑动规格 */
    private fun parseFingers(swipes: JSONArray): List<FingerSwipe> {
        val fingers = mutableListOf<FingerSwipe>()
        for (i in 0 until swipes.length()) {
            val s = swipes.optJSONObject(i) ?: continue
            val begin = s.optJSONArray("begin") ?: continue
            val end = s.optJSONArray("end") ?: continue
            if (begin.length() < 2 || end.length() < 2) continue
            // [x, y, w, h] 取中心点
            val bx = begin.optInt(0) + begin.optInt(2) / 2f
            val by = begin.optInt(1) + begin.optInt(3) / 2f
            val ex = end.optInt(0) + end.optInt(2) / 2f
            val ey = end.optInt(1) + end.optInt(3) / 2f
            val dur = s.optLong("duration", 150L)
            fingers.add(FingerSwipe(bx, by, ex, ey, dur))
        }
        return fingers
    }

    /** 注入一个多指针 MotionEvent  */
    private fun injectPointers(
        displayId: Int, downTime: Long, eventTime: Long,
        action: Int, coords: List<Pair<Float, Float>>, actionIndex: Int = -1
    ): Boolean {
        val n = coords.size
        if (n == 0) return false
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val cs = Array(n) { i ->
            MotionEvent.PointerCoords().apply {
                x = coords[i].first
                y = coords[i].second
                pressure = 1f
                size = 1f
            }
        }
        var act = action
        if (actionIndex >= 0 && (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)) {
            act = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        val ev = MotionEvent.obtain(
            downTime, eventTime, act, n, props, cs,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        return runCatching {
            InputManager.setDisplayId(ev, displayId)
            ServiceManager.getInputManager().injectInputEvent(
                ev, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
            )
        }.getOrDefault(false)
    }

    /** 执行多指滑动：所有手指同时按下 -> 逐步移动 -> 依次抬起 */
    private fun multiSwipeInject(swipes: JSONArray) {
        val displayId = NativeBridge.getDisplayId()
        if (displayId < 0 || swipes.length() == 0) {
            Ln.w("$TAG multiSwipeInject: displayId=$displayId swipes=${swipes.length()}")
            return
        }
        val fingers = parseFingers(swipes)
        if (fingers.isEmpty()) return
        val maxDur = fingers.maxOf { it.dur }.coerceAtLeast(50L)

        val downTime = SystemClock.uptimeMillis()
        val startCoords = fingers.map { it.bx to it.by }

        // 1) 手指 0 DOWN
        injectPointers(displayId, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, listOf(startCoords[0]))
        // 2) 手指 1..n POINTER_DOWN（逐指加入）
        for (i in 1 until fingers.size) {
            injectPointers(
                displayId, downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_POINTER_DOWN, startCoords.subList(0, i + 1), actionIndex = i
            )
            SystemClock.sleep(10)
        }
        // 3) 分步 MOVE（所有手指按各自轨迹插值移动）
        val steps = (maxDur / 50).toInt().coerceIn(4, 20)
        for (s in 1..steps) {
            val t = s.toFloat() / steps
            val coords = fingers.map { (it.bx + (it.ex - it.bx) * t) to (it.by + (it.ey - it.by) * t) }
            injectPointers(displayId, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, coords)
            SystemClock.sleep((maxDur / steps).coerceAtLeast(10))
        }
        // 4) 手指 n..1 POINTER_UP（逆序抬起）
        for (i in fingers.size - 1 downTo 1) {
            val coords = fingers.subList(0, i + 1).map { it.ex to it.ey }
            injectPointers(
                displayId, downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_POINTER_UP, coords, actionIndex = i
            )
            SystemClock.sleep(10)
        }
        // 5) 手指 0 UP
        injectPointers(displayId, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, listOf(fingers[0].ex to fingers[0].ey))
        Ln.i("$TAG multiSwipeInject: ${fingers.size}指 时长=${maxDur}ms displayId=$displayId")
    }

    // ==================== Custom Actions ====================

    /** StopTaskList：停止当前任务以及后续任务列表 */
    private fun actionStopTaskList(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        stopTask(context)
        return 0 // 与 PC 版一致：post_stop + success=False
    }

    /** RetryFailed：重试失败，PC 版做校验/截图；这里返回成功即可 */
    private fun actionRetryFailed(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        return 1
    }

    /** NonlinearSwipe：非线性滑动 */
    private fun actionNonlinearSwipe(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        return try {
            val p = param?.let { JSONObject(it) } ?: JSONObject()
            nonlinearSwipe(
                context,
                p.optInt("start_x", 0), p.optInt("start_y", 0),
                p.optInt("end_x", 0), p.optInt("end_y", 0),
                duration = p.optInt("duration", 150),
                endHold = p.optBoolean("end_hold", false),
                afterSwipeDelay = p.optInt("after_swipe_delay", 300),
                steps = p.optInt("steps", 7),
            )
            1
        } catch (e: Exception) {
            Ln.e("$TAG NonlinearSwipe 失败: ${e.message}")
            0
        }
    }

    /**
     * MultiSwipeCustom：多指同时滑动（组合技）。
     * 替代 MaaFramework 的 MultiSwipe（AndroidNative controller 不支持多指），
     * 在引擎进程直接注入多指针 MotionEvent 到虚拟屏。
     * param: {"swipes": [{"begin":[x,y,w,h],"end":[x,y,w,h],"duration":ms}, ...]}
     */
    private fun actionMultiSwipeCustom(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        return try {
            val p = param?.let { JSONObject(it) } ?: JSONObject()
            val swipes = p.optJSONArray("swipes")
            if (swipes == null || swipes.length() == 0) {
                Ln.w("$TAG MultiSwipeCustom: swipes 为空（node=$nodeName）")
                return 1
            }
            multiSwipeInject(swipes)
            1
        } catch (e: Exception) {
            Ln.e("$TAG MultiSwipeCustom 失败: ${e.message}")
            0
        }
    }

    /** GoIntoEntryByGuide：从忍界指引进入特定功能 */
    private fun actionGoIntoEntryByGuide(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        if (context == null) return 0
        return try {
            val p = param?.let { JSONObject(it) } ?: JSONObject()
            var enterName = p.opt("entry_name")
            if (enterName == null || enterName.toString().isBlank()) {
                stopTask(context)
                return 0
            }
            val names = if (enterName is JSONArray) {
                (0 until enterName.length()).map { enterName.optString(it) }
            } else listOf(enterName.toString())

            var start = intArrayOf(0, 0)
            var end = intArrayOf(0, 0)
            var listRoi = intArrayOf(26, 60, 404, 616)

            val img = captureToImageBuffer() ?: return 0
            try {
                val box1 = ocrFind(context, img, listOf("回流"), intArrayOf(0, 0, 195, 285))
                if (box1 == null) {
                    // 非回归账号
                    start = intArrayOf(70, 600)
                    end = intArrayOf(70, 300)
                    listRoi = intArrayOf(0, 66, 219, 627)
                } else {
                    // 回归账号
                    start = intArrayOf(300, 600)
                    end = intArrayOf(300, 300)
                    listRoi = intArrayOf(209, 88, 200, 580)
                    val box2 = ocrFind(context, img, listOf("忍界指引"), intArrayOf(0, 600, 212, 120))
                    if (box2 == null) return 0
                    click(context, box2[0], box2[1], box2[2], box2[3])
                }
            } finally {
                lib.MaaImageBufferDestroy(img)
            }

            waitFreezes(context, 300)
            if (isStopping(context)) return 0

            // 滑到最顶端（识别"天赋"）
            var guard = 0
            while (guard++ < 30) {
                if (isStopping(context)) return 0
                val img2 = captureToImageBuffer() ?: break
                val hitTalent = try {
                    ocrFind(context, img2, listOf("天赋"), listRoi) != null
                } finally {
                    lib.MaaImageBufferDestroy(img2)
                }
                if (hitTalent) break
                nonlinearSwipe(context, end[0], end[1], start[0], start[1], endHold = false)
            }

            // 查找功能入口
            var foundBox: IntArray? = null
            for (i in 0 until 20) {
                if (isStopping(context)) return 0
                val img3 = captureToImageBuffer() ?: break
                foundBox = try {
                    ocrFind(context, img3, names, listRoi)
                } finally {
                    lib.MaaImageBufferDestroy(img3)
                }
                if (foundBox != null) break
                nonlinearSwipe(context, start[0], start[1], end[0], end[1])
            }
            if (foundBox == null) return 0
            if (isStopping(context)) return 0

            click(context, foundBox[0], foundBox[1], foundBox[2], foundBox[3])
            SystemClock.sleep(500)

            val img4 = captureToImageBuffer() ?: return 0
            val goBox = try {
                ocrFind(context, img4, listOf("前往"), intArrayOf(834, 539, 287, 149))
            } finally {
                lib.MaaImageBufferDestroy(img4)
            }
            if (goBox == null) return 0
            click(context, goBox[0], goBox[1], goBox[2], goBox[3])
            1
        } catch (e: Exception) {
            Ln.e("$TAG GoIntoEntryByGuide 失败: ${e.message}")
            0
        }
    }

    /** ShopSwipeBack：商店兑换滑动回商品头部 */
    private fun actionShopSwipeBack(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        return try {
            if (context != null) {
                runCatching { lib.MaaContextClearHitCount(context, "shop_swipe_for_goods") }
                fastSwipe(context, 280, 409, 1200, 404)
            }
            1
        } catch (e: Exception) {
            Ln.e("$TAG ShopSwipeBack 失败: ${e.message}")
            0
        }
    }

    /** CounterIncrement：计数器自增（按 task_id） */
    private fun actionCounterIncrement(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        counters.merge(taskId, 1) { a, b -> a + b }
        return 1
    }

    /** LoseCounterIncrement：刷胜率失败计数自增（按 task_id） */
    private fun actionLoseCounterIncrement(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        loseCounters.merge(taskId, 1) { a, b -> a + b }
        Ln.i("$TAG LoseCounterIncrement node=$nodeName now=${loseCounters[taskId]}")
        return 1
    }

    /** LoseCounterReset：刷胜率失败计数清零（按 task_id） */
    private fun actionLoseCounterReset(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        loseCounters[taskId] = 0
        Ln.i("$TAG LoseCounterReset node=$nodeName")
        return 1
    }

    /** PresetRotateIncrement：刷熟练度预设轮循计数自增（按 task_id），每局结束 +1 */
    private fun actionPresetRotateIncrement(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        presetRotation.merge(taskId, 1) { a, b -> a + b }
        Ln.i("$TAG PresetRotateIncrement node=$nodeName now=${presetRotation[taskId]}")
        return 1
    }

    /**
     * PresetTargetWriter：决策本局目标预设并写入 pipeline（供 agent/引擎的 PresetDecision 读取）。
     * 决策在引擎进程完成（能读 loseCounters/presetRotation 内存计数器），结果通过
     * MaaContextOverridePipeline 动态注入 pvp_weekly_win_preset_decision 的 custom_recognition_param.target_preset。
     * param（custom_action_param）:
     *   mode: "lose" | "rotate"
     *   max_hit / preset_win / preset_lose（lose 模式）
     */
    private fun actionPresetTargetWriter(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        if (context == null) return 0
        return try {
            val p = runCatching { JSONObject(param ?: "{}") }.getOrDefault(JSONObject())
            val mode = p.optString("mode", "lose")
            val target: Int = when (mode) {
                "rotate" -> (getPresetRotation(taskId) % 4) + 1
                else -> {
                    val maxHit = p.optInt("max_hit", 5)
                    val presetWin = Regex("(\\d+)").find(p.optString("preset_win", "预设1"))?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 4) ?: 1
                    val presetLose = Regex("(\\d+)").find(p.optString("preset_lose", "预设2"))?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 4) ?: 2
                    val lose = getLoseCounter(taskId)
                    if (lose >= maxHit) presetWin else presetLose
                }
            }
            // 动态覆盖：目标预设 + 自定义页签文字 注入 PresetDecision（agent 兜底用），
            // target_preset 注入 4 个「切换到预设N」节点（IsPresetTarget 分流判断用）
            val presetNames = JSONArray()
            for (i in 1..4) {
                val n = p.optString("preset_name_$i", i.toString()).trim()
                presetNames.put(n.ifBlank { i.toString() })
            }
            val override = JSONObject()
                .put("pvp_weekly_win_preset_decision", JSONObject()
                    .put("custom_recognition_param", JSONObject()
                        .put("target_preset", target)
                        .put("preset_names", presetNames)))
                .put("切换到预设1识图和点击位置", JSONObject()
                    .put("custom_recognition_param", JSONObject()
                        .put("target_preset", target).put("n", 1)))
                .put("切换到预设2识图和点击位置", JSONObject()
                    .put("custom_recognition_param", JSONObject()
                        .put("target_preset", target).put("n", 2)))
                .put("切换到预设3识图和点击位置", JSONObject()
                    .put("custom_recognition_param", JSONObject()
                        .put("target_preset", target).put("n", 3)))
                .put("切换到预设4识图和点击位置", JSONObject()
                    .put("custom_recognition_param", JSONObject()
                        .put("target_preset", target).put("n", 4)))
                .toString()
            lib.MaaContextOverridePipeline(context, override)
            Ln.i("$TAG PresetTargetWriter node=$nodeName mode=$mode 目标预设=$target 页签文字=$presetNames 已注入 pipeline")
            1
        } catch (e: Exception) {
            Ln.e("$TAG PresetTargetWriter 失败: ${e.message}")
            0
        }
    }

    /**
     * SwitchPreset：切换忍者预设（刷胜率/刷熟练度用）。
     * 校准坐标（1280x720 虚拟屏）：
     *   预设按钮 (750,460) -> 预设1/2/3/4页签（依次往右，间距114,-7） -> 确认预设 (1001,582)
     * param: {"preset": 1|2|3|4}
     */
    private fun actionSwitchPreset(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        if (context == null) return 0
        return try {
            val p = param?.let { JSONObject(it) } ?: JSONObject()
            val preset = p.optInt("preset", 1).coerceIn(1, 4)
            // 预设页签中心点：预设1(907,126) 预设2(1021,119) 预设3(1135,112) 预设4(1249,105)，依次往右间距(114,-7)
            val tabs = listOf(
                907 to 126,
                1021 to 119,
                1135 to 112,
                1249 to 105
            )
            val (tx, ty) = tabs[preset - 1]
            // 1) 打开预设面板
            click(context, 750, 460, 8, 8)
            SystemClock.sleep(300)
            // 2) 选择预设页签
            click(context, tx, ty, 8, 8)
            SystemClock.sleep(300)
            // 3) 确认预设
            click(context, 1001, 582, 8, 8)
            Ln.i("$TAG SwitchPreset 完成 preset=$preset node=$nodeName")
            1
        } catch (e: Exception) {
            Ln.e("$TAG SwitchPreset 失败: ${e.message}")
            0
        }
    }

    /** Cleanup 系列：清理文件，返回成功 */
    private fun actionCleanupVisionImg(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte = cleanupDir("vision") ?: 1
    private fun actionCleanupOnErrorImg(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte = cleanupDir("on_error") ?: 1
    private fun actionCleanupCustomImg(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte = cleanupDir("custom_img") ?: 1
    private fun actionCleanupCustomLog(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte = cleanupDir("custom_log") ?: 1
    private fun actionCleanupMaafwBakLogs(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte = cleanupDir("bak") ?: 1

    /** CleanupAgentDebug：清理 agent 调试日志（Android 无 PC debug 目录结构，宽松返回成功） */
    private fun actionCleanupAgentDebug(context: Pointer?, taskId: Long, nodeName: String?, actionName: String?, param: String?, recoId: Long, box: Pointer?): Byte {
        // Android 端无 PC 的 debug/maafw.bak 目录，宽松返回成功即可（可顺带清理 agent.log 旧文件）
        runCatching {
            maaFilesDir?.let { dir ->
                File(dir, "debug").takeIf { it.exists() }?.listFiles()?.forEach { it.delete() }
            }
        }
        return 1
    }

    /** 清理 files 下指定子目录里的文件（保留目录本身） */
    private fun cleanupDir(sub: String): Byte? {
        return try {
            val dir = File(maaFilesDir ?: return null, sub)
            if (dir.exists()) dir.listFiles()?.forEach { it.delete() }
            1
        } catch (e: Exception) {
            Ln.e("$TAG cleanupDir($sub) 失败: ${e.message}")
            null
        }
    }

    @Volatile
    var maaFilesDir: File? = null
        private set

    fun setFilesDir(dir: File?) {
        maaFilesDir = dir
    }
}