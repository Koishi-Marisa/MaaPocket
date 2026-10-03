# RECAPTURE.md — app-hsr 资源包的「必须重新采集」清单

本文档由 `MaaPocket/scripts/migrate_march7th.py` 的迁移结果直接推导，描述
**哪些部分已经是对的、哪些部分一定是错的、以及按什么顺序修**。

阅读前先接受一句话结论：

> 这次迁移搬过来的**不是可用的图像资产**，而是
> **屏幕图的拓扑结构（56 个节点 / 110 条边）、每条边的动作序列语义、以及任务分类**。
> 395 张模板图全部是 **PC 客户端 1920×1080 截图的小块裁剪**，在手机上**一条都不可靠**；
> 而且 51 个动作是**键盘按键**，手机端根本没有键盘。

---

## 1. 核心未解问题

### 1.1 上游是 PC 客户端，本包目标在手机

`refs/March7thAssistant` 的全部输入实现都是 Windows 桌面：

- `module/automation/input_base.py` 的抽象方法就是 `mouse_click / mouse_move / mouse_drag /
  mouse_scroll / press_key / press_key_down / press_key_up / secretly_write` —— 鼠标 + 键盘。
- `module/automation/local_input.py`（Windows 实现）每一行都是 `pyautogui` 薄包装。
  仓库里 **`module/` 下 grep `adb|android` 命中 0 次**，从来没有为安卓写过一行代码。
- 因此 `module/automation/cdp_input.py`（云游戏浏览器输入）里的
  `SPECIAL_KEY_MAP`（esc→vk27、f1..f12→vk111+i …）是**唯一的键盘权威表**，
  但它描述的是浏览器 `<input>` 事件，不是 Android `KeyEvent`。

手机客户端的差异有两层，**都不要低估**：

1. **图像层**：分辨率、宽高比、DPI、UI 缩放、安全区、语言字体渲染全都不同。
   PC 是固定 1920×1080（16:9）；主流手机是 20:9 / 19.5:9，游戏内容通常被信箱化或拉伸。
   同一块 UI 元素在手机上的**归一化坐标与像素尺寸都变了**。
2. **输入层**：手机上没有 `esc`、没有 `m`、没有 `f3`。
   上游 `SCREEN_MATCH_THRESHOLD = 0.88`（`module/screen/screen.py`）也是在 PC 截图上调出来的。

### 1.2 本包的「设计分辨率」是一个声明出来的约定，不是测量出来的事实

`interface.json` 里写的是：

```json
"controller": [{ "name": "Android", "type": "Adb",
                 "display_long_side": 1920, "attach_resource_path": ["./resource"] }]
```

选择 1920 的唯一理由是**自洽**：`screens.json` 里每一个归一化裁剪
`crop=(x/1920, y/1080, w/1920, h/1080)` 都是按 1920×1080 写的，
把识别空间也定成 1920×1080，就能**原样搬运 ROI 和模板、不做任何重采样**
（而本脚本是 stdlib-only，根本没办法重采样 PNG）。

这条约定在真机上**大概率是错的**：

- 真机截图的长边要被 MaaFramework 缩放到 1920，缩放后短边 = `1920 / 手机宽高比`。
  在 20:9 手机上就是 1920×864 —— **不是 1080**。
- 于是所有 y 方向的 ROI 和所有模板的 y 尺度都偏了。
- 更糟的是 MaaFramework 的官方模板规范写的是
  「所使用的图片需要是无损原图缩放到 **720p** 后的裁剪」（`pipeline.schema.json`
  的 `TemplateMatch.template` 描述），我们**没有**遵守这条。

**结论：`display_long_side: 1920` 是一个占位约定。**
重新采集的第一步就是重新决定它（见 §3 步骤 0）。

### 1.3 上游的容差语义无法一比一搬运（已做近似，必须重标定）

