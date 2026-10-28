package com.maafw.naruto.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * MAAFW 屏保遮罩。
 *
 * 后台挂机时的防烧屏/防偷看遮罩：
 * - 任务运行中显示半透明黑色遮罩，遮住游戏画面（OLED 防烧屏、防旁人偷看）；
 * - 附带一行小字说明（"任务运行中，点击返回查看"），点按即可移出（防误以为卡死）；
 * - 任务结束/关屏时自动隐藏；与悬浮球共用悬浮窗权限。
 */
object MaaFwScreenSaver {

    private var wm: WindowManager? = null
    private var overlayView: View? = null

    fun isShowing(): Boolean = overlayView != null

    /** 显示遮罩（需悬浮窗权限） */
    fun show(context: Context) {
        if (overlayView != null) return
        if (!MaaFwFloatingControl.canShow(context)) return
        val ctx = context.applicationContext
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val overlay = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xF0000000.toInt()) // 全屏近黑
            }
            // 提示文字
            addView(TextView(ctx).apply {
                text = "任务运行中 · 点按屏幕返回"
                setTextColor(0x99FFFFFF.toInt())
                textSize = 14f
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        // 点按任意位置 → 移出遮罩（回到预览/悬浮控制）
        overlay.setOnClickListener { hide() }
        runCatching { wm?.addView(overlay, params) }.onFailure { return }
        overlayView = overlay
    }

    /** 隐藏遮罩 */
    fun hide() {
        overlayView?.let { runCatching { wm?.removeView(it) } }
        overlayView = null
        wm = null
    }
}