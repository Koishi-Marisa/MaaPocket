# 崩坏：星穹铁道（app-hsr）— B服 支持与每任务可调选项

本文记录两件事：

1. 给 HSR 资源包补上 **B服（哔哩哔哩渠道服）** 支持（新增一条 overlay 资源层 + 启动/关闭游戏任务）。
2. 把 March7thAssistant GUI 里的**每任务配置项**映射成 PI-V2 的 `option`，并明确列出**没能迁移**的部分及原因。

改动全部落在迁移脚本 `MaaPocket/scripts/migrate_march7th.py` 里（产物是脚本生成的），所以三条命令可以完全复现本文描述的一切。

---

## 0. 一句话摘要

| 项目 | 改动 |
| --- | --- |
| `resource` 资源层 | 1 条（国服）→ **2 条**（国服 / B服），B服 那层用 `path: ["./resource", "./resource_bilibili"]` 叠加 |
| `option` 顶层定义 | 0 → **5 个** |
| 带 option 的 task | 0 → **5 个**（`start_up_game` / `close_game` / `divergent_universe` / `universe` / `currency_wars`） |
| 任务数 | 30 → **32**（新增「启动游戏」「关闭游戏」） |
| `default_check: true` 的 task | 0 → **9 个**（按上游 `config.example.yaml` 的 `*_enable` 默认值） |
| `interface.json` | 374 行 → **607 行**（17092 字节） |
| pipeline 节点总数 | 427 → **442**（基线 +10 个启动/关闭节点；overlay +6 个，其中 1 个是覆盖） |

---

## 1. 变更文件清单

### 1.1 迁移脚本（手写改动，唯一的事实源）

`D:\Trea\二游自动化\MaaPocket\scripts\migrate_march7th.py`（1893 行 → 2580 行）

| 位置 | 内容 |
| --- | --- |
| `:798-801` | 新常量 `RESOURCE_BILIBILI_NAME="B服"`、`RESOURCE_BILIBILI_DIR="resource_bilibili"`、`HSR_OFFICIAL_PACKAGE="com.miHoYo.hkrpg"`、`HSR_BILIBILI_PACKAGE="com.miHoYo.hkrpg.bilibili"` |
| `:804` | `EXTRA_TASK_TABLE` —— 两个新增任务的元数据（沿用 `TASK_TABLE` 的 7 元组格式） |
| `:816` | `build_game_control_nodes()` —— 启动/关闭游戏的 10 个基线节点 |
| `:954` | `build_bilibili_overlay_nodes()` —— B服 overlay 的 6 个节点 |
| `:1066` | `OPTION_TABLE` —— 5 个 option 定义 |
| `:1168` | `TASK_OPTION_TABLE` —— task 名 → option 名列表 |
| `:1179` / `:1197` | `TASK_DEFAULT_CHECK` / `TASK_DEFAULT_CHECK_EVIDENCE` —— `default_check` 及其上游行号证据 |
| `:1217-1296` | `CONFIG_MIGRATION_NOTES` —— 29 条「上游配置项 → 有没有迁移」的清单（本文件第 5 节的唯一事实源） |
| `:2076-2115` | `main()` 第 5c-bis 段：写 `start_up_game.json` / `close_game.json` / `resource_bilibili/pipeline/bilibili_login.json`，并把两个新任务并入 `task_entries` |
| `:2202-2266` | interface 组装：`interface_options`、`interface_tasks`（`default_check` + `option`）、两条 `resource`、根对象 `option` |
| `:2267-2278` | `controller` **不再写** `attach_resource_path`（理由见 §2.2） |
| `:2316-2360` | 报告新增 `options` / `tasks_with_options` / `task_default_check` / `resource_layers` / `config_migration_notes` / `bilibili_overlay` / `game_control_tasks` |

另外修了一处会导致重跑误判的指纹：原来用 `controller[0].attach_resource_path == ["./resource"]` 判断「这份 interface.json 是不是上一次的自己生成的」，而本次生成结果**故意不再写**该字段（§2.2），所以改成用顶层 `name == "MaaPocketHSR"` 当指纹（`:1694-1716`）。

### 1.2 脚本生成 / 新增的产物

| 路径 | 状态 |
| --- | --- |
| `MaaPocket/app-hsr/src/main/assets/pi/interface.json` | 重写（607 行） |
| `MaaPocket/app-hsr/src/main/assets/pi/migration_report.json` | 重写（新增 7 个报告段） |
| `MaaPocket/app-hsr/src/main/assets/pi/resource/locale/zh_cn.json` | 重写（新增 resource/option 相关键） |
| `MaaPocket/app-hsr/src/main/assets/pi/resource/pipeline/task/start_up_game.json` | **新增**（10 节点） |
| `MaaPocket/app-hsr/src/main/assets/pi/resource/pipeline/task/close_game.json` | **新增**（2 节点） |
| `MaaPocket/app-hsr/src/main/assets/pi/resource_bilibili/pipeline/bilibili_login.json` | **新增**（6 节点） |
| `MaaPocket/scripts/check_hsr_pi.py` | **新增**（只读自检器，见 §6.3） |

