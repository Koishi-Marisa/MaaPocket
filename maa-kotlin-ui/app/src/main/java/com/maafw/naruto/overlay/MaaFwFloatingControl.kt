package com.maafw.naruto.overlay

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.maafw.naruto.IRemoteEngineService

/**
 * MAAFW 悬浮球控制（MaaFW 专属命名与风格）。
 *
 * 相比常规悬浮球更专业的设计：
 * - 悬浮球：品牌色渐变 + 状态指示点（运行绿/暂停橙/待机灰）+ 阴影 + 拖动自动吸附屏幕边缘 + 位置记忆；
 * - 控制面板：深色半透明毛玻璃质感、圆角边框、引擎状态行（含当前任务与运行时长）、两排快捷操作；
 * - 特色：迷你/完整两级面板切换、按钮按引擎状态智能禁用、静音开关状态记忆。
 */
object MaaFwFloatingControl {

    private const val TAG = "MaaFwFloatingControl"
    private const val PREF_NAME = "maa_floating"
    private const val PREF_BALL_X = "ball_x"
    private const val PREF_BALL_Y = "ball_y"

    // 状态色
    private const val COLOR_RUNNING = 0xFF4CAF50.toInt()
    private const val COLOR_PAUSED = 0xFFFFB74D.toInt()
    private const val COLOR_IDLE = 0xFF90A4AE.toInt()
    private const val COLOR_ACCENT = 0xFF7C7CF0.toInt()
    private const val COLOR_BG = 0xF51E1E30.toInt()

