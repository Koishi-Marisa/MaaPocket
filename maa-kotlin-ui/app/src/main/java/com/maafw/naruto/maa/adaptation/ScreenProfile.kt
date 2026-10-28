package com.maafw.naruto.maa.adaptation

import java.util.Locale

/**
 * 当前虚拟屏的坐标空间。
 *
 * 资源包里的模板与 roi 都按 1280×720（MaaFramework 的 720p 约定）采集。虚拟屏
 * 分辨率可由设置页切到 1080p（1920×1080），这时同一块 UI 元素的像素坐标会等比放大，
 * 直接拿 720p 的 roi 去匹配 1080p 画面必然失败。
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
        const val BASELINE_WIDTH = 1280
        const val BASELINE_HEIGHT = 720
    }
}