### 1.3 没有碰的文件

按任务约束，**没有**改任何 `.kt` / `.gradle.kts`。同时仓库里还有并行任务在改的文件（`scripts/migrate_maaend.py`、`scripts/migrate_onedragon_zzz.py`、`app-endfield/**`、`app-zzz/**`、`scripts/check_pi_options.py`），本次一个都没动。

---

## 2. 任务 1：B服 支持

### 2.1 为什么是「overlay 资源层」而不是复制整棵树

PI-V2 的 `resource[].path` 是**字符串数组**，同一条 resource 内的多个路径按顺序叠加加载，后加载的同名节点覆盖先加载的。B服 与官服的差异只有两点：

1. 启动的包名不同（`com.miHoYo.hkrpg` vs `com.miHoYo.hkrpg.bilibili`）；
2. 启动过程中多一段「同意隐私政策 / 选登录记录 / 点登录」的分支。

所以 B服 只需要一个只放**被覆盖 + 被新增**节点的目录：

```jsonc
// MaaPocket/app-hsr/src/main/assets/pi/interface.json  （resource 段）
[
  { "name": "国服", "label": "国服", "path": ["./resource"] },
  { "name": "B服",  "label": "B服（哔哩哔哩）", "path": ["./resource", "./resource_bilibili"] }
]
```

`resource_bilibili/` 下只有 1 个文件、6 个节点（§2.4），其中只有 `GameEnterCheck` 是与基线同名的**覆盖**，其余 5 个是新增。整棵树 442 个节点不会因此翻倍。

迁移脚本里对应 `OPTION_TABLE` 之前的注释与 `report["resource_layers"]`（`:2346`）：

```json
[{"name":"国服","path":["./resource"],"role":"baseline（March7thAssistant 全量迁移结果）"},
 {"name":"B服","path":["./resource","./resource_bilibili"],"role":"baseline + B服 overlay（只覆盖 GameEnterCheck，并新增 B服 登录节点）"}]
```

### 2.2 为什么删掉 `controller.attach_resource_path`

原 `interface.json` 的 controller 是：

```json
{"name":"Android","type":"Adb","label":"Android（ADB）","display_long_side":1920,"attach_resource_path":["./resource"]}
```

PI-V2 文档 `_research/maafw_dev/docs/en_us/3.3-ProjectInterfaceV2.md:181` 与 `:289` 明确：**`attach_resource_path` 在所有 `resource.path` 加载完成之后才加载**。也就是说，如果保留它，B服 资源层叠加完 `resource_bilibili` 之后，controller 又会把 `./resource` 重新加载一遍，把 `GameEnterCheck` 的覆盖**静默撤销**——没有任何报错，只是 B服 登录分支永远不生效。

另外该字段本来就是冗余的：`resource` 两条声明里已经各自写了 `./resource`，controller 不需要再挂一次。因此生成结果里 controller 只有 `name/type/label/display_long_side`（`:2267-2278` 附了同样的 DESCISION 注释）。

### 2.3 启动 / 关闭游戏 + `Server`、`StartupWait` 选项

新增任务（`EXTRA_TASK_TABLE` `:804`，节点由 `build_game_control_nodes()` `:816` 生成）：

```jsonc
// MaaPocket/app-hsr/src/main/assets/pi/resource/pipeline/task/start_up_game.json
Task_start_up_game  DirectHit -> ["StartUpGame"]
StartUpGame         StartApp{package:"com.miHoYo.hkrpg"} -> ["WaitGameReady"]
WaitGameReady       DirectHit, post_delay 20000 -> ["GameEnterCheck"]
GameEnterCheck      DirectHit -> ["ClickEnter","ClickStartGame","GameEnterRetry","NavTo_main"]
ClickEnter          TemplateMatch screen/click_enter.png 0.9, Click target:true -> ["GameEnterDone"]
ClickStartGame      TemplateMatch screen/start_game.png 0.9, Click target:true -> ["GameEnterDone"]
GameEnterRetry      DirectHit, post_delay 6000, max_hit 15 -> ["GameEnterCheck"]
GameEnterDone       DirectHit, post_delay 6000 -> ["NavTo_main"]

// MaaPocket/app-hsr/src/main/assets/pi/resource/pipeline/task/close_game.json
Task_close_game     DirectHit -> ["CloseGame"]
CloseGame           StopApp{package:"com.miHoYo.hkrpg"}, next []
```