- `module/automation/automation.py:213 find_image_element`：
  模板**带 alpha 遮罩**时用 `TM_SQDIFF`（`utils/image_utils.py:33` 的 `mask is not None` 分支）
  → **分数越低越好**；不带遮罩时用 `TM_CCOEFF_NORMED` → **分数越高越好**。
  上游注释原话：
  > `我不知道为什么这里要用低分匹配方法，这导致了部分文件识别设置阈值要设置的很大很大（如界域锚点要设置到3,000,000）`
  > `另外，就是用mask的图片和不用mask的图片判断逻辑是完全相反的，一个阈值要设置的高一个阈值要设置的低 带mask的是越低越好`

  `screens.json` 里所有 `<path>,'image',2000000` 都属于这一类，
  2 000 000 实际上等于「随便哪个位置都接受」。
  本脚本把这一类统一近似为 `TemplateMatch` + `threshold: 0.7`
  （并写进节点的 `march7th:masked_template: true` 与 `march7th:upstream_threshold`）。
  **MaaFramework 的 `green_mask` 只能抠纯绿 RGB(0,255,0)，无法表达任意 alpha 遮罩**，
  所以这是一个真实的近似，必须在真机上重新标定阈值。
- 屏幕锚点阈值：上游 `SCREEN_MATCH_THRESHOLD = 0.88`，
  本包统一写 `0.8`（`SCREEN_THRESHOLD`）。同样是占位。

---

## 2. 迁移之后**已经正确**的部分

这些是**语义层**的成果，重新采集图像**不需要**重做它们：

| 已经正确 | 证据 / 位置 |
|---|---|
| **屏幕图拓扑**：56 个屏幕、110 条有向边、每条边的动作序列长度（1/2/3/4 条） | `resource/pipeline/screen/<id>.json`，节点名 `<from>__<to>__<i>` |
| **每条边的意图**：`march7th:expression` 原样保留了上游那一行 Python 表达式 | 每个边节点都带 |
| **每条边的动作序列顺序**：`next` 串成的链就是上游 `perform_operations` 的 `eval` 顺序 | `module/screen/screen.py:365 perform_operations` |
| **「第一个匹配目标的 action 生效」语义**：上游 `screen.py:354 get_operations` 取**第一条** `target_screen == next` 的边；本包 `NavTo_<B>` 的前驱守卫按 `(from, 边序)` 排序，`first_edge = min(...)` 与之等价 | `NavTo_*` 节点 |
| **任务分类**：7 个分组、30 个任务及其入口屏幕 | `interface.json` 的 `group` / `task`，每条 `description` 都引用了上游 `文件:行号` |
| **每个屏幕的中文名**：`march7th:screen_name`（如 `guide3` = 星际和平指南-生存索引），重新采集时用来判断「我现在该找哪个界面」 | `Screen_<id>` 节点 |
| **每个模板的上游来源路径**：`march7th:source_image_path`，说明这块裁剪原本取自哪里 | `Screen_<id>` 节点 |
| **全部 56 个锚点 + 110 个守卫 + 56 个路由器的引用完整性** | `migration_report.json` → `pipeline_node_names`（0 重复、0 悬空）与 `nav_guard_coverage`（110/110） |
| **schema 合法性** | `migration_report.json` → `schema_validation`：`pipeline.schema.json` 与 `interface.schema.json` 都通过 |

特别说明：`Screen_<id>` 的 `next` **故意是空的**（终止节点）。
如果让它串到出边，MaaFramework「`next` 里第一个识别到的就执行」的规则会导致
**一进主界面就自动按 esc**。导航统一由 `NavTo_<id>` 路由器驱动 —— 这是设计，不是缺陷。

---

## 3. 必须重新采集的部分 + 有序流程

### 步骤 0 —— 先决定真机的设计分辨率，再动任何一张图

1. 在目标手机上确定游戏的渲染方式（全屏拉伸 / 信箱化 / 刘海安全区），
   取一张标准截图，记录**像素尺寸**（例如 2400×1080、2340×1080、1920×864 …）。
