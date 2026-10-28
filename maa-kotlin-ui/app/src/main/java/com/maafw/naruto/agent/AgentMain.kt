package com.maafw.naruto.agent

import com.maafw.naruto.maa.MaaAgentServerLib
import com.maafw.naruto.maa.MaaCustomRecognitionCallback
import com.maafw.naruto.maa.MaaFrameworkLib
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 独立进程入口（方案 A）。
 *
 * 由主引擎用 app_process 启动：
 *   app_process -Djava.class.path=<apk> /system/bin \
 *       com.maafw.naruto.agent.AgentMain <identifier> <libDir> <userDir>
 *
 * 职责：
 *  1. 加载 libMaaAgentServer.so 及其依赖 so
 *  2. 注册 CustomRecognition（回调在本进程执行，可自由调引擎识别->ZMQ转发，不死锁）
 *  3. StartUp(identifier) + Join() 进入消息循环
 *
 * 已注册识别器：
 *  - FindToChallenge：积分赛战力对比（复刻原版 point_race.py，OCR 走 GetTextWithNumers 节点）
 */
object AgentMain {

    @JvmStatic
    fun main(args: Array<String>) {
        println("[AgentMain] start args=${args.joinToString(" ")}")
        if (args.size < 3) {
            println("[AgentMain] usage: <identifier> <libDir> <userDir>")
            return
        }
        val identifier = args[0]
        val libDir = args[1]
        val userDir = args[2]

        // 让 JNA 能按库名找到 so + jnidispatch 桥
        System.setProperty("java.library.path", libDir)
        System.setProperty("jna.boot.library.path", libDir)
        try {
            com.sun.jna.NativeLibrary::class.java.getDeclaredField("libraryPathCache")
                .apply { isAccessible = true }.let { f -> f.set(null, null) }
        } catch (_: Throwable) {
        }

        // 按依赖顺序加载 so
        try {
            System.load("$libDir/libc++_shared.so")
            System.load("$libDir/libopencv_world4.so")
            System.load("$libDir/libonnxruntime.so")
            System.load("$libDir/libfastdeploy_ppocr.so")
            System.load("$libDir/libMaaUtils.so")
            System.load("$libDir/libMaaFramework.so")
            System.load("$libDir/libMaaAgentServer.so")
            println("[AgentMain] all so loaded")
        } catch (e: Throwable) {
            println("[AgentMain] load so failed: ${e.message}")
            return
        }

        val serverLib = runCatching { MaaAgentServerLib.INSTANCE }.getOrElse { e ->
            println("[AgentMain] JNA MaaAgentServerLib load failed: ${e.message}")
            return
        }
        println("[AgentMain] JNA lib ok: $serverLib")

        // ============ 注册 FindToChallenge（原版积分赛战力对比） ============
        val findToChallengeCb = object : MaaCustomRecognitionCallback {
            override fun invoke(
                context: Pointer?, taskId: Long, nodeName: String?, recoName: String?,
                recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?,
                outBox: Pointer?, outDetail: Pointer?
            ): Byte {
                return try {
                    if (context == null) {
                        setBox(outBox, 0, 0, 1, 1)
                        return 1
                    }
                    val p = runCatching { JSONObject(recoParam ?: "{}") }.getOrDefault(JSONObject())
                    val fourceBattle = p.optJSONObject("custom_recognition_param")?.optBoolean("fource_battle", false) ?: false
                    println("[AgentMain][FindToChallenge] 当前配置：${if (fourceBattle) "强制挑战" else "非强制挑战"}")

                    println("[AgentMain][FindToChallenge] 尝试读取我方小队战力...")
                    val team = getSenryoku(context, image, intArrayOf(271, 337, 178, 29))
                    if (team == null) {
                        // 识别不到我方战力 -> 宽松命中，避免卡住
                        setBox(outBox, 0, 0, 1, 1)
                        return 1
                    }

                    println("[AgentMain][FindToChallenge] 尝试读取敌方小队战力...")
                    val enemyRois = listOf(
                        intArrayOf(841, 234, 115, 32),
                        intArrayOf(841, 352, 113, 32),
                        intArrayOf(841, 471, 115, 32),
                        intArrayOf(841, 589, 111, 29),
                    )
                    val enemyValues = mutableListOf<Long>()
                    enemyRois.forEachIndexed { index, roi ->
                        val v = getSenryoku(context, image, roi)
                        if (v != null) {
                            enemyValues.add(v.toLong())
                        } else {
                            println("[AgentMain][FindToChallenge] 无法解析战力文本: ${index + 1}")
                            enemyValues.add(1145141919810L)
                        }
                    }
                    val minIdx = enemyValues.indices.minByOrNull { enemyValues[it] } ?: 0
                    println("[AgentMain][FindToChallenge] 敌队${minIdx + 1}战力最低：${enemyValues[minIdx] / 10000.0}万")

                    if (enemyValues[minIdx] > team.toLong() && !fourceBattle) {
                        println("[AgentMain][FindToChallenge] 没一个打得过的，溜了溜了。")
                        return 0
                    }

                    println("[AgentMain][FindToChallenge] 挑战敌队${minIdx + 1}!")
                    val targets = listOf(
                        intArrayOf(986, 195, 92, 39),
                        intArrayOf(987, 312, 92, 39),
                        intArrayOf(988, 430, 92, 39),
                        intArrayOf(987, 548, 92, 39),
                    )
                    val t = targets[minIdx]
                    setBox(outBox, t[0], t[1], t[2], t[3])
                    1
                } catch (e: Throwable) {
                    println("[AgentMain][FindToChallenge] error: ${e.message}")
                    setBox(outBox, 0, 0, 1, 1)
                    1
                }
            }
        }
        val r1 = serverLib.MaaAgentServerRegisterCustomRecognition("FindToChallenge", findToChallengeCb, null)
        println("[AgentMain] register FindToChallenge -> $r1")

        // 注册其他常用识别器（复用 CustomRecognitions 逻辑，OCR 走 agent 转发不死锁）
        registerCommonRecognitions(serverLib)
        // 注册 base.py 剩余识别器（翻牌卷/任务集会所/招财/买体力）
        registerMoreRecognitions(serverLib)
        // FlipCard 完整版（4x4 贪心算法，覆盖宽松版）
        registerFlipCard(serverLib)
        // 决斗场预设页签 OCR 确认（agent 安全执行，防止点错页签；决策已在 JSON 分流完成）
        registerPresetTabOCR(serverLib)

        // ============ 启动 server ============
        println("[AgentMain] StartUp identifier=$identifier")
        val up = MaaAgentServerLib.INSTANCE.MaaAgentServerStartUp(identifier)
        println("[AgentMain] after StartUp -> $up")
        MaaAgentServerLib.INSTANCE.MaaAgentServerJoin()
        println("[AgentMain] server joined, exit")
    }

    // ==================== 战力读取（复刻原版 get_senryoku/get_digit_count） ====================

