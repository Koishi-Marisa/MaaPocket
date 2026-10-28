package com.maafw.naruto.data.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置仓库
 * 目前支持：屏幕常亮、主题模式。
 */
object SettingsRepository {

    private const val PREF_NAME = "maa_settings"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_THEME = "theme"
    private const val KEY_AUTO_START_SHIZUKU = "auto_start_shizuku"
    private const val KEY_SHOW_FLOATING_LOG = "show_floating_log"
private const val KEY_FLOATING_CONTROL = "floating_control"
private const val KEY_SCREEN_SAVER = "screen_saver"
private const val KEY_MEMORY_CLEAN_BEFORE_TASK = "memory_clean_before_task"
    private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
    private const val KEY_NOTIFICATION_SOUND = "notification_sound"
    private const val KEY_NOTIFICATION_VIBRATE = "notification_vibrate"
    private const val KEY_NOTIFY_TASK_START = "notify_task_start"
    private const val KEY_FULLSCREEN_EXTRA_INFO = "fullscreen_extra_info"
    private const val KEY_KEEP_ALIVE = "keep_alive"
private const val KEY_TASK_CONFIG_PREFIX = "task_config_"
private const val KEY_EDIT_DRAG_TIP_DISMISSED = "edit_drag_tip_dismissed"

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    private fun prefs(context: Context): SharedPreferences {
        // UserService（Shizuku shell 进程）的 context.applicationContext 可能为 null，兜底用原 context
        val app = context.applicationContext
        return (app ?: context).getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // ========== 通知设置 ==========
    fun isNotificationEnabled(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_NOTIFICATION_ENABLED, true)
    }

