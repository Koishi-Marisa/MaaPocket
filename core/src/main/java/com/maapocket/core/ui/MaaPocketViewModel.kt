package com.maapocket.core.ui

import android.app.Application
import android.graphics.Bitmap
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maapocket.core.constant.DefaultDisplayConfig
import com.maapocket.core.pi.PiDisplaySize
import com.maapocket.core.pi.PiInstalledPackages
import com.maapocket.core.pi.PiOption
import com.maapocket.core.pi.PiRepository
import com.maapocket.core.pi.PiSelection
import com.maapocket.core.privilege.BootstrapRegistry
import com.maapocket.core.privilege.PrivilegeKind
import com.maapocket.core.run.MaaRunController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * UI 与 [MaaRunController] 之间唯一的桥。刻意不持有 Activity / View，
 * 这样旋转、重建、切后台都不会把 controller 的会话一起带走。
 *
 * 这里不做任何线程调度：controller 的 suspend 函数自己切 IO，这里只用 [viewModelScope]。
 */
class MaaPocketViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = MaaRunController(application)

    /**
     * 「这个客户端包名装在本机吗」探针。`PiSelection.resolve` 拿它把写死的国服默认值
     * 自动换成用户真装了的那个（B服 / 国际服）。见 `PiInstalledPackages`。
     */
    private val installedPackages = PiInstalledPackages.of(application)

    // ---------------------------------------------------------------- 直通状态

    val state: StateFlow<MaaRunController.State> = controller.state

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    /**
     * 预览开关只影响本地渲染。
     *
     * 资源包里没有暴露任何「向特权进程发 capture 指令」的公开 API（MaaRunController 只公开了
     * refreshPrivilegeOptions/extractPack/prepare/runTasks/requestStop/setRepository/setSelection/shutdown），
     * 所以这里关掉的是 Image 的绘制，而不是远端的截图。省的是渲染与跨进程传输解码的 CPU，
     * 不是截图本身的 CPU —— 真正的远端开关需要 core 增加 API。
     */
    private val _previewEnabled = MutableStateFlow(true)
    val previewEnabled: StateFlow<Boolean> = _previewEnabled.asStateFlow()

    /**
     * 界面上显示的日志。**不等于** controller.logs：controller 是 500 条环形缓冲，
     * 「清空」在它那里不存在，所以 UI 侧自己维护一份只增不减的漏斗。
     */
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /** 上一次收到的 controller.logs 原始值，用来做增量 diff。 */
    private var lastRawLogs: List<String> = emptyList()

    // ---------------------------------------------------------------- 业务状态

    private val _repository = MutableStateFlow<PiRepository?>(null)
    val repository: StateFlow<PiRepository?> = _repository.asStateFlow()

    private val _controllerName = MutableStateFlow("")
    val controllerName: StateFlow<String> = _controllerName.asStateFlow()

    private val _resourceName = MutableStateFlow("")
    val resourceName: StateFlow<String> = _resourceName.asStateFlow()

    private val _selectedTasks = MutableStateFlow<Set<String>>(emptySet())
    val selectedTasks: StateFlow<Set<String>> = _selectedTasks.asStateFlow()

    /**
     * 只存**用户显式改过**的选项值。
     *
     * 不能把默认值预填进来：`PiSelection.resolve()` 会把 `optionValues.keys` 全部并入
     * resolvedOptions（PiSelection.kt:100-101），于是未勾选 task 的选项默认值也会变成
     * pipeline override 打进 MaaResource。留空则只有勾选 task 声明的 option + global_option 生效，
     * 这正是 PI 的语义。
     */
    private val _optionValues = MutableStateFlow<Map<String, String>>(emptyMap())
    val optionValues: StateFlow<Map<String, String>> = _optionValues.asStateFlow()

    private val _privilegeKind = MutableStateFlow(PrivilegeKind.ROOT)
    val privilegeKind: StateFlow<PrivilegeKind> = _privilegeKind.asStateFlow()

    // 预填默认值，让用户看得见真正生效的分辨率。**这里只是解包前的占位**：
    // 解包之后就由 `applyPackDisplaySize` 按资源包自己声明的 PI `display_*` 字段改写
    // （崩铁 1920x1080、绝区零 1920x1080、终末地 1280x720）。虚拟屏必须正好等于
    // 资源包采集时的分辨率，模板才 1:1 匹配，见 `PiDisplaySize` 与
    // `DefaultDisplayConfig.WIDTH` 的注释。清空输入框 = 回落到默认。
    private val _displayWidth = MutableStateFlow(DefaultDisplayConfig.WIDTH.toString())
    val displayWidth: StateFlow<String> = _displayWidth.asStateFlow()

    private val _displayHeight = MutableStateFlow(DefaultDisplayConfig.HEIGHT.toString())
    val displayHeight: StateFlow<String> = _displayHeight.asStateFlow()

    /** 用户手动改过分辨率输入框之后，就不再按资源包的声明自动改写（他比我更清楚）。 */
    private var displaySizePickedByUser = false

    private val _plan = MutableStateFlow<PiSelection.PiRunPlan?>(null)
    val plan: StateFlow<PiSelection.PiRunPlan?> = _plan.asStateFlow()

    /** UI 层自己产生的错误（选择不完整之类），与 controller 的 lastError 分开显示。 */
    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()

    /** 用户手动点过后端之后，刷新不再自动改写他的选择。 */
    private var kindPickedByUser = false

    init {
        // 用 onEach + launchIn 而不是 .collect { }：前者是稳定 API，不依赖 collect 扩展的导入形态。
        controller.preview
            .onEach { bitmap -> _preview.value = bitmap }
            .launchIn(viewModelScope)

        controller.logs
            .onEach { incoming ->
                val fresh = appendedTail(lastRawLogs, incoming)
                lastRawLogs = incoming
                if (fresh.isNotEmpty()) {
                    _logs.update { (it + fresh).takeLast(MaaRunController.LOG_CAPACITY) }
                }
            }
            .launchIn(viewModelScope)

        // 提权探测会走 libsu（fork su）与 Shizuku binder，Shizuku 被系统冻结时能卡满 5s；
        // 以前这里是同步调用，真机上直接把主线程拖进 ANR（Input dispatching timed out, 5001ms）。
        viewModelScope.launch { refreshPrivilegeOptions() }
    }

    // ------------------------------------------------------------------ 权限

    fun refreshPrivilegeOptions() {
        viewModelScope.launch {
            controller.refreshPrivilegeOptions()
            if (!kindPickedByUser) {
                // 这里以前只在「有后端已经 Ready」时才改选，于是：设备未 root、Shizuku 只差
                // 一次授权时，选择一直停在默认的 root 上 —— 真机点「准备」直接得到
                // 「特权进程未连上（root uid=-1）：root 不可用」，而 shizuku 明明可用。
                // 现在交给 controller 的统一优先级（Ready > 未授权 > 被拒 > 不支持）。
                controller.preferredKind()?.let { _privilegeKind.value = it }
            }
        }
    }

    fun selectPrivilegeKind(kind: PrivilegeKind) {
        kindPickedByUser = true
        _privilegeKind.value = kind
    }

    /**
     * 申请一次提权授权（Shizuku 会弹授权框）。
     *
     * 之前 UI 上没有任何入口能触发 `Shizuku.requestPermission`，而没授权时
     * `PrivilegedSession.start()` 会直接返回，用户只能看到「特权进程未连上」。
     */
    fun requestPrivilege() {
        viewModelScope.launch {
            val kind = _privilegeKind.value
            val result = controller.requestPrivilege(kind)
            // 授权成功后顺手把后端状态刷新成 ready。
            if (result.isReady) refreshPrivilegeOptions()
        }
    }

    // ---------------------------------------------------------------- 资源包

    fun extractPack(force: Boolean = false) {
        viewModelScope.launch {
            val repo = controller.extractPack(force) ?: return@launch
            applyRepository(repo)
        }
    }

    private fun applyRepository(repo: PiRepository) {
        // extractPack 只把 piRoot/packLabel 写进 state，repository 得由调用方交回来。
        controller.setRepository(repo)
        _repository.value = repo

        val controllerItem = repo.onDeviceControllers().firstOrNull()
            ?: repo.controllers().firstOrNull()
        val resourceItem = repo.resources().firstOrNull()
        val cName = controllerItem?.name.orEmpty()
        val rName = resourceItem?.name.orEmpty()

        _controllerName.value = cName
        _resourceName.value = rName
        controller.setSelection(cName, rName)
        applyPackDisplaySize(repo, cName)
        _selectedTasks.value = PiSelection.defaultChecked(repo).map { it.name }.toSet()
        // 换包必须丢弃旧包的选项值，否则同名 option 会带着上一款游戏的取值生效。
        _optionValues.value = emptyMap()
        _plan.value = null
        _uiError.value = null
    }

    fun selectController(name: String) {
        _controllerName.value = name
        controller.setSelection(name, _resourceName.value)
        _repository.value?.let { applyPackDisplaySize(it, name) }
        invalidatePlan()
    }

    /**
     * 把输入框换成资源包自己声明的分辨率（PI `controller.display_*`）。
     *
     * 不同游戏的资源包采集分辨率不一样（崩铁/绝区零 1920x1080、终末地 1280x720），
     * 虚拟屏必须正好等于它，模板才 1:1 匹配；写死任何一个都会让另一款游戏整包失配。
     * 用户手动改过就不动他的值。
     */
    private fun applyPackDisplaySize(repo: PiRepository, controllerName: String) {
        if (displaySizePickedByUser) return
        val res = PiDisplaySize.of(repo, controllerName) ?: return
        _displayWidth.value = res.width.toString()
        _displayHeight.value = res.height.toString()
    }

    fun selectResource(name: String) {
        _resourceName.value = name
        controller.setSelection(_controllerName.value, name)
        invalidatePlan()
    }

    // ------------------------------------------------------------------ 任务

    fun setTaskChecked(name: String, checked: Boolean) {
        _selectedTasks.update { current -> if (checked) current + name else current - name }
        invalidatePlan()
    }

    fun selectAllTasks() {
        _selectedTasks.value = _repository.value?.tasks()?.map { it.name }?.toSet() ?: emptySet()
        invalidatePlan()
    }

    fun clearAllTasks() {
        _selectedTasks.value = emptySet()
        invalidatePlan()
    }

    /**
     * 按名单批量勾选（用于「按当前筛选结果全选」）。
     *
     * [selectAllTasks] 勾的是仓库里**全部**任务：搜索框里筛出 3 个之后按「全选」，会把剩下
     * 27 个连看都看不见的任务一起勾上，用户完全无从察觉。所以搜索词非空时要走这个重载。
     *
     * 语义是**并集**（`current + names`）而不是替换 —— 筛选只该缩小"这一下点到谁"的范围，
     * 不该顺手清掉用户之前手工勾好的任务。
     */
    fun selectTasks(names: Collection<String>) {
        if (names.isEmpty()) return
        _selectedTasks.update { it + names }
        invalidatePlan()
    }

    /** 按名单批量取消勾选。与 [selectTasks] 对称；[clearAllTasks] 才是全清。 */
    fun unselectTasks(names: Collection<String>) {
        if (names.isEmpty()) return
        val drop = names.toSet()
        _selectedTasks.update { it - drop }
        invalidatePlan()
    }

    fun setOptionValue(name: String, value: String) {
        _optionValues.update { it + (name to value) }
        invalidatePlan()
    }

    /** PI 的 switch 默认取 `default_case`，input 取 `default`，都没有就取第一个 case。 */
    fun optionDefault(def: PiOption): String =
        def.defaultCase
            ?: def.cases.firstOrNull()?.name
            ?: (def.default as? JsonPrimitive)?.contentOrNull
            ?: ""

    /** UI 渲染时该显示的取值：用户改过就用用户的，否则用声明默认值。 */
    fun optionDisplayValue(name: String, def: PiOption): String =
        _optionValues.value[name] ?: optionDefault(def)

    /** 布尔型 switch 的「真值」case 名（PI 里可能是 true/yes/on/1/是/开）。 */
    fun optionTrueCase(def: PiOption): String? {
        if (def.cases.size != 2) return null
        val trueWords = setOf("true", "yes", "on", "1", "是", "开", "启用")
        return def.cases.firstOrNull { it.name.lowercase() in trueWords }?.name
    }

    // ---------------------------------------------------------------- 显示尺寸

    fun setDisplayWidth(text: String) {
        displaySizePickedByUser = true
        _displayWidth.value = text.filter { it.isDigit() }.take(5)
    }

    fun setDisplayHeight(text: String) {
        displaySizePickedByUser = true
        _displayHeight.value = text.filter { it.isDigit() }.take(5)
    }

    // ------------------------------------------------------------------ 运行

    fun prepare() {
        val context = buildPlanOrReport() ?: return
        viewModelScope.launch {
            controller.prepare(
                plan = context.plan,
                kind = _privilegeKind.value,
                displayWidth = _displayWidth.value.toIntOrNull(),
                displayHeight = _displayHeight.value.toIntOrNull(),
            )
        }
    }

    fun run() {
        val context = buildPlanOrReport() ?: return
        if (context.plan.tasks.isEmpty()) {
            _uiError.value = "至少勾选一个任务"
            return
        }
        controller.runTasks(
            plan = context.plan,
            taskNames = context.selected,
            optionValues = context.options,
        )
    }

    fun requestStop() = controller.requestStop()

    fun shutdown() = controller.shutdown()

    fun setPreviewEnabled(enabled: Boolean) {
        _previewEnabled.value = enabled
        // 预览画面现在走 Surface：app 的 SurfaceView 直接把窗口交给特权进程的原生渲染线程
        // （见 PreviewSurfaceBridge）。关掉时把 Surface 摘掉，否则原生侧会继续往一块
        // 即将销毁的窗口上画 —— 真机上表现为「关了预览还在闪」。
        if (!enabled) {
            BootstrapRegistry.setPreviewSurface(null)
        }
        // 旧的 JPEG 帧通道（`capture.preview`）保留着，但不再用来驱动预览；
        // 这里只在关闭时通知 helper 停掉可能还在跑的抓帧循环。
        controller.setPreviewEnabled(enabled)
    }

    /**
     * 预览 Surface 的交接点：[com.maapocket.core.ui.HomeScreen] 的 `SurfaceView` 在
     * `surfaceChanged` / `surfaceDestroyed` 时调进来。
     *
     * 这里只写进 [BootstrapRegistry]（app 进程内），由特权进程按
     * [com.maapocket.core.privilege.BootstrapProtocol.METHOD_PREVIEW_SURFACE] 轮询取走 ——
     * 所以这个函数可以安全地在主线程调用，不含任何跨进程等待。
     */
    fun onPreviewSurface(surface: Surface?) {
        BootstrapRegistry.setPreviewSurface(surface)
    }

    fun clearLogs() {
        // 只清 UI 侧；lastRawLogs 保留，之后的增量还能接着算出来。
        _logs.value = emptyList()
    }

    fun logsText(): String = _logs.value.joinToString("\n")

    fun dismissUiError() {
        _uiError.value = null
    }

    // ------------------------------------------------------------------ 内部

    private class Resolved(
        val plan: PiSelection.PiRunPlan,
        val selected: Set<String>,
        val options: Map<String, String>,
    )

    /**
     * 解算 PiRunPlan，失败时把原因写进 uiError 并返回 null。
     *
     * `PiSelection.resolve()` 在资源目录为空、controller/resource 名对不上等情况下只往
     * `warnings` 里写，不会抛；抛的是 i18n / JSON 层面的意外。两种都要让用户看见。
     */
    private fun buildPlanOrReport(): Resolved? {
        val repo = _repository.value ?: run {
            _uiError.value = "还没解包资源包"
            return null
        }
        val root = controller.state.value.piRoot ?: run {
            _uiError.value = "资源包目录未知，请先解包"
            return null
        }
        val selected = _selectedTasks.value
        val options = _optionValues.value.filterKeys { it in relevantOptionNames(repo, selected) }
        val plan = runCatching {
            PiSelection.resolve(
                repo = repo,
                root = root,
                controllerName = _controllerName.value,
                resourceName = _resourceName.value,
                taskNames = selected,
                optionValues = options,
                installedPackages = installedPackages,
            )
        }.getOrElse { t ->
            _uiError.value = "无法解算运行计划：${t.javaClass.simpleName}: ${t.message}"
            return null
        }
        _uiError.value = null
        _plan.value = plan
        return Resolved(plan, selected, options)
    }

    /**
     * 只把「勾选 task 声明的 option + global_option」算作相关。
     * 勾了 task A 的选项后又取消勾选 A 时，残留的取值不能继续影响 override。
     */
    private fun relevantOptionNames(repo: PiRepository, selected: Set<String>): Set<String> {
        val names = LinkedHashSet<String>(repo.globalOptionNames())
        repo.tasks().filter { it.name in selected }.forEach { names += it.option }
        return names
    }

    /** 改选择 / 改选项后旧的 plan 就不再对应 UI 了，丢掉避免误导。 */
    private fun invalidatePlan() {
        _plan.value = null
        _uiError.value = null
    }

    /**
     * controller.logs 是「每 500 条丢掉最老一条」的环形缓冲，且用 StateFlow 整体重发。
     * 直接按 size 差值取尾部会在满仓之后整屏重复，所以这里先按整段前缀相等走快路径，
     * 不相等再从尾部回溯最长重叠。
     */
    private fun appendedTail(previous: List<String>, incoming: List<String>): List<String> {
        if (previous.isEmpty() || incoming.isEmpty()) return incoming
        if (incoming.size >= previous.size && incoming.subList(0, previous.size) == previous) {
            return incoming.subList(previous.size, incoming.size)
        }
        for (k in minOf(previous.size, incoming.size) downTo 1) {
            if (previous.subList(previous.size - k, previous.size) == incoming.subList(0, k)) {
                return incoming.subList(k, incoming.size)
            }
        }
        return incoming
    }

    override fun onCleared() {
        // Activity 的 DisposableEffect 通常已经关过一次；shutdown 幂等，这里再兜一次防泄漏。
        controller.shutdown()
        super.onCleared()
    }
}