    /** 运行 pipeline 节点 GetTextWithNumers 做 OCR（对应 py context.run_recognition，走 ZMQ 转发不死锁） */
    private fun ocrText(context: Pointer?, image: Pointer?, roi: IntArray): String? {
        if (context == null || image == null) return null
        return try {
            val override = JSONObject()
                .put("GetTextWithNumers", JSONObject().put("roi", JSONArray().apply { roi.forEach { put(it) } }))
                .toString()
            println("[AgentMain][ocrText] run_recognition start roi=${roi.contentToString()}")
            val recoId = MaaFrameworkLib.INSTANCE.MaaContextRunRecognition(context, "GetTextWithNumers", override, image)
            println("[AgentMain][ocrText] recoId=$recoId")
            if (recoId == 0L) return null
            val tasker = MaaFrameworkLib.INSTANCE.MaaContextGetTasker(context)
            println("[AgentMain][ocrText] tasker=${tasker?.let { java.lang.Long.toHexString(Pointer.nativeValue(it)) }}")
            if (tasker == null) return null
            val detail = getRecoDetail(tasker, recoId)
            println("[AgentMain][ocrText] detail=${detail?.toString()?.take(200)}")
            if (detail == null) return null
            // 注意：MaaFramework OCR detail 键名是 "best" / "all"（不是 best_result/all_results）！
            val best = detail.optJSONObject("best")?.optString("text", null)
            if (!best.isNullOrBlank()) return best
            val arr = detail.optJSONArray("all") ?: detail.optJSONArray("all_results") ?: detail.optJSONArray("results")
            val sb = StringBuilder()
            for (i in 0 until (arr?.length() ?: 0)) {
                sb.append(arr.getJSONObject(i).optString("text", ""))
            }
            val text = sb.toString().ifBlank { null }
            println("[AgentMain][ocrText] result=$text")
            text
        } catch (e: Exception) {
            println("[AgentMain] ocrText error: ${e.message}")
            null
        }
    }

    /** 读取 ROI 战力（"万"单位 ×10000），对应原版 get_senryoku */
    private fun getSenryoku(context: Pointer?, image: Pointer?, roi: IntArray): Int? {
        val text = ocrText(context, image, roi)?.trim() ?: return null
        val m = Regex("(\\d+(?:\\.\\d+)?)").find(text.replace(" ", "")) ?: return null
        val num = m.groupValues[1].toDoubleOrNull() ?: return null
        val v = if (text.endsWith("万")) (num * 10000).toInt() else num.toInt()
        println("[AgentMain] 读取到战力：$v (roi=${roi.contentToString()})")
        return v
    }

    private fun getRecoDetail(tasker: Pointer, recoId: Long): JSONObject? {
        return try {
            val hit = IntByReference(0)
            val nodeName = MaaFrameworkLib.INSTANCE.MaaStringBufferCreate()
            val algorithm = MaaFrameworkLib.INSTANCE.MaaStringBufferCreate()
            val detailJson = MaaFrameworkLib.INSTANCE.MaaStringBufferCreate()
            val box = MaaFrameworkLib.INSTANCE.MaaRectCreate()
            val ok = MaaFrameworkLib.INSTANCE.MaaTaskerGetRecognitionDetail(
                tasker, recoId, nodeName, algorithm, hit, box, detailJson, null, null
            )
            val detail = if (ok == 1.toByte()) {
                runCatching { JSONObject(MaaFrameworkLib.INSTANCE.MaaStringBufferGet(detailJson) ?: "{}") }.getOrNull()
            } else null
            MaaFrameworkLib.INSTANCE.MaaStringBufferDestroy(nodeName)
            MaaFrameworkLib.INSTANCE.MaaStringBufferDestroy(algorithm)
            MaaFrameworkLib.INSTANCE.MaaStringBufferDestroy(detailJson)
            MaaFrameworkLib.INSTANCE.MaaRectDestroy(box)
            detail
        } catch (e: Exception) {
            println("[AgentMain] getRecoDetail error: ${e.message}")
            null
        }
    }

    private fun setBox(outBox: Pointer?, x: Int, y: Int, w: Int, h: Int) {
        if (outBox != null && Pointer.nativeValue(outBox) != 0L) {
            MaaFrameworkLib.INSTANCE.MaaRectSet(outBox, x, y, w, h)
        }
    }

    // ==================== Shopping 完整移植（原版 shopping.py） ====================
    private data class ShopConfig(
        val slot1Anchor: String, val slot2Anchor: String,
        val nodeDataKey1: String, val nodeDataKey2: String,
        val checkPipeline: String, val shopping: String,
        val shoppingInterface: String, val followUpShopping: String,
        val totalRoi: IntArray,
    )

    private val SHOP_CONFIGS: Map<String, ShopConfig> = mapOf(
        "jade_child_shop" to ShopConfig(
            "jade_child_shop_slot_1", "jade_child_shop_slot_2",
            "jade_good_slot_1_set", "jade_good_slot_2_set",
            "shop_jade_child_check_shopping_count", "shop_jade_child_shopping",
            "shop_jade_child_shopping_interface", "shop_jade_child_follow_up_shopping",
            intArrayOf(1019, 17, 128, 37),
        ),
        "survival_child_shop" to ShopConfig(
            "survival_child_shop_slot_1", "survival_child_shop_slot_2",
            "survival_good_slot_1_set", "survival_good_slot_2_set",
            "shop_survival_child_check_shopping_count", "shop_survival_child_shopping",
            "shop_survival_child_shopping_interface", "shop_survival_child_follow_up_shopping",
            intArrayOf(1019, 17, 128, 37),
        ),
        "point_race_child_shop" to ShopConfig(
            "point_race_child_shop_slot_1", "point_race_child_shop_slot_2",
            "point_race_good_slot_1_set", "point_race_good_slot_2_set",
            "shop_point_race_child_check_shopping_count", "shop_point_race_child_shopping",
            "shop_point_race_child_shopping_interface", "shop_point_race_child_follow_up_shopping",
            intArrayOf(1019, 17, 128, 37),
        ),
        "group_child_shop" to ShopConfig(
            "group_child_shop_slot_1", "group_child_shop_slot_2",
            "group_good_slot_1_set", "group_good_slot_2_set",
            "shop_group_child_check_shopping_count", "shop_group_child_shopping",
            "shop_group_child_shopping_interface", "shop_group_child_follow_up_shopping",
            intArrayOf(646, 16, 130, 37),
        ),
    )