2. 把 `interface.json` 的 `display_long_side: 1920` 改成与你决定的设计分辨率一致的值。
   如果要遵守 MaaFramework 的 720p 约定，就改成
   `display_short_side: 720`（注意：schema 写明它与 `display_long_side` **互斥**）。
3. **一旦这一步定了，下面所有模板都必须按同一尺度重采。**

> ⚠️ 顺序不能反。先采图再改分辨率 = 全部重做。

### 步骤 1 —— 屏幕锚点模板（56 个屏幕 / 58 张图，必须全部替换）

这些是「我现在在哪个界面」的判据，错了后面全错。

56 个屏幕一共关联 **58 张**锚点模板（`activity` 和 `divergent_mode_select_cycle`
这两个屏幕各用 2 张图做 OR 匹配，命中任意一张即算在该屏幕，见上游
`module/screen/screen.py:299 _find_image` 对 `image_path` 为 list/tuple 的处理）。
其中 **54 张在 `resource/image/screen/**`，另外 4 张不在这个目录里**：

```
forgottenhall/prepare_fight.png     <- 屏幕 prepare_fight
share/menu/more.png                 <- 屏幕 menu
zh_CN/fight/fight_exit.png          <- 屏幕 fight_exit
zh_CN/fight/retreat.png             <- 屏幕 retreat
```

完整的 58 张锚点清单（路径都是 `resource/image/` 下的相对路径）：

```
screen/main.png  screen/menu.png  screen/map.png  screen/map3d.png
screen/monthly_card.png  screen/cloud/enter_cloud_game.png  screen/start_game.png
screen/click_enter.png  screen/universe/leave_temporarily.png
screen/universe/universe_report.png  screen/universe/universe_map.png
zh_CN/fight/retreat.png  zh_CN/fight/fight_exit.png
screen/guide/guide2.png  screen/guide/guide3.png  screen/guide/guide4.png
screen/guide/guide5.png  screen/mail.png  screen/visa.png  screen/redemption.png
screen/camera.png  screen/photo_preview.png  screen/universe/universe_main.png
screen/universe/universe_expansion.png  screen/universe/divergent_main.png
screen/universe/divergent_mode_select.png  screen/universe/mode_select_normal.png
screen/universe/mode_select_cycle.png  screen/universe/mode_select_cycle2.png
screen/currency_wars/homepage.png  screen/currency_wars/mode_select.png
screen/currency_wars/mode_select_normal.png  screen/currency_wars/mode_select_overclock.png
screen/dispatch.png  screen/start_srpass.png  screen/pass/pass1.png  screen/pass/pass2.png
screen/synthesis/consumables.png  screen/synthesis/material.png
screen/bag/bag_consumables.png  screen/bag/bag_relicset.png  screen/bag/bag_lc.png
screen/forgottenhall/memory_of_chaos.png  screen/forgottenhall/memory.png
screen/purefiction/purefiction.png  screen/apocalyptic/apocalyptic.png
screen/configure_team.png  screen/activity.png  screen/activity_2.png
screen/warp/character_warp.png  screen/warp/stellar_warp.png
screen/warp/himeko_try.png  screen/warp/himeko_try_banner.png  screen/warp/himeko_prepare.png
screen/achievement.png  screen/incoming_message.png  screen/reward_guide.png
forgottenhall/prepare_fight.png  share/menu/more.png
```

> 注意 `menu` 这个屏幕的锚点是 `share/menu/more.png`（主菜单里那个「更多」按钮），
> 不是在 `screen/` 下 —— 上游就是这么写的，别以为漏了。

这 58 张同时也被 `Nav_<to>_from_<pred>` 守卫节点复用（同一个屏幕锚点模板，
引用 113 次），所以**改一次就同时修好了定位与路由守卫**。

**替换方法（对每一个文件名）：**

1. 用节点里的 `march7th:screen_name` 知道该去哪个界面；
   用 `march7th:source_image_path` 知道上游截的是哪一块（如 `./assets/images/screen/main.png`，
   32×29 像素，是「主界面」右上角一小块特征）。
   也可以用 `git show HEAD:<source_image_path>` 看原图，确认裁剪的是哪个 UI 元素。
