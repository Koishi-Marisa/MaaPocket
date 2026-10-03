package com.maapocket.core.pi

import com.maapocket.core.constant.DefaultDisplayConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * PI-V2 的屏幕适配字段 → 虚拟屏该建多大。
 *
 * PI 的 `controller` 里允许声明「默认缩放分辨率」，规范原文
 * （`docs/en_us/3.3-ProjectInterfaceV2.md:164-174`，也在 `tools/interface.schema.json`）：
 *
 *  - `display_short_side`  number  默认缩放分辨率的**短边**，用于屏幕适配。可选，**默认 720**。
 *                                  与 `display_long_side` / `display_expand` / `display_raw` 互斥。
 *  - `display_long_side`   number  默认缩放分辨率的**长边**。可选。
 *  - `display_expand`      [w, h]  Unity Canvas Scaler 的 Expand 参考分辨率。
 *  - `display_raw`         bool    截图不做缩放、直接用原始分辨率。可选，默认 false。
 *
 * 这三个字段是本项目「虚拟屏该开多大」的**唯一权威来源**：资源包里的模板图与 roi 是按
 * 某个分辨率采集的，虚拟屏必须正好等于它，模板才是 1:1 匹配。
 *
 * 实测三个包各自的声明与真实采集分辨率一致：
 *  - 崩铁 `display_long_side: 1920`  → 1920x1080（模板 `screen/click_enter.png` 101x27，
 *    实机 2664 宽下「点击进入」约 140 px，140/101 ≈ 1.387 ≈ 2664/1920，见
 *    `DefaultDisplayConfig` 的注释与 `_research/vmatch.py`）
 *  - 绝区零 `display_short_side: 1080` → 1920x1080（全部 roi 里 579 个 x+w > 1280）
 *  - 终末地**没有任何 display 字段** → 规范默认短边 720 → 1280x720
 *    （1921 个 roi 里只有 3 个越界，`tasks/CloseGamePC.json` 的 PC 分辨率默认值也是 1280/720）
 *
 * 所以旧代码「所有游戏一律 1280x720」是错的：它让崩铁/绝区零的整包模板按 0.667 缩放，
 * 表现就是 `ClickEnter`（阈值 0.9）与 `NavTo_main` 的 20 个模板一路 `Recognition.Failed`。
 */
object PiDisplaySize {

    /** 规范默认：短边 720。 */
    const val DEFAULT_SHORT_SIDE = 720

    /**
     * 算出某个 controller 声明的分辨率。返回 null 表示「这个包没声明，交给
     * [DefaultDisplayConfig] 兜底」——只有 `display_raw` 会走到这里。
     */
    fun of(controller: PiController?): DefaultDisplayConfig.Resolution? {
        if (controller == null) return null
        // display_raw：截图不缩放，直接原始分辨率。我们没法把它映射成虚拟屏尺寸。
        if (bool(controller.displayRaw) == true) return null

        expand(controller.displayExpand)?.let { (w, h) ->
            if (w > 0 && h > 0) return resolution(w, h)
        }
        shortSide(controller.displayShortSide)?.let { s ->
            if (s > 0) return fromShortSide(s)
        }
        longSide(controller.displayLongSide)?.let { l ->
            if (l > 0) return fromLongSide(l)
        }
        // 规范说 display_short_side 默认 720。
        return fromShortSide(DEFAULT_SHORT_SIDE)
    }

    /** 先按名字在包自己的 controller 列表里找，找不到就退回列表第一个（与 UI 的选择逻辑一致）。 */
    fun of(repo: PiRepository, controllerName: String): DefaultDisplayConfig.Resolution? {
        val hit = repo.controllers().firstOrNull { it.name == controllerName }
            ?: repo.onDeviceControllers().firstOrNull { it.name == controllerName }
            ?: repo.controllers().firstOrNull()
        return of(hit)
    }

    private fun fromShortSide(short: Int): DefaultDisplayConfig.Resolution {
        val long = (short.toLong() * DefaultDisplayConfig.ASPECT_RATIO_WIDTH /
            DefaultDisplayConfig.ASPECT_RATIO_HEIGHT).toInt()
        return resolution(long, short)
    }

    private fun fromLongSide(long: Int): DefaultDisplayConfig.Resolution {
        val short = (long.toLong() * DefaultDisplayConfig.ASPECT_RATIO_HEIGHT /
            DefaultDisplayConfig.ASPECT_RATIO_WIDTH).toInt()
        return resolution(long, short)
    }

    /**
     * dpi 按「短边 720 → 160、1080 → 240」线性给，和已有的 `RES_720P` / `RES_1080P` 对齐。
     * 它只影响虚拟屏的 density，进而影响游戏自己的 UI 缩放；写死 160 会让 1080p 屏上的
     * 游戏把 UI 画得比资源包采集时更大。
     */
    private fun resolution(width: Int, height: Int): DefaultDisplayConfig.Resolution {
        val short = minOf(width, height)
        val dpi = (short.toLong() * 2 / 9).toInt().coerceAtLeast(80)
        return DefaultDisplayConfig.Resolution(width, height, dpi)
    }

    private fun expand(el: JsonElement?): Pair<Int, Int>? {
        val arr = el as? JsonArray ?: return null
        if (arr.size != 2) return null
        val w = number(arr[0]) ?: return null
        val h = number(arr[1]) ?: return null
        return w to h
    }

    private fun shortSide(el: JsonElement?): Int? = number(el)

    private fun longSide(el: JsonElement?): Int? = number(el)

    private fun number(el: JsonElement?): Int? =
        (el as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

    private fun bool(el: JsonElement?): Boolean? =
        (el as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.let {
            when (it) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> null
            }
        }
}
