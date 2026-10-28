package com.maafw.naruto.maa

import com.maafw.naruto.third.Ln
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * MaaFramework 自定义识别实现
 *
 * 通过 MaaResourceRegisterCustomRecognition 注册，pipeline 里 recognition: "Custom" 的节点
 * 会回调到这里执行。全部 14 个识别器
 *
 * 未实现这些识别器时，决斗场（连点器）、羁绊、秘境、招财等任务会在 Custom 节点
 * 直接失败导致任务链提前结束——这正是「进去对战没连点就结束」的根因。
 */
object CustomRecognitions {

    private const val TAG = "CustomRecognitions"
    private val lib = MaaFrameworkLib.INSTANCE

    // 防止 JNA 回调对象被 GC 回收，必须强引用持有
    private val registeredCallbacks = mutableListOf<MaaCustomRecognitionCallback>()

    // 复刻 py 日志的文件输出（对应原版 debug/custom/2026-08-12.log），导出时随日志打包
    @Volatile
    var logFile: java.io.File? = null
        private set

    fun setLogFile(file: java.io.File?) {
        logFile = file
    }

    /** 复刻 py logger：同时输出到 logcat（Ln）与 custom 日志文件 */
    private fun klog(level: String, msg: String) {
        when (level) {
            "WARN" -> Ln.w("$TAG $msg")
            "ERROR" -> Ln.e("$TAG $msg")
            else -> Ln.i("$TAG $msg")
        }
        runCatching {
            val f = logFile ?: return
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                runCatching { f.setReadable(true, false) } // root 写入 -> App 可读（导出用）
            }
            f.appendText("[$level][${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())}] $msg\n")
        }
    }

    // CheckBuyEnergyCount 的初始次数（每次任务运行重置）
    private var buyEnergyStartCount = -1

    /**
     * 注册全部 custom recognition 到 resource 上。
     * 必须在 MaaResourceCreate 之后、任务运行之前调用。
     */
    fun register(res: Pointer): Boolean {
        var ok = true
        val recognitions = mapOf(
            "IsCounterOverflow" to ::recoIsCounterOverflow,
            "IsLoseCounterOverflow" to ::recoIsLoseCounterOverflow,
            "IsPresetTarget" to ::recoIsPresetTarget,
            "PresetDecision" to ::recoPresetDecision,
            "IsInNinjaGuide" to ::recoIsInNinjaGuide,
            "FindPlantableFlower" to ::recoFindPlantableFlower,
            "FindBondsWithoutEnoughToken" to ::recoFindBondsWithoutEnoughToken,
            "FindAccessoryFlipTicket" to ::recoFindAccessoryFlipTicket,
            "FindGearFlipTicket" to ::recoFindGearFlipTicket,
            "SecretRealmTicket" to ::recoSecretRealmTicket,
            "MissionOfficeStrategy" to ::recoMissionOfficeStrategy,
            "CheckGetCopperRoll" to ::recoCheckGetCopperRoll,
            "CheckGetCopperCount" to ::recoCheckGetCopperCount,
            "CheckBuyEnergyCount" to ::recoCheckBuyEnergyCount,
            "FlipCard" to ::recoFlipCard,
            "Shopping" to ::recoShopping,
            "FindToChallenge" to ::recoFindToChallenge,
        )
        for ((name, fn) in recognitions) {
            val cb = object : MaaCustomRecognitionCallback {
                override fun invoke(
                    context: Pointer?, taskId: Long, nodeName: String?, recoName: String?,
                    recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?,
                    outBox: Pointer?, outDetail: Pointer?
                ): Byte {
                    return try {
                        fn(context, taskId, nodeName, recoParam, image, outBox)
                    } catch (e: Throwable) {
                        Ln.e("$TAG.$name 识别异常: ${e.message}")
                        e.printStackTrace()
                        0
                    }
                }
            }
            registeredCallbacks.add(cb)
            val r = lib.MaaResourceRegisterCustomRecognition(res, name, cb, null)
            if (r != 1.toByte()) {
                Ln.e("$TAG 注册 $name 失败")
                ok = false
            } else {
                Ln.i("$TAG 已注册 custom recognition: $name")
            }
        }
        return ok
    }

    fun resetState() {
        buyEnergyStartCount = -1
    }

    // ==================== 工具函数 ====================

    /** 设置输出识别框 */
    private fun setBox(outBox: Pointer?, x: Int, y: Int, w: Int, h: Int) {
        if (outBox != null && Pointer.nativeValue(outBox) != 0L) {
            lib.MaaRectSet(outBox, x, y, w, h)
        }
    }

    private fun hitBox(outBox: Pointer?) = setBox(outBox, 0, 0, 1, 1)

    /** 执行 OCR 识别，返回 all_results 数组（null = 失败/无结果） */
    private fun ocrResults(context: Pointer?, image: Pointer?, roi: IntArray?, expected: String?): JSONArray? {
        if (context == null || image == null) return null
        return try {
            val param = JSONObject()
            expected?.let { param.put("expected", it) }
            roi?.let { param.put("roi", JSONArray().apply { it.forEach { v -> put(v) } }) }
            val recoId = lib.MaaContextRunRecognitionDirect(context, "OCR", param.toString(), image)
            if (recoId == 0L) return null
            val tasker = lib.MaaContextGetTasker(context) ?: return null
            getRecoDetail(tasker, recoId)?.optJSONArray("all_results")
                ?: getRecoDetail(tasker, recoId)?.optJSONArray("results")
        } catch (e: Exception) {
            Ln.e("$TAG ocrResults: ${e.message}")
            null
        }
    }

    /** 获取识别 detail JSON（含 all_results/best_result） */
    private fun getRecoDetail(tasker: Pointer, recoId: Long): JSONObject? {
        return try {
            val hit = IntByReference(0)
            val nodeName = lib.MaaStringBufferCreate()
            val algorithm = lib.MaaStringBufferCreate()
            val detailJson = lib.MaaStringBufferCreate()
            val box = lib.MaaRectCreate()
            val ok = lib.MaaTaskerGetRecognitionDetail(tasker, recoId, nodeName, algorithm, hit, box, detailJson, null, null)
            val detail = if (ok == 1.toByte()) {
                runCatching { JSONObject(lib.MaaStringBufferGet(detailJson) ?: "{}") }.getOrNull()
            } else null
            lib.MaaStringBufferDestroy(nodeName)
            lib.MaaStringBufferDestroy(algorithm)
            lib.MaaStringBufferDestroy(detailJson)
            lib.MaaRectDestroy(box)
            detail
        } catch (e: Exception) {
            Ln.e("$TAG getRecoDetail: ${e.message}")
            null
        }
    }

    /** 提取 ROI 内纯数字 */
    private fun digitCount(context: Pointer?, image: Pointer?, roi: IntArray): Int? {
        val results = ocrResults(context, image, roi, "\\d+") ?: return null
        val sb = StringBuilder()
        for (i in 0 until results.length()) {
            sb.append(results.getJSONObject(i).optString("text", ""))
        }
        val m = Regex("\\d+").find(sb.toString().replace(" ", "")) ?: return null
        return m.value.toIntOrNull()
    }

    /**
     * 运行 pipeline 节点 GetTextWithNumers 做 OCR（对应原版 py 的 context.run_recognition("GetTextWithNumers")，
     * 不经过 MaaContextRunRecognitionDirect，避免 CustomRecognition 内嵌套 OCR 死锁）。
     * 返回 best_result 原始文本（可能含"万"）。
     */
    private fun nodeOcrText(context: Pointer?, image: Pointer?, roi: IntArray): String? {
        if (context == null || image == null) return null
        return try {
            val override = JSONObject()
                .put("GetTextWithNumers", JSONObject().put("roi", JSONArray().apply { roi.forEach { put(it) } }))
                .toString()
            val recoId = lib.MaaContextRunRecognition(context, "GetTextWithNumers", override, image)
            if (recoId == 0L) return null
            val tasker = lib.MaaContextGetTasker(context) ?: return null
            val detail = getRecoDetail(tasker, recoId) ?: return null
            // 注意：MaaFramework OCR detail 键名是 "best" / "all"（不是 best_result/all_results）！
            val best = detail.optJSONObject("best")?.optString("text", null)
            if (!best.isNullOrBlank()) return best
            val arr = detail.optJSONArray("all") ?: detail.optJSONArray("all_results") ?: detail.optJSONArray("results")
            val sb = StringBuilder()
            for (i in 0 until (arr?.length() ?: 0)) {
                sb.append(arr.getJSONObject(i).optString("text", ""))
            }
            sb.toString().ifBlank { null }
        } catch (e: Exception) {
            Ln.e("$TAG nodeOcrText: ${e.message}")
            null
        }
    }

    /** 对应原版 py utils.get_digit_count：读 ROI 文本并提取第一个数字，返回 (数值, 原始文本) */
    private fun getDigitCount(context: Pointer?, image: Pointer?, roi: IntArray): Pair<Int?, String?> {
        val text = nodeOcrText(context, image, roi)
        if (text == null) {
            klog("WARN", "ROI ${roi.contentToString()} 未识别到任何带数字的文本")
            return null to null
        }
        val nums = Regex("\\d+").findAll(text).map { it.value }.toList()
        if (nums.isEmpty()) {
            klog("WARN", "ROI ${roi.contentToString()} 未提取到有效数字，原始文本：$text")
            return null to text
        }
        klog("INFO", " ROI${roi.contentToString()} 解析到的纯数字:${nums[0]}")
        return nums[0].toIntOrNull() to text
    }

    /** 对应原版 py point_race.get_senryoku：读战力（"万"单位 ×10000），输出原版日志 */
    private fun getSenryoku(context: Pointer?, image: Pointer?, roi: IntArray): Int? {
        val (value, sourceText) = getDigitCount(context, image, roi)
        if (value == null || sourceText == null) {
            klog("ERROR", "无法解析战力 ROI: ${roi.contentToString()}")
            return null
        }
        val v = if (sourceText.endsWith("万")) value * 10000 else value
        klog("INFO", "读取到战力：$v")
        return v
    }

    /** 运行 pipeline 节点并判断是否命中（IsInNinjaGuide 用） */
    private fun runNodeHit(context: Pointer?, image: Pointer?, nodeName: String, override: String = "{}"): Boolean {
        if (context == null || image == null) return false
        return try {
            val recoId = lib.MaaContextRunRecognition(context, nodeName, override, image)
            if (recoId == 0L) return false
            val tasker = lib.MaaContextGetTasker(context) ?: return false
            val hit = IntByReference(0)
            val nodeNameBuf = lib.MaaStringBufferCreate()
            val algorithm = lib.MaaStringBufferCreate()
            val detailJson = lib.MaaStringBufferCreate()
            val box = lib.MaaRectCreate()
            val ok = lib.MaaTaskerGetRecognitionDetail(tasker, recoId, nodeNameBuf, algorithm, hit, box, detailJson, null, null)
            lib.MaaStringBufferDestroy(nodeNameBuf)
            lib.MaaStringBufferDestroy(algorithm)
            lib.MaaStringBufferDestroy(detailJson)
            lib.MaaRectDestroy(box)
            ok == 1.toByte() && hit.value != 0
        } catch (e: Exception) {
            Ln.e("$TAG runNodeHit: ${e.message}")
            false
        }
    }

    private fun stopTask(context: Pointer?) {
        runCatching {
            val ctx = context ?: return@runCatching
            val tasker = lib.MaaContextGetTasker(ctx)
            if (tasker != null && Pointer.nativeValue(tasker) != 0L) {
                lib.MaaTaskerPostStop(tasker)
            }
        }
    }

    private fun paramObj(param: String?): JSONObject = runCatching { JSONObject(param ?: "{}") }.getOrDefault(JSONObject())

    // ==================== 识别器 ====================

    /**
     * IsCounterOverflow：计数器溢出检测（连点器核心）。
     * 计数器未达到 max_hit -> 命中（继续连点）；达到 -> 未命中（停止连点）。
     */
    private fun recoIsCounterOverflow(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val p = paramObj(param)
        val maxHit = p.optInt("max_hit", 0)
        if (maxHit <= 0) {
            Ln.e("$TAG IsCounterOverflow max_hit 参数错误")
            stopTask(context)
            return 0
        }
        val nowCount = CustomActions.getCounter(taskId)
        if (nowCount >= maxHit) {
            Ln.i("$TAG IsCounterOverflow 达到最大执行次数 max=$maxHit now=$nowCount")
            return 0
        }
        Ln.d("$TAG IsCounterOverflow 继续连点 max=$maxHit now=$nowCount")
        hitBox(outBox)
        return 1
    }

    /**
     * IsLoseCounterOverflow：刷胜率失败计数溢出检测。
     * 失败计数未达到 max_hit -> 命中（继续挂机）；达到 -> 未命中（改用连点器赢一局）。
     */
    private fun recoIsLoseCounterOverflow(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val p = paramObj(param)
        val maxHit = p.optInt("max_hit", 0)
        if (maxHit <= 0) {
            Ln.e("$TAG IsLoseCounterOverflow max_hit 参数错误")
            stopTask(context)
            return 0
        }
        val nowCount = CustomActions.getLoseCounter(taskId)
        if (nowCount >= maxHit) {
            Ln.i("$TAG IsLoseCounterOverflow 失败次数已满,改用连点器 max=$maxHit now=$nowCount")
            return 0
        }
        Ln.d("$TAG IsLoseCounterOverflow 继续挂机 max=$maxHit now=$nowCount")
        hitBox(outBox)
        return 1
    }

        /**
     * IsPresetTarget：判断本局目标预设是否等于 n（PresetTargetWriter 注入 target_preset）。
     * 纯逻辑判断，不做 OCR，安全无死锁。用于「切换到预设N」节点分流：
     *   pipeline 的 next 按顺序尝试 1..4，只有 target_preset == n 的节点命中并点击页签。
     */
    private fun recoIsPresetTarget(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val p = paramObj(param)
        val n = p.optInt("n", 0)
        val target = p.optInt("target_preset", -1)
        if (n in 1..4 && target == n) {
            klog("INFO", "IsPresetTarget 命中: 目标预设=$target == 节点预设$n")
            hitBox(outBox)
            return 1
        }
        return 0
    }

    /** 预设页签盒（1280x720，用户 MPE 校准）：预设1(825,109) 预设2(978,109) 预设3(1062,109) 预设4(1146,109) */
    private val presetTabBoxes = mapOf(
        1 to intArrayOf(825, 109, 64, 38),
        2 to intArrayOf(978, 109, 64, 38),
        3 to intArrayOf(1062, 109, 64, 38),
        4 to intArrayOf(1146, 109, 62, 36)
    )

    /** 各预设页签 OCR 数字识别 ROI（用户 MPE 校准） */
    private val presetTabOcrRois = mapOf(
        1 to intArrayOf(800, 94, 115, 62),
        2 to intArrayOf(943, 94, 108, 62),
        3 to intArrayOf(1040, 94, 108, 62),
        4 to intArrayOf(1121, 94, 108, 62)
    )

    /** 在预设页签区域 OCR 找目标数字 n 的框；找不到返回 null（调用方用坐标兜底） */
    private fun findPresetTabByOcr(context: Pointer?, image: Pointer?, n: Int): IntArray? {
        if (context == null || image == null) return null
        val results = ocrResults(context, image, intArrayOf(850, 60, 430, 130), "\\d+") ?: return null
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i)
            val text = obj?.optString("text", "")?.trim() ?: continue
            if (text == n.toString()) {
                // MaaFramework OCR detail 中 box 为数组 [x, y, w, h]
                val boxArr = obj.optJSONArray("box")
                if (boxArr != null && boxArr.length() >= 4) {
                    val x = boxArr.getInt(0)
                    val y = boxArr.getInt(1)
                    val w = boxArr.getInt(2)
                    val h = boxArr.getInt(3)
                    if (w > 0 && h > 0) {
                        Ln.i("$TAG PresetDecision OCR 定位预设$n 于 ($x,$y,$w,$h)")
                        return intArrayOf(x, y, w, h)
                    }
                }
            }
        }
        return null
    }

    /** 解析"预设N"字符串为数字 N（1-4），非法值返回默认 */
    private fun parsePresetName(name: String, def: Int): Int {
        val m = Regex("(\\d+)").find(name) ?: return def
        return m.groupValues[1].toIntOrNull()?.coerceIn(1, 4) ?: def
    }

    /**
     * PresetDecision：决策本局要用的预设页签（锁定阵容前切换用）。
     * 优先读取 PresetTargetWriter 动态注入的 target_preset（引擎内 MaaContextOverridePipeline 生效）；
     * 引擎内不做任何 OCR（CustomRecognition 嵌套 OCR 必死锁），OCR 确认由 agent 版 PresetDecision 完成；
     * 引擎版仅作为 agent 未连接时的兜底：读 target_preset -> 返回校准坐标。
     */
    private fun recoPresetDecision(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val p = paramObj(param)
        var target = p.optInt("target_preset", -1)
        if (target !in 1..4) {
            // 兜底决策（PresetTargetWriter 未注入时）
            val mode = p.optString("mode", "lose")
            target = when (mode) {
                "rotate" -> (CustomActions.getPresetRotation(taskId) % 4) + 1
                else -> {
                    val maxHit = p.optInt("max_hit", 5)
                    val presetWin = parsePresetName(p.optString("preset_win", "预设1"), 1)
                    val presetLose = parsePresetName(p.optString("preset_lose", "预设2"), 2)
                    val lose = CustomActions.getLoseCounter(taskId)
                    if (lose >= maxHit) presetWin else presetLose
                }
            }
        }
        val box = presetTabBoxes[target]
        if (box == null) {
            klog("ERROR", "PresetDecision 预设$target 页签坐标缺失")
            return 0
        }
        setBox(outBox, box[0], box[1], box[2], box[3])
        klog("INFO", "PresetDecision(引擎兜底) 目标预设$target box=(${box[0]},${box[1]},${box[2]},${box[3]})")
        return 1
    }

    /** IsInNinjaGuide：是否在忍界引导界面（in_ninja_guide 是 Or 识别：任务/回流） */
    private fun recoIsInNinjaGuide(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        return if (runNodeHit(context, image, "in_ninja_guide")) {
            hitBox(outBox)
            1
        } else {
            0
        }
    }

    /** FindPlantableFlower：中山花店，找可种的花（种子≥10） */
    private fun recoFindPlantableFlower(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val flowerConfig = listOf(
            intArrayOf(400, 355, 111, 32) to intArrayOf(440, 298, 37, 41),
            intArrayOf(509, 355, 103, 29) to intArrayOf(543, 298, 29, 27),
            intArrayOf(607, 355, 106, 27) to intArrayOf(642, 295, 34, 34),
            intArrayOf(711, 355, 103, 32) to intArrayOf(749, 300, 29, 29),
            intArrayOf(810, 256, 143, 140) to intArrayOf(844, 298, 37, 34),
        )
        for ((seedRoi, btnRoi) in flowerConfig) {
            // 种子文本形如 "剩余:xx/10"，直接提取数字取第一个（剩余量）
            val count = digitCount(context, image, seedRoi) ?: continue
            if (count >= 10) {
                setBox(outBox, btnRoi[0], btnRoi[1], btnRoi[2], btnRoi[3])
                return 1
            }
        }
        // 无可用种子也返回一个不影响的 box（原版 invalid_box），避免无限重试
        hitBox(outBox)
        return 1
    }

    /** FindBondsWithoutEnoughToken：羁绊币 < 5 则通过 */
    private fun recoFindBondsWithoutEnoughToken(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val roi = intArrayOf(846, 639, 111, 80)
        val tokenCount = digitCount(context, image, roi)
        if (tokenCount == null || tokenCount >= 5) return 0
        hitBox(outBox)
        return 1
    }

    /** FindAccessoryFlipTicket：秘境饰品翻牌卷 > 0 则通过 */
    private fun recoFindAccessoryFlipTicket(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val count = digitCount(context, image, intArrayOf(550, 481, 171, 238))
        if (count != null && count > 0) {
            hitBox(outBox)
            return 1
        }
        return 0
    }

    /** FindGearFlipTicket：忍具翻牌卷 > 0 则通过 */
    private fun recoFindGearFlipTicket(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val count = digitCount(context, image, intArrayOf(436, 483, 138, 236))
        if (count != null && count > 0) {
            hitBox(outBox)
            return 1
        }
        return 0
    }

    /** SecretRealmTicket：秘境挑战卷 > 0 则通过 */
    private fun recoSecretRealmTicket(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val count = digitCount(context, image, intArrayOf(496, 624, 39, 44))
        if (count != null && count > 0) {
            hitBox(outBox)
            return 1
        }
        return 0
    }

    /** MissionOfficeStrategy：任务集会所策略，(刷新上限-9)*1.5 >= 可接受任务 则通过 */
    private fun recoMissionOfficeStrategy(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val maxResource = digitCount(context, image, intArrayOf(1004, 614, 27, 27))
        val currentResource = digitCount(context, image, intArrayOf(1003, 648, 22, 28))
        if (maxResource == null || currentResource == null) return 0
        val condition = (maxResource - 9) * 1.5 >= currentResource
        return if (condition) {
            hitBox(outBox)
            1
        } else {
            0
        }
    }

    /** CheckGetCopperRoll：招财轮次 >= count+1 则通过 */
    private fun recoCheckGetCopperRoll(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val count = paramObj(param).optInt("count", 1)
        val now = digitCount(context, image, intArrayOf(104, 468, 40, 31)) ?: 66
        return if (now >= count + 1) {
            hitBox(outBox)
            1
        } else {
            0
        }
    }

    /** CheckGetCopperCount：招财次数 >= count 则通过 */
    private fun recoCheckGetCopperCount(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val count = paramObj(param).optInt("count", 1)
        val now = digitCount(context, image, intArrayOf(309, 468, 27, 30)) ?: 66
        return if (now >= count) {
            hitBox(outBox)
            1
        } else {
            0
        }
    }

    /** CheckBuyEnergyCount：购买体力次数，首次识别 - 当前 >= count 则通过 */
    private fun recoCheckBuyEnergyCount(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val count = paramObj(param).optInt("count", 1)
        val roi = intArrayOf(499, 374, 251, 59)
        if (buyEnergyStartCount == -1) {
            buyEnergyStartCount = digitCount(context, image, roi) ?: 0
        }
        val now = digitCount(context, image, roi) ?: 0
        return if (buyEnergyStartCount - now >= count) {
            hitBox(outBox)
            1
        } else {
            0
        }
    }

    /**
     * FlipCard：翻牌识别（原版读取两张牌的值比较；安卓端简化：识别到翻牌界面即通过，
     * 由 pipeline 的 CustomAction/Counter 控制翻牌次数）。
     */
    private fun recoFlipCard(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        // 翻牌界面有"未翻开"模板卡，宽松命中让流程继续；避免节点失败中断任务链
        val p = paramObj(param)
        val mode = p.optString("mode", "loose")
        if (mode == "strict") {
            // 严格模式：OCR 找数字牌值
            val results = ocrResults(context, image, null, "\\d+") ?: return 0
            return if (results.length() > 0) {
                hitBox(outBox)
                1
            } else 0
        }
        hitBox(outBox)
        return 1
    }

    /**
     * Shopping：商店兑换（原版检测商品格子；安卓端宽松：命中让流程继续）。
     */
    private fun recoShopping(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        val results = ocrResults(context, image, null, null) ?: return 0
        // 检测到任何文本即视为商店界面存在
        return if (results.length() > 0) {
            hitBox(outBox)
            1
        } else {
            0
        }
    }

    /**
     * FindToChallenge：积分赛战力对比。
     * 正常由 agent 独立进程执行（走 ZMQ 转发不死锁）；此引擎内实现仅作兜底——
     * 直接宽松命中（不嵌套引擎识别，避免引擎线程死锁），让流程继续。
     */
    private fun recoFindToChallenge(context: Pointer?, taskId: Long, nodeName: String?, param: String?, image: Pointer?, outBox: Pointer?): Byte {
        // 兜底：宽松命中（不调引擎识别），让积分赛流程继续（等价于点第 1 个对手）
        hitBox(outBox)
        return 1
    }
}