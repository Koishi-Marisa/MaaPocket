package com.maafw.naruto.maa.adaptation

/**
 * 把 pipeline 里按 720p 基线写的 `[x, y, w, h]` 等比换算到当前虚拟屏坐标空间。
 *
 * `[0, 0, 0, 0]`（全屏）原样返回：MaaFramework 对它的语义是「不限制 ROI」，
 * 缩放成一个具体的矩形反而会缩小搜索范围。
 */
object RoiScaler {

    fun isFullScreen(roi: IntArray): Boolean =
        roi.size != 4 || roi[2] <= 0 || roi[3] <= 0

    fun scale(roi: IntArray, profile: ScreenProfile): IntArray {
        if (isFullScreen(roi) || profile.isBaseline) return roi
        val x = (roi[0] * profile.scaleX).toInt().coerceIn(0, profile.width)
        val y = (roi[1] * profile.scaleY).toInt().coerceIn(0, profile.height)
        val w = (roi[2] * profile.scaleX).toInt().coerceAtLeast(1)
        val h = (roi[3] * profile.scaleY).toInt().coerceAtLeast(1)
        return intArrayOf(
            x,
            y,
            w.coerceAtMost(profile.width - x),
            h.coerceAtMost(profile.height - y),
        )
    }

}