上游依据：`refs/March7thAssistant/tasks/game/__init__.py:71 start_game()` 是上游唯一的启动入口，`:186 check_and_click_enter()` 轮询点击「进入」；`screen/click_enter.png` / `screen/start_game.png` 两张模板上游用的都是 0.9 阈值（`tasks/game/__init__.py` 里 `check_and_click_enter` 的 `auto.click_element(..., "image", 0.9)`），且都已随包迁移（`resource/image/screen/click_enter.png`、`resource/image/screen/start_game.png`）。

「关闭游戏」在上游**没有**对应任务（只有 `tasks/game/starrailcontroller.py` 的 Windows 窗口控制，Android 无对应物），这里用 `StopApp` 提供最小可用动作，`confidence` 标为 `low`，`description` 里写明了这一点。

两个 option（`OPTION_TABLE` `:1066`）：

**`Server`**（label「服务器」，`default_case: Official`，case 数 2）

| case | label | `pipeline_override` |
| --- | --- | --- |
| `Official` | 国服（米哈游） | `{"StartUpGame":{"package":"com.miHoYo.hkrpg"},"CloseGame":{"package":"com.miHoYo.hkrpg"}}` |
| `Bilibili` | B服（哔哩哔哩） | `{"StartUpGame":{"package":"com.miHoYo.hkrpg.bilibili"},"CloseGame":{"package":"com.miHoYo.hkrpg.bilibili"}}` |

上游依据：`refs/March7thAssistant/assets/config/config.example.yaml:46-48`（`game_title_name` / `game_process_name` / `game_path`）。包名是 Android 侧的新事实——上游是 PC 端，靠 `StarRail.exe` 路径启动，没有包名概念；B服 包名 `com.miHoYo.hkrpg.bilibili` 来自真机（HONOR AGI-AN00，Android 15）。

**`StartupWait`**（label「启动等待」，`default_case: Normal`，case 数 3）

| case | `pipeline_override` |
| --- | --- |
| `Fast` | `{"WaitGameReady":{"post_delay":10000},"GameEnterRetry":{"max_hit":5}}` |
| `Normal` | `{"WaitGameReady":{"post_delay":20000},"GameEnterRetry":{"max_hit":15}}` |
| `Slow` | `{"WaitGameReady":{"post_delay":40000},"GameEnterRetry":{"max_hit":40}}` |

上游依据：`config.example.yaml:55 start_game_timeout: 10 # 启动游戏超时时间（分）`。上游是 `wait_until(check_and_click_enter, start_game_timeout*60)` 的**整体超时**；MaaFramework 里没有「整体超时」这一个字段，所以拆成两个真实存在的字段：`WaitGameReady.post_delay`（`StartApp` 之后等多久开始找「进入」）和 `GameEnterRetry.max_hit`（找不到时最多重试几轮，`max_hit` 超限后该节点在 `next` 列表里被跳过，循环自然收敛）。

### 2.4 B服 登录 overlay 的 6 个节点（`build_bilibili_overlay_nodes()` `:954`）

上游证据 `refs/March7thAssistant/tasks/game/__init__.py:104-115`：

```python
:104  # 适配B服，需要点击“登录”，强制使用前台截图方式（#901）
:105  if auto.find_element(("bilibili游戏隐私政策提示", "登录记录"), "text", use_background_screenshot=False):
:106      if auto.matched_text == "bilibili游戏隐私政策提示":
:107          auto.click_element("同意", "text", take_screenshot=False)
:108      elif auto.matched_text == "登录记录":
:109          auto.click_element("登录", "text", take_screenshot=False)
```

生成结果 `MaaPocket/app-hsr/src/main/assets/pi/resource_bilibili/pipeline/bilibili_login.json`：

| 节点 | 识别 | 动作 | `next` | 说明 |
| --- | --- | --- | --- | --- |
| `GameEnterCheck` | DirectHit | — | `["BilibiliLoginDispatch"]` | **覆盖**基线同名节点（基线是 `["ClickEnter","ClickStartGame","GameEnterRetry","NavTo_main"]`） |
| `BilibiliLoginDispatch` | DirectHit | — | `["BilibiliPrivacyPolicy","BilibiliLoginRecord","ClickEnter","ClickStartGame","GameEnterRetry","NavTo_main"]` | 对应上游 `:105` 那一次「二选一」查找 |
| `BilibiliPrivacyPolicy` | OCR `["bilibili游戏隐私政策提示"]` 0.3 | — | `["BilibiliPrivacyAgree","GameEnterRetry","NavTo_main"]` | 对应 `:106` |
| `BilibiliPrivacyAgree` | OCR `["同意"]` 0.3 | Click `target:true`, `post_delay 2000`, `max_hit 8` | `["BilibiliLoginDispatch"]` | 对应 `:107` |
| `BilibiliLoginRecord` | OCR `["登录记录"]` 0.3 | — | `["BilibiliLoginButton","GameEnterRetry","NavTo_main"]` | 对应 `:108` |
| `BilibiliLoginButton` | OCR `["登录"]` 0.3 | Click `target:true`, `post_delay 2000`, `max_hit 8` | `["BilibiliLoginDispatch"]` | 对应 `:109` |

