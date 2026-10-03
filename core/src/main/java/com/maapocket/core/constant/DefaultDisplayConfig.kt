package com.maapocket.core.constant

/**
 * 默认允许的一些宽高配置
 */
object DefaultDisplayConfig {
    const val VD_NAME = "MAA_VD"
    const val DISPLAY_NONE = -1

    // 兜底分辨率：只有「还没解包 / 资源包没声明」时才会用到。
    //
    // 真正生效的值来自资源包自己的 PI `controller.display_*` 字段，由
    // `com.maapocket.core.pi.PiDisplaySize` 解析：崩铁 1920x1080（`display_long_side: 1920`）、
    // 绝区零 1920x1080（`display_short_side: 1080`）、终末地 1280x720（无声明 → 规范默认短边 720）。
    //
    // 为什么**不能**对所有游戏写死同一个值：资源包里的模板图与 roi 都是按某个分辨率采集的
    // （崩铁/绝区零是 1920x1080 的 PC 截图，终末地是 1280x720），虚拟屏必须正好等于它，
    // 模板才是 1:1 匹配。星穹铁道的 UI 按**设计宽度 1920** 线性缩放：实机 2664x1200 上
    // 「点击进入」四个字量到约 140x37 px，而模板 `screen/click_enter.png` 是 101x27 ——
    // 140/101 ≈ 1.387 ≈ 2664/1920。用离线 NCC 复算（`_research/vmatch.py`）：同一张实机帧上，
    // 模板放大到 1.3875 时匹配分 0.88 且落点正是「点击进入」；1.0 只有 0.31。
    // 1280 宽下缩放比是 0.667，崩铁整包模板全部失配，表现就是启动任务卡在
    // ClickEnter（阈值 0.9）/ NavTo_main（20 个模板最高 0.639）一路 Recognition.Failed。
    const val WIDTH = 1920
    const val HEIGHT = 1080
    const val DPI = 240

    const val ASPECT_RATIO_WIDTH = 16
    const val ASPECT_RATIO_HEIGHT = 9

    /** 16:9 宽高比 */
    val ASPECT_RATIO: Float get() = WIDTH.toFloat() / HEIGHT

    const val FRAME_INTERVAL_MS = 16L

    data class Resolution(val width: Int, val height: Int, val dpi: Int)

    val RES_720P = Resolution(1280, 720, 160)
    val RES_1080P = Resolution(1920, 1080, 240)

    /** 用户可选的后台虚拟屏分辨率偏好 */
    enum class ResolutionPreference { P720, P1080 }

    /**
     * 根据用户偏好 + clientType 解析最终分辨率。
     * YoStarEN 静默强制 1080p；其他客户端使用用户偏好。
     */
    fun resolveResolution(
        clientType: String,
        preference: ResolutionPreference
    ): Resolution {
        if (clientType == "YoStarEN") return RES_1080P
        return when (preference) {
            ResolutionPreference.P720 -> RES_720P
            ResolutionPreference.P1080 -> RES_1080P
        }
    }
}
