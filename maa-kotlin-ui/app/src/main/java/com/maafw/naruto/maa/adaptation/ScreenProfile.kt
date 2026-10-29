package com.maafw.naruto.maa.adaptation

import java.util.Locale

/**
 * 当前虚拟屏的坐标空间。
 *
 * 当前资源包直接复用原 PC 项目的 1920×1080 模板与 roi，因此基线是 1080p。
 * 虚拟屏分辨率由设置页决定；若用户切到 720p，roi 会按比例缩放，但模板本身
 * 仍是 1920 尺度（不会重采样），识别会失败——所以 PC 模板直用必须选 1080p。
 *
 * 这个类只做「换算」这一件事，不碰引擎，也不改变 MaaKotlinUI 的虚拟屏模型。
 */
data class ScreenProfile(
    val width: Int,
    val height: Int,
    val dpi: Int = 160,
    val baselineWidth: Int = BASELINE_WIDTH,
    val baselineHeight: Int = BASELINE_HEIGHT,
) {

    val scaleX: Float get() = width.toFloat() / baselineWidth
    val scaleY: Float get() = height.toFloat() / baselineHeight

    val isBaseline: Boolean get() = width == baselineWidth && height == baselineHeight

    /** 资源包里的模板集目录名：720p / 1080p。 */
    val setKey: String
        get() = when {
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            else -> "${height}p"
        }

    override fun toString(): String = String.format(
        Locale.US,
        "%dx%d@%ddpi (scale=%.3f/%.3f, set=%s)",
        width, height, dpi, scaleX, scaleY, setKey,
    )

    companion object {
        const val BASELINE_WIDTH = 1920
        const val BASELINE_HEIGHT = 1080
    }
}