    fun setNotificationEnabled(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_NOTIFICATION_ENABLED, value)
    }

    fun isNotificationSound(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_NOTIFICATION_SOUND, true)
    }

    fun setNotificationSound(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_NOTIFICATION_SOUND, value)
    }

    fun isNotificationVibrate(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_NOTIFICATION_VIBRATE, false)
    }

    fun setNotificationVibrate(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_NOTIFICATION_VIBRATE, value)
    }

    /** 任务开始时的本地通知（默认关闭） */
    fun isNotifyTaskStart(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_NOTIFY_TASK_START, false)
    }

    fun setNotifyTaskStart(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_NOTIFY_TASK_START, value)
    }

    // ========== 通知类型独立开关（本地通知） ==========
    private const val KEY_NOTIFY_TASK_COMPLETE = "notify_task_complete"
    private const val KEY_NOTIFY_TASK_ERROR = "notify_task_error"
    private const val KEY_NOTIFY_SERVICE_EVENT = "notify_service_event"

    /** 任务完成本地通知（默认开启，受总开关控制） */
    fun isNotifyTaskComplete(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_NOTIFY_TASK_COMPLETE, true)
    }

    fun setNotifyTaskComplete(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_NOTIFY_TASK_COMPLETE, value)
    }

    /** 任务出错本地通知（默认开启，受总开关控制） */
    fun isNotifyTaskError(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_NOTIFY_TASK_ERROR, true)
    }

    fun setNotifyTaskError(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_NOTIFY_TASK_ERROR, value)
    }

    /** 服务异常本地通知（默认开启，受总开关控制） */
    fun isNotifyServiceEvent(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_NOTIFY_SERVICE_EVENT, true)
    }

    fun setNotifyServiceEvent(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_NOTIFY_SERVICE_EVENT, value)
    }

    /** 全屏虚拟屏上方显示额外信息（运行状态/任务/分辨率，默认关闭） */
    fun isFullscreenExtraInfo(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_FULLSCREEN_EXTRA_INFO, false)
    }

    fun setFullscreenExtraInfo(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_FULLSCREEN_EXTRA_INFO, value)
    }

    /** 后台保活（前台服务防杀后台，默认关闭） */
    fun isKeepAliveEnabled(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_KEEP_ALIVE, false)
    }

    /** 无障碍防杀服务是否已启用（检测系统无障碍服务列表；非 root 保活核心） */
    fun isAccessibilityKeepAliveEnabled(context: Context): Boolean {
        return runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.contains("com.maafw.naruto/.service.KeepAliveAccessibilityService") == true
        }.getOrDefault(false)
    }

    fun setKeepAliveEnabled(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_KEEP_ALIVE, value)
    }

    // ========== 任务配置 ==========
    data class TaskConfig(
        val entry: String,
        val enabled: Boolean = false,
        val options: Map<String, String> = emptyMap()
    )

    /** task_config key：按 profile 隔离（不同任务配置的选项设置互不串用）；默认配置用旧 key 兼容历史数据 */
    private fun taskConfigKey(profile: String, entry: String): String {
        return if (profile.isBlank() || profile == "default") {
            KEY_TASK_CONFIG_PREFIX + entry
        } else {
            "task_config_${profile}_$entry"
        }
    }

    fun getTaskConfig(context: Context, entry: String, profile: String = "default"): TaskConfig {
        val json = MaaFwSettingsStore.getString(taskConfigKey(profile, entry), "")
        if (json.isEmpty()) return TaskConfig(entry)
        return try {
            val obj = org.json.JSONObject(json)
            val opts = mutableMapOf<String, String>()
            val optJson = obj.optJSONObject("options") ?: org.json.JSONObject()
            val keys = optJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                opts[key] = optJson.getString(key)
            }
            TaskConfig(
                entry = entry,
                enabled = obj.optBoolean("enabled", false),
                options = opts
            )
        } catch (e: Exception) {
            TaskConfig(entry)
        }
    }

    fun setTaskConfig(context: Context, config: TaskConfig, profile: String = "default") {
        val obj = org.json.JSONObject().apply {
            put("entry", config.entry)
            put("enabled", config.enabled)
            put("options", org.json.JSONObject().apply {
                for ((k, v) in config.options) {
                    put(k, v)
                }
            })
        }
        MaaFwSettingsStore.put(context, taskConfigKey(profile, config.entry), obj.toString())
    }

    fun setTaskEnabled(context: Context, entry: String, enabled: Boolean, profile: String = "default") {
        val cfg = getTaskConfig(context, entry, profile).copy(enabled = enabled)
        setTaskConfig(context, cfg, profile)
    }

    fun setTaskOptions(context: Context, entry: String, options: Map<String, String>, profile: String = "default") {
        val cfg = getTaskConfig(context, entry, profile).copy(options = options)
        setTaskConfig(context, cfg, profile)
    }

    fun getEnabledTasks(context: Context, profile: String = "default"): List<String> {
        val prefix = if (profile.isBlank() || profile == "default") KEY_TASK_CONFIG_PREFIX else "task_config_${profile}_"
        return MaaFwSettingsStore.keys()
            .filter { it.startsWith(prefix) }
            .mapNotNull { key ->
                val entry = if (profile.isBlank() || profile == "default") {
                    key.removePrefix(KEY_TASK_CONFIG_PREFIX)
                } else {
                    key.removePrefix(prefix)
                }
                val cfg = getTaskConfig(context, entry, profile)
                if (cfg.enabled) entry else null
            }
    }

// ========== 编辑任务拖拽提示 ==========
/** 编辑任务时的长按拖拽提示是否已被关闭（点 X 后不再显示，未点过则每次进入编辑模式都显示） */
fun isEditDragTipDismissed(context: Context): Boolean {
    return MaaFwSettingsStore.getBoolean(KEY_EDIT_DRAG_TIP_DISMISSED, false)
}