设计要点：`BilibiliLoginDispatch` 的 `next` 把 B服 的三个分支排在前面、基线的 `ClickEnter` / `ClickStartGame` / `GameEnterRetry` / `NavTo_main` 排在后面。MaaFramework 的 `next` 是**顺序轮询取第一个命中**，所以官服路径不受影响，B服 路径会被优先接管；`Bilibili*` 节点都带 `max_hit`，弹窗反复出现也不会死循环。

### 2.5 B服 的已知限制（必须知道）

1. **OCR 模型不在包里。** 上游 `:105-109` 用的是文本识别（`auto.find_element(..., "text")`），翻译到 MaaFramework 就是 `recognition: "OCR"`；而 OCR 需要 `resource/model/ocr/{rec.onnx,det.onnx,keys.txt}`（`_research/maafw_dev/docs/en_us/3.1-PipelineProtocol.md:643-644`）。`MaaPocket` 全仓目前**没有任何 `model/` 目录**（`_research/maafw_android/MAA-android-aarch64-v5.14.2.zip` 里也只有 `bin/libfastdeploy_ppocr.so`，没有模型文件）。⇒ **在补上模型之前，这 4 个 OCR 节点不会命中**；链路不会卡死（会顺序回落 `GameEnterRetry` → `NavTo_main`），但 B服 的隐私政策弹窗/登录页需要人工点一次。补上模型后无需改任何 JSON。
2. **上游那 5 张 B服 模板图片不存在。** `tasks/game/__init__.py:111-115` 引用 `./assets/images/screen/bilibili_login.png` / `bilibili_login_2.png` / `bilibili_login_3.png` / `bilibili_agree_update.png` / `bilibili_agree_update_2.png`，但在 `refs/March7thAssistant/` 全仓 glob `*bilibili*` 是 **0 命中**（上游仓库自己没带这些图）。所以无法用 `TemplateMatch` 兜底，只能走 OCR。
3. 上游 `tasks/game/__init__.py:193` 还有一处 B服 修复（`# 修复B服问题 .../discussions/321#discussioncomment-10565807` 之后的 `auto.press_mouse()`），是 PC 端鼠标事件，Android 无对应物，未迁移。

### 2.6 在 App 里跑 B服 需要做两件事

B服 是「资源层 + 包名」两个独立的开关，**两个都要切**：

1. 资源 `resource` 选 **B服**（这样才会叠加 `resource_bilibili`，启用登录分支）；
2. 「启动游戏」任务的 `Server` 选项选 **B服**（这样启动的是 `com.miHoYo.hkrpg.bilibili`）。

只切其中一个会得到「启动了 B服 客户端但不点登录」或「点登录流程但启动了官服包」的半成品状态。`Server` 的 label/description 里已经写了这句提示。

---

## 3. 任务 2：5 个真实可调的 option

### 3.1 全部 option 一览

| option | label | type | default | 落点 | 上游依据 |
| --- | --- | --- | --- | --- | --- |
| `Server` | 服务器 | select | `Official` | `StartUpGame.package` / `CloseGame.package` | `config.example.yaml:46-48` + 真机包名 |
| `StartupWait` | 启动等待 | select | `Normal` | `WaitGameReady.post_delay` / `GameEnterRetry.max_hit` | `config.example.yaml:55` |
| `DivergentMode` | 差分宇宙玩法 | select | `Cycle` | `Task_divergent_universe.next` | `config.example.yaml:223`（`weekly_divergent_type: cycle`，`:259`） |
| `UniverseCategory` | 宇宙类别 | select | `Divergent` | `Task_universe.next` | `config.example.yaml:246`（`universe_category: divergent`） |
| `CurrencyWarsMode` | 货币战争玩法 | select | `Overclock` | `Task_currency_wars.next` | `config.example.yaml:212`（`currencywars_type: overclock`） |

挂载关系（`TASK_OPTION_TABLE` `:1168`）：

```json
{"start_up_game":["Server","StartupWait"],
 "close_game":["Server"],
 "divergent_universe":["DivergentMode"],
 "universe":["UniverseCategory"],
 "currency_wars":["CurrencyWarsMode"]}
```

三个「玩法类型」option 覆盖的都是 task 入口节点的 `next`，也就是切换进哪一块界面：