2. 在手机上手动导航到那个界面：
   ```powershell
   adb exec-out screencap -p > shot.png
   ```
3. 在 `shot.png` 上裁出**同一个 UI 元素**，尺寸按步骤 0 定的分辨率缩放后保存。
4. **覆盖同名文件**，路径 = `resource/image/` + （`march7th:source_image_path` 去掉
   `./assets/images/` 前缀）。例如 `./assets/images/screen/main.png`
   → `resource/image/screen/main.png`。
5. 一个小自检：把采集好的模板与截图做一次 `cv2.matchTemplate`，
   `TM_CCOEFF_NORMED` 分数应 ≥ 0.9；同一次会话里再去匹配**别的**界面截图，
   分数应 ≤ 0.6。达不到就换一块更有区分度的 UI 元素重裁。
6. 全部换完后跑一次「我在哪」验证（见步骤 4）。

### 步骤 2 —— 边上的点击模板（被引用的共 89 张里的其余部分）

`pipeline` 一共引用了 **89 个不同的模板文件**（引用次数 174 次）。
其中 58 个是上一步的屏幕锚点（守卫节点复用了同一批），
**剩下 31 个只出现在边链上**，是「界内的进入 / 返回 / 确认按钮」：

| 目录（`resource/image/` 下） | 本包引用的**不同**文件数 | 说明 |
|---|---|---|
| `screen/`（含全部子目录） | 58 | 58 个锚点里的 54 个 + 4 个「进入」按钮（`screen/guide/enter_guide2..5.png`） |
| `share/menu/` | 14 | 主菜单左侧那一列入口按钮（`bag.png`、`guide.png`、`camera.png`、`activity.png`、`dispatch.png`、`achievement.png`、`configure_team.png`、`synthesis.png`、`pass.png`、`more.png`、`bag_consumables.png`、`bag_relicset.png`、`bag_lc.png`、`activity_2nd.png`） |
| `zh_CN/` | 6 | **中文 UI 专属**：`base/confirm.png`、`map/back.png`、`fight/fight_exit.png`、`fight/retreat.png`、`warp/character_trial.png`、`warp/start_trial.png` |
| `share/reward/pass/`、`share/synthesis/`、`share/warp/`、`share/map/` | 7 | 二级入口与返回（`enter_pass1/2.png`、`enter_consumables/material.png`、`stellar_warp.png`、`back.png`） |
| `forgottenhall/` | 3 | `prepare_fight.png`、`memory.png`、`memory-fin.png` |

这 89 张全部是 PC 截图裁剪，**全部要按步骤 1 的方法重采**。

> ⚠️ `zh_CN/` 这一类还有一个额外风险：它们匹配的是**中文文字渲染**。
> 手机客户端的字体、行高、抗锯齿与 PC 不同，文字类模板最容易失效。
> 优先改用**图标 / 边框 / 色块**做模板，别用文字。

### 步骤 3 —— 重新推导 ROI（15 个节点，2 个矩形，**必须**）

本包只有 **15 个** `TemplateMatch` 节点带非全屏 ROI，来自 2 个上游裁剪：

| ROI（像素） | 节点数 | 来源 |
|---|---|---|
| `[1264, 286, 546, 746]` | 14 | 来自上游 `crop=(0.658…, 0.265…, 0.284…, 0.690…)`，即主菜单右侧那一竖列入口 |
| `[1824, 108, 96, 648]` | 1 | 来自 `share/menu/camera.png` 的 `crop=(0.95, 0.1, 0.05, 0.6)`，上游这个裁剪**宽高越过了 1.0**，本脚本已 clamp |

带 ROI 的节点完整清单（文件名都是 `resource/pipeline/screen/menu.json` 里的）：

```
menu__guide2__0  menu__guide3__0  menu__guide4__0  menu__guide5__0
menu__camera__0  menu__dispatch__0  menu__pass1__0  menu__consumables__0
menu__bag_consumables__0  menu__bag_relicset__0  menu__bag_lc__0
menu__configure_team__0  menu__activity__0  menu__activity__1
menu__achievement__0
```