fun setEditDragTipDismissed(context: Context, dismissed: Boolean) {
    MaaFwSettingsStore.put(context, KEY_EDIT_DRAG_TIP_DISMISSED, dismissed)
}

    fun isKeepScreenOn(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_KEEP_SCREEN_ON, true)
    }

    fun setKeepScreenOn(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_KEEP_SCREEN_ON, value)
    }

    fun getTheme(context: Context): String {
        return MaaFwSettingsStore.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
    }

    fun setTheme(context: Context, value: String) {
        MaaFwSettingsStore.put(context, KEY_THEME, value)
    }

    fun isAutoStartShizuku(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_AUTO_START_SHIZUKU, false)
    }

    fun setAutoStartShizuku(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_AUTO_START_SHIZUKU, value)
    }

    fun isShowFloatingLog(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_SHOW_FLOATING_LOG, false)
    }

    /** 悬浮球控制开关（需悬浮窗权限，默认关闭） */
    fun isFloatingControlEnabled(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_FLOATING_CONTROL, false)
    }

    fun setFloatingControlEnabled(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_FLOATING_CONTROL, value)
    }

    // ========== 详细日志（L-6：日志级别联动） ==========
    private const val KEY_VERBOSE_LOGGING = "verbose_logging"

    /** 详细日志开关（默认关闭；开启后引擎输出更详细的识别/动作日志） */
    fun isVerboseLogging(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_VERBOSE_LOGGING, false)
    }

    fun setVerboseLogging(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_VERBOSE_LOGGING, value)
    }

    // ========== 强制重启游戏（P2：force_stop 策略，默认关闭） ==========
    private const val KEY_FORCE_STOP = "force_stop"

    /** 强制重启游戏：任务启动时 force-stop 游戏再启动（游戏状态不干净时更稳；默认关闭） */
    fun isForceStopEnabled(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_FORCE_STOP, false)
    }

    fun setForceStopEnabled(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_FORCE_STOP, value)
    }

    /** 任务时屏保遮罩（后台挂机防烧屏/防偷看，需悬浮窗权限，默认关闭） */
    fun isScreenSaverEnabled(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_SCREEN_SAVER, false)
    }

    fun setScreenSaverEnabled(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_SCREEN_SAVER, value)
    }

    /** 开始任务前清理后台内存（默认开启；仅手动点开始任务时触发，杀后台缓存进程，不杀自身/引擎/游戏/Shizuku） */
    fun isMemoryCleanBeforeTask(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_MEMORY_CLEAN_BEFORE_TASK, true)
    }

    fun setMemoryCleanBeforeTask(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_MEMORY_CLEAN_BEFORE_TASK, value)
    }

    fun setShowFloatingLog(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_SHOW_FLOATING_LOG, value)
    }

        // ========== 运行设置（ 全局开关） ==========
    private const val KEY_CLOSE_GAME_AFTER_TASK = "close_game_after_task"
    private const val KEY_MUTE_ON_GAME_LAUNCH = "mute_on_game_launch"
    private const val KEY_USE_HARDWARE_SCREEN_OFF = "use_hardware_screen_off"
    private const val KEY_SHOW_TOUCH_PREVIEW = "show_touch_preview"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_RUN_MODE = "run_mode"
    private const val KEY_UI_SCALE = "ui_scale"
    private const val KEY_PUSH_CHANNEL = "push_channel"
    private const val KEY_PUSH_MIAOTIXING_TOKEN = "push_miaotixing_token"
    private const val KEY_PUSH_SERVERCHAN_KEY = "push_serverchan_key"
    private const val KEY_PUSH_DINGTALK_TOKEN = "push_dingtalk_token"
    private const val KEY_PUSH_SMTP_HOST = "push_smtp_host"
    private const val KEY_PUSH_SMTP_PORT = "push_smtp_port"
    private const val KEY_PUSH_SMTP_USER = "push_smtp_user"
    private const val KEY_PUSH_SMTP_PASS = "push_smtp_pass"
    private const val KEY_PUSH_SMTP_TO = "push_smtp_to"
    private const val KEY_PUSH_WEBHOOK_URL = "push_webhook_url"
    private const val KEY_PUSH_WEBHOOK_BODY = "push_webhook_body"

    const val THEME_MONET = "monet"

    const val RUN_MODE_SHIZUKU = "shizuku"
    const val RUN_MODE_ROOT = "root"

    fun isCloseGameAfterTask(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_CLOSE_GAME_AFTER_TASK, false)
    }

    fun setCloseGameAfterTask(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_CLOSE_GAME_AFTER_TASK, value)
    }

    /** 启动任务时自动静音游戏（ muteOnGameLaunch） */
    fun isMuteOnGameLaunch(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_MUTE_ON_GAME_LAUNCH, false)
    }

    fun setMuteOnGameLaunch(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_MUTE_ON_GAME_LAUNCH, value)
    }

    /** 硬件熄屏（关物理屏幕而非虚拟屏） */
    fun isUseHardwareScreenOff(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_USE_HARDWARE_SCREEN_OFF, false)
    }

    fun setUseHardwareScreenOff(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_USE_HARDWARE_SCREEN_OFF, value)
    }

    /** 预览屏显示触摸标记 */