| option / case | `pipeline_override` | 目标节点是否存在 |
| --- | --- | --- |
| `DivergentMode.OnlyModeSelect` | `{"Task_divergent_universe":{"next":["NavTo_divergent_mode_select"]}}` | ✅（迁移基线行为） |
| `DivergentMode.Normal` | `{"Task_divergent_universe":{"next":["NavTo_divergent_mode_select_normal"]}}` | ✅ 模板 `resource/image/screen/universe/mode_select_normal.png` |
| `DivergentMode.Cycle` | `{"Task_divergent_universe":{"next":["NavTo_divergent_mode_select_cycle"]}}` | ✅ 模板 `mode_select_cycle.png` / `mode_select_cycle2.png` |
| `UniverseCategory.Divergent` | `{"Task_universe":{"next":["NavTo_divergent_main"]}}` | ✅（迁移基线行为） |
| `UniverseCategory.Universe` | `{"Task_universe":{"next":["NavTo_universe_main"]}}` | ✅ 模板 `screen/universe/universe_main.png` |
| `CurrencyWarsMode.OnlyHomepage` | `{"Task_currency_wars":{"next":["NavTo_currency_wars_homepage"]}}` | ✅（迁移基线行为） |
| `CurrencyWarsMode.Normal` | `{"Task_currency_wars":{"next":["NavTo_currency_wars_mode_select_normal"]}}` | ✅ 模板 `screen/currency_wars/mode_select_normal.png` |
| `CurrencyWarsMode.Overclock` | `{"Task_currency_wars":{"next":["NavTo_currency_wars_mode_select_overclock"]}}` | ✅ 模板 `screen/currency_wars/mode_select_overclock.png` |

`NavTo_*` 这些路由器本来就存在（属于迁移基线 442 个节点之一），它们的 `next` 以同 id 的 `Screen_<id>` 兜底，所以改 `Task_*.next` 不会产生悬空引用——`check_hsr_pi.py` 会把这一点重新验一遍（§6.3）。

### 3.2 `default_case` 取上游默认值（一个刻意的行为变化）

每个 option 的 `default_case` 都取**上游 `config.example.yaml` 的默认值**，而不是「迁移前的浅入口」。原因是 option 本身就是把上游配置项搬过来，默认值应当与 PC 端一致。

代价（**行为变化，需知悉**）：`divergent_universe` 与 `currency_wars` 两个任务在**不选任何选项**时，比改造前的包多走一跳——

| 任务 | 改造前 | 现在（默认） |
| --- | --- | --- |
| `divergent_universe` | `Task_divergent_universe.next = ["NavTo_divergent_mode_select"]` | `["NavTo_divergent_mode_select_cycle"]`（上游 `weekly_divergent_type: cycle`） |
| `currency_wars` | `Task_currency_wars.next = ["NavTo_currency_wars_homepage"]` | `["NavTo_currency_wars_mode_select_overclock"]`（上游 `currencywars_type: overclock`） |

想回到改造前的浅入口，把这两个 option 分别切成 `OnlyModeSelect` / `OnlyHomepage` 即可（这两个 case 是为「只想进到界面、不想再往下走」保留的，上游没有对应配置项，label 里已写明「只到…」）。

上游 key 的**唯一**代码使用点（核实过 grep 全仓 `*.py`）：

- `refs/March7thAssistant/tasks/weekly/divergent_universe.py:128` → `if self.start_war(cfg.weekly_divergent_type):`（差分宇宙真正读的是 `weekly_divergent_type`，默认 `cycle`）
- `refs/March7thAssistant/tasks/weekly/currency_wars.py:277` → `if self.start_war(cfg.currencywars_type):`（默认 `overclock`）
- `refs/March7thAssistant/tasks/weekly/universe.py:197` → `category=cfg.universe_category`（默认 `divergent`），另见 `tasks/daily/daily.py:168`、`:179`
- `cfg.divergent_type`（`:259`）**只被 `refs/March7thAssistant/module/config/asu_config.py:17` 使用**，没有任何 task 读它——它只是给外部工具导出配置用的。

### 3.3 被**慎重放弃**的候选 option（避免造假开关）

| 曾考虑 | 为什么不做 |
| --- | --- |
| `ApocalypticEntry` / `MemoryOfChaosEntry` / `PureFictionEntry`（把 `Task_apocalyptic` 等从 `NavTo_guide4` 直接改到 `NavTo_apocalyptic` / `NavTo_memory_of_chaos` / `NavTo_purefiction`） | 这些 `NavTo_*` 节点确实存在，但 `guide4 → apocalyptic / memory_of_chaos / purefiction` 这条边的**第一跳是 `__UNMAPPED_*` 占位（DoNothing）**，实际不会产生任何点击；上游也没有对应配置项。做了就是个假开关。 |
| 云游戏开关（`config.example.yaml:59 cloud_game_enable`） | 迁移基线里只有 `resource/image/screen/cloud/enter_cloud_game.png` 一张截图，没有云游戏客户端包名，也没有上游 `tasks/game/__init__.py:119-152` 的浏览器授权/免责声明/引导点击链。 |
| B服 的 5 张登录模板 | 图片在上游仓库里就不存在（§2.5 第 2 条），无法建 `TemplateMatch`。 |
| 体力用来刷什么 / 刷几次 / 副本名 / 遗器分解策略 / 队伍 | 见 §5：迁移后的 pipeline 在这些地方**没有任何节点**。 |