**重新推导方法**：在新分辨率下截一张主菜单图，量出那一列入口的
`(x, y, w, h)` 像素矩形，直接替换节点里的 `roi` 数组
（`roi` 是 `[x, y, w, h]`，`[0,0,0,0]` 表示全屏）。
其余 **全部 `roi` 都是 `[0, 0, 0, 0]`（全屏）**，不需要动。

### 步骤 4 —— 验证「我现在在哪」

`resource/pipeline/screen/_index.json` 里只有一个节点 `ScreenIndex`：
`DirectHit` → `next` 是按 `screens.json` 文件顺序排列的 56 个 `Screen_<id>` 锚点，
语义是「第一个匹配上的就是当前界面」。
用它就能一次性检验步骤 1 的 58 张锚点：在每一个界面各跑一次，看命中的 id 对不对。
把这 56 个屏幕全部验证通过，再进入步骤 5。

### 步骤 5 —— 键盘动作必须改掉（51 个节点）

| 上游表达式 | 出现次数 | 本包现在生成的 | 手机上应该改成 |
|---|---|---|---|
| `auto.press_key('esc')` | **48** | `ClickKey` `key: 111`（Android KEYCODE_ESCAPE） | **`key: 4`（KEYCODE_BACK）**，或改成点击屏幕上的返回/暂停按钮的模板匹配节点。Android 客户端没有键盘，KEYCODE_ESCAPE 不会到达游戏 |
| `auto.press_key('p')` | **1** | `ClickKey` `key: 44`（KEYCODE_P） | 同上，按字符映射没有意义，改成点击对应按钮 |
| `auto.press_key(cfg.get_value('hotkey_map','m'))` | **1** | `ClickKey` `key: 41`（KEYCODE_M），已常量折叠成默认热键 `m` | **必须改成点击地图入口的模板匹配节点**。手机上没有「M 打开地图」这种热键 |
| `auto.press_key(cfg.get_value('hotkey_warp','f3'))` | **1** | `ClickKey` `key: 133`（KEYCODE_F3） | **同上，改成点击跃迁/传送入口** |

前两类（49 个 `esc`/`p`）在节点里带了 `march7th:recapture_note` 提醒；
后两类额外带 `march7th:config_dependency`，说明它其实依赖用户的 `config.yaml`，
而静态资源包读不到那个文件，只能常量折叠成上游默认值。

> 这是整个迁移里**最严重的语义损失**：上游 110 条边中有 51 条的第一跳是键盘，
> 而这 51 跳在手机上**全部需要重新变成图像识别 + 点击**。

### 步骤 6 —— 20 个 OCR 占位节点

`find_type == 'text'` 的点击共 **20 个**（16 个不同字符串），现在被生成为
`DoNothing` 占位节点，名字以 `__UNMAPPED_<n>` 结尾，排在链里保持边的连通性。

上游用的是 `auto.click_element('<中文>','text',...)`（OCR 文字匹配后点击）。
要真正实现，需要：

1. 在资源包里提供 MaaFramework 的 OCR 模型（本包**没有**附带任何模型）。
2. 把节点改成：
   ```json
   { "recognition": "OCR", "expected": "<中文>", "roi": [x,y,w,h],
     "action": "Click", "target": true, "next": [...] }
   ```
   （`roi` 用节点里 `march7th:unmapped_...` 附近记录的归一化裁剪反算，
   报告 `migration_report.json` 的 `unmapped_expressions` 里给了 `planned_recognition` 的具体值。）
3. **中文 UI 文本在 PC 与手机客户端之间也会变**（版本、排版、甚至字体），
   所以 OCR 也必须重新标定，不能照抄上游字符串。