fun isShowTouchPreview(context: Context): Boolean {
    return MaaFwSettingsStore.getBoolean(KEY_SHOW_TOUCH_PREVIEW, false)
}

private const val KEY_TOUCH_PREVIEW_COUNT = "touch_preview_count"

/** 触摸预览最多显示的操作数量（默认 1：只显示最近一次操作） */
fun getTouchPreviewCount(context: Context): Int {
    return MaaFwSettingsStore.getInt(KEY_TOUCH_PREVIEW_COUNT, 1).coerceIn(1, 30)
}

fun setTouchPreviewCount(context: Context, value: Int) {
    MaaFwSettingsStore.put(context, KEY_TOUCH_PREVIEW_COUNT, value.coerceIn(1, 30))
}

private const val KEY_SCRIPT_LOG_VISIBLE = "script_log_visible"

/** 脚本页是否显示「日志」分页（默认显示） */
fun isScriptLogVisible(context: Context): Boolean {
    return MaaFwSettingsStore.getBoolean(KEY_SCRIPT_LOG_VISIBLE, true)
}

fun setScriptLogVisible(context: Context, value: Boolean) {
    MaaFwSettingsStore.put(context, KEY_SCRIPT_LOG_VISIBLE, value)
}

private const val KEY_SCRIPT_LOG_COPY_VISIBLE = "script_log_copy_visible"

/** 脚本页日志分页的「复制日志」按钮是否显示（默认隐藏） */
fun isScriptLogCopyVisible(context: Context): Boolean {
    return MaaFwSettingsStore.getBoolean(KEY_SCRIPT_LOG_COPY_VISIBLE, false)
}

fun setScriptLogCopyVisible(context: Context, value: Boolean) {
    MaaFwSettingsStore.put(context, KEY_SCRIPT_LOG_COPY_VISIBLE, value)
}

private const val KEY_SCRIPT_DEBUG_TOUCH = "script_debug_touch"

/** 脚本调试：全屏预览触摸时采集坐标并导出 Maa 点击位置（默认关闭） */
fun isScriptDebugTouch(context: Context): Boolean {
    return MaaFwSettingsStore.getBoolean(KEY_SCRIPT_DEBUG_TOUCH, false)
}

fun setScriptDebugTouch(context: Context, value: Boolean) {
    MaaFwSettingsStore.put(context, KEY_SCRIPT_DEBUG_TOUCH, value)
}

// ========== 原版识别测试（积分赛 FindToChallenge 嵌套 OCR 复现报错用） ==========
private const val KEY_TEST_LEGACY_FIND_TO_CHALLENGE = "test_legacy_find_to_challenge"

/** 原版识别测试：积分赛 point_race_challenge 使用原版 FindToChallenge（嵌套 OCR，默认关闭） */
fun isTestLegacyFindToChallenge(context: Context): Boolean {
    return MaaFwSettingsStore.getBoolean(KEY_TEST_LEGACY_FIND_TO_CHALLENGE, false)
}

fun setTestLegacyFindToChallenge(context: Context, value: Boolean) {
    MaaFwSettingsStore.put(context, KEY_TEST_LEGACY_FIND_TO_CHALLENGE, value)
}

// ========== 情报社村口点击坐标（脚本页输入框填入，供 ClickStoredPoint 使用） ==========
private const val KEY_CLUB_VILLAGE_POINT = "club_village_point"

/** 情报社「进入村口」点击坐标（如 "1181,464" 或采集面板复制格式），空=未配置 */
fun getClubVillagePoint(context: Context): String {
    return MaaFwSettingsStore.getString(KEY_CLUB_VILLAGE_POINT, "") ?: ""
}