    /** 获取商店信息：返回可购买商品价格区域坐标 [x,y,w,h]，否则 null（对应原版 get_child_shop_info） */
    private fun getChildShopInfo(context: Pointer?, image: Pointer?, config: ShopConfig): IntArray? {
        if (context == null) return null
        // 1) 读锚点选商品位
        val slot1 = getAnchor(context, config.slot1Anchor)
        val slot2 = if (slot1 == null) getAnchor(context, config.slot2Anchor) else null
        val slot = slot1 ?: slot2 ?: run { println("[AgentMain][Shopping] 未找到商店锚点"); return null }
        val nodeDataKey = if (slot1 != null) config.nodeDataKey1 else config.nodeDataKey2

        // 2) 读购买数量
        val nodeData = getNodeData(context, nodeDataKey) ?: run { println("[AgentMain][Shopping] 节点数据缺失 $nodeDataKey"); return null }
        val count = nodeData.optInt("max_hit", 0)
        println("[AgentMain][Shopping] 购买数量: $count")

        // 3) 覆盖购买数量检查
        MaaFrameworkLib.INSTANCE.MaaContextOverridePipeline(
            context,
            JSONObject().put(config.checkPipeline, JSONObject().put("expected", count.toString())).toString()
        )

        // 4) 识别商品图标（run_recognition(slot)）
        val recoId = MaaFrameworkLib.INSTANCE.MaaContextRunRecognition(context, slot, "{}", image)
        val bestBox = getRecoBox(context, recoId) ?: run { println("[AgentMain][Shopping] 商品图标识别未命中"); return null }

        // 5) 解析限购文本
        val limitRoi = intArrayOf(bestBox[0] + 10, bestBox[1] + 87, 192, 138)
        val limitText = ocrTextAll(context, image, limitRoi)
        println("[AgentMain][Shopping] 限购文本: '$limitText'")
        if (!parseLimitText(limitText, count)) { println("[AgentMain][Shopping] 限购条件不满足"); return null }

        // 6) 货币总数
        val totalText = ocrTextAll(context, image, config.totalRoi) ?: ""
        val w = if (totalText.contains("万")) 10000 else 1
        val totalValue = extractNumber(totalText)?.times(w)

        // 7) 价格（取识别文本）
        val priceRoi = intArrayOf(bestBox[0] + 42, bestBox[1] + 179, 123, 54)
        val priceText = ocrTextAll(context, image, priceRoi)
        val priceValue = extractNumber(priceText)
        if (totalValue == null || priceValue == null) { println("[AgentMain][Shopping] 货币或价格解析失败"); return null }
        if (priceValue <= 40) { println("[AgentMain][Shopping] 价格识别出错,跳过购买"); return null }
        if (totalValue < priceValue * count) { println("[AgentMain][Shopping] 货币不足 需要${priceValue * count} 拥有$totalValue"); return null }
        println("[AgentMain][Shopping] 需要${priceValue * count},拥有$totalValue")

        // 8) 多次购买事务
        if (count > 1) {
            val repeatCount = count - 1
            val nextList = MaaFrameworkLib.INSTANCE.MaaStringListBufferCreate()
            MaaFrameworkLib.INSTANCE.MaaStringListBufferAppend(nextList, config.shoppingInterface)
            MaaFrameworkLib.INSTANCE.MaaStringListBufferAppend(nextList, "[JumpBack]shop_confirm_exchange")
            MaaFrameworkLib.INSTANCE.MaaStringListBufferAppend(nextList, config.followUpShopping)
            MaaFrameworkLib.INSTANCE.MaaStringListBufferAppend(nextList, "shop_swipe_back_for_good")
            MaaFrameworkLib.INSTANCE.MaaContextOverrideNext(context, config.shopping, nextList)
            MaaFrameworkLib.INSTANCE.MaaStringListBufferDestroy(nextList)
            MaaFrameworkLib.INSTANCE.MaaContextOverridePipeline(
                context,
                JSONObject()
                    .put(config.followUpShopping, JSONObject()
                        .put("target", JSONArray().apply { priceRoi.forEach { put(it) } })
                        .put("repeat", repeatCount))
                    .toString()
            )
        }
        return priceRoi
    }

    /** 读取 ROI 数字（无"万"换算，普通计数用） */
    private fun digitFromRoi(context: Pointer?, image: Pointer?, roi: IntArray): Int? {
        val text = ocrText(context, image, roi)?.trim() ?: return null
        val m = Regex("\\d+").find(text.replace(" ", "")) ?: return null
        return m.value.toIntOrNull()
    }

    /** 全文本 OCR（custom_ocr 节点，不限 expected，读限购/货币/价格等完整文本） */
    private fun ocrTextAll(context: Pointer?, image: Pointer?, roi: IntArray?): String? {
        if (context == null || image == null) return null
        return try {
            val override = JSONObject()
                .put("custom_ocr", JSONObject().apply {
                    roi?.let { put("roi", JSONArray().apply { it.forEach { v -> put(v) } }) }
                })
                .toString()
            val recoId = MaaFrameworkLib.INSTANCE.MaaContextRunRecognition(context, "custom_ocr", override, image)
            if (recoId == 0L) return null
            val tasker = MaaFrameworkLib.INSTANCE.MaaContextGetTasker(context) ?: return null
            val detail = getRecoDetail(tasker, recoId) ?: return null
            val best = detail.optJSONObject("best")?.optString("text", null)
            if (!best.isNullOrBlank()) return best
            val arr = detail.optJSONArray("all") ?: detail.optJSONArray("all_results")
            val sb = StringBuilder()
            for (i in 0 until (arr?.length() ?: 0)) {
                sb.append(arr.getJSONObject(i).optString("text", ""))
            }
            sb.toString().ifBlank { null }
        } catch (e: Exception) {
            println("[AgentMain] ocrTextAll error: ${e.message}")
            null
        }
    }

    /** 读识别结果 box（best_result.box） */
    private fun getRecoBox(context: Pointer?, recoId: Long): IntArray? {
        if (context == null || recoId == 0L) return null
        return try {
            val tasker = MaaFrameworkLib.INSTANCE.MaaContextGetTasker(context) ?: return null
            val hit = IntByReference(0)
            val nodeBuf = MaaFrameworkLib.INSTANCE.MaaStringBufferCreate()
            val algBuf = MaaFrameworkLib.INSTANCE.MaaStringBufferCreate()
            val detailBuf = MaaFrameworkLib.INSTANCE.MaaStringBufferCreate()
            val box = MaaFrameworkLib.INSTANCE.MaaRectCreate()
            val ok = MaaFrameworkLib.INSTANCE.MaaTaskerGetRecognitionDetail(tasker, recoId, nodeBuf, algBuf, hit, box, detailBuf, null, null)
            val result = if (ok == 1.toByte() && hit.value != 0) {
                intArrayOf(
                    MaaFrameworkLib.INSTANCE.MaaRectGetX(box), MaaFrameworkLib.INSTANCE.MaaRectGetY(box),
                    MaaFrameworkLib.INSTANCE.MaaRectGetW(box), MaaFrameworkLib.INSTANCE.MaaRectGetH(box)
                )
            } else null
            MaaFrameworkLib.INSTANCE.MaaStringBufferDestroy(nodeBuf)
            MaaFrameworkLib.INSTANCE.MaaStringBufferDestroy(algBuf)
            MaaFrameworkLib.INSTANCE.MaaStringBufferDestroy(detailBuf)
            MaaFrameworkLib.INSTANCE.MaaRectDestroy(box)
            result
        } catch (e: Exception) {
            println("[AgentMain] getRecoBox error: ${e.message}")
            null
        }
    }