---

## 4. `default_check`：把上游「任务开关」的默认值搬过来

`TASK_DEFAULT_CHECK`（`:1179`）与 `TASK_DEFAULT_CHECK_EVIDENCE`（`:1197`）把上游 `config.example.yaml` 里的 `*_enable` 默认值写进 `task.default_check`。32 个任务里 **9 个为 `true`**：

| task | `default_check` | 上游依据 |
| --- | --- | --- |
| `power` | ✅ | `config.example.yaml:90 power_enable: true` |
| `daily` | ✅ | `config.example.yaml:173 daily_enable: true` |
| `mail` | ✅ | `config.example.yaml:164 reward_mail_enable: true` |
| `assist` | ✅ | `config.example.yaml:165 reward_assist_enable: true` |
| `dispatch` | ✅ | `config.example.yaml:163 reward_dispatch_enable: true` |
| `quest` | ✅ | `config.example.yaml:166 reward_quest_enable: true` |
| `srpass` | ✅ | `config.example.yaml:167 reward_srpass_enable: true` |
| `redemption` | ✅ | `config.example.yaml:168 reward_redemption_code_enable: true` |
| `activity` | ✅ | `config.example.yaml:190 activity_enable: true` |

显式为 `false`（上游有 `*_enable: false`）：`achievement`:169、`message`:170、`himekotry`:175、`memoryone`:176、`buildtarget`:112、`journey_highlights_notification`:196。其余任务在上游没有独立的 `*_enable`，一律 `false`。

---

## 5. 没能迁移的 PC 端配置项（29 条清单的原文出处）

下表来自 `report["config_migration_notes"]`（脚本 `CONFIG_MIGRATION_NOTES` `:1217-1296`，中文说明在这里展开）。29 条里 **8 条 landed、1 条 partially_landed、20 条 not_landed**。**结论：HSR 资源包当前的 `option` 只覆盖了上游配置的很小一部分；其余部分在迁移后的 pipeline 里没有落点。**

### 5.1 已迁移（8 条 landed + 1 条 partially_landed）

| 上游配置项 | 状态 | 落点 |
| --- | --- | --- |
| `config.example.yaml:46-48 game_title_name / game_process_name / game_path` | landed | `Server` option → `StartUpGame.package` / `CloseGame.package` |
| `config.example.yaml:55 start_game_timeout` | landed | `StartupWait` option → `WaitGameReady.post_delay` + `GameEnterRetry.max_hit` |
| `config.example.yaml:162-170 reward_*_enable` | landed | 对应任务的 `default_check` |
| `config.example.yaml:173-176 daily_*_enable` | landed | `daily` / `himekotry` / `memoryone` / `buildtarget` 的 `default_check` |
| `config.example.yaml:190-196 activity_*_enable` | landed | `activity` 的 `default_check` |
| `config.example.yaml:212 currencywars_type` | landed | `CurrencyWarsMode` → `Task_currency_wars.next` |
| `config.example.yaml:222-228 weekly_divergent_*` | landed | `DivergentMode` → `Task_divergent_universe.next`（**仅类型**；难度/奖励/稳定模式未迁移） |
| `config.example.yaml:244-252 universe_*` | landed | `UniverseCategory` → `Task_universe.next` |
| `config.example.yaml:259-262 divergent_type / divergent_team_type / universe_fate / universe_difficulty` | partially_landed | `divergent_type` 已并入 `DivergentMode`；队伍流派/命途/难度未迁移 |

### 5.2 未迁移（20 条 not_landed）