相关字符串：`传送`、`前往参与`、`差分宇宙`、`漫游签证`、`兑换码`、`忘却之庭`、
`虚构叙事`、`日幻影`（`include=True` 子串匹配）、`货币战争`、`前往模拟宇宙`、
`常规演算`、`周期演算`、`标准博弈`、`进入标准博弈`、`超频博弈`、`进入超频博弈`。

### 步骤 7 —— 阈值重标定

真机验证时逐节点调：

- 屏幕锚点 `threshold: 0.8` → 实际用 `0.85 ~ 0.9` 起步，误识别多就升、漏识别多就降。
- `march7th:masked_template: true` 的节点 `threshold: 0.7` → **重新标**。
  上游那一类是 `TM_SQDIFF` + alpha 遮罩（越低越好），
  MaaFramework 这边是 `TM_CCOEFF_NORMED`（越高越好），**两边分数不可换算**。
  如果发现误报，可以在 MaaFramework 里改 `method`（`pipeline.schema.json` 的
  `TemplateMatch.method` 允许 `1`/`3`/`5`/`10001`，默认 `5` = `TM_CCOEFF_NORMED`，
  `10001` = 反向 `TM_SQDIFF_NORMED`），或把 alpha 遮罩的透明区手工涂成纯绿
  RGB(0,255,0) 再用 `green_mask: true`。
- `method` 和 `green_mask` 是本包**没有**使用的两个字段，需要时按上一条调整。

### 步骤 8 —— 已知的功能缺口（不是「采集」能解决的，是设计缺口）

1. **多跳导航没有实现。**
   `NavTo_<B>` 只覆盖**单跳**：它依次试探「哪些前驱屏幕现在在屏幕上」，
   命中就走那条上游边；都不命中就回退到 `Screen_<B>` 锚点
   —— 已经在 B 上就成功，否则**诚实失败**（不会静默跳过）。
   上游 `module/screen/screen.py:213 find_shortest_path` 是 BFS，能跨任意多跳。
   全量复现需要给 **2313 个 (target, source) 对**各物化一条路径，远超本次
   「一屏一文件」的输出结构。
   **补齐配方**：为每一对需要多跳的 (目标 B, 起点 X) 生成一个
   `NavTo_<B>_from_<X>` 节点，`next` = `["Screen_" + s for s in BFS(X→B)]`
   —— 即把 BFS 得到的屏幕序列当成「先确认自己在哪、再逐跳」的链。
2. **`actions_list_on_timeout`（5 条边 / 11 个表达式）没有写进 pipeline。**
   它们在 `migration_report.json` 里被完整统计（`expression_census` 把 185 个
   表达式全算进去了），但生成器只把 `actions_list` 落成节点。
   上游语义见 `module/screen/screen.py:356 get_timeout_operations`：
   等待屏幕切换超时后的补救操作。
   MaaFramework 侧对应的是节点的 `timeout` + `on_error` 字段
   （`pipeline.schema.json` 的 `Node.timeout` 默认 20000ms，`Node.on_error` 默认空）。
   涉及：`menu`→`bag_consumables`/`bag_relicset`/`bag_lc`（各 3 个动作：
   点确认、等 2 秒、点背包图标），`guide5`→`currency_wars_homepage` 与
   `guide5`→`divergent_main`（各 1 个 `auto.press_key('f')`）。
   **注意这 2 个 `f` 也是键盘** —— 它们只存在于超时列表里，
   所以上面的「51 个 ClickKey 节点」不含它们（51 = 48 个 `esc` + 1 个 `p`
   + 2 个常量折叠的热键）；如果要补超时逻辑，这 2 个 `f` 同样要改成点击。
3. **任务只有入口，没有身体。**
   `Task_<name>` 节点只是 `DirectHit → NavTo_<entry_screen>`，
   也就是「走到该任务的上游入口界面」为止。
   上游那些 `class`（战斗循环、遗器筛选、合成次数、清体力策略……）
   **一行都没有迁移**，因为本次任务的范围就是「屏幕导航 + 任务结构」。