fun setClubVillagePoint(context: Context, value: String) {
    MaaFwSettingsStore.put(context, KEY_CLUB_VILLAGE_POINT, value)
}

// ========== 自定义点击坐标解析（坐标采集面板复制的文本 -> x,y） ==========

/**
 * 解析坐标文本，支持多种格式（坐标采集面板复制内容可直接粘贴）：
 * 1. 采集面板格式： `"target": [x-30, y-30, 60, 60],   // 点击点 (1181, 464)` -> (1181, 464)
 * 2. 数组格式： `[1181, 464]` 或 `"target": [1151, 434, 60, 60]` -> 取前两个
 * 3. 纯坐标： `1181, 464`
 */
fun parseClickPoint(raw: String): IntArray? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    // 采集面板格式：括号内点击点 (x, y)
    Regex("\\((\\d+)\\s*,\\s*(\\d+)\\)").find(t)?.let {
        return intArrayOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
    }
    // 数组格式 [x, y(, w, h)]
    Regex("\\[(\\d+)\\s*,\\s*(\\d+)(?:\\s*,\\s*\\d+){0,2}\\]").find(t)?.let {
        return intArrayOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
    }
    // 纯 x,y
    Regex("^(\\d+)\\s*,\\s*(\\d+)$").find(t)?.let {
        return intArrayOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
    }
    return null
}

    fun setShowTouchPreview(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_SHOW_TOUCH_PREVIEW, value)
    }