| 上游配置项 | 未迁移原因 |
| --- | --- |
| `:59 cloud_game_enable` / `:62-63` 云游戏排队与登录超时 | 缺云游戏客户端包名与 `cloud/` 下的授权/免责声明/引导点击链（上游 `tasks/game/__init__.py:119-152`） |
| `:90-109 power_* / instance_type / calyx_golden_preference / instance_names / instance_names_challenge_count / tp_before_instance` | 副本名/次数/花萼偏好没有对应节点：迁移只做到 `screen/guide3` 入口（`march7th:kind=screen-anchor`，`next` 为空），副本列表界面的模板与点击链完全没迁移 |
| `:112-114 build_target_*` | 培养目标流程（instance/drop 两种方案）在迁移后的 pipeline 里没有任何节点 |
| `:118-120 break_down_level_four_relicset / weekly_relic_*` | 遗器分解策略是 `bag_relicset` 界面内部的选中与分解逻辑，迁移只到 `Screen_bag_relicset` |
| `:124-128 instance_team_*` | 队伍选择在 `configure_team` 界面内部，迁移只到 `Screen_configure_team`（`next` 为空） |
| `:131-132 merge_immersifier / merge_immersifier_limit` | 合成数量由 `consumables` / `material` 界面内部的拖动与输入决定，迁移后这两条边是 `__UNMAPPED_*`（DoNothing），没有可覆盖字段 |
| `:135-136 use_reserved_trailblaze_power / use_fuel` | 体力补充弹窗没有迁移；`guide3` 之后的界面没有节点 |
| `:139-141 echo_of_war_*` | 历战余响的时间窗控制属于调度器逻辑，pipeline 里只有 `NavTo_guide3` |
| `:144-159 borrow_*` | 支援角色选择在 `visa` 界面内部，迁移只到 `Screen_visa` |
| `:199-208 asset_*` | 资产/余烬兑换没有对应的迁移任务（上游 `assets` 模块整体不在 `TASK_TABLE` 里） |
| `:213-217 currencywars_rank_difficulty / bonus_enable / fast_mode / strategy` | 博弈内的玩法参数没有迁移到 pipeline（只到 `mode_select` 屏幕） |
| `:231-242 divergent_station_*` | 站台优先级配置需要差分宇宙内部节点，迁移里一个都没有 |
| `:265-278 fight_*` | 锄大地模块（`tasks/daily/fight.py`）的导航只迁到 `NavTo_main`，地图/购买/队伍配置全在战斗循环里 |
| `:285-309 forgottenhall_*` | 忘却之庭层数与两队配置在 `memory` 界面内部；迁移只到 `Screen_memory` / `guide4` |
| `:313-337 purefiction_*` | 虚构叙事层数与队伍配置同样没有迁移节点 |
| `:341-365 apocalyptic_*` | 末日幻影层数与队伍配置同样没有迁移节点 |
| `:368-389 auto_battle_detect_enable / auto_set_resolution_enable / loop_mode / scheduled_time / power_limit / refresh_hour` | PC 端调度器与游戏窗口控制行为，属于 App 侧（`MaaPocket/core`）职责，不属于资源包 |
| `:395-398 hotkey_*` | 热键只影响 PC 端的自动战斗/地图/传送，对应边在迁移后是 `__UNMAPPED_*` 占位 |
| `:445 use_background_screenshot` | Android 侧只有前台截图一种方式，没有可切换字段 |
| `:461 redemption_code` | 兑换码需要 `InputText` 动作落点；迁移只到 `Screen_redemption`，没有输入框节点可覆盖 |

### 5.3 支撑「不能造假开关」的硬证据

把 `app-hsr/src/main/assets/pi/resource/pipeline/**` 所有节点的 `template` 字段汇总，共 **89 个不同模板**，非 `screen/` 前缀的只有 29 个，全部是导航图标（`share/menu/*`、`forgottenhall/*`、`share/warp/stellar_warp.png`、`zh_CN/base/confirm.png`、`zh_CN/fight/{fight_exit,retreat}.png`、`zh_CN/warp/{character_trial,start_trial}.png`、`share/reward/pass/*`、`share/synthesis/enter_*`、`share/map/back.png`、`zh_CN/map/back.png`）。

也就是说：`resource/image/share/calyx/golden/`、`resource/image/share/power/`、`resource/image/share/relicset/`、`resource/image/zh_CN/relicset/`、`resource/image/share/character/`（105 张）这些图片**虽然被复制进了包，但没有任何 pipeline 节点引用它们**。体力刷什么、遗器怎么分解、用哪支队伍——这些界面在迁移里根本没有节点，所以给它们做 `option` 只会得到一个点了没反应的假开关。

---

## 6. 自检命令与真实输出

所有命令都在 `D:\Trea\二游自动化\MaaPocket` 下执行，前置 `$env:PYTHONIOENCODING="utf-8"`。

### 6.1 重新跑迁移脚本 → 产物一致

```
> python scripts/migrate_march7th.py
[migrate_march7th] source   : D:\Trea\二游自动化\refs\March7thAssistant @ 5bbe11c4a1e799b89258dd01cf8d30837eadb32f
[migrate_march7th] out      : D:\Trea\二游自动化\MaaPocket\app-hsr\src\main\assets\pi
[migrate_march7th] screens  : 56 emitted / 56 in source
[migrate_march7th] edges    : 110 emitted / 110 in source (0 omitted)
[migrate_march7th] exprs    : 185 total, 20 unmapped
[migrate_march7th] tasks    : 32 emitted, 14 skipped
[migrate_march7th] options  : 5 defined, used by 5 tasks
[migrate_march7th] resources: 国服<./resource>, B服<./resource + ./resource_bilibili>
[migrate_march7th] images   : 395 copied (3883745 bytes)
[migrate_march7th] schema   : pipeline OK, interface OK
```