    /** 读锚点值（get_anchor） */
    private fun getAnchor(context: Pointer?, anchorName: String): String? {
        if (context == null) return null
        return try {
            val buf = MaaFrameworkLib.INSTANCE.MaaStringBufferCreate()
            val ok = MaaFrameworkLib.INSTANCE.MaaContextGetAnchor(context, anchorName, buf)
            val s = if (ok == 1.toByte()) MaaFrameworkLib.INSTANCE.MaaStringBufferGet(buf)?.takeIf { it.isNotBlank() } else null
            MaaFrameworkLib.INSTANCE.MaaStringBufferDestroy(buf)
            s
        } catch (e: Exception) {
            println("[AgentMain] getAnchor error: ${e.message}")
            null
        }
    }

    /** 读节点数据（get_node_data -> JSON） */
    private fun getNodeData(context: Pointer?, key: String): JSONObject? {
        if (context == null) return null
        return try {
            val buf = MaaFrameworkLib.INSTANCE.MaaStringBufferCreate()
            val ok = MaaFrameworkLib.INSTANCE.MaaContextGetNodeData(context, key, buf)
            val s = if (ok == 1.toByte()) MaaFrameworkLib.INSTANCE.MaaStringBufferGet(buf) else null
            MaaFrameworkLib.INSTANCE.MaaStringBufferDestroy(buf)
            s?.let { runCatching { JSONObject(it) }.getOrNull() }
        } catch (e: Exception) {
            println("[AgentMain] getNodeData error: ${e.message}")
            null
        }
    }

    /** 提取第一个连续数字 */
    private fun extractNumber(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val nums = Regex("\\d+").findAll(text).map { it.value }.toList()
        return if (nums.isNotEmpty()) nums[0].toIntOrNull() else null
    }

    /** 限购文本解析（已拥有/售罄/已购+总量） */
    private fun parseLimitText(limitText: String?, buyCount: Int): Boolean {
        if (limitText.isNullOrBlank()) return false
        if (listOf("已拥有", "售罄", "售馨", "开启").any { limitText.contains(it) }) return false
        val nums = Regex("\\d+").findAll(limitText).map { it.value }.toList()
        if (nums.size >= 2) {
            val bought = nums[0].toIntOrNull() ?: return false
            val total = nums[1].toIntOrNull() ?: return false
            if (bought + buyCount > total) return false
            return true
        }
        return false
    }

    /** 运行 pipeline 节点判断是否命中（IsInNinjaGuide 用） */
    private fun nodeHit(context: Pointer?, image: Pointer?, nodeName: String): Boolean {
        if (context == null || image == null) return false
        return try {
            val recoId = MaaFrameworkLib.INSTANCE.MaaContextRunRecognition(context, nodeName, "{}", image)
            if (recoId == 0L) return false
            val tasker = MaaFrameworkLib.INSTANCE.MaaContextGetTasker(context) ?: return false
            val hit = IntByReference(0)
            val nodeBuf = MaaFrameworkLib.INSTANCE.MaaStringBufferCreate()
            val algBuf = MaaFrameworkLib.INSTANCE.MaaStringBufferCreate()
            val detailBuf = MaaFrameworkLib.INSTANCE.MaaStringBufferCreate()
            val box = MaaFrameworkLib.INSTANCE.MaaRectCreate()
            val ok = MaaFrameworkLib.INSTANCE.MaaTaskerGetRecognitionDetail(tasker, recoId, nodeBuf, algBuf, hit, box, detailBuf, null, null)
            MaaFrameworkLib.INSTANCE.MaaStringBufferDestroy(nodeBuf)
            MaaFrameworkLib.INSTANCE.MaaStringBufferDestroy(algBuf)
            MaaFrameworkLib.INSTANCE.MaaStringBufferDestroy(detailBuf)
            MaaFrameworkLib.INSTANCE.MaaRectDestroy(box)
            ok == 1.toByte() && hit.value != 0
        } catch (e: Exception) {
            println("[AgentMain] nodeHit error: ${e.message}")
            false
        }
    }

    /** 运行 pipeline 节点（带 ROI override）判断是否命中（FlipCard 用） */
    private fun nodeHitRoi(context: Pointer?, image: Pointer?, nodeName: String, roi: IntArray): Boolean {
        if (context == null || image == null) return false
        return try {
            val override = JSONObject()
                .put(nodeName, JSONObject().put("roi", JSONArray().apply { roi.forEach { put(it) } }))
                .toString()
            val recoId = MaaFrameworkLib.INSTANCE.MaaContextRunRecognition(context, nodeName, override, image)
            if (recoId == 0L) return false
            val tasker = MaaFrameworkLib.INSTANCE.MaaContextGetTasker(context) ?: return false
            val hit = IntByReference(0)
            val nodeBuf = MaaFrameworkLib.INSTANCE.MaaStringBufferCreate()
            val algBuf = MaaFrameworkLib.INSTANCE.MaaStringBufferCreate()
            val detailBuf = MaaFrameworkLib.INSTANCE.MaaStringBufferCreate()
            val box = MaaFrameworkLib.INSTANCE.MaaRectCreate()
            val ok = MaaFrameworkLib.INSTANCE.MaaTaskerGetRecognitionDetail(tasker, recoId, nodeBuf, algBuf, hit, box, detailBuf, null, null)
            MaaFrameworkLib.INSTANCE.MaaStringBufferDestroy(nodeBuf)
            MaaFrameworkLib.INSTANCE.MaaStringBufferDestroy(algBuf)
            MaaFrameworkLib.INSTANCE.MaaStringBufferDestroy(detailBuf)
            MaaFrameworkLib.INSTANCE.MaaRectDestroy(box)
            ok == 1.toByte() && hit.value != 0
        } catch (e: Exception) {
            println("[AgentMain] nodeHitRoi error: ${e.message}")
            false
        }
    }

    /** 读取 ROI 翻牌卷/数量（custom_ocr 全文本 -> 第一个数字），对应原版 get_flip_ticket_count */
    private fun flipTicketCount(context: Pointer?, image: Pointer?, roi: IntArray): Int? {
        val text = ocrTextAll(context, image, roi) ?: return null
        return extractNumber(text)
    }

    /** 读取种子数量（解析"剩余:xx/10"格式），对应原版 get_seed_count */
    private fun getSeedCount(context: Pointer?, image: Pointer?, roi: IntArray): Int? {
        val text = ocrTextAll(context, image, roi)?.replace(" ", "") ?: return null
        val prefix = "剩余"
        val idx = text.indexOf(prefix)
        if (idx < 0) return null
        val after = text.substring(idx + prefix.length)
        val colonIdx = after.indexOfFirst { it == ':' || it == '：' }
        if (colonIdx < 0) return null
        val slashIdx = after.indexOf('/', colonIdx)
        if (slashIdx < 0) return null
        return after.substring(colonIdx + 1, slashIdx).toIntOrNull()
    }