    private var wm: WindowManager? = null
    private var ballView: View? = null
    private var panelView: View? = null
    private var ballDot: View? = null
    private var statusTitle: TextView? = null
    private var statusDetail: TextView? = null
    private var timerText: TextView? = null
    private var engineRef: IRemoteEngineService? = null
    private var muted = false
    private var miniMode = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private var runStartMs = 0L
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)
    fun isShowing(): Boolean = ballView != null

    /** 显示悬浮球（引擎引用可空，连接后 updateEngine 自动生效） */
    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, engine: IRemoteEngineService?) {
        if (ballView != null) return
        if (!canShow(context)) return
        engineRef = engine
        val ctx = context.applicationContext
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // ── 悬浮球：渐变圆 + 状态点 + 阴影 ──
        val ballSize = dp(ctx, 56)
        val ball = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_BG)
                setStroke(2, COLOR_ACCENT)
            }
            // 品牌文字
            val label = TextView(ctx).apply {
                text = "MAA"
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
            }
            addView(label, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            // 状态指示点（右上角）
            val dot = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(COLOR_IDLE)
                }
            }
            ballDot = dot
            val dotSize = dp(ctx, 12)
            val dotLp = FrameLayout.LayoutParams(dotSize, dotSize)
            dotLp.gravity = Gravity.TOP or Gravity.END
            dotLp.topMargin = dp(ctx, 2); dotLp.rightMargin = dp(ctx, 2)
            addView(dot, dotLp)
            elevation = dp(ctx, 8).toFloat()
        }
        val savedX = prefs.getInt(PREF_BALL_X, dp(ctx, 16))
        val savedY = prefs.getInt(PREF_BALL_Y, dp(ctx, 200))
        val ballParams = WindowManager.LayoutParams(
            ballSize, ballSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX; y = savedY
        }
        ball.setOnTouchListener(createDragListener(ctx, ballParams))
        ball.setOnClickListener {
            if (panelView != null) hidePanel() else showPanel(ctx)
        }
        runCatching { wm?.addView(ball, ballParams) }.onFailure { return }
        ballView = ball

        // ── 控制面板（默认隐藏，点击悬浮球展开） ──
        panelView = buildPanel(ctx)
        hidePanel()

        refreshRunnable = object : Runnable {
            override fun run() {
                refreshStatus()
                uiHandler.postDelayed(this, 800)
            }
        }
        uiHandler.post(refreshRunnable!!)
    }

    fun hidePanel() {
        panelView?.let { runCatching { wm?.removeView(it) } }
        panelView = null
    }

    fun dismiss() {
        refreshRunnable?.let { uiHandler.removeCallbacks(it) }
        refreshRunnable = null
        hidePanel()
        ballView?.let { runCatching { wm?.removeView(it) } }
        ballView = null
        wm = null
        engineRef = null
    }

    fun updateEngine(engine: IRemoteEngineService?) {
        engineRef = engine
        // 引擎重连后，面板已展开则刷新一次
        if (panelView != null) refreshStatus()
    }

    // ───────────── 面板构建（专业美观） ─────────────

    private fun buildPanel(ctx: Context): LinearLayout {
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, 18).toFloat()
                setColor(COLOR_BG)
                setStroke(2, COLOR_ACCENT)
            }
            elevation = dp(ctx, 16).toFloat()
        }
        // 头部：标题 + 迷你切换
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = "MAAFW 悬浮控制"
            setTextColor(Color.WHITE)
            textSize = 15f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(makeSmallButton(ctx, "迷你") { toggleMini(ctx) })
        panel.addView(header)

        // 状态行：标题 + 详情
        statusTitle = TextView(ctx).apply {
            text = "引擎待机"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(ctx, 8), 0, 0)
        }
        statusDetail = TextView(ctx).apply {
            text = "连接引擎后可用"
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 12f
            setPadding(0, dp(ctx, 2), 0, 0)
        }
        timerText = TextView(ctx).apply {
            text = ""
            setTextColor(0xFF90A4AE.toInt())
            textSize = 11f
            setPadding(0, dp(ctx, 2), 0, 0)
        }
        panel.addView(statusTitle)
        panel.addView(statusDetail)
        panel.addView(timerText)

        // 按钮组：任务控制 + 显示控制（两行，每行 3 个）
        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(makeActionButton(ctx, "开始", 0xFF43A047.toInt()) { doAction { it.startTasksJson(defaultTasks()) } })
        row1.addView(makeActionButton(ctx, "停止", 0xFFE53935.toInt()) { doAction { it.stopTask() } })
        row1.addView(makeActionButton(ctx, "暂停", 0xFFFB8C00.toInt()) { doAction { it.pauseTask() } })
        panel.addView(row1)

        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(makeActionButton(ctx, "关屏", 0xFF1E88E5.toInt()) { doAction { it.setDisplayPower(false) } })
        row2.addView(makeActionButton(ctx, "亮屏", 0xFF1E88E5.toInt()) { doAction { it.setDisplayPower(true) } })
        row2.addView(makeActionButton(ctx, if (muted) "静音开" else "静音", 0xFF8E24AA.toInt()) {
            doAction { runCatching { it.setAudioMuted(!muted) }; muted = !muted; refreshStatus() }
        })
        panel.addView(row2)

        // 底部：关闭悬浮窗
        panel.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(makeSmallButton(ctx, "关闭悬浮窗") { dismiss() })
        })

        return panel
    }

    private fun makeActionButton(ctx: Context, text: String, color: Int, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, 10).toFloat()
                setColor(color)
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1f).apply {
                marginStart = dp(ctx, 3); marginEnd = dp(ctx, 3)
                topMargin = dp(ctx, 10)
            }
        }
    }

    private fun makeSmallButton(ctx: Context, text: String, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            this.text = text
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, 8).toFloat()
                setColor(0x33000000)
                setStroke(1, 0x667C7CF0.toInt())
            }
            setOnClickListener { onClick() }
        }
    }

    private fun showPanel(ctx: Context) {
        if (panelView == null) return
        val ballLp = ballView?.layoutParams as? WindowManager.LayoutParams
        val params = WindowManager.LayoutParams(
            dp(ctx, if (miniMode) 170 else 250),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballLp?.x ?: dp(ctx, 16)
            y = (ballLp?.y ?: dp(ctx, 200)) + dp(ctx, 64)
        }
        runCatching { wm?.addView(panelView, params) }
    }

    private fun toggleMini(ctx: Context) {
        miniMode = !miniMode
        if (panelView != null) {
            hidePanel()
            showPanel(ctx)
        }
    }

    // ───────────── 拖动吸附 + 位置记忆 ─────────────

    private fun createDragListener(ctx: Context, params: WindowManager.LayoutParams): View.OnTouchListener {
        return View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragOffsetX = event.rawX - params.x
                    dragOffsetY = event.rawY - params.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (event.rawX - dragOffsetX).toInt().coerceAtLeast(0)
                    params.y = (event.rawY - dragOffsetY).toInt().coerceAtLeast(0)
                    runCatching { wm?.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 松手吸附到屏幕左/右边缘（更专业：自动归边）
                    val screenW = ctx.resources.displayMetrics.widthPixels
                    val targetX = if (params.x + view.width / 2 > screenW / 2) screenW - view.width else 0
                    if (targetX != params.x) {
                        ObjectAnimator.ofInt(params, "x", params.x, targetX).apply {
                            duration = 180
                            addUpdateListener { runCatching { wm?.updateViewLayout(view, params) } }
                            start()
                        }
                    }
                    // 记忆位置
                    ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .edit().putInt(PREF_BALL_X, params.x).putInt(PREF_BALL_Y, params.y).apply()
                    true
                }
                else -> false
            }
        }
    }

    // ───────────── 状态刷新（引擎状态 / 当前任务 / 运行时长） ─────────────

    private fun refreshStatus() {
        val engine = engineRef
        val title = statusTitle ?: return
        val detail = statusDetail ?: return
        val timer = timerText ?: return
        val dot = ballDot ?: return

        if (engine == null) {
            title.text = "引擎未连接"
            detail.text = "请先在 App 内连接引擎"
            timer.text = ""
            dot.setBackgroundColor(COLOR_IDLE)
            return
        }
        val alive = runCatching { engine.isRunning }.getOrDefault(false)
        if (alive) {
            if (runStartMs == 0L) runStartMs = System.currentTimeMillis()
            val task = runCatching { engine.currentTask() }.getOrDefault("")
            title.text = "任务运行中"
            detail.text = if (task.isNullOrBlank()) "正在执行任务…" else "当前：$task"
            timer.text = "已运行 ${formatDuration((System.currentTimeMillis() - runStartMs) / 1000)}"
            dot.setBackgroundColor(COLOR_RUNNING)
        } else {
            runStartMs = 0L
            title.text = "引擎待机"
            detail.text = if (muted) "已静音（游戏）" else "就绪，可开始任务"
            timer.text = ""
            dot.setBackgroundColor(COLOR_IDLE)
        }
    }

    private fun doAction(action: (IRemoteEngineService) -> Unit) {
        val engine = engineRef
        if (engine == null) {
            statusTitle?.text = "引擎未连接"
            statusDetail?.text = "请先在 App 内连接引擎"
            return
        }
        runCatching { action(engine) }.onFailure {
            statusDetail?.text = "操作失败: ${it.message}"
        }
    }

    private fun defaultTasks(): String = "[{\"entry\":\"start_up\",\"options\":{}}]"

    private fun formatDuration(sec: Long): String {
        if (sec <= 0) return "0 秒"
        val m = sec / 60
        val s = sec % 60
        return if (m > 0) "${m} 分 ${s} 秒" else "${s} 秒"
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}