（最后一行是脚本内建的 draft-07 子集校验器，用真实 schema `_research/maafw_dev/tools/pipeline.schema.json` 与 `_research/maafw_dev/tools/interface.schema.json` 校验所有生成实例。）

确定性对比（先跑一遍 → 全量 sha256 → 再跑一遍 → 再全量 sha256）：

```
run A files: 488  run B files: 488
byte-identical (all files, incl. report): True
report identical (ignoring source/out/self-hash): True
```

**逐字节一致，连 `migration_report.json` 都是**（该报告里记录的是相对路径与自哈希，不再随机器变化）。

### 6.2 打包

```
> python scripts/build_pi_pack.py --source app-hsr/src/main/assets/pi --out $env:TEMP\hsrpack --game hsr
...
[2/5] 检查下划线目录名  OK（没有被包含的下划线目录）
...
[4/5] interface.json  version=0.1.0 interface_version=2 sha256=71a7c980a0c7c3f0...
...
完成: hsr  ->  D:\Temp\hsrpack
文件数 : 487  总大小 : 4206866 B (4.01 MB)
```

退出码 0。输出目录里除了 `interface.json` / `pi_pack.json` / `resource`，还有 **`resource_bilibili`** —— 说明 overlay 目录天然进了包（命中 `build_pi_pack.py` 的 `DEFAULT_INCLUDE` 通配 `resource_*/**`，见 `scripts/build_pi_pack.py:87-96`），打包侧无需任何额外配置。

### 6.3 JSON + option 交叉引用校验

新增只读校验器 `MaaPocket/scripts/check_hsr_pi.py`（约 220 行）：

```
> python scripts/check_hsr_pi.py
[OK] 全部 93 个 *.json 可 json.load，UTF-8 无 BOM
[OK] 全部 task.option 引用（6 处）都能在顶层 option 里找到
[OK] 全部 5 个 option 自洽（case/default_case/switch 约束）
[OK] 资源层: 国服[./resource], B服[./resource + ./resource_bilibili]
[OK] 全部 pipeline_override 落点（36 条，覆盖 2 个资源层）都指向真实节点、字段都是合法 Node 字段
[OK] 没有被包含的下划线目录
------------------------------------------------------------------------
  checks: 6, failures: 0
  结果: PASS
```

退出码 0。这 6 项分别对应任务要求里的：JSON 可 `json.load`、`option` 引用的 task 名存在、`task.option` 里的名字能在 `option` 定义里找到，另外补了「override 落点必须是真实节点 + 字段必须是合法 Node 字段」和「不能有下划线开头的目录」。

> 实现细节：校验器最初把合法 Node 字段硬编码成 21 个通用字段，结果对 `StartUpGame` 的 `package` 误报失败。查 `_research/maafw_dev/tools/pipeline.schema.json` 的 `$defs` 后发现 `package` 定义在 `StartApp` / `StopApp`（动作私有字段），而 `$defs.Node` 用 `unevaluatedProperties: false` + action 变体组合，所以动作私有字段也是合法的。修法是把「已加载资源里任何节点用过的键」并进白名单。

---

## 7. 复现方式

```powershell
cd D:\Trea\二游自动化\MaaPocket
$env:PYTHONIOENCODING="utf-8"

# 1) 重新生成资源包（默认 --source refs\March7thAssistant --out app-hsr\src\main\assets\pi）
python scripts/migrate_march7th.py

# 2) 静态自检
python scripts/check_hsr_pi.py

# 3) 打包验证
python scripts/build_pi_pack.py --source app-hsr/src/main/assets/pi --out $env:TEMP\hsrpack --game hsr
```

想从零重建（清掉旧产物）加 `--force`。`migrate_march7th.py` 不会删除它没生成过的文件，`--force` 会先 `rmtree` 输出目录。

---

## 8. 后续可以做的事（按性价比排序）

1. **补 OCR 模型**：把 `rec.onnx` / `det.onnx` / `keys.txt` 放进 `app-hsr/src/main/assets/pi/resource/model/ocr/`，B服 登录分支立即生效，不需要改 JSON。这是当前投入产出比最高的一步。
2. **迁移副本/花萼界面**（上游 `tasks/power/`）：能让 `power` 任务的「刷什么 / 刷几次」变成真 `option`（对应 §5.2 的 `:90-109`）。
3. **迁移 `configure_team` 内部**（上游 `tasks/` 的队伍配置）：解放一大批 `instance_team_*` / 忘却之庭两队配置。
4. **`redemption_code`**：需要一个 `InputText` 节点（`Screen_redemption` 里加输入框坐标），之后就能用 `option.type = "input"` 直接映射。