    /** 注册 base.py 剩余识别器：翻牌卷 x3 / 任务集会所 / 招财 x2 / 买体力 */
    private fun registerMoreRecognitions(serverLib: MaaAgentServerLib) {
        // 通用翻牌卷>0 判断
        fun ticketReco(roi: IntArray, tag: String): MaaCustomRecognitionCallback = object : MaaCustomRecognitionCallback {
            override fun invoke(context: Pointer?, taskId: Long, nodeName: String?, recoName: String?, recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?, outBox: Pointer?, outDetail: Pointer?): Byte {
                val count = flipTicketCount(context, image, roi)
                if (count != null && count > 0) { setBox(outBox, 0, 0, 1, 1); return 1 }
                println("[AgentMain][$tag] 数量=${count},不通过")
                return 0
            }
        }
        println("[AgentMain] register FindAccessoryFlipTicket -> ${serverLib.MaaAgentServerRegisterCustomRecognition("FindAccessoryFlipTicket", ticketReco(intArrayOf(550, 481, 171, 238), "FindAccessoryFlipTicket"), null)}")
        println("[AgentMain] register FindGearFlipTicket -> ${serverLib.MaaAgentServerRegisterCustomRecognition("FindGearFlipTicket", ticketReco(intArrayOf(436, 483, 138, 236), "FindGearFlipTicket"), null)}")
        println("[AgentMain] register SecretRealmTicket -> ${serverLib.MaaAgentServerRegisterCustomRecognition("SecretRealmTicket", ticketReco(intArrayOf(496, 624, 39, 44), "SecretRealmTicket"), null)}")

        // MissionOfficeStrategy：任务集会所策略 (max-9)*1.5 >= current
        val missionOffice = object : MaaCustomRecognitionCallback {
            override fun invoke(context: Pointer?, taskId: Long, nodeName: String?, recoName: String?, recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?, outBox: Pointer?, outDetail: Pointer?): Byte {
                val maxResource = flipTicketCount(context, image, intArrayOf(1004, 614, 27, 27))
                val currentResource = flipTicketCount(context, image, intArrayOf(1003, 648, 22, 28))
                if (maxResource == null || currentResource == null) return 0
                val condition = (maxResource - 9) * 1.5 >= currentResource
                println("[AgentMain][MissionOfficeStrategy] 刷新上限=$maxResource 可接取=$currentResource condition=$condition")
                if (condition) { setBox(outBox, 0, 0, 1, 1); return 1 }
                return 0
            }
        }
        println("[AgentMain] register MissionOfficeStrategy -> ${serverLib.MaaAgentServerRegisterCustomRecognition("MissionOfficeStrategy", missionOffice, null)}")

        // CheckGetCopperRoll：招财轮次 >= count+1
        val copperRoll = object : MaaCustomRecognitionCallback {
            override fun invoke(context: Pointer?, taskId: Long, nodeName: String?, recoName: String?, recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?, outBox: Pointer?, outDetail: Pointer?): Byte {
                val count = runCatching { JSONObject(recoParam ?: "{}").optInt("count", 1) }.getOrDefault(1)
                val now = flipTicketCount(context, image, intArrayOf(104, 468, 40, 31)) ?: 66
                if (now >= count + 1) { setBox(outBox, 0, 0, 1, 1); return 1 }
                return 0
            }
        }
        println("[AgentMain] register CheckGetCopperRoll -> ${serverLib.MaaAgentServerRegisterCustomRecognition("CheckGetCopperRoll", copperRoll, null)}")

        // CheckGetCopperCount：招财次数 >= count
        val copperCount = object : MaaCustomRecognitionCallback {
            override fun invoke(context: Pointer?, taskId: Long, nodeName: String?, recoName: String?, recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?, outBox: Pointer?, outDetail: Pointer?): Byte {
                val count = runCatching { JSONObject(recoParam ?: "{}").optInt("count", 1) }.getOrDefault(1)
                val now = flipTicketCount(context, image, intArrayOf(309, 468, 27, 30)) ?: 66
                if (now >= count) { setBox(outBox, 0, 0, 1, 1); return 1 }
                return 0
            }
        }
        println("[AgentMain] register CheckGetCopperCount -> ${serverLib.MaaAgentServerRegisterCustomRecognition("CheckGetCopperCount", copperCount, null)}")

        // CheckBuyEnergyCount：购买体力（首次-当前 >= count）
        val buyEnergy = object : MaaCustomRecognitionCallback {
            var startCount = -1
            override fun invoke(context: Pointer?, taskId: Long, nodeName: String?, recoName: String?, recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?, outBox: Pointer?, outDetail: Pointer?): Byte {
                val count = runCatching { JSONObject(recoParam ?: "{}").optInt("count", 1) }.getOrDefault(1)
                val roi = intArrayOf(499, 374, 251, 59)
                if (startCount == -1) { startCount = flipTicketCount(context, image, roi) ?: 0 }
                val now = flipTicketCount(context, image, roi) ?: 0
                if (startCount - now >= count) { setBox(outBox, 0, 0, 1, 1); return 1 }
                return 0
            }
        }
        println("[AgentMain] register CheckBuyEnergyCount -> ${serverLib.MaaAgentServerRegisterCustomRecognition("CheckBuyEnergyCount", buyEnergy, null)}")

        // SwitchAccountFindTargetArea：切换账号找目标区（OCR 匹配 expected）
        val switchAccountFindTargetArea = object : MaaCustomRecognitionCallback {
            override fun invoke(context: Pointer?, taskId: Long, nodeName: String?, recoName: String?, recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?, outBox: Pointer?, outDetail: Pointer?): Byte {
                return try {
                    if (context == null) return 0
                    val param = runCatching { JSONObject(recoParam ?: "{}") }.getOrDefault(JSONObject())
                    val targetArea = param.optString("expected", "521")
                    val recoId = MaaFrameworkLib.INSTANCE.MaaContextRunRecognition(context, "switch_account_target_area_roi", "{}", image)
                    if (recoId == 0L) return 0
                    val tasker = MaaFrameworkLib.INSTANCE.MaaContextGetTasker(context) ?: return 0
                    val detail = getRecoDetail(tasker, recoId) ?: return 0
                    val all = detail.optJSONArray("all") ?: detail.optJSONArray("all_results") ?: return 0
                    for (i in 0 until all.length()) {
                        val boxArr = all.getJSONObject(i).optJSONArray("box") ?: continue
                        if (boxArr.length() < 4) continue
                        val bx = boxArr.getInt(0); val by = boxArr.getInt(1); val bw = boxArr.getInt(2); val bh = boxArr.getInt(3)
                        val areaNum = ocrTextAll(context, image, intArrayOf(bx, by, bw, bh))?.trim()
                        if (areaNum == targetArea) {
                            println("[AgentMain][SwitchAccountFindTargetArea] 找到目标区 $targetArea @($bx,$by,$bw,$bh)")
                            setBox(outBox, bx, by, bw, bh); return 1
                        }
                    }
                    println("[AgentMain][SwitchAccountFindTargetArea] 未找到目标区 $targetArea")
                    return 0
                } catch (e: Throwable) {
                    println("[AgentMain][SwitchAccountFindTargetArea] error: ${e.message}")
                    0
                }
            }
        }
        println("[AgentMain] register SwitchAccountFindTargetArea -> ${serverLib.MaaAgentServerRegisterCustomRecognition("SwitchAccountFindTargetArea", switchAccountFindTargetArea, null)}")
    }