4. **`time.sleep(<n>)` 映射成了 `post_delay: <n*1000>`。**
   这是最接近的等价物，但延迟只有下限意义（上游是额外的 wall-clock 等待，
   MaaFramework 的 `post_delay` 是节点后固定等待）。真机上慢的话需要加大。

---

## 4. 生成物里哪些是「占位符」而不是可用资产

| 路径 | 是什么 | 占位程度 |
|---|---|---|
| `resource/image/**`（**395 张 PNG，3 883 745 字节**） | 上游 `assets/images/` 的原样拷贝 | **全部占位**。被本包引用的只有 **89 张**；剩下 306 张是上游其它模块（战斗/奖励/遗器/角色头像）用的图，本包根本没引用 |
| `resource/image/screen/**`（拷贝 106 张，引用 **60 张**） | 54 张屏幕锚点 + 4 张 `enter_guide2..5.png` + 界内其它按钮 | **引用的 60 张必须重采**，见步骤 1、2 |
| `resource/image/share/**`（拷贝 221 张，引用 **20 张**） | 主菜单 / 背包 / 合成 / 奖励入口 | **引用的 20 张必须重采** |
| `resource/image/zh_CN/**`（拷贝 39 张，引用 **6 张**） | 中文 UI 文字/按钮模板（含 2 张屏幕锚点） | **引用的 6 张必须重采**，且优先改成图标类模板，见步骤 2 |
| `resource/image/forgottenhall/**`（拷贝 15 张，引用 **3 张**） | 忘却之庭 / 准备战斗（含 1 张屏幕锚点） | **引用的 3 张必须重采** |
| `resource/image/purefiction/**`（9 张）、`resource/image/apocalyptic/**`（5 张） | 上游纯虚构/末日的图 | **本包一张都没引用**（这两个屏幕的锚点用的是 `screen/purefiction/purefiction.png` 和 `screen/apocalyptic/apocalyptic.png`），可以整目录从资源包里删掉 |
| `resource/pipeline/screen/*__UNMAPPED_*.json`（**20 个节点**） | OCR 点击的 `DoNothing` 占位 | **纯占位**，见步骤 6 |
| `resource/pipeline/screen/*.json` 里的 `ClickKey` 节点（**51 个**） | 键盘映射，`key` 用的是同名 Android 虚拟键码 | **语义占位**，见步骤 5 |
| 所有 `threshold: 0.8` / `0.7` | 未在真机标定的常量 | **占位**，见步骤 7 |
| 15 个节点的 `roi`（2 个矩形） | 按 1920×1080 反算的像素矩形 | **必须重算**，见步骤 3 |
| `interface.json` 的 `"display_long_side": 1920` | 声明出来的设计分辨率 | **占位约定**，见步骤 0 |
| `resource/pipeline/screen/_index.json` | `ScreenIndex` 全屏定位器 | 结构正确，但**依赖上面全部锚点**，图片不换就不可用 |
| `resource/locale/zh_cn.json` | 界面文案 | **不是占位**，是真实内容 |
| `migration_report.json` | 迁移报告（含 census、邻接表、任务映射、schema 校验结果） | 报告，`build_pi_pack.py` 的白名单不会把它打进 APK |
| `interface.json` 的 `task[].description` | 每条都引用上游真实 `文件:行号` | **不是占位**，是对迁移来源的记录 |

**注意**：`interface.json` **没有 `icon` 键**。
上游仓库里没有任何 logo 资产（`git ls-files` 只找到 `app/common/icon.py` 和一个
无关的聊天气泡 png），所以宁可省略这个键，也不指向一个不存在的文件。

---

## 5. 一句话的优先级

```
步骤 0（定分辨率）→ 步骤 1（56 个屏幕 / 58 张锚点）→ 步骤 4（验证定位）
→ 步骤 2（其余 31 张边模板）→ 步骤 3（2 个 ROI）
→ 步骤 5（51 个键盘跳改成点击）→ 步骤 6（20 个 OCR）→ 步骤 7（阈值）
→ 步骤 8（多跳、超时、任务身体 —— 属于补功能，不属于补资产）
```