const val RES_720P = "720p"
    const val RES_1080P = "1080p"
    const val RES_CUSTOM = "custom"
    private const val KEY_RES_CUSTOM_W = "res_custom_w"
    private const val KEY_RES_CUSTOM_H = "res_custom_h"
    private const val KEY_RES_CUSTOM_DPI = "res_custom_dpi"

    /** 虚拟屏分辨率（720p/1080p/custom） */
    fun getResolution(context: Context): String {
        return MaaFwSettingsStore.getString(KEY_RESOLUTION, RES_720P) ?: RES_720P
    }

    fun setResolution(context: Context, value: String) {
        MaaFwSettingsStore.put(context, KEY_RESOLUTION, value)
    }

    fun getCustomWidth(context: Context): Int = MaaFwSettingsStore.getInt(KEY_RES_CUSTOM_W, 1600)
    fun getCustomHeight(context: Context): Int = MaaFwSettingsStore.getInt(KEY_RES_CUSTOM_H, 900)
    fun getCustomDpi(context: Context): Int = MaaFwSettingsStore.getInt(KEY_RES_CUSTOM_DPI, 240)
    fun setCustomResolution(context: Context, w: Int, h: Int, dpi: Int) {
        MaaFwSettingsStore.put(context, KEY_RES_CUSTOM_W, w)
        MaaFwSettingsStore.put(context, KEY_RES_CUSTOM_H, h)
        MaaFwSettingsStore.put(context, KEY_RES_CUSTOM_DPI, dpi)
    }

    /** 返回 (宽, 高, DPI)  */
    fun getResolutionFull(value: String, context: Context?): Triple<Int, Int, Int> = when (value) {
        RES_1080P -> Triple(1920, 1080, 320)
        RES_CUSTOM -> {
            if (context != null) Triple(getCustomWidth(context), getCustomHeight(context), getCustomDpi(context))
            else Triple(1600, 900, 240)
        }
        else -> Triple(1280, 720, 160)
    }
    /** 运行模式（shizuku/root） */
    fun getRunMode(context: Context): String {
        return MaaFwSettingsStore.getString(KEY_RUN_MODE, RUN_MODE_SHIZUKU) ?: RUN_MODE_SHIZUKU
    }

    fun setRunMode(context: Context, value: String) {
        MaaFwSettingsStore.put(context, KEY_RUN_MODE, value)
    }

    fun isRootMode(context: Context): Boolean = getRunMode(context) == RUN_MODE_ROOT

    /** 页面缩放（0.7~1.3，默认 0.9 = 90%） */
    fun getUiScale(context: Context): Float {
        return MaaFwSettingsStore.getFloat(KEY_UI_SCALE, 0.9f).coerceIn(0.7f, 1.3f)
    }

    fun setUiScale(context: Context, value: Float) {
        MaaFwSettingsStore.put(context, KEY_UI_SCALE, value.coerceIn(0.7f, 1.3f))
    }

        // ========== 当前任务配置（记住上次选择） ==========
    private const val KEY_CURRENT_PROFILE = "current_profile"

    fun getCurrentProfile(context: Context): String {
        return MaaFwSettingsStore.getString(KEY_CURRENT_PROFILE, com.maafw.naruto.data.profile.ProfileManager.DEFAULT_PROFILE_NAME)
            ?: com.maafw.naruto.data.profile.ProfileManager.DEFAULT_PROFILE_NAME
    }

    fun setCurrentProfile(context: Context, name: String) {
        // DataStore 异步落盘 + 缓存同步更新（读侧立即生效）
        MaaFwSettingsStore.put(context, KEY_CURRENT_PROFILE, name)
    }

    // ========== 定时任务后台唤醒 ==========
    private const val KEY_SCHEDULE_WAKE = "schedule_wake_on"
    private const val KEY_ONBOARDING_DONE = "onboarding_done"

    /** 是否已完成首次启动引导 */
    fun isOnboardingDone(context: Context): Boolean = MaaFwSettingsStore.getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone(context: Context, done: Boolean) {
        MaaFwSettingsStore.put(context, KEY_ONBOARDING_DONE, done)
    }

    /** 后台唤醒：锁屏/应用未启动时由系统精确唤醒并执行定时任务 */
    fun isScheduleWakeOn(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_SCHEDULE_WAKE, true)
    }

    fun setScheduleWakeOn(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_SCHEDULE_WAKE, value)
    }

    // ========== Root 守护进程（彻底无后台执行定时任务） ==========
    private const val KEY_ROOT_DAEMON = "root_daemon_enabled"

    /** Root 守护进程：常驻 root 进程调度定时任务，App 被杀也能执行（需已 root） */
    fun isRootDaemonEnabled(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_ROOT_DAEMON, false)
    }

    fun setRootDaemonEnabled(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_ROOT_DAEMON, value)
    }

    // ========== 帧率显示调试（虚拟屏游戏真实帧率 + 脚本识别频率） ==========
    private const val KEY_FPS_DEBUG = "fps_debug"

    /** 虚拟屏预览左上角显示游戏/脚本帧率（debug，默认开启） */
    fun isFpsDebugEnabled(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_FPS_DEBUG, true)
    }

    fun setFpsDebugEnabled(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_FPS_DEBUG, value)
    }

    // ========== 引擎复用（性能优化：跳过任务间资源/模型重载；默认开启） ==========
    private const val KEY_ENGINE_REUSE = "engine_reuse"

    /**
     * 引擎实例复用：任务正常结束后保留 MaaFramework resource/controller/tasker，
     * 下次任务跳过 pipeline/图片/OCR 模型重载（省 2~5s）。识别实时性由
     * 「清识别缓存 + 控制器重连 + 最新帧缓冲」三重保障，不会用到旧帧。
     * 关闭后每次任务完整重建引擎（最保守，理论上最实时但慢）。
     */
    fun isEngineReuseEnabled(context: Context): Boolean {
        return MaaFwSettingsStore.getBoolean(KEY_ENGINE_REUSE, true)
    }

    fun setEngineReuseEnabled(context: Context, value: Boolean) {
        MaaFwSettingsStore.put(context, KEY_ENGINE_REUSE, value)
    }

    // ========== 首次启动引导 ==========
    private const val KEY_ONBOARDED = "onboarded"

    fun isOnboarded(context: Context): Boolean = MaaFwSettingsStore.getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: Context) {
        MaaFwSettingsStore.put(context, KEY_ONBOARDED, true)
    }

    // ========== 第三方通知推送配置 ==========

    private const val KEY_PUSH_NOTIFY_SUCCESS = "push_notify_success"
    private const val KEY_PUSH_NOTIFY_ERROR = "push_notify_error"
    private const val KEY_PUSH_NOTIFY_START = "push_notify_start"

    fun isPushNotifySuccess(context: Context): Boolean = MaaFwSettingsStore.getBoolean(KEY_PUSH_NOTIFY_SUCCESS, true)
    fun setPushNotifySuccess(context: Context, v: Boolean) = MaaFwSettingsStore.put(context, KEY_PUSH_NOTIFY_SUCCESS, v)
    fun isPushNotifyError(context: Context): Boolean = MaaFwSettingsStore.getBoolean(KEY_PUSH_NOTIFY_ERROR, true)
    fun setPushNotifyError(context: Context, v: Boolean) = MaaFwSettingsStore.put(context, KEY_PUSH_NOTIFY_ERROR, v)
    /** 任务开始时第三方推送（默认关闭） */
    fun isPushNotifyStart(context: Context): Boolean = MaaFwSettingsStore.getBoolean(KEY_PUSH_NOTIFY_START, false)
    fun setPushNotifyStart(context: Context, v: Boolean) = MaaFwSettingsStore.put(context, KEY_PUSH_NOTIFY_START, v)

    fun getPushChannel(context: Context): String {
        return MaaFwSettingsStore.getString(KEY_PUSH_CHANNEL, "none") ?: "none"
    }

    fun setPushChannel(context: Context, value: String) {
        MaaFwSettingsStore.put(context, KEY_PUSH_CHANNEL, value)
    }

    fun getPushString(context: Context, key: String): String {
        return MaaFwSettingsStore.getString(key, "") ?: ""
    }

    fun setPushString(context: Context, key: String, value: String) {
        MaaFwSettingsStore.put(context, key, value)
    }

    fun getPushMiaotixingToken(context: Context) = getPushString(context, KEY_PUSH_MIAOTIXING_TOKEN)
    fun setPushMiaotixingToken(context: Context, v: String) = setPushString(context, KEY_PUSH_MIAOTIXING_TOKEN, v)
    fun getPushServerChanKey(context: Context) = getPushString(context, KEY_PUSH_SERVERCHAN_KEY)
    fun setPushServerChanKey(context: Context, v: String) = setPushString(context, KEY_PUSH_SERVERCHAN_KEY, v)
    fun getPushDingTalkToken(context: Context) = getPushString(context, KEY_PUSH_DINGTALK_TOKEN)
    fun setPushDingTalkToken(context: Context, v: String) = setPushString(context, KEY_PUSH_DINGTALK_TOKEN, v)
    fun getPushSmtpHost(context: Context) = getPushString(context, KEY_PUSH_SMTP_HOST)
    fun getPushSmtpPort(context: Context) = getPushString(context, KEY_PUSH_SMTP_PORT).toIntOrNull() ?: 465
    fun getPushSmtpUser(context: Context) = getPushString(context, KEY_PUSH_SMTP_USER)
    fun getPushSmtpPass(context: Context) = getPushString(context, KEY_PUSH_SMTP_PASS)
    fun getPushSmtpTo(context: Context) = getPushString(context, KEY_PUSH_SMTP_TO)
    fun setPushSmtpHost(context: Context, v: String) = setPushString(context, KEY_PUSH_SMTP_HOST, v)
    fun setPushSmtpPort(context: Context, v: String) = setPushString(context, KEY_PUSH_SMTP_PORT, v)
    fun setPushSmtpUser(context: Context, v: String) = setPushString(context, KEY_PUSH_SMTP_USER, v)
    fun setPushSmtpPass(context: Context, v: String) = setPushString(context, KEY_PUSH_SMTP_PASS, v)
    fun setPushSmtpTo(context: Context, v: String) = setPushString(context, KEY_PUSH_SMTP_TO, v)
    fun getPushWebhookUrl(context: Context) = getPushString(context, KEY_PUSH_WEBHOOK_URL)
    fun getPushWebhookBody(context: Context) = getPushString(context, KEY_PUSH_WEBHOOK_BODY)
    fun setPushWebhookUrl(context: Context, v: String) = setPushString(context, KEY_PUSH_WEBHOOK_URL, v)
    fun setPushWebhookBody(context: Context, v: String) = setPushString(context, KEY_PUSH_WEBHOOK_BODY, v)
}