    /** FlipCard：4x4 翻牌游戏（完整移植原版 flip_card.py 贪心算法） */
    private fun registerFlipCard(serverLib: MaaAgentServerLib) {
        val cardRoi = listOf(
            listOf(intArrayOf(206, 94, 145, 109), intArrayOf(357, 94, 145, 111), intArrayOf(508, 94, 148, 111), intArrayOf(661, 94, 145, 111)),
            listOf(intArrayOf(206, 212, 145, 111), intArrayOf(360, 212, 143, 108), intArrayOf(510, 212, 143, 108), intArrayOf(661, 212, 145, 111)),
            listOf(intArrayOf(204, 328, 145, 111), intArrayOf(360, 328, 143, 111), intArrayOf(510, 328, 143, 111), intArrayOf(661, 328, 145, 111)),
            listOf(intArrayOf(206, 447, 143, 111), intArrayOf(357, 444, 145, 111), intArrayOf(510, 447, 143, 111), intArrayOf(661, 447, 145, 111)),
        )
        val tipRoi = intArrayOf(1035, 229, 103, 93)
        val mainDiag = listOf(0 to 0, 1 to 1, 2 to 2, 3 to 3)
        val subDiag = listOf(0 to 3, 1 to 2, 2 to 1, 3 to 0)
        val allDiag = mainDiag + subDiag

        // 识别单张牌：0未翻 1紫 2橙 3失败
        fun cardType(context: Pointer?, image: Pointer?, roi: IntArray): Int {
            if (nodeHitRoi(context, image, "card_0", roi)) return 1
            if (nodeHitRoi(context, image, "card_1", roi)) return 2
            if (nodeHitRoi(context, image, "card_wait", roi)) return 0
            return 3
        }

        // 橙色信息
        data class OrangeInfo(val pos: List<Pair<Int, Int>>, val rows: Set<Int>, val cols: Set<Int>, val diags: Set<String>, val bothDiag: Boolean)

        fun orangeInfo(grid: List<List<Int>>): OrangeInfo {
            val pos = mutableListOf<Pair<Int, Int>>()
            val rows = mutableSetOf<Int>()
            val cols = mutableSetOf<Int>()
            val diags = mutableSetOf<String>()
            for (r in 0..3) for (c in 0..3) {
                if (grid[r][c] == 2) {
                    pos += r to c; rows += r; cols += c
                    if ((r to c) in mainDiag) diags += "main"
                    if ((r to c) in subDiag) diags += "sub"
                }
            }
            return OrangeInfo(pos, rows, cols, diags, "main" in diags && "sub" in diags)
        }

        fun checkVictory(grid: List<List<Int>>): Boolean {
            for (r in 0..3) if (grid[r].count { it == 1 } == 4) return true
            for (c in 0..3) if ((0..3).count { grid[it][c] == 1 } == 4) return true
            if ((0..3).count { grid[it][it] == 1 } == 4) return true
            if ((0..3).count { grid[it][3 - it] == 1 } == 4) return true
            return false
        }

        fun isInitial(grid: List<List<Int>>): Boolean = grid.all { row -> row.all { it == 0 || it == 2 } }

        fun validInitialPos(grid: List<List<Int>>, orange: OrangeInfo): Pair<Int, Int> {
            val unflip = (0..3).flatMap { r -> (0..3).map { c -> r to c } }.filter { (r, c) -> grid[r][c] == 0 }
            if (unflip.isEmpty()) return 0 to 0
            if (orange.bothDiag) {
                val valid = unflip.filter { (r, c) -> r !in orange.rows && c !in orange.cols }
                return valid.firstOrNull() ?: unflip[0]
            }
            val diagUnflip = unflip.filter { it in allDiag }
            if (diagUnflip.isEmpty()) return unflip[0]
            val p1 = diagUnflip.filter { (r, c) -> r !in orange.rows && c !in orange.cols && (r to c) !in (if ("main" in orange.diags) mainDiag else emptyList()) && (r to c) !in (if ("sub" in orange.diags) subDiag else emptyList()) }
            val p2 = diagUnflip.filter { (r, c) -> r !in orange.rows && c !in orange.cols }
            val p3 = diagUnflip.filter { (r, c) -> r in orange.rows || c in orange.cols }
            return p1.firstOrNull() ?: p2.firstOrNull() ?: p3.firstOrNull() ?: diagUnflip[0]
        }

        // 单一方向分数
        data class DirScore(val row: Int, val col: Int, val diag: Int, val max: Int, val dir: String)

        fun dirScore(pos: Pair<Int, Int>, grid: List<List<Int>>, orange: OrangeInfo): DirScore {
            val (r, c) = pos
            val rowScore = if (r !in orange.rows) (0..3).count { grid[r][it] == 1 } else 0
            val colScore = if (c !in orange.cols) (0..3).count { grid[it][c] == 1 } else 0
            var diagScore = 0
            if ((r to c) in mainDiag && "main" !in orange.diags) diagScore = mainDiag.count { grid[it.first][it.second] == 1 }
            if ((r to c) in subDiag && "sub" !in orange.diags) diagScore = maxOf(diagScore, subDiag.count { grid[it.first][it.second] == 1 })
            val max = maxOf(rowScore, colScore, diagScore)
            val dir = when (max) {
                rowScore -> "row"
                colScore -> "col"
                else -> "diag"
            }
            return DirScore(rowScore, colScore, diagScore, max, dir)
        }

        fun bestGrowthPos(grid: List<List<Int>>, orange: OrangeInfo): Pair<Int, Int>? {
            val unflip = (0..3).flatMap { r -> (0..3).map { c -> r to c } }.filter { (r, c) -> grid[r][c] == 0 }
            if (unflip.isEmpty()) return null
            return unflip.map { pos ->
                val s = dirScore(pos, grid, orange)
                val dirP = when (s.dir) { "row" -> 0; "col" -> 1; else -> 2 }
                val isDiag = if (pos in allDiag && !orange.bothDiag) 1 else 0
                Triple(-s.max, dirP, -isDiag) to pos
            }.sortedWith(compareBy({ it.first.first }, { it.first.second }, { it.first.third }, { it.second.first }, { it.second.second })).first().second
        }

        val flipCard = object : MaaCustomRecognitionCallback {
            override fun invoke(context: Pointer?, taskId: Long, nodeName: String?, recoName: String?, recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?, outBox: Pointer?, outDetail: Pointer?): Byte {
                return try {
                    // 识别16张牌
                    val grid = (0..3).map { r -> (0..3).map { c -> cardType(context, image, cardRoi[r][c]) } }
                    println("[AgentMain][FlipCard] 卡牌网格=$grid")
                    // 识别失败 -> 点提示
                    if (grid.any { row -> row.any { it == 3 } }) {
                        println("[AgentMain][FlipCard] 识别失败,点提示")
                        setBox(outBox, tipRoi[0], tipRoi[1], tipRoi[2], tipRoi[3]); return 1
                    }
                    // 胜利 -> 结束
                    if (checkVictory(grid)) { setBox(outBox, 0, 0, 1, 1); return 1 }
                    val orange = orangeInfo(grid)
                    val target: Pair<Int, Int>
                    if (isInitial(grid)) {
                        target = validInitialPos(grid, orange)
                        println("[AgentMain][FlipCard] 初始翻牌 (${target.first + 1},${target.second + 1})")
                    } else {
                        target = bestGrowthPos(grid, orange) ?: run { setBox(outBox, 0, 0, 1, 1); return 1 }
                        println("[AgentMain][FlipCard] 生长翻牌 (${target.first + 1},${target.second + 1})")
                    }
                    val roi = cardRoi[target.first][target.second]
                    setBox(outBox, roi[0], roi[1], roi[2], roi[3])
                    1
                } catch (e: Throwable) {
                    println("[AgentMain][FlipCard] error: ${e.message}")
                    setBox(outBox, 0, 0, 1, 1); 1
                }
            }
        }
        println("[AgentMain] register FlipCard -> ${serverLib.MaaAgentServerRegisterCustomRecognition("FlipCard", flipCard, null)}")
    }

    /** 注册常用识别器：IsInNinjaGuide / FlipCard / Shopping / FindPlantableFlower / FindBondsWithoutEnoughToken */
    private fun registerCommonRecognitions(serverLib: MaaAgentServerLib) {
        // IsInNinjaGuide：是否在忍界引导界面
        val isInNinjaGuide = object : MaaCustomRecognitionCallback {
            override fun invoke(context: Pointer?, taskId: Long, nodeName: String?, recoName: String?, recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?, outBox: Pointer?, outDetail: Pointer?): Byte {
                return if (nodeHit(context, image, "in_ninja_guide")) {
                    setBox(outBox, 0, 0, 1, 1); 1
                } else 0
            }
        }
        println("[AgentMain] register IsInNinjaGuide -> ${serverLib.MaaAgentServerRegisterCustomRecognition("IsInNinjaGuide", isInNinjaGuide, null)}")

        // FlipCard：翻牌界面宽松命中（原版简化）
        val flipCard = object : MaaCustomRecognitionCallback {
            override fun invoke(context: Pointer?, taskId: Long, nodeName: String?, recoName: String?, recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?, outBox: Pointer?, outDetail: Pointer?): Byte {
                setBox(outBox, 0, 0, 1, 1); return 1
            }
        }
        println("[AgentMain] register FlipCard -> ${serverLib.MaaAgentServerRegisterCustomRecognition("FlipCard", flipCard, null)}")

        // Shopping：商店兑换（完整移植原版 shopping.py：读锚点/节点数据/限购/货币/价格判断购买）
        val shopping = object : MaaCustomRecognitionCallback {
            override fun invoke(context: Pointer?, taskId: Long, nodeName: String?, recoName: String?, recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?, outBox: Pointer?, outDetail: Pointer?): Byte {
                return try {
                    if (context == null) { setBox(outBox, 0, 0, 1, 1); return 1 }
                    MaaFrameworkLib.INSTANCE.MaaContextClearHitCount(context, "shop_swipe_back_for_good")
                    val param = runCatching { JSONObject(recoParam ?: "{}") }.getOrDefault(JSONObject())
                    val shopType = param.optString("shop_type", "root_shop")
                    val config = SHOP_CONFIGS[shopType]
                    if (config == null) { println("[AgentMain][Shopping] 暂不支持 shop_type=$shopType"); return 0 }
                    val target = getChildShopInfo(context, image, config)
                    if (target == null) { println("[AgentMain][Shopping] 未找到可购买商品"); return 0 }
                    println("[AgentMain][Shopping] 点击位置[${target[0]},${target[1]},${target[2]},${target[3]}]")
                    setBox(outBox, target[0], target[1], target[2], target[3])
                    1
                } catch (e: Throwable) {
                    println("[AgentMain][Shopping] error: ${e.message}")
                    setBox(outBox, 0, 0, 1, 1); 1
                }
            }
        }
        println("[AgentMain] register Shopping -> ${serverLib.MaaAgentServerRegisterCustomRecognition("Shopping", shopping, null)}")

        // FindPlantableFlower：花店找可种的花（种子≥10）
        val findPlantableFlower = object : MaaCustomRecognitionCallback {
            override fun invoke(context: Pointer?, taskId: Long, nodeName: String?, recoName: String?, recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?, outBox: Pointer?, outDetail: Pointer?): Byte {
                val configs = listOf(
                    intArrayOf(400, 355, 111, 32) to intArrayOf(440, 298, 37, 41),
                    intArrayOf(509, 355, 103, 29) to intArrayOf(543, 298, 29, 27),
                    intArrayOf(607, 355, 106, 27) to intArrayOf(642, 295, 34, 34),
                    intArrayOf(711, 355, 103, 32) to intArrayOf(749, 300, 29, 29),
                    intArrayOf(810, 256, 143, 140) to intArrayOf(844, 298, 37, 34),
                )
                for ((seedRoi, btnRoi) in configs) {
                    val count = getSeedCount(context, image, seedRoi) ?: continue
                    if (count >= 10) { setBox(outBox, btnRoi[0], btnRoi[1], btnRoi[2], btnRoi[3]); return 1 }
                }
                setBox(outBox, 0, 0, 1, 1); return 1
            }
        }
        println("[AgentMain] register FindPlantableFlower -> ${serverLib.MaaAgentServerRegisterCustomRecognition("FindPlantableFlower", findPlantableFlower, null)}")

        // FindBondsWithoutEnoughToken：羁绊币 < 5 则通过
        val findBonds = object : MaaCustomRecognitionCallback {
            override fun invoke(context: Pointer?, taskId: Long, nodeName: String?, recoName: String?, recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?, outBox: Pointer?, outDetail: Pointer?): Byte {
                val token = digitFromRoi(context, image, intArrayOf(846, 639, 111, 80))
                if (token == null || token >= 5) return 0
                setBox(outBox, 0, 0, 1, 1); return 1
            }
        }
        println("[AgentMain] register FindBondsWithoutEnoughToken -> ${serverLib.MaaAgentServerRegisterCustomRecognition("FindBondsWithoutEnoughToken", findBonds, null)}")
    }

    // ==================== 决斗场预设页签 OCR 确认（agent 安全执行） ====================

/** 注册 PresetTabOCR：OCR 识别预设N页签文字，确认存在才命中（防止点错页签）。
 * 注意：agent 回调的 recoParam 可能不含 custom_recognition_param，因此预设编号从节点名解析，
 * roi/expected 用代码内校准表（用户自定义文字可通过 param.expected 覆盖，缺省用数字）。
 */
private fun registerPresetTabOCR(serverLib: MaaAgentServerLib) {
    // 各预设页签 OCR ROI（用户 MPE 校准，1280x720）
    val presetRois = mapOf(
        1 to intArrayOf(800, 94, 115, 62),
        2 to intArrayOf(943, 94, 108, 62),
        3 to intArrayOf(1040, 94, 108, 62),
        4 to intArrayOf(1121, 94, 108, 62),
    )
    val presetTabOcr = object : MaaCustomRecognitionCallback {
        override fun invoke(context: Pointer?, taskId: Long, nodeName: String?, recoName: String?, recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?, outBox: Pointer?, outDetail: Pointer?): Byte {
            return try {
                if (context == null) { setBox(outBox, 0, 0, 1, 1); return 1 }
                // 从节点名解析预设编号：切换到预设3识图和点击位置 -> 3
                val n = Regex("切换到预设(\\d+)").find(nodeName ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val roi = presetRois[n] ?: run { println("[AgentMain][PresetTabOCR] 预设$n 无ROI配置"); return 0 }
                // expected 优先用 param（用户自定义文字），缺省用数字
                val nodeCfg = runCatching { JSONObject(recoParam ?: "{}") }.getOrDefault(JSONObject())
                val p = nodeCfg.optJSONObject("custom_recognition_param")
                val expected = p?.optString("expected", n.toString())?.takeIf { it.isNotBlank() } ?: n.toString()
                val text = ocrTextAll(context, image, roi)
                println("[AgentMain][PresetTabOCR] node=$nodeName 预设$n 期望'$expected' roi=${roi.contentToString()} text='$text'")
                if (text != null && text.isNotEmpty() && text.contains(expected)) {
                    println("[AgentMain][PresetTabOCR] OCR确认预设$n 页签存在，允许点击")
                    setBox(outBox, 0, 0, 1, 1)
                    return 1
                }
                println("[AgentMain][PresetTabOCR] 未识别到预设$n 文字'$expected'，不点击(防点错)")
                0
            } catch (e: Throwable) {
                println("[AgentMain][PresetTabOCR] error: ${e.message}")
                0
            }
        }
    }
    println("[AgentMain] register PresetTabOCR -> ${serverLib.MaaAgentServerRegisterCustomRecognition("PresetTabOCR", presetTabOcr, null)}")
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

    /** 解析"预设N"字符串为数字 N（1-4），非法值返回默认 */
    private fun parsePresetName(name: String, def: Int): Int {
        val m = Regex("(\\d+)").find(name) ?: return def
        return m.groupValues[1].toIntOrNull()?.coerceIn(1, 4) ?: def
    }

    /**
     * 在目标预设页签的 OCR ROI 中识别对应文字（custom_ocr 全文本 -> 匹配 name）。
     * agent 内走 ZMQ 转发不死锁；找不到返回 null（调用方用坐标兜底）。
     */
    private fun findPresetTabByOcr(context: Pointer?, image: Pointer?, n: Int, name: String): IntArray? {
        if (context == null || image == null) return null
        val roi = presetTabOcrRois[n] ?: return null
        return try {
            val override = JSONObject()
                .put("custom_ocr", JSONObject().put("roi", JSONArray().apply { roi.forEach { put(it) } }))
                .toString()
            val recoId = MaaFrameworkLib.INSTANCE.MaaContextRunRecognition(context, "custom_ocr", override, image)
            if (recoId == 0L) return null
            val tasker = MaaFrameworkLib.INSTANCE.MaaContextGetTasker(context) ?: return null
            val detail = getRecoDetail(tasker, recoId) ?: return null
            val arr = detail.optJSONArray("all") ?: detail.optJSONArray("all_results") ?: detail.optJSONArray("results")
            val text = StringBuilder()
            for (i in 0 until (arr?.length() ?: 0)) {
                text.append(arr.getJSONObject(i).optString("text", ""))
            }
            if (text.isNotEmpty() && text.contains(name)) {
                // OCR 确认目标页签文字存在，返回用户校准的点击坐标
                println("[AgentMain][PresetDecision] OCR 确认预设$n 文字'$name'存在 roi=${roi.contentToString()} text='$text'")
                presetTabBoxes[n]
            } else {
                println("[AgentMain][PresetDecision] OCR 未识别到预设$n 文字'$name' text='$text',将使用坐标兜底")
                null
            }
        } catch (e: Exception) {
            println("[AgentMain] findPresetTabByOcr error: ${e.message}")
            null
        }
    }

    /** 注册 PresetDecision：决策本局要用的预设页签（锁定阵容前切换），OCR 数字定位 + 坐标兜底 */
    private fun registerPresetDecision(serverLib: MaaAgentServerLib) {
        val presetDecision = object : MaaCustomRecognitionCallback {
            override fun invoke(context: Pointer?, taskId: Long, nodeName: String?, recoName: String?, recoParam: String?, image: Pointer?, box: Pointer?, transArg: Pointer?, outBox: Pointer?, outDetail: Pointer?): Byte {
                return try {
                    if (context == null) { setBox(outBox, 0, 0, 1, 1); return 1 }
                    // agent 回调的 recoParam 为节点基础配置（不含引擎运行时 override），
                    // PresetTargetWriter 用 MaaContextOverridePipeline 注入的 target_preset 需通过
                    // MaaContextGetNodeData 读取（返回 override 后的节点数据）
                    val nodeCfg = runCatching { JSONObject(recoParam ?: "{}") }.getOrDefault(JSONObject())
                    var p = nodeCfg.optJSONObject("custom_recognition_param") ?: JSONObject()
                    val nodeData = getNodeData(context, "pvp_weekly_win_preset_decision")
                    val dataCp = nodeData?.optJSONObject("custom_recognition_param")
                    if (dataCp != null && dataCp.has("target_preset")) {
                        p = dataCp
                        println("[AgentMain][PresetDecision] 通过节点数据读取到 target_preset=${dataCp.optInt("target_preset", -1)}")
                    }
                    // 优先用引擎 PresetTargetWriter 动态注入的 target_preset（决策在引擎进程完成）
                    var target = p.optInt("target_preset", -1)
                    if (target !in 1..4) {
                        val mode = p.optString("mode", "lose")
                        target = when (mode) {
                            "rotate" -> 1
                            else -> {
                                val maxHit = p.optInt("max_hit", 5)
                                val presetWin = parsePresetName(p.optString("preset_win", "预设1"), 1)
                                val presetLose = parsePresetName(p.optString("preset_lose", "预设2"), 2)
                                presetLose
                            }
                        }
                    }
                    // 读取用户自定义页签文字（默认 1/2/3/4）
                    val names = mutableListOf("1", "2", "3", "4")
                    val namesArr = p.optJSONArray("preset_names")
                    if (namesArr != null && namesArr.length() >= 4) {
                        for (i in 0..3) {
                            val v = namesArr.optString(i, names[i]).trim()
                            if (v.isNotEmpty()) names[i] = v
                        }
                    }
                    val tabName = names[target - 1]
                    println("[AgentMain][PresetDecision] node=$nodeName target_preset=$target 页签文字='$tabName'")
                    val box = findPresetTabByOcr(context, image, target, tabName) ?: presetTabBoxes[target]
                    if (box == null) {
                        println("[AgentMain][PresetDecision] 预设$target 页签坐标缺失")
                        return 0
                    }
                    setBox(outBox, box[0], box[1], box[2], box[3])
                    println("[AgentMain][PresetDecision] 返回页签 box=(${box[0]},${box[1]},${box[2]},${box[3]})")
                    1
                } catch (e: Throwable) {
                    println("[AgentMain][PresetDecision] error: ${e.message}")
                    setBox(outBox, 0, 0, 1, 1); 1
                }
            }
        }
        println("[AgentMain] register PresetDecision -> ${serverLib.MaaAgentServerRegisterCustomRecognition("PresetDecision", presetDecision, null)}")
    }